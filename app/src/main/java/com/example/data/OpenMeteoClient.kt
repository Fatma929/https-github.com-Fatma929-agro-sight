@file:SuppressLint("NewApi")

package com.example.data

import android.annotation.SuppressLint
import com.example.model.AgroClimaticAlert
import com.example.model.AlertSeverity
import com.example.model.DailyMeteoRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

enum class TelemetryDataMode(val badgeAr: String) {
    LIVE("بيانات حية (LIVE)"),
    CACHED("بيانات مخزنة مؤقتاً (CACHED)"),
    DEMO_ESTIMATED("وضع تجريبي / تقديري (DEMO / ESTIMATED)")
}

data class OpenMeteoResult(
    val records: List<DailyMeteoRecord>,
    val isLiveConnected: Boolean,
    val statusMessageAr: String,
    val alerts: List<AgroClimaticAlert>,
    val httpStatusCode: Int = 0,
    val latencyMs: Long = 0L,
    val lastSuccessfulSyncIso: String = "",
    val dataMode: TelemetryDataMode = if (isLiveConnected) TelemetryDataMode.LIVE else TelemetryDataMode.DEMO_ESTIMATED,
    val errorDetailAr: String? = null
)

interface MeteoForecastDataSource {
    suspend fun fetch7DayAgroForecast(
        latitude: Double,
        longitude: Double,
        baseKc: Float
    ): OpenMeteoResult
}

class OpenMeteoClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : MeteoForecastDataSource {

    @Volatile
    private var lastCachedLiveResult: OpenMeteoResult? = null

    override suspend fun fetch7DayAgroForecast(
        latitude: Double,
        longitude: Double,
        baseKc: Float
    ): OpenMeteoResult = withContext(Dispatchers.IO) {
        if (latitude.isNaN() || longitude.isNaN() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return@withContext buildFallbackForecast(
                latitude = latitude.coerceIn(-90.0, 90.0),
                longitude = longitude.coerceIn(-180.0, 180.0),
                baseKc = baseKc.coerceIn(0.20f, 1.45f),
                isLive = false,
                httpStatusCode = 400,
                latencyMs = 0L,
                errorReasonAr = "إحداثيات جغرافية غير صالحة ($latitude, $longitude)"
            )
        }

        val safeLat = latitude
        val safeLon = longitude
        val safeKc = baseKc.coerceIn(0.20f, 1.45f)

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$safeLat" +
            "&longitude=$safeLon" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,et0_fao_evapotranspiration,shortwave_radiation_sum" +
            "&hourly=relative_humidity_2m" +
            "&timezone=Africa%2FCairo"

        val startMs = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val code = response.code
                if (!response.isSuccessful) {
                    val cached = lastCachedLiveResult
                    if (cached != null) {
                        return@withContext cached.copy(
                            isLiveConnected = false,
                            httpStatusCode = code,
                            latencyMs = elapsed,
                            dataMode = TelemetryDataMode.CACHED,
                            statusMessageAr = "بيانات مخزنة مؤقتاً (CACHED) — تعذر التحديث الحي من Open-Meteo (HTTP $code)",
                            errorDetailAr = "HTTP $code"
                        )
                    }
                    return@withContext buildFallbackForecast(
                        latitude = safeLat,
                        longitude = safeLon,
                        baseKc = safeKc,
                        isLive = false,
                        httpStatusCode = code,
                        latencyMs = elapsed,
                        errorReasonAr = "استجابة غير ناجحة من خادم Open-Meteo (HTTP $code)"
                    )
                }
                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isBlank()) {
                    return@withContext buildFallbackForecast(
                        latitude = safeLat,
                        longitude = safeLon,
                        baseKc = safeKc,
                        isLive = false,
                        httpStatusCode = code,
                        latencyMs = elapsed,
                        errorReasonAr = "محتوى فارغ من خادم Open-Meteo"
                    )
                }
                val parsed = parseOpenMeteoJson(
                    jsonStr = bodyStr,
                    baseKc = safeKc,
                    httpStatusCode = code,
                    latencyMs = elapsed
                )
                lastCachedLiveResult = parsed
                parsed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val cached = lastCachedLiveResult
            if (cached != null) {
                return@withContext cached.copy(
                    isLiveConnected = false,
                    httpStatusCode = 0,
                    latencyMs = elapsed,
                    dataMode = TelemetryDataMode.CACHED,
                    statusMessageAr = "بيانات مخزنة مؤقتاً (CACHED) — انقطاع الاتصال بالشبكة (${e.javaClass.simpleName})",
                    errorDetailAr = e.javaClass.simpleName
                )
            }
            buildFallbackForecast(
                latitude = safeLat,
                longitude = safeLon,
                baseKc = safeKc,
                isLive = false,
                httpStatusCode = 0,
                latencyMs = elapsed,
                errorReasonAr = "تعذر الاتصال بالشبكة (${e.javaClass.simpleName})"
            )
        }
    }

    internal fun parseOpenMeteoJson(
        jsonStr: String,
        baseKc: Float,
        httpStatusCode: Int = 200,
        latencyMs: Long = 1L
    ): OpenMeteoResult {
        val root = JSONObject(jsonStr)
        val daily = root.getJSONObject("daily")
        val timeArr = daily.getJSONArray("time")
        val tMaxArr = daily.getJSONArray("temperature_2m_max")
        val tMinArr = daily.getJSONArray("temperature_2m_min")
        val precipArr = daily.getJSONArray("precipitation_sum")
        val windArr = daily.getJSONArray("wind_speed_10m_max")
        val et0Arr = daily.getJSONArray("et0_fao_evapotranspiration")
        val radArr = daily.getJSONArray("shortwave_radiation_sum")

        val hourlyRhArr = root.optJSONObject("hourly")?.optJSONArray("relative_humidity_2m")

        val count = minOf(7, timeArr.length())
        require(count > 0) { "Open-Meteo daily array is empty" }

        val rawDays = mutableListOf<DailyMeteoRecord>()
        var cumulativeDeficit = 0f

        for (i in 0 until count) {
            val dateIso = timeArr.optString(i, "")
            val dayLabel = formatArabicDayName(dateIso, i)
            val tMax = tMaxArr.optDouble(i, 31.0).toFloat().coerceIn(-15f, 58f)
            val tMin = tMinArr.optDouble(i, 18.0).toFloat().coerceIn(-20f, tMax)
            val precip = precipArr.optDouble(i, 0.0).toFloat().coerceIn(0f, 300f)
            val wind = windArr.optDouble(i, 16.0).toFloat().coerceIn(0f, 150f)
            val et0 = et0Arr.optDouble(i, 5.4).toFloat().coerceIn(0.3f, 18.0f)
            val rad = radArr.optDouble(i, 23.5).toFloat().coerceIn(0.5f, 42.0f)

            var rhSum = 0
            var rhCount = 0
            if (hourlyRhArr != null) {
                val startHr = i * 24
                val endHr = minOf(hourlyRhArr.length(), startHr + 24)
                for (h in startHr until endHr) {
                    val v = hourlyRhArr.optInt(h, -1)
                    if (v in 1..100) {
                        rhSum += v
                        rhCount++
                    }
                }
            }
            val avgRh = if (rhCount > 0) (rhSum / rhCount).coerceIn(8, 99) else 44

            // FAO-56 Eq. 47 logarithmic wind profile conversion from 10m (km/h) to 2m (m/s)
            val u10Ms = wind / 3.6f
            val u2 = (u10Ms * (4.87f / ln(67.8f * 10f - 5.42f))).coerceAtLeast(0.5f)
            val climateDelta = (0.04f * (u2 - 2f) - 0.004f * (avgRh - 45f)).coerceIn(-0.08f, 0.14f)
            val adjustedKc = (baseKc + climateDelta).coerceIn(0.25f, 1.35f)
            val effectiveRain = if (precip > 2.0f) precip * 0.80f else 0f
            val etc = max(0.8f, et0 * adjustedKc - effectiveRain)
            val dailyDeficitM3 = etc * 4.2f // 1 mm depth on 1 Feddan (4200 m²) = 4.2 m³
            cumulativeDeficit += dailyDeficitM3

            rawDays.add(
                DailyMeteoRecord(
                    dateIso = dateIso,
                    dayLabelAr = dayLabel,
                    tempMaxC = tMax,
                    tempMinC = tMin,
                    relativeHumidityPct = avgRh,
                    windSpeedKmh = wind,
                    solarRadiationMjM2 = rad,
                    precipitationMm = precip,
                    et0MmDay = et0,
                    adjustedKc = adjustedKc,
                    etcMmDay = etc,
                    waterDeficitM3PerFeddan = dailyDeficitM3,
                    cumulativeDeficitM3PerFeddan = cumulativeDeficit
                )
            )
        }

        val syncIso = Instant.now().toString().take(19).replace('T', ' ')
        return OpenMeteoResult(
            records = rawDays,
            isLiveConnected = true,
            statusMessageAr = "[LIVE] بيانات أرصاد حية من محطة Open-Meteo FAO-56 (HTTP $httpStatusCode • ${latencyMs}ms • $syncIso UTC)",
            alerts = buildAgroAlerts(rawDays),
            httpStatusCode = httpStatusCode,
            latencyMs = latencyMs,
            lastSuccessfulSyncIso = syncIso,
            dataMode = TelemetryDataMode.LIVE,
            errorDetailAr = null
        )
    }

    fun buildFallbackForecast(
        latitude: Double,
        longitude: Double,
        baseKc: Float,
        isLive: Boolean = false,
        httpStatusCode: Int = 0,
        latencyMs: Long = 0L,
        errorReasonAr: String? = null
    ): OpenMeteoResult {
        val southWarmthOffset = ((31.0 - latitude) * 0.85).toFloat().coerceIn(-1.5f, 7.5f)
        val baseTempsMax = listOf(31.5f, 33.0f, 35.8f, 36.4f, 32.8f, 30.9f, 31.4f)
        val baseTempsMin = listOf(18.2f, 19.0f, 20.5f, 21.2f, 19.4f, 18.0f, 18.5f)
        val baseWind = listOf(18.5f, 21.0f, 27.4f, 24.0f, 19.2f, 16.5f, 17.8f)
        val baseEt0 = listOf(5.4f, 5.8f, 6.7f, 6.9f, 5.9f, 5.3f, 5.5f)
        val baseRad = listOf(23.8f, 24.5f, 26.1f, 26.4f, 24.9f, 23.4f, 24.0f)
        val baseRh = listOf(46, 42, 34, 32, 41, 48, 45)

        val today = LocalDate.now()
        var cumulativeDeficit = 0f
        val records = (0 until 7).map { i ->
            val date = today.plusDays(i.toLong())
            val dateIso = date.toString()
            val tMax = baseTempsMax[i] + southWarmthOffset
            val tMin = baseTempsMin[i] + southWarmthOffset * 0.7f
            val wind = baseWind[i]
            val et0 = baseEt0[i] + southWarmthOffset * 0.22f
            val rad = baseRad[i] + southWarmthOffset * 0.3f
            val rh = (baseRh[i] - (southWarmthOffset * 1.8f).roundToInt()).coerceIn(18, 80)

            val u10Ms = wind / 3.6f
            val u2 = (u10Ms * (4.87f / ln(67.8f * 10f - 5.42f))).coerceAtLeast(0.5f)
            val climateDelta = (0.04f * (u2 - 2f) - 0.004f * (rh - 45f)).coerceIn(-0.08f, 0.14f)
            val adjKc = (baseKc + climateDelta).coerceIn(0.25f, 1.35f)
            val etc = max(1.0f, et0 * adjKc)
            val deficitM3 = etc * 4.2f
            cumulativeDeficit += deficitM3

            DailyMeteoRecord(
                dateIso = dateIso,
                dayLabelAr = formatArabicDayName(dateIso, i),
                tempMaxC = tMax,
                tempMinC = tMin,
                relativeHumidityPct = rh,
                windSpeedKmh = wind,
                solarRadiationMjM2 = rad,
                precipitationMm = 0f,
                et0MmDay = et0,
                adjustedKc = adjKc,
                etcMmDay = etc,
                waterDeficitM3PerFeddan = deficitM3,
                cumulativeDeficitM3PerFeddan = cumulativeDeficit
            )
        }

        val reasonSuffix = if (!errorReasonAr.isNullOrBlank()) " — السبب: $errorReasonAr" else ""
        return OpenMeteoResult(
            records = records,
            isLiveConnected = isLive,
            statusMessageAr = "[DEMO / ESTIMATED] وضع تقديري احتياطي (Offline Baseline): نموذج مناخي استرشادي للإحداثيات (${"%.2f".format(Locale.US, latitude)}°N, ${"%.2f".format(Locale.US, longitude)}°E)$reasonSuffix",
            alerts = buildAgroAlerts(records),
            httpStatusCode = if (isLive) 200 else httpStatusCode,
            latencyMs = latencyMs,
            lastSuccessfulSyncIso = LocalDate.now().toString(),
            dataMode = if (isLive) TelemetryDataMode.LIVE else TelemetryDataMode.DEMO_ESTIMATED,
            errorDetailAr = errorReasonAr
        )
    }

    private fun buildAgroAlerts(records: List<DailyMeteoRecord>): List<AgroClimaticAlert> {
        if (records.isEmpty()) return emptyList()
        val alerts = mutableListOf<AgroClimaticAlert>()
        val peakHeatDay = records.maxByOrNull { it.tempMaxC }
        val peakWindDay = records.maxByOrNull { it.windSpeedKmh }
        val peakEt0Day = records.maxByOrNull { it.et0MmDay }

        if (peakHeatDay != null && peakHeatDay.tempMaxC >= 35.0f) {
            alerts.add(
                AgroClimaticAlert(
                    titleAr = "تحذير موجة حارة (${"%.1f".format(Locale.US, peakHeatDay.tempMaxC)}°C) - ${peakHeatDay.dayLabelAr}",
                    detailAr = "ارتفاع الحرارة فوق 35°م يضاعف الإجهاد الحراري والبخر-نتح؛ يُنصح بنقل مواعيد الري إلى الفجر أو المساء وتجنب التسميد الآزوتي الثقيل وقت الذروة.",
                    severity = AlertSeverity.CRITICAL_HEAT
                )
            )
        }
        if (peakWindDay != null && peakWindDay.windSpeedKmh >= 25.0f) {
            alerts.add(
                AgroClimaticAlert(
                    titleAr = "نشاط رياح مرتفع (${"%.1f".format(Locale.US, peakWindDay.windSpeedKmh)} كم/س) - ${peakWindDay.dayLabelAr}",
                    detailAr = "سرعة الرياح تتجاوز 25 كم/س؛ يُنصح بتأجيل الرش الورقي للمبيدات والعناصر، ومراعاة انخفاض كفاءة توزيع البيفوت والرشاشات الثابتة.",
                    severity = AlertSeverity.HIGH_WIND
                )
            )
        }
        if (peakEt0Day != null && peakEt0Day.et0MmDay >= 6.0f) {
            alerts.add(
                AgroClimaticAlert(
                    titleAr = "طلب بخر-نتح مرتفع (ET0 = ${"%.2f".format(Locale.US, peakEt0Day.et0MmDay)} مم/يوم)",
                    detailAr = "الإشعاع الشمسي وانخفاض الرطوبة النسبية يرفعان الفقد المائي؛ تم تعديل معامل المحصول (Kc) تلقائياً لتعويض البخر.",
                    severity = AlertSeverity.HIGH_EVAPORATION
                )
            )
        }
        if (alerts.isEmpty()) {
            alerts.add(
                AgroClimaticAlert(
                    titleAr = "استقرار مناخي مناسب للري والتسميد",
                    detailAr = "درجات الحرارة وسرعة الرياح ضمن النطاق المعتاد لأنظمة الري والرش الورقي خلال الـ 7 أيام القادمة.",
                    severity = AlertSeverity.OPTIMAL_CLIMATE
                )
            )
        }
        return alerts
    }

    private fun formatArabicDayName(dateIso: String, index: Int): String {
        if (index == 0) return "اليوم"
        if (index == 1) return "غداً"
        return try {
            val date = LocalDate.parse(dateIso)
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("ar"))
        } catch (_: Exception) {
            "يوم ${index + 1}"
        }
    }
}
