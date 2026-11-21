@file:SuppressLint("NewApi")

package com.example.data

import android.annotation.SuppressLint
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CronDispatchStepResult(
    val farmerId: String,
    val farmerName: String,
    val whatsappPhone: String,
    val fieldId: String,
    val fieldName: String,
    val cropType: String,
    val et0Mm: Float,
    val maxTempC: Float,
    val humidityPct: Int,
    val kcUsed: Float,
    val etcMm: Float,
    val leachingFraction: Float,
    val waterM3Feddan: Float,
    val valveRunMinutes: Int,
    val diseaseAlertTriggered: Boolean,
    val generatedMessageText: String,
    val directWaMeUrl: String
)

data class DailyDispatchBatchSummary(
    val timestamp: String,
    val totalFieldsProcessed: Int,
    val stepResults: List<CronDispatchStepResult>,
    val statusJson: String
)

class AgroSightCloudWorker(
    private val databaseService: DatabaseService,
    private val geminiAgroService: AgroAiCopilotDataSource
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Generates a direct https://wa.me/<phone>?text=<encoded_message> link
     * that opens WhatsApp directly with the farmer's whatsapp_phone and pre-filled message.
     */
    fun buildDirectWaMeLink(
        toPhone: String,
        bodyText: String
    ): String = Companion.buildDirectWaMeLink(toPhone, bodyText)

    // Automated Morning Dispatch across ALL Tenant Fields (POST /daily-dispatch)
    suspend fun executeDailyMultiTenantDispatch(
        onProgress: (String) -> Unit = {}
    ): DailyDispatchBatchSummary = withContext(Dispatchers.IO) {
        val snapshot = databaseService.getSnapshot()
        val results = mutableListOf<CronDispatchStepResult>()

        for (field in snapshot.fields) {
            val farmer = snapshot.farmers.find { it.id == field.farmerId } ?: continue
            val soil = snapshot.soilProfiles.find { it.fieldId == field.id }
                ?: SoilProfileEntity("default", field.id, "رملية", 7.8f, 2.5f, 1.2f)
            val latestScan = snapshot.satelliteSpectralScans.find { it.fieldId == field.id }
                ?: SatelliteSpectralScanEntity(
                    id = "default",
                    fieldId = field.id,
                    scanDate = LocalDate.now().toString(),
                    ndviMean = 0.68f,
                    ndreMean = 0.42f,
                    psriMean = 0.06f,
                    ndmiMean = 0.32f,
                    anomalyPattern = AnomalyPattern.HEALTHY,
                    infectedAreaFeddans = 0f,
                    hotspotCoordinates = "الحقل بالكامل",
                    detectedRiskName = "سليم",
                    confidenceScore = 95
                )

            onProgress("جاري سحب أرصاد Open-Meteo وحساب البخر-نتح لحقل: ${field.fieldName}...")

            // 1. Fetch live Open-Meteo weather for field.lat & field.lon
            val (et0, maxTemp, humidity) = fetchFieldOpenMeteoSnapshot(field.lat, field.lon)

            // 2. Compute satellite-derived Kc, ETc, Leaching Fraction (LR), Gross Water m³/feddan, and Run Minutes
            val kc = ((1.25f * latestScan.ndviMean + 0.10f) * 100f).roundToInt() / 100f
            val etc = ((et0 * kc) * 100f).roundToInt() / 100f
            val denom = max(1.0f, 5f * 2.5f - soil.waterEc)
            val lr = min(0.35f, max(0f, soil.waterEc / denom))

            val sysEffPercent = when {
                field.irrigationType.contains("تنقيط") -> 90f
                field.irrigationType.contains("بيفوت") -> 82f
                else -> 75f
            }
            val grossMm = etc / (sysEffPercent / 100f) / (1f - lr)
            val waterM3Feddan = ((grossMm * 4.2f) * 10f).roundToInt() / 10f
            val valveRunMinutes = ((waterM3Feddan / max(4f, field.flowRateM3Hr)) * 60f).roundToInt().coerceAtLeast(20)
            val runHoursPart = valveRunMinutes / 60
            val runMinsPart = valveRunMinutes % 60

            val hasEarlyDiseaseRisk = latestScan.psriMean > 0.12f || latestScan.ndreMean < 0.35f

            onProgress("جاري توليد رسالة واتساب الاستباقية عبر Gemini 2.5 Flash للمزارع: ${farmer.fullName}...")

            val workerPrompt = """
                أنت محرك AgroSight الذكي. اكتب رسالة واتساب قصيرة ومباشرة باللهجة المصرية الاحترافية للمزارع "${farmer.fullName}" عن أرضه "${field.fieldName}" المزروعة "${field.cropType}":
                - البخر نتح اليوم: ${formatDecimal2(et0)} مم، والحرارة العظمى: ${formatDecimal1(maxTemp)}°م، والرطوبة: $humidity%.
                - كمية المياه المطلوبة اليوم: ${formatDecimal1(waterM3Feddan)} م3/فدان (إجمالي تشغيل المحبس: $runHoursPart ساعة و $runMinsPart دقيقة).
                - قلوية التربة pH: ${formatDecimal1(soil.soilPh)}، وملوحة التربة: ${formatDecimal1(soil.soilEc)} dS/m.
                - قراءات القمر الصناعي Sentinel-2: NDVI=${formatDecimal2(latestScan.ndviMean)}, NDRE=${formatDecimal2(latestScan.ndreMean)}, PSRI=${formatDecimal2(latestScan.psriMean)}.
                إذا كان PSRI > 0.12 أو NDRE منخفضاً مع رطوبة عالية، أضف قسماً تحذيرياً مبكراً بوجود بؤرة مرضية مرصودة بالقمر الصناعي (${latestScan.detectedRiskName} في ${latestScan.hotspotCoordinates}) وحدد تعديل الري المطلوب.
            """.trimIndent()

            val fallbackWorkerMessage = buildWorkerFallbackMessage(
                farmerName = farmer.fullName,
                fieldName = field.fieldName,
                cropType = field.cropType,
                et0 = et0,
                maxTemp = maxTemp,
                humidity = humidity,
                kc = kc,
                waterM3Feddan = waterM3Feddan,
                runHoursPart = runHoursPart,
                runMinsPart = runMinsPart,
                soilPh = soil.soilPh,
                soilEc = soil.soilEc,
                scan = latestScan,
                hasEarlyDiseaseRisk = hasEarlyDiseaseRisk
            )

            val messageText = geminiAgroService.generateCustomWorkerMessage(
                prompt = workerPrompt,
                fallbackText = fallbackWorkerMessage
            )

            // 3. Insert into irrigation_logs (local DB + optional Supabase REST)
            val acidLiters = SatelliteEpidemiologyEngine.calculateRequiredAcidLitersPerFeddan(soil.soilPh)
            val logEntry = IrrigationAndTreatmentLogEntity(
                id = "cron_log_${System.currentTimeMillis()}_${field.id}",
                fieldId = field.id,
                date = LocalDate.now().toString(),
                et0 = et0,
                kc = kc,
                waterM3PerFeddan = waterM3Feddan,
                valveRunMinutes = valveRunMinutes,
                acidDoseLiters = acidLiters,
                preventiveSprayAction = if (hasEarlyDiseaseRisk) {
                    "إنذار مبكر Sentinel-2: ${latestScan.detectedRiskName} (${latestScan.hotspotCoordinates})"
                } else {
                    "جدولة صباحية آلية • غسيل ملوحة LR=${(lr * 100).roundToInt()}%"
                },
                status = "CRON_LOGGED"
            )
            databaseService.appendIrrigationTreatmentLog(logEntry)

            // 4. Generate direct https://wa.me/ link & log sent message history in database
            val directWaMeUrl = buildDirectWaMeLink(farmer.whatsappPhone, messageText)
            val waEntity = WhatsAppMessageEntity(
                id = "wa_cron_${System.currentTimeMillis()}_${farmer.id}",
                farmerId = farmer.id,
                fieldId = field.id,
                timestamp = DatabaseService.currentTimeFormatted(),
                direction = WhatsAppDirection.OUTBOUND_ALERT,
                alertCategory = if (hasEarlyDiseaseRisk) {
                    WhatsAppAlertCategory.SATELLITE_DISEASE_WARNING
                } else {
                    WhatsAppAlertCategory.IRRIGATION_DAILY
                },
                messageText = messageText,
                status = "WA_ME_LOGGED"
            )
            databaseService.appendWhatsAppMessage(waEntity)

            results.add(
                CronDispatchStepResult(
                    farmerId = farmer.id,
                    farmerName = farmer.fullName,
                    whatsappPhone = farmer.whatsappPhone,
                    fieldId = field.id,
                    fieldName = field.fieldName,
                    cropType = field.cropType,
                    et0Mm = et0,
                    maxTempC = maxTemp,
                    humidityPct = humidity,
                    kcUsed = kc,
                    etcMm = etc,
                    leachingFraction = lr,
                    waterM3Feddan = waterM3Feddan,
                    valveRunMinutes = valveRunMinutes,
                    diseaseAlertTriggered = hasEarlyDiseaseRisk,
                    generatedMessageText = messageText,
                    directWaMeUrl = directWaMeUrl
                )
            )
        }

        DailyDispatchBatchSummary(
            timestamp = DatabaseService.currentTimeFormatted(),
            totalFieldsProcessed = results.size,
            stepResults = results,
            statusJson = """{"status": "Generated & Logged wa.me links", "fields_dispatched": ${results.size}}"""
        )
    }

    private fun fetchFieldOpenMeteoSnapshot(lat: Double, lon: Double): Triple<Float, Float, Int> {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&daily=temperature_2m_max,et0_fao_evapotranspiration" +
            "&hourly=relative_humidity_2m" +
            "&timezone=Africa%2FCairo"
        return try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return Triple(5.5f, 31.5f, 68)
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val daily = json.optJSONObject("daily")
                val et0 = daily?.optJSONArray("et0_fao_evapotranspiration")?.optDouble(0, 5.5)?.toFloat() ?: 5.5f
                val maxT = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, 31.5)?.toFloat() ?: 31.5f
                val hourlyRh = json.optJSONObject("hourly")?.optJSONArray("relative_humidity_2m")
                val rh6am = hourlyRh?.optInt(6, 68) ?: 68
                Triple(et0, maxT, rh6am)
            }
        } catch (_: Exception) {
            Triple(5.5f, 31.5f, 68)
        }
    }

    private fun buildWorkerFallbackMessage(
        farmerName: String,
        fieldName: String,
        cropType: String,
        et0: Float,
        maxTemp: Float,
        humidity: Int,
        kc: Float,
        waterM3Feddan: Float,
        runHoursPart: Int,
        runMinsPart: Int,
        soilPh: Float,
        soilEc: Float,
        scan: SatelliteSpectralScanEntity,
        hasEarlyDiseaseRisk: Boolean
    ): String {
        val sb = StringBuilder()
        sb.appendLine("🌅 *نشرة المسح الصباحي الآلي (AgroSight) - $fieldName*")
        sb.appendLine("صباح الخير يا $farmerName، إليك التقرير اليومي لمحصول *$cropType*:")
        sb.appendLine("☀️ *الأرصاد والبخر-نتح:* `ET0 = ${formatDecimal2(et0)} مم` | الحرارة العظمى: `${formatDecimal1(maxTemp)}°م` | الرطوبة: `$humidity%`")
        sb.appendLine("💧 *مقنن الري المحسوب اليوم (Kc=${formatDecimal2(kc)}):* `${formatDecimal1(waterM3Feddan)} م³/فدان` (تشغيل المحبس: *$runHoursPart ساعة و $runMinsPart دقيقة*).")
        sb.appendLine("🧪 *كيمياء التربة:* `pH = ${formatDecimal1(soilPh)}` | الملوحة `ECe = ${formatDecimal1(soilEc)} dS/m`.")
        sb.appendLine("🛰️ *مؤشرات Sentinel-2:* `NDVI=${formatDecimal2(scan.ndviMean)}` | `NDRE=${formatDecimal2(scan.ndreMean)}` | `PSRI=${formatDecimal2(scan.psriMean)}`")
        if (hasEarlyDiseaseRisk) {
            sb.appendLine("⚠️ *إنذار مبكر من القمر الصناعي:* رصد تغير طيفي في (${scan.hotspotCoordinates}) يشير إلى *${scan.detectedRiskName}* (ثقة ${scan.confidenceScore}%). يُنصح بضبط ري القطاع المصاب فوراً والتدخل الموضعي دون رش الحقل بالكامل.")
        }
        return sb.toString().trim()
    }

    companion object {
        fun buildDirectWaMeLink(toPhone: String, bodyText: String): String {
            val cleanPhone = toPhone.filter { it.isDigit() }.ifBlank { "201012345678" }
            val encodedText = try {
                URLEncoder.encode(bodyText, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
            } catch (_: Exception) {
                ""
            }
            return "https://wa.me/$cleanPhone?text=$encodedText"
        }

        val SUPABASE_EDGE_FUNCTION_TS_CODE = """
// supabase/functions/agrosight-worker/index.ts
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { GoogleGenAI } from "npm:@google/genai";

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
);
const ai = new GoogleGenAI({ apiKey: Deno.env.get("GEMINI_API_KEY")! });

// توليد رابط واتساب مباشر (https://wa.me/) مع رقم المزارع ونص الرسالة الجاهز للإرسال
function buildDirectWhatsAppLink(toPhone: string, bodyText: string): string {
  const cleanPhone = toPhone.replace(/\D/g, "");
  return `https://wa.me/${'$'}{cleanPhone}?text=${'$'}{encodeURIComponent(bodyText)}`;
}

serve(async (req) => {
  const url = new URL(req.url);

  // تشغيل المسح الصباحي الآلي لكل الحقول وتوليد روابط wa.me وتوثيقها في قاعدة البيانات
  if (req.method === "POST" && url.pathname.endsWith("/daily-dispatch")) {
    const { data: fields } = await supabase
      .from("fields")
      .select("*, farmers(*), soil_profiles(*), satellite_scans(*)");

    const dispatchLinks = [];

    for (const field of fields || []) {
      const weatherRes = await fetch(
        `https://api.open-meteo.com/v1/forecast?latitude=${'$'}{field.lat}&longitude=${'$'}{field.lon}&daily=temperature_2m_max,et0_fao_evapotranspiration&hourly=relative_humidity_2m&timezone=Africa%2FCairo`
      );
      const weather = await weatherRes.json();
      const et0 = weather.daily.et0_fao_evapotranspiration[0] || 5.2;
      const maxTemp = weather.daily.temperature_2m_max[0] || 30;
      const humidity = weather.hourly.relative_humidity_2m[6] || 65;

      const soil = field.soil_profiles?.[0] || { soil_ph: 7.8, soil_ec: 2.5, water_ec: 1.2 };
      const latestScan = field.satellite_scans?.[0] || { ndvi_mean: 0.68, ndre_mean: 0.42, psri_mean: 0.06 };

      const kc = Number((1.25 * latestScan.ndvi_mean + 0.1).toFixed(2));
      const etc = Number((et0 * kc).toFixed(2));
      const lr = Math.min(0.35, Math.max(0, soil.water_ec / (5 * 2.5 - soil.water_ec)));
      const grossMm = etc / (field.system_efficiency / 100) / (1 - lr);
      const waterM3Feddan = Number((grossMm * 4.2).toFixed(1));
      const runMinutes = Math.round(((waterM3Feddan * field.area_feddans) / field.flow_rate_m3_hr) * 60);

      const prompt = `
      أنت محرك AgroSight الذكي. اكتب رسالة واتساب قصيرة ومباشرة باللهجة المصرية الاحترافية للمزارع "${'$'}{field.farmers.full_name}" عن أرضه "${'$'}{field.field_name}" المزروعة "${'$'}{field.crop_type}":
      - البخر نتح اليوم: ${'$'}{et0} مم، والحرارة العظمى: ${'$'}{maxTemp}°م، والرطوبة: ${'$'}{humidity}%.
      - كمية المياه المطلوبة اليوم: ${'$'}{waterM3Feddan} م3/فدان (إجمالي تشغيل المحبس: ${'$'}{Math.floor(runMinutes / 60)} ساعة و ${'$'}{runMinutes % 60} دقيقة).
      - قلوية التربة pH: ${'$'}{soil.soil_ph}، وملوحة التربة: ${'$'}{soil.soil_ec} dS/m.
      - قراءات القمر الصناعي Sentinel-2: NDVI=${'$'}{latestScan.ndvi_mean}, NDRE=${'$'}{latestScan.ndre_mean}, PSRI=${'$'}{latestScan.psri_mean}.
      إذا كان PSRI > 0.12 أو NDRE منخفضاً مع رطوبة عالية، أضف قسماً تحذيرياً مبكراً بوجود بؤرة مرضية مرصودة بالقمر الصناعي وحدد تعديل الري المطلوب.
      `;

      const aiResponse = await ai.models.generateContent({
        model: "gemini-2.5-flash",
        contents: prompt,
      });

      const messageText = aiResponse.text || "تقرير الري اليومي جاهز على منصة AgroSight.";
      const waMeUrl = buildDirectWhatsAppLink(field.farmers.whatsapp_phone, messageText);

      // 1. تسجيل عملية الري في جدول irrigation_and_treatment_logs
      await supabase.from("irrigation_and_treatment_logs").insert({
        field_id: field.id,
        et0: et0,
        kc: kc,
        water_m3_per_feddan: waterM3Feddan,
        valve_run_minutes: runMinutes,
        status: "WA_ME_DISPATCHED"
      });

      // 2. توثيق سجل الرسالة ورابط wa.me في جدول whatsapp_messages بقاعدة البيانات
      await supabase.from("whatsapp_messages").insert({
        farmer_id: field.farmers.id,
        field_id: field.id,
        direction: "OUTBOUND_ALERT",
        alert_category: latestScan.psri_mean > 0.12 ? "SATELLITE_DISEASE_WARNING" : "IRRIGATION_DAILY",
        message_text: messageText,
        status: "WA_ME_LOGGED"
      });

      dispatchLinks.push({
        farmer: field.farmers.full_name,
        phone: field.farmers.whatsapp_phone,
        wa_me_url: waMeUrl
      });
    }

    return new Response(JSON.stringify({ status: "Dispatched successfully", links: dispatchLinks }), {
      headers: { "Content-Type": "application/json" },
      status: 200
    });
  }

  return new Response("OK", { status: 200 });
});
        """.trimIndent()
    }
}
