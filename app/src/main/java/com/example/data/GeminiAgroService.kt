package com.example.data

import com.example.BuildConfig
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GroundingCitation(
    val title: String,
    val url: String,
    val sourceType: String // "GOOGLE_SEARCH" | "GOOGLE_MAPS" | "SENTINEL_2_STAC" | "FAO_56"
)

data class GeminiAgronomyChatMessage(
    val id: String,
    val role: String, // "USER" | "ASSISTANT"
    val channel: String, // "GEMINI_CHAT" | "VOICE_CONVERSATION" | "SEARCH_GROUNDED" | "MAPS_GROUNDED"
    val text: String,
    val timestamp: String,
    val citations: List<GroundingCitation> = emptyList(),
    val isLiveAiGenerated: Boolean = false
)

data class GeminiGroundedResponse(
    val replyText: String,
    val citations: List<GroundingCitation>,
    val isLiveAiGenerated: Boolean = false
)

interface AgroAiCopilotDataSource {
    suspend fun generateMultiTurnGroundedAgronomyReply(
        chatHistory: List<GeminiAgronomyChatMessage>,
        userMessage: String,
        useGoogleSearchGrounding: Boolean,
        useGoogleMapsGrounding: Boolean,
        farmerName: String,
        fieldName: String,
        regionName: String,
        latitude: Double,
        longitude: Double,
        cropNameAr: String,
        areaFeddans: Float,
        soilPh: Float,
        soilEc: Float,
        waterEc: Float,
        et0MmDay: Float,
        kcValue: Float,
        dailyM3PerFeddan: Float,
        valveRunHours: Float,
        report: EpidemiologyDiagnosisReport
    ): GeminiGroundedResponse

    suspend fun generateEpidemiologicalInsight(
        farmerName: String,
        fieldName: String,
        cropNameAr: String,
        report: EpidemiologyDiagnosisReport,
        soilPh: Float,
        soilEc: Float,
        tempMaxC: Float,
        humidityPct: Int,
        et0MmDay: Float
    ): String

    suspend fun replyToFarmerWhatsAppMessage(
        farmerQuestion: String,
        farmerName: String,
        fieldName: String,
        cropNameAr: String,
        areaFeddans: Float,
        soilPh: Float,
        soilEc: Float,
        et0MmDay: Float,
        kcValue: Float,
        dailyM3PerFeddan: Float,
        valveRunHours: Float,
        report: EpidemiologyDiagnosisReport
    ): String

    suspend fun generateCustomWorkerMessage(
        prompt: String,
        fallbackText: String
    ): String
}

class GeminiAgroService(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : AgroAiCopilotDataSource {

    companion object {
        const val AGRONOMIC_SAFETY_DISCLAIMER_AR =
            "⚠️ تنويه إرشادي وعلمي: هذه التقديرات مبنية على نماذج استشعار عن بُعد ومعادلات FAO-56 الإرشادية؛ يُوصى بالمعاينة الحقلية ومراجعة مهندس زراعي معتمد قبل تطبيق الأحماض أو الكيماويات أو المبيدات."
    }

    override suspend fun generateMultiTurnGroundedAgronomyReply(
        chatHistory: List<GeminiAgronomyChatMessage>,
        userMessage: String,
        useGoogleSearchGrounding: Boolean,
        useGoogleMapsGrounding: Boolean,
        farmerName: String,
        fieldName: String,
        regionName: String,
        latitude: Double,
        longitude: Double,
        cropNameAr: String,
        areaFeddans: Float,
        soilPh: Float,
        soilEc: Float,
        waterEc: Float,
        et0MmDay: Float,
        kcValue: Float,
        dailyM3PerFeddan: Float,
        valveRunHours: Float,
        report: EpidemiologyDiagnosisReport
    ): GeminiGroundedResponse = withContext(Dispatchers.IO) {
        val systemInstruction = """
            أنت المستشار الزراعي الذكي (Gemini Agronomy & Remote Sensing Copilot) في منصة "أجرو سايت" (AgroSight) المصرية.
            القاعدة الذهبية: المنصة تعمل بالاستشعار عن بعد بالأقمار الصناعية (Sentinel-2: NDVI, NDRE, PSRI, NDMI) والأرصاد (Open-Meteo FAO-56) وكيمياء التربة (pH & ECe).
            بيانات الحقل الحالية للمزارع ($farmerName):
            - الحقل: $fieldName في منطقة ($regionName) عند الإحداثيات (Lat=$latitude, Lon=$longitude) بمساحة ${areaFeddans.toInt()} فدان.
            - المحصول: $cropNameAr | معامل المحصول Kc=${formatDecimal2(kcValue)} | البخر-نتح المرجعي ET0=${formatDecimal2(et0MmDay)} مم/يوم.
            - المقنن المائي اليومي المقدر: ${formatDecimal1(dailyM3PerFeddan)} م³/فدان (تشغيل المحبس ${formatDecimal1(valveRunHours)} ساعة).
            - كيمياء التربة والمياه: pH=${formatDecimal1(soilPh)} | ECe=${formatDecimal1(soilEc)} dS/m | ECw=${formatDecimal1(waterEc)} dS/m | جرعة الحامض الإرشادية=${formatDecimal1(report.acidDoseLitersPerFeddan)} لتر/فدان.
            - مؤشرات القمر الصناعي Sentinel-2: NDVI=${formatDecimal2(report.ndviMean)}, NDRE=${formatDecimal2(report.ndreMean)}, PSRI=${formatDecimal2(report.psriMean)}, NDMI=${formatDecimal2(report.ndmiMean)}.
            - المؤشر المكاني المرصود: ${report.detectedRiskNameAr} (${report.hotspotLocationAr}) بثقة تقديرية ${report.confidenceScorePct}%.
            أجب باللغة العربية العلمية الواضحة، مع التمييز بين القراءات والتقديرات، واختم بتوصية مراجعة مهندس زراعي مختص قبل أي تطبيق كيميائي.
        """.trimIndent()

        val defaultCitations = mutableListOf(
            GroundingCitation(
                title = "Copernicus Sentinel-2 L2A Multi-Spectral STAC (${formatDecimal2(report.ndviMean)} NDVI)",
                url = "https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a",
                sourceType = "SENTINEL_2_STAC"
            ),
            GroundingCitation(
                title = "Open-Meteo & FAO-56 Penman-Monteith ET0 (${formatDecimal2(et0MmDay)} mm/day)",
                url = "https://open-meteo.com/en/docs",
                sourceType = "FAO_56"
            )
        )
        if (useGoogleMapsGrounding) {
            defaultCitations.add(
                GroundingCitation(
                    title = "Google Maps Spatial Sector Reference ($regionName: $latitude, $longitude)",
                    url = "https://www.google.com/maps/@$latitude,$longitude,16z",
                    sourceType = "GOOGLE_MAPS"
                )
            )
        }
        if (useGoogleSearchGrounding) {
            defaultCitations.add(
                GroundingCitation(
                    title = "FAO Agronomy & Crop Information Bulletins ($cropNameAr)",
                    url = "https://www.fao.org/land-water/databases-and-software/crop-information/en/",
                    sourceType = "GOOGLE_SEARCH"
                )
            )
        }

        val fallbackReply = buildSmartEgyptianArabicFallbackReply(
            farmerQuestion = userMessage,
            farmerName = farmerName,
            fieldName = fieldName,
            cropNameAr = cropNameAr,
            soilPh = soilPh,
            soilEc = soilEc,
            et0MmDay = et0MmDay,
            kcValue = kcValue,
            dailyM3PerFeddan = dailyM3PerFeddan,
            valveRunHours = valveRunHours,
            report = report
        ) + if (useGoogleSearchGrounding || useGoogleMapsGrounding) {
            "\n\n🌐 *المراجع الإرشادية (Reference Citations):*\n" +
                "• الموقع الجغرافي: `$latitude, $longitude` ($regionName)\n" +
                "• Copernicus Sentinel-2 L2A & NASA POWER Agroclimatology\n" +
                "• FAO Irrigation & Drainage Paper No. 56 / نشرات مركز البحوث الزراعية"
        } else {
            ""
        } + "\n\n$AGRONOMIC_SAFETY_DISCLAIMER_AR"

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext GeminiGroundedResponse(
                replyText = fallbackReply,
                citations = defaultCitations,
                isLiveAiGenerated = false
            )
        }

        try {
            val contentsArray = JSONArray()
            chatHistory.takeLast(8).forEach { msg ->
                val geminiRole = if (msg.role == "USER") "user" else "model"
                contentsArray.put(
                    JSONObject().apply {
                        put("role", geminiRole)
                        put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                    }
                )
            }
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                }
            )

            val requestJson = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemInstruction))
                    )
                )
                put("contents", contentsArray)
                if (useGoogleSearchGrounding) {
                    put(
                        "tools",
                        JSONArray().put(JSONObject().put("google_search", JSONObject()))
                    )
                }
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.35)
                    }
                )
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            val body = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext GeminiGroundedResponse(fallbackReply, defaultCitations, isLiveAiGenerated = false)
                }
                val respStr = response.body?.string().orEmpty()
                if (respStr.isBlank()) {
                    return@withContext GeminiGroundedResponse(fallbackReply, defaultCitations, isLiveAiGenerated = false)
                }
                val root = JSONObject(respStr)
                val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                val text = candidate
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()

                val parsedCitations = mutableListOf<GroundingCitation>()
                val groundingChunks = candidate
                    ?.optJSONObject("groundingMetadata")
                    ?.optJSONArray("groundingChunks")
                if (groundingChunks != null) {
                    for (i in 0 until groundingChunks.length()) {
                        val chunk = groundingChunks.optJSONObject(i)
                        val web = chunk?.optJSONObject("web")
                        if (web != null) {
                            val title = web.optString("title", "Google Search Grounding Source")
                            val uri = web.optString("uri", "")
                            if (uri.isNotBlank()) {
                                parsedCitations.add(
                                    GroundingCitation(
                                        title = title,
                                        url = uri,
                                        sourceType = "GOOGLE_SEARCH"
                                    )
                                )
                            }
                        }
                    }
                }
                val combinedCitations = (parsedCitations + defaultCitations).distinctBy { it.url }
                if (text.isNullOrBlank()) {
                    GeminiGroundedResponse(fallbackReply, combinedCitations, isLiveAiGenerated = false)
                } else {
                    GeminiGroundedResponse(
                        replyText = "$text\n\n$AGRONOMIC_SAFETY_DISCLAIMER_AR",
                        citations = combinedCitations,
                        isLiveAiGenerated = true
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            GeminiGroundedResponse(
                replyText = fallbackReply,
                citations = defaultCitations,
                isLiveAiGenerated = false
            )
        }
    }

    override suspend fun generateEpidemiologicalInsight(
        farmerName: String,
        fieldName: String,
        cropNameAr: String,
        report: EpidemiologyDiagnosisReport,
        soilPh: Float,
        soilEc: Float,
        tempMaxC: Float,
        humidityPct: Int,
        et0MmDay: Float
    ): String = withContext(Dispatchers.IO) {
        val systemPrompt = """
            أنت خبير أمراض نبات واستشعار عن بعد بالأقمار الصناعية (Sentinel-2) في منصة AgroSight المصرية.
            مهمتك تحليل المؤشرات الطيفية (NDRE=${formatDecimal2(report.ndreMean)}, PSRI=${formatDecimal2(report.psriMean)}, NDMI=${formatDecimal2(report.ndmiMean)}, NDVI=${formatDecimal2(report.ndviMean)})
            مع بيانات الأرصاد (الحرارة=${formatDecimal1(tempMaxC)}°C، الرطوبة=$humidityPct%، ET0=${formatDecimal2(et0MmDay)} مم/يوم)
            وكيمياء التربة (pH=${formatDecimal1(soilPh)}, ECe=${formatDecimal1(soilEc)} dS/m) لمحصول ($cropNameAr) في حقل ($fieldName) للمزارع ($farmerName).
            النمط المكاني المرصود: ${report.anomalyPattern.code} (${report.caseTitleAr}).
            اكتب تقريراً فنياً موجزاً (4 نقاط مركزة بالعربية الواضحة) يوضح:
            1. التفسير الطيفي الإرشادي للفرق بين المؤشر المرضي المحتمل والعطل الميكانيكي وقلوية التربة.
            2. توصية تعديل الري المقترحة للمحبس المتأثر.
            3. الإجراء الوقائي الموجه للبؤرة مع التأكيد على المعاينة الميدانية ومراجعة مهندس زراعي مختص.
        """.trimIndent()

        val fallback = "🔬 *تحليل استشاري طيفي (Agro-Epidemiology Advisory):*\n" +
            "• *المؤشر المكاني المقدر:* ${report.detectedRiskNameAr} (مؤشر توافق النمط الطيفي ${report.confidenceScorePct}%) في (${report.hotspotLocationAr}).\n" +
            "• *التفسير الفيزيائي:* قراءة الكلوروفيل المبكر (NDRE=${formatDecimal2(report.ndreMean)}) مع مؤشر شيخوخة الأنسجة (PSRI=${formatDecimal2(report.psriMean)}) ورطوبة المجموع الخضري (NDMI=${formatDecimal2(report.ndmiMean)}) تشير إلى ${if (report.isBiologicalDisease) "احتمال نشاط فطري بؤري يستلزم خفض الرطوبة الحرة وفحص البؤرة ميدانياً" else "رجحان سبب هيدروليكي/كيميائي غير فطري"}.\n" +
            "• *توصية الري والسمادة:* ${report.immediateIrrigationActionAr} (جرعة الحامض الإرشادية لمعادلة pH ${formatDecimal1(soilPh)} هي ${formatDecimal1(report.acidDoseLitersPerFeddan)} لتر/فدان).\n" +
            "• *التدخل الإرشادي الموجه:* ${report.targetedPreventiveActionAr}\n" +
            "$AGRONOMIC_SAFETY_DISCLAIMER_AR"

        callGeminiRestOrFallback(
            prompt = systemPrompt,
            fallbackText = fallback
        )
    }

    override suspend fun replyToFarmerWhatsAppMessage(
        farmerQuestion: String,
        farmerName: String,
        fieldName: String,
        cropNameAr: String,
        areaFeddans: Float,
        soilPh: Float,
        soilEc: Float,
        et0MmDay: Float,
        kcValue: Float,
        dailyM3PerFeddan: Float,
        valveRunHours: Float,
        report: EpidemiologyDiagnosisReport
    ): String = withContext(Dispatchers.IO) {
        val prompt = """
            أنت المساعد الاستشاري لمنصة "أجرو سايت" (AgroSight) على واتساب، تتحدث بلهجة زراعية مصرية محترمة وواضحة وعلمية مع المزارع "$farmerName".
            بيانات الحقل الحالية:
            - الحقل: $fieldName (${areaFeddans.toInt()} فدان) - المحصول: $cropNameAr (معامل Kc=${formatDecimal2(kcValue)})
            - البخر-نتح اليوم ET0=${formatDecimal2(et0MmDay)} مم/يوم | المقنن اليومي المقدر=${formatDecimal1(dailyM3PerFeddan)} م³/فدان | تشغيل المحبس=${formatDecimal1(valveRunHours)} ساعة
            - كيمياء التربة: pH=${formatDecimal1(soilPh)} | ملوحة التربة ECe=${formatDecimal1(soilEc)} dS/m | جرعة الحامض الإرشادية=${formatDecimal1(report.acidDoseLitersPerFeddan)} لتر/فدان
            - مؤشرات القمر الصناعي Sentinel-2: NDVI=${formatDecimal2(report.ndviMean)}, NDRE=${formatDecimal2(report.ndreMean)}, PSRI=${formatDecimal2(report.psriMean)}, NDMI=${formatDecimal2(report.ndmiMean)}
            - المؤشر الفضائي الحالي: ${report.detectedRiskNameAr} (${report.hotspotLocationAr}) - توافق النمط ${report.confidenceScorePct}%
            - توصية الري والوقاية: ${report.immediateIrrigationActionAr} | ${report.targetedPreventiveActionAr}

            سؤال المزارع على واتساب: "$farmerQuestion"
            أجب في رسالة واتساب منظمة ومختصرة ومباشرة مستخدماً الأرقام أعلاه مع التنبيه على مراجعة المهندس الزراعي المختص قبل تطبيق الكيماويات.
        """.trimIndent()

        val fallback = buildSmartEgyptianArabicFallbackReply(
            farmerQuestion = farmerQuestion,
            farmerName = farmerName,
            fieldName = fieldName,
            cropNameAr = cropNameAr,
            soilPh = soilPh,
            soilEc = soilEc,
            et0MmDay = et0MmDay,
            kcValue = kcValue,
            dailyM3PerFeddan = dailyM3PerFeddan,
            valveRunHours = valveRunHours,
            report = report
        ) + "\n\n$AGRONOMIC_SAFETY_DISCLAIMER_AR"

        callGeminiRestOrFallback(prompt = prompt, fallbackText = fallback)
    }

    override suspend fun generateCustomWorkerMessage(
        prompt: String,
        fallbackText: String
    ): String = withContext(Dispatchers.IO) {
        callGeminiRestOrFallback(prompt = prompt, fallbackText = fallbackText)
    }

    private fun callGeminiRestOrFallback(prompt: String, fallbackText: String): String {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return fallbackText
        }

        return try {
            val requestJson = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.35)
                    }
                )
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            val body = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return fallbackText
                val respStr = response.body?.string().orEmpty()
                if (respStr.isBlank()) return fallbackText
                val root = JSONObject(respStr)
                val text = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                if (text.isNullOrBlank()) fallbackText else "$text\n\n$AGRONOMIC_SAFETY_DISCLAIMER_AR"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallbackText
        }
    }

    private fun buildSmartEgyptianArabicFallbackReply(
        farmerQuestion: String,
        farmerName: String,
        fieldName: String,
        cropNameAr: String,
        soilPh: Float,
        soilEc: Float,
        et0MmDay: Float,
        kcValue: Float,
        dailyM3PerFeddan: Float,
        valveRunHours: Float,
        report: EpidemiologyDiagnosisReport
    ): String {
        val q = farmerQuestion.lowercase()
        return when {
            q.contains("مرض") || q.contains("اصفرار") || q.contains("بؤرة") || q.contains("رش") -> {
                "أهلاً بك يا $farmerName 🌿\n" +
                    "بخصوص سؤالك عن حالة محصول *$cropNameAr* في *$fieldName*:\n" +
                    "🛰️ *مؤشرات القمر الصناعي Sentinel-2 (نموذج استشعار عن بُعد):*\n" +
                    "• مؤشر الكلوروفيل المبكر `NDRE = ${formatDecimal2(report.ndreMean)}`\n" +
                    "• مؤشر تلف الأنسجة `PSRI = ${formatDecimal2(report.psriMean)}`\n" +
                    "• مؤشر رطوبة المجموع الخضري `NDMI = ${formatDecimal2(report.ndmiMean)}`\n\n" +
                    "🔎 *التقدير الإرشادي (توافق النمط ${report.confidenceScorePct}%):* ${report.detectedRiskNameAr} في (${report.hotspotLocationAr}).\n" +
                    "💧 *تعديل الري المقترح:* ${report.immediateIrrigationActionAr}\n" +
                    "🛡️ *التوصية الإرشادية:* ${report.targetedPreventiveActionAr}"
            }

            q.contains("حامض") || q.contains("ph") || q.contains("قلوي") || q.contains("ملوح") || q.contains("سماد") -> {
                "يا $farmerName، بالنسبة لكيمياء التربة في *$fieldName*:\n" +
                    "🧪 *رقم الحموضة المدخل:* `pH = ${formatDecimal1(soilPh)}` | *ملوحة التربة:* `ECe = ${formatDecimal1(soilEc)} dS/m`\n" +
                    "• التقدير الإرشادي لتقليل تثبيت الفوسفور والحديد: حقن نحو *${formatDecimal1(report.acidDoseLitersPerFeddan)} لتر/فدان حامض فوسفوريك (85%)* في الربع الأخير من وقت الري بعد معايرة قلوية مياه الري.\n" +
                    "• يُفضل استخدام الحديد المخلبي *EDDHA 6%* لثباته في الأراضي القلوية."
            }

            else -> {
                "أهلاً بك يا $farmerName 👋\n" +
                    "إليك الملخص الإرشادي لحقل *$fieldName* ($cropNameAr) بناءً على نموذج القمر الصناعي والأرصاد:\n" +
                    "☀️ *البخر-نتح المرجعي اليوم:* `ET0 = ${formatDecimal2(et0MmDay)} مم/يوم` (معامل المحصول `Kc = ${formatDecimal2(kcValue)}`)\n" +
                    "💧 *المقنن المائي المقدر:* `${formatDecimal1(dailyM3PerFeddan)} م³/فدان` (زمن تشغيل المحبس: *${formatDecimal1(valveRunHours)} ساعة*)\n" +
                    "🛰️ *رادار المؤشرات الطيفية:* ${report.detectedRiskNameAr} في (${report.hotspotLocationAr}).\n" +
                    "🧪 *جرعة الحامض الإرشادية لمعادلة pH (${formatDecimal1(soilPh)}):* `${formatDecimal1(report.acidDoseLitersPerFeddan)} لتر/فدان`."
            }
        }
    }
}
