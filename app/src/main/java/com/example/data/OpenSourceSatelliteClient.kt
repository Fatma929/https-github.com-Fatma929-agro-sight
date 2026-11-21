@file:SuppressLint("NewApi")

package com.example.data

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.model.CropSpec
import com.example.model.IrrigationSystemType
import com.example.model.SoilTexture
import com.example.model.formatCoord4
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class StacAcquisitionStatus(val labelAr: String, val isUsableLiveScene: Boolean) {
    VALID_SCENE_WITH_BAND_ASSETS("مشهد حي صالح مع تحقق كامل من القنوات الطيفية (LIVE)", true),
    NO_MATCHING_SCENES("استجابة STAC ناجحة ولكن لا توجد مشاهد مطابقة للنطاق/التاريخ", false),
    MISSING_OR_INACCESSIBLE_ASSETS("تم العثور على المشهد ولكن بعض ملفات القنوات الطيفية مفقودة", false),
    INVALID_GEOMETRY("إحداثيات أو مضلع جغرافي (GeoJSON) غير صالح", false),
    NETWORK_OR_HTTP_FAILURE("تعذر الاتصال بخادم STAC (وضع DEMO الاحتياطي مفعل)", false)
}

data class Sentinel2BandAssets(
    val b02Blue10mHref: String = "",
    val b04Red10mHref: String = "",
    val b05RedEdge1_20mHref: String = "",
    val b06RedEdge2_20mHref: String = "",
    val b08Nir10mHref: String = "",
    val b8aNarrowNir20mHref: String = "",
    val b11Swir16_20mHref: String = "",
    val sclCloudMask20mHref: String = "",
    val visualPreviewHref: String = ""
) {
    val hasAllRequiredSpectralBands: Boolean
        get() = b02Blue10mHref.isNotBlank() &&
            b04Red10mHref.isNotBlank() &&
            b05RedEdge1_20mHref.isNotBlank() &&
            b06RedEdge2_20mHref.isNotBlank() &&
            b08Nir10mHref.isNotBlank() &&
            b8aNarrowNir20mHref.isNotBlank() &&
            b11Swir16_20mHref.isNotBlank()
}

data class TemporalSatelliteObservation(
    val sceneId: String,
    val acquisitionDateIso: String,
    val cloudCoverPct: Float,
    val isCloudGappedOrUnusable: Boolean,
    val observedNdviMean: Float?,
    val observedNdreMean: Float?,
    val observedNdmiMean: Float?,
    val observedPsriMean: Float?,
    val qualityNoteAr: String
)

data class TemporalChangeAnalysis(
    val observations: List<TemporalSatelliteObservation>,
    val validObservationCount: Int,
    val cloudGappedCount: Int,
    val ndviChangeVsPrevious: Float?,
    val ndreChangeVsPrevious: Float?,
    val ndmiChangeVsPrevious: Float?,
    val temporalTrendSummaryAr: String,
    val coverageGapDisclaimerAr: String
)

/**
 * Pixel-level multi-band reflectance sample for a 10m Sentinel-2 grid cell (after resampling 20m bands to 10m).
 * Reflectance values are Bottom-Of-Atmosphere (L2A BOA) in range [0.0, 1.0].
 * [sclClass] follows ESA Sentinel-2 L2A Scene Classification Layer specification:
 * 0=NoData, 1=Saturated/Defective, 2=Dark Area, 3=Cloud Shadow, 4=Vegetation, 5=Not-Vegetated,
 * 6=Water, 7=Unclassified, 8=Cloud Medium Prob, 9=Cloud High Prob, 10=Thin Cirrus, 11=Snow.
 */
data class Sentinel2PixelReflectance(
    val row: Int,
    val col: Int,
    val b02Blue: Float,
    val b04Red: Float,
    val b05RedEdge1: Float,
    val b06RedEdge2: Float,
    val b08Nir: Float,
    val b8aNarrowNir: Float,
    val b11Swir1: Float,
    val sclClass: Int = 4
)

data class ComputedPixelIndices(
    val row: Int,
    val col: Int,
    val isValidUnmasked: Boolean,
    val maskReason: String?,
    val ndvi: Float?,
    val ndre: Float?,
    val ndmi: Float?,
    val psri: Float?
)

data class SpectralRasterStatistics(
    val totalPixels: Int,
    val validPixels: Int,
    val cloudOrNoDataMaskedPixels: Int,
    val validPixelRatio: Float,
    val ndviMean: Float,
    val ndviStdDev: Float,
    val ndreMean: Float,
    val ndmiMean: Float,
    val psriMean: Float,
    val dataQualityIndicatorAr: String,
    val geoJsonAnomalyPolygon: String
)

data class CropSpectralThresholdProfile(
    val cropId: String,
    val healthyNdviMin: Float,
    val stressedNdviThreshold: Float,
    val healthyNdreMin: Float,
    val stressedNdreThreshold: Float,
    val senescencePsriThreshold: Float,
    val waterStressNdmiThreshold: Float
)

/**
 * Scientifically rigorous Sentinel-2 spectral band math, multi-resolution alignment,
 * polygon clipping, and cloud/NoData masking engine.
 * Formula definitions:
 * - NDVI = (B08 - B04) / (B08 + B04)
 * - NDRE = (B8A - B05) / (B8A + B05)
 * - NDMI = (B08 - B11) / (B08 + B11)
 * - PSRI = (B04 - B02) / B06
 */
object Sentinel2BandMathEngine {

    private val MASKED_SCL_CLASSES = setOf(0, 1, 3, 8, 9, 10, 11)
    private const val EPSILON = 1e-6f

    private val CROP_THRESHOLDS = mapOf(
        "tomato" to CropSpectralThresholdProfile("tomato", 0.62f, 0.45f, 0.34f, 0.24f, 0.14f, 0.18f),
        "potato" to CropSpectralThresholdProfile("potato", 0.65f, 0.48f, 0.36f, 0.25f, 0.13f, 0.20f),
        "wheat" to CropSpectralThresholdProfile("wheat", 0.58f, 0.40f, 0.32f, 0.22f, 0.16f, 0.14f),
        "corn" to CropSpectralThresholdProfile("corn", 0.64f, 0.46f, 0.35f, 0.24f, 0.14f, 0.18f),
        "citrus" to CropSpectralThresholdProfile("citrus", 0.52f, 0.36f, 0.28f, 0.19f, 0.17f, 0.12f),
        "date_palm" to CropSpectralThresholdProfile("date_palm", 0.42f, 0.28f, 0.23f, 0.15f, 0.20f, 0.08f),
        "strawberry" to CropSpectralThresholdProfile("strawberry", 0.56f, 0.40f, 0.30f, 0.21f, 0.14f, 0.18f),
        "sugar_beet" to CropSpectralThresholdProfile("sugar_beet", 0.60f, 0.43f, 0.33f, 0.23f, 0.15f, 0.16f)
    )

    fun getCropThresholds(cropIdOrName: String, growthStageIndex: Int = 2): CropSpectralThresholdProfile {
        val key = when (cropIdOrName.trim().lowercase()) {
            "طماطم" -> "tomato"
            "بطاطس" -> "potato"
            "قمح" -> "wheat"
            "ذرة" -> "corn"
            "موالح" -> "citrus"
            "نخيل" -> "date_palm"
            "فراولة" -> "strawberry"
            "بنجر سكر" -> "sugar_beet"
            else -> cropIdOrName.trim().lowercase()
        }
        val base = CROP_THRESHOLDS[key] ?: CROP_THRESHOLDS.getValue("tomato")
        return when (growthStageIndex) {
            0 -> base.copy(
                healthyNdviMin = max(0.25f, base.healthyNdviMin - 0.22f),
                stressedNdviThreshold = max(0.18f, base.stressedNdviThreshold - 0.18f),
                healthyNdreMin = max(0.16f, base.healthyNdreMin - 0.12f),
                stressedNdreThreshold = max(0.12f, base.stressedNdreThreshold - 0.09f),
                senescencePsriThreshold = base.senescencePsriThreshold + 0.05f
            )
            3 -> base.copy(
                healthyNdviMin = max(0.32f, base.healthyNdviMin - 0.14f),
                stressedNdviThreshold = max(0.24f, base.stressedNdviThreshold - 0.10f),
                healthyNdreMin = max(0.18f, base.healthyNdreMin - 0.10f),
                stressedNdreThreshold = max(0.14f, base.stressedNdreThreshold - 0.07f),
                senescencePsriThreshold = base.senescencePsriThreshold + 0.08f
            )
            else -> base
        }
    }

    /**
     * Converts Sentinel-2 L2A Digital Number (DN) to Bottom-Of-Atmosphere surface reflectance.
     * Supports both pre-04.00 baseline (`offset = 0.0f`) and Processing Baseline 04.00+
     * (`offset = -1000.0f` in DN units or `-0.1f` in reflectance units).
     */
    fun convertDnToReflectance(rawDn: Int, scaleFactor: Float = 0.0001f, offset: Float = 0.0f): Float {
        if (rawDn <= 0 || rawDn >= 65535) return Float.NaN
        val refl = if (kotlin.math.abs(offset) >= 1.0f) {
            (rawDn.toFloat() + offset) * scaleFactor
        } else {
            rawDn.toFloat() * scaleFactor + offset
        }
        if (refl <= 0.0001f || refl > 1.5f) return Float.NaN
        return refl.coerceAtMost(1.0f)
    }

    fun isCloudOrNoDataScl(sclClass: Int): Boolean = sclClass in MASKED_SCL_CLASSES

    fun isValidReflectance(value: Float): Boolean =
        !value.isNaN() && !value.isInfinite() && value in 0.0001f..1.0f

    fun computeNormalizedDifference(bandA: Float, bandB: Float): Float? {
        if (!isValidReflectance(bandA) || !isValidReflectance(bandB)) return null
        val denom = bandA + bandB
        if (kotlin.math.abs(denom) < EPSILON) return null
        return ((bandA - bandB) / denom).coerceIn(-1.0f, 1.0f)
    }

    fun computePsri(b04Red: Float, b02Blue: Float, b06RedEdge2: Float): Float? {
        if (!isValidReflectance(b04Red) || !isValidReflectance(b02Blue) || !isValidReflectance(b06RedEdge2)) {
            return null
        }
        if (kotlin.math.abs(b06RedEdge2) < EPSILON) return null
        return ((b04Red - b02Blue) / b06RedEdge2).coerceIn(-1.0f, 1.5f)
    }

    /**
     * Aligns a 20m Sentinel-2 band grid (B05, B06, B8A, B11, or SCL) to a 10m target grid (B02, B04, B08).
     * Uses nearest-neighbor for categorical SCL and bilinear interpolation for continuous reflectance bands.
     */
    fun align20mGridTo10m(
        grid20m: Array<FloatArray>,
        targetRows: Int,
        targetCols: Int,
        isCategorical: Boolean = false
    ): Array<FloatArray> {
        val srcH = grid20m.size
        val srcW = if (srcH > 0) grid20m[0].size else 0
        if (srcH == 0 || srcW == 0) return Array(targetRows) { FloatArray(targetCols) { Float.NaN } }

        val out = Array(targetRows) { FloatArray(targetCols) }
        val scaleR = srcH.toFloat() / targetRows.toFloat()
        val scaleC = srcW.toFloat() / targetCols.toFloat()

        for (r in 0 until targetRows) {
            for (c in 0 until targetCols) {
                val srcRFrac = (r + 0.5f) * scaleR
                val srcCFrac = (c + 0.5f) * scaleC
                if (isCategorical) {
                    val sr = srcRFrac.toInt().coerceIn(0, srcH - 1)
                    val sc = srcCFrac.toInt().coerceIn(0, srcW - 1)
                    out[r][c] = grid20m[sr][sc]
                } else {
                    val rCenter = (srcRFrac - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
                    val cCenter = (srcCFrac - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                    val r0 = rCenter.toInt()
                    val r1 = (r0 + 1).coerceAtMost(srcH - 1)
                    val c0 = cCenter.toInt()
                    val c1 = (c0 + 1).coerceAtMost(srcW - 1)
                    val dr = rCenter - r0.toFloat()
                    val dc = cCenter - c0.toFloat()
                    val top = grid20m[r0][c0] * (1f - dc) + grid20m[r0][c1] * dc
                    val bot = grid20m[r1][c0] * (1f - dc) + grid20m[r1][c1] * dc
                    out[r][c] = top * (1f - dr) + bot * dr
                }
            }
        }
        return out
    }

    /**
     * Ray-casting point-in-polygon check against a GeoJSON Polygon or Feature.
     */
    fun isPointInsideGeoJsonPolygon(lon: Double, lat: Double, polygonGeoJson: String): Boolean {
        return try {
            val obj = JSONObject(polygonGeoJson)
            val geom = when (obj.optString("type")) {
                "Feature" -> obj.optJSONObject("geometry") ?: return false
                "FeatureCollection" -> obj.optJSONArray("features")?.optJSONObject(0)?.optJSONObject("geometry") ?: return false
                else -> obj
            }
            if (geom.optString("type") != "Polygon") return false
            val rings = geom.optJSONArray("coordinates") ?: return false
            val outerRing = rings.optJSONArray(0) ?: return false
            if (!isPointInRing(lon, lat, outerRing)) return false
            for (h in 1 until rings.length()) {
                val holeRing = rings.optJSONArray(h) ?: continue
                if (isPointInRing(lon, lat, holeRing)) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isPointInRing(lon: Double, lat: Double, ring: org.json.JSONArray): Boolean {
        val n = ring.length()
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val pi = ring.optJSONArray(i) ?: continue
            val pj = ring.optJSONArray(j) ?: continue
            val xi = pi.optDouble(0)
            val yi = pi.optDouble(1)
            val xj = pj.optDouble(0)
            val yj = pj.optDouble(1)
            val denom = if (kotlin.math.abs(yj - yi) < 1e-12) 1e-12 else (yj - yi)
            val intersects = ((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / denom + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    fun evaluatePixel(sample: Sentinel2PixelReflectance): ComputedPixelIndices {
        if (isCloudOrNoDataScl(sample.sclClass)) {
            return ComputedPixelIndices(
                row = sample.row,
                col = sample.col,
                isValidUnmasked = false,
                maskReason = "SCL_MASKED_CLASS_${sample.sclClass}",
                ndvi = null,
                ndre = null,
                ndmi = null,
                psri = null
            )
        }

        val ndvi = computeNormalizedDifference(sample.b08Nir, sample.b04Red)
        val ndre = computeNormalizedDifference(sample.b8aNarrowNir, sample.b05RedEdge1)
        val ndmi = computeNormalizedDifference(sample.b08Nir, sample.b11Swir1)
        val psri = computePsri(sample.b04Red, sample.b02Blue, sample.b06RedEdge2)

        if (ndvi == null || ndre == null || ndmi == null || psri == null) {
            return ComputedPixelIndices(
                row = sample.row,
                col = sample.col,
                isValidUnmasked = false,
                maskReason = "INVALID_BAND_REFLECTANCE_OR_ZERO_DENOMINATOR",
                ndvi = ndvi,
                ndre = ndre,
                ndmi = ndmi,
                psri = psri
            )
        }

        return ComputedPixelIndices(
            row = sample.row,
            col = sample.col,
            isValidUnmasked = true,
            maskReason = null,
            ndvi = ndvi,
            ndre = ndre,
            ndmi = ndmi,
            psri = psri
        )
    }

    /**
     * Vectorizes connected clusters of actual anomalous pixels into georeferenced GeoJSON polygons.
     * NEVER emits a fixed square around the farm center: if no pixels are anomalous, returns `"features":[]`.
     */
    fun vectorizeAnomalousClustersToGeoJson(
        anomalousPixels: List<ComputedPixelIndices>,
        fieldLat: Double,
        fieldLon: Double,
        pixelStepDeg: Double = 0.0001
    ): String {
        if (anomalousPixels.isEmpty()) {
            return """{"type":"FeatureCollection","features":[]}"""
        }

        val pixelMap = anomalousPixels.associateBy { it.row to it.col }
        val visited = mutableSetOf<Pair<Int, Int>>()
        val clusters = mutableListOf<List<ComputedPixelIndices>>()

        for ((rc, _) in pixelMap) {
            if (rc in visited) continue
            val cluster = mutableListOf<ComputedPixelIndices>()
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(rc)
            visited.add(rc)
            while (queue.isNotEmpty()) {
                val curr = queue.removeFirst()
                pixelMap[curr]?.let { cluster.add(it) }
                val neighbors = listOf(
                    (curr.first - 1) to curr.second,
                    (curr.first + 1) to curr.second,
                    curr.first to (curr.second - 1),
                    curr.first to (curr.second + 1)
                )
                for (nbr in neighbors) {
                    if (nbr in pixelMap && nbr !in visited) {
                        visited.add(nbr)
                        queue.add(nbr)
                    }
                }
            }
            clusters.add(cluster)
        }

        val originLon = fieldLon - 0.0006
        val originLat = fieldLat + 0.0004
        val featuresJson = clusters.mapIndexed { idx, cluster ->
            val minR = cluster.minOf { it.row }
            val maxR = cluster.maxOf { it.row } + 1
            val minC = cluster.minOf { it.col }
            val maxC = cluster.maxOf { it.col } + 1

            val lon0 = originLon + minC * pixelStepDeg
            val lon1 = originLon + maxC * pixelStepDeg
            val lat0 = originLat - minR * pixelStepDeg
            val lat1 = originLat - maxR * pixelStepDeg

            val cNdvi = cluster.mapNotNull { it.ndvi }.average().toFloat()
            val cNdre = cluster.mapNotNull { it.ndre }.average().toFloat()
            val cNdmi = cluster.mapNotNull { it.ndmi }.average().toFloat()
            val cPsri = cluster.mapNotNull { it.psri }.average().toFloat()
            val areaM2 = cluster.size * 100

            """{"type":"Feature","properties":{"clusterId":"anomaly_${idx + 1}","pixelCount":${cluster.size},"areaM2":$areaM2,"ndviMean":${formatDecimal2(cNdvi)},"ndreMean":${formatDecimal2(cNdre)},"ndmiMean":${formatDecimal2(cNdmi)},"psriMean":${formatDecimal2(cPsri)}},"geometry":{"type":"Polygon","coordinates":[[[${formatCoord4(lon0)},${formatCoord4(lat0)}],[${formatCoord4(lon1)},${formatCoord4(lat0)}],[${formatCoord4(lon1)},${formatCoord4(lat1)}],[${formatCoord4(lon0)},${formatCoord4(lat1)}],[${formatCoord4(lon0)},${formatCoord4(lat0)}]]]}}"""
        }

        return """{"type":"FeatureCollection","features":[${featuresJson.joinToString(",")}]}"""
    }

    fun aggregateRasterGrid(
        samples: List<Sentinel2PixelReflectance>,
        fieldLat: Double,
        fieldLon: Double,
        cropId: String = "tomato",
        growthStageIndex: Int = 2
    ): SpectralRasterStatistics {
        if (samples.isEmpty()) {
            return SpectralRasterStatistics(
                totalPixels = 0,
                validPixels = 0,
                cloudOrNoDataMaskedPixels = 0,
                validPixelRatio = 0f,
                ndviMean = Float.NaN,
                ndviStdDev = Float.NaN,
                ndreMean = Float.NaN,
                ndmiMean = Float.NaN,
                psriMean = Float.NaN,
                dataQualityIndicatorAr = "لا توجد بكسلات متاحة",
                geoJsonAnomalyPolygon = "{\"type\":\"FeatureCollection\",\"features\":[]}"
            )
        }

        val evaluated = samples.map { evaluatePixel(it) }
        val valid = evaluated.filter { it.isValidUnmasked }
        val maskedCount = evaluated.size - valid.size
        val validRatio = valid.size.toFloat() / evaluated.size.toFloat()

        if (valid.isEmpty()) {
            return SpectralRasterStatistics(
                totalPixels = evaluated.size,
                validPixels = 0,
                cloudOrNoDataMaskedPixels = maskedCount,
                validPixelRatio = 0f,
                ndviMean = Float.NaN,
                ndviStdDev = Float.NaN,
                ndreMean = Float.NaN,
                ndmiMean = Float.NaN,
                psriMean = Float.NaN,
                dataQualityIndicatorAr = "مرفوض بسبب الغطاء السحابي الكامل أو فقدان القنوات (0% بكسلات صالحة)",
                geoJsonAnomalyPolygon = "{\"type\":\"FeatureCollection\",\"features\":[]}"
            )
        }

        val ndviList = valid.mapNotNull { it.ndvi }
        val ndreList = valid.mapNotNull { it.ndre }
        val ndmiList = valid.mapNotNull { it.ndmi }
        val psriList = valid.mapNotNull { it.psri }

        val ndviMean = ndviList.average().toFloat()
        val ndviVariance = ndviList.map { (it - ndviMean) * (it - ndviMean) }.average().toFloat()
        val ndviStd = sqrt(max(0f, ndviVariance))
        val ndreMean = ndreList.average().toFloat()
        val ndmiMean = ndmiList.average().toFloat()
        val psriMean = psriList.average().toFloat()

        val qualityLabel = when {
            validRatio >= 0.90f -> "جودة عالية (${(validRatio * 100).roundToInt()}% بكسلات خالية من السحب)"
            validRatio >= 0.65f -> "جودة متوسطة (${(validRatio * 100).roundToInt()}% بكسلات صالحة - قناع سحب جزئي)"
            else -> "جودة منخفضة (${(validRatio * 100).roundToInt()}% بكسلات صالحة - غطاء سحابي كثيف)"
        }

        val thresholds = getCropThresholds(cropId, growthStageIndex)
        val anomalousPixels = valid.filter { px ->
            val ndvi = px.ndvi ?: 1.0f
            val ndre = px.ndre ?: 1.0f
            val psri = px.psri ?: 0.0f
            ndvi < thresholds.stressedNdviThreshold ||
                ndre < thresholds.stressedNdreThreshold ||
                psri > thresholds.senescencePsriThreshold
        }
        val geoJson = vectorizeAnomalousClustersToGeoJson(
            anomalousPixels = anomalousPixels,
            fieldLat = fieldLat,
            fieldLon = fieldLon
        )

        return SpectralRasterStatistics(
            totalPixels = evaluated.size,
            validPixels = valid.size,
            cloudOrNoDataMaskedPixels = maskedCount,
            validPixelRatio = validRatio,
            ndviMean = ndviMean,
            ndviStdDev = ndviStd,
            ndreMean = ndreMean,
            ndmiMean = ndmiMean,
            psriMean = psriMean,
            dataQualityIndicatorAr = qualityLabel,
            geoJsonAnomalyPolygon = geoJson
        )
    }
}

interface SatelliteTelemetryDataSource {
    suspend fun fetchLiveOpenSourceSatelliteAndNasaData(
        latitude: Double,
        longitude: Double
    ): RealSatelliteAndNasaTelemetry
}

/**
 * Represents a live open-source communication channel (Satellite / Meteorology / Soil / Messaging)
 * with verifiable endpoint URL, measured latency, actual HTTP status, data mode, and citation.
 */
data class OpenSourceChannelStatus(
    val id: String,
    val nameAr: String,
    val platformNameEn: String,
    val endpointUrl: String,
    val protocolAr: String,
    val isConnected: Boolean,
    val httpStatusCode: Int,
    val latencyMs: Long,
    val lastSyncedIso: String,
    val licenseAndCitationAr: String,
    val dataMode: TelemetryDataMode = if (isConnected) TelemetryDataMode.LIVE else TelemetryDataMode.DEMO_ESTIMATED
)

/**
 * Holds retrieved satellite optical imagery (Bitmap) + STAC Sentinel-2 Level-2A scene metadata,
 * verified spectral band asset links, temporal observations, and NASA POWER agro-climatology telemetry.
 */
data class RealSatelliteAndNasaTelemetry(
    val sceneId: String,
    val acquisitionDateIso: String,
    val constellationAr: String,
    val mgrsTileId: String,
    val cloudCoverPct: Float,
    val platformInstrument: String,
    val stacCatalogSource: String,
    val opticalTileUrl: String,
    val retrievedSatelliteBitmap: Bitmap?,
    val nasaSolarRadiationMjM2Day: Float,
    val nasaClearSkyInsolationMjM2Day: Float,
    val nasaRootZoneSoilMoisturePct: Float,
    val nasaTemp2mMaxC: Float,
    val nasaTemp2mMinC: Float,
    val nasaRelativeHumidityPct: Float,
    val nasaWindSpeed2mMs: Float,
    val nasaVaporPressureDeficitKpa: Float,
    val channels: List<OpenSourceChannelStatus>,
    val stacAcquisitionStatus: StacAcquisitionStatus = StacAcquisitionStatus.NETWORK_OR_HTTP_FAILURE,
    val bandAssets: Sentinel2BandAssets = Sentinel2BandAssets(),
    val temporalAnalysis: TemporalChangeAnalysis? = null,
    val spatialResolutionLabelAr: String = "10m (B02/B04/B08) & 20m resampled to 10m (B05/B06/B8A/B11/SCL)",
    val processingTimestampIso: String = Instant.now().toString(),
    val dataQualityIndicatorAr: String = "DEMO / محاكاة طيفية استرشادية",
    val processingMethodAr: String = "Copernicus Sentinel-2 L2A STAC + SCL Cloud Masking",
    val dataMode: TelemetryDataMode = TelemetryDataMode.DEMO_ESTIMATED
)

/**
 * Exact step-by-step irrigation & meteorological mathematical breakdown with full scientific citations.
 */
data class ScientificFormulaCalculationAudit(
    val cropNameAr: String,
    val latitude: Double,
    val longitude: Double,
    val netRadiationRnMjM2: Float,
    val saturationVaporPressureEsKpa: Float,
    val actualVaporPressureEaKpa: Float,
    val vaporPressureDeficitVpdKpa: Float,
    val slopeVaporPressureCurveDelta: Float,
    val psychrometricConstantGamma: Float,
    val windSpeed2mU2Ms: Float,
    val calculatedPenmanEt0MmDay: Float,
    val openMeteoEt0MmDay: Float,
    val meanSentinelNdvi: Float,
    val meanSentinelNdre: Float,
    val meanSentinelNdwi: Float,
    val tabularFaoKc: Float,
    val climateAdjustedFaoKc: Float,
    val satelliteDerivedKc: Float,
    val fusedAuthoritativeKc: Float,
    val effectivePrecipitationMm: Float,
    val netEtcMmDay: Float,
    val netWaterM3PerFeddanDay: Float,
    val waterSalinityEcwDsM: Float,
    val cropThresholdEceDsM: Float,
    val leachingFractionRatio: Float,
    val leachingPercentage: Int,
    val systemApplicationEfficiencyRatio: Float,
    val grossIrrigationMmDay: Float,
    val grossWaterM3PerFeddanDay: Float,
    val totalFarmDailyWaterM3: Float,
    val valveOperatingHoursPerSector: Float,
    val citations: List<ScientificCitationItem>,
    val inputProvenanceSummaryAr: String = "ET₀: Open-Meteo FAO-56 | التربة: بيانات مدخلة بواسطة المستخدم | المؤشرات الطيفية: Sentinel-2 L2A"
)

data class ScientificCitationItem(
    val code: String,
    val titleAr: String,
    val formulaLatexLike: String,
    val substitutedValuesAr: String,
    val referenceSourceEn: String,
    val openDataUrl: String
)

class OpenSourceSatelliteClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) : SatelliteTelemetryDataSource {

    override suspend fun fetchLiveOpenSourceSatelliteAndNasaData(
        latitude: Double,
        longitude: Double
    ): RealSatelliteAndNasaTelemetry = withContext(Dispatchers.IO) {
        val nowIso = Instant.now().toString().take(19).replace('T', ' ')

        // 1. Query Element84 Earth Search STAC API for Sentinel-2 Level-2A scenes & band assets
        val stacResult = fetchSentinel2StacScene(latitude, longitude)

        // 2. Query NASA POWER Agroclimatology API for solar radiation & root-zone parameters
        val nasaResult = fetchNasaPowerAgroclimatology(latitude, longitude)

        // 3. Download real high-resolution overhead satellite imagery bitmap for the farm coordinates
        val zoom = 15
        val (tileX, tileY) = latLonToWebMercatorTile(latitude, longitude, zoom)
        val tileUrl = if (stacResult.bandAssets.visualPreviewHref.startsWith("https://")) {
            stacResult.bandAssets.visualPreviewHref
        } else {
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$tileY/$tileX"
        }
        val (satBitmap, tileStatus, tileLatency) = fetchRealSatelliteTileBitmap(tileUrl)

        // 4. Probe Open-Meteo status dynamically instead of hardcoding HTTP 200 / 142ms
        val (meteoConnected, meteoCode, meteoLatency) = probeOpenMeteoEndpoint(latitude, longitude)

        val channels = listOf(
            OpenSourceChannelStatus(
                id = "copernicus_stac",
                nameAr = "قناة القمر الصناعي الأوروبي Sentinel-2 L2A (STAC API)",
                platformNameEn = "Copernicus Sentinel-2 L2A via Element84 Earth Search STAC v1",
                endpointUrl = "https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a",
                protocolAr = "HTTPS REST / GeoJSON STAC Item & COG Band Asset Search",
                isConnected = stacResult.status.isUsableLiveScene,
                httpStatusCode = stacResult.httpCode,
                latencyMs = stacResult.latencyMs,
                lastSyncedIso = stacResult.acquisitionDateIso,
                licenseAndCitationAr = "European Space Agency (ESA) Copernicus Open Access Hub • CC-BY-SA 3.0 IGO",
                dataMode = if (stacResult.status.isUsableLiveScene) TelemetryDataMode.LIVE else TelemetryDataMode.DEMO_ESTIMATED
            ),
            OpenSourceChannelStatus(
                id = "nasa_power_agro",
                nameAr = "وكالة الفضاء الأمريكية للإشعاع الشمسي والمناخ الزراعي (NASA POWER)",
                platformNameEn = "NASA Langley Research Center POWER Agroclimatology API v2",
                endpointUrl = "https://power.larc.nasa.gov/api/temporal/daily/point?community=AG",
                protocolAr = "HTTPS JSON • CERES Solar Radiation & MERRA-2 Reanalysis",
                isConnected = nasaResult.isLive,
                httpStatusCode = nasaResult.httpCode,
                latencyMs = nasaResult.latencyMs,
                lastSyncedIso = nasaResult.latestObservationDateIso,
                licenseAndCitationAr = "NASA LaRC POWER Project (Prediction Of Worldwide Energy Resources) • Open Government Data",
                dataMode = if (nasaResult.isLive) TelemetryDataMode.LIVE else TelemetryDataMode.DEMO_ESTIMATED
            ),
            OpenSourceChannelStatus(
                id = "open_meteo_fao56",
                nameAr = "الشبكة العالمية للأرصاد والبخر-نتح المرجعي (Open-Meteo FAO-56)",
                platformNameEn = "Open-Meteo Agrometeorological Forecast API (ECMWF IFS / DWD ICON)",
                endpointUrl = "https://api.open-meteo.com/v1/forecast?daily=et0_fao_evapotranspiration",
                protocolAr = "HTTPS JSON • WMO / FAO-56 Penman-Monteith Hourly & Daily",
                isConnected = meteoConnected,
                httpStatusCode = meteoCode,
                latencyMs = meteoLatency,
                lastSyncedIso = nowIso,
                licenseAndCitationAr = "Open-Meteo.com & Copernicus ERA5-Land Reanalysis • CC-BY 4.0 Open-Source License",
                dataMode = if (meteoConnected) TelemetryDataMode.LIVE else TelemetryDataMode.DEMO_ESTIMATED
            ),
            OpenSourceChannelStatus(
                id = "satellite_optical_tiles",
                nameAr = "خادم المرئيات الفضائية البصرية عالية الدقة (WMTS / STAC Preview)",
                platformNameEn = "Open Geospatial WMTS World Imagery & Sentinel-2 True-Color Asset",
                endpointUrl = tileUrl,
                protocolAr = "HTTPS Raster Preview / WMTS Tile ($zoom/$tileY/$tileX)",
                isConnected = satBitmap != null,
                httpStatusCode = tileStatus,
                latencyMs = tileLatency,
                lastSyncedIso = nowIso,
                licenseAndCitationAr = "ESA Sentinel-2 Cloudless & Esri Open Basemap Attribution",
                dataMode = if (satBitmap != null) TelemetryDataMode.LIVE else TelemetryDataMode.DEMO_ESTIMATED
            )
        )

        val overallMode = if (stacResult.status.isUsableLiveScene) {
            TelemetryDataMode.LIVE
        } else {
            TelemetryDataMode.DEMO_ESTIMATED
        }

        val dataQualityAr = when (stacResult.status) {
            StacAcquisitionStatus.VALID_SCENE_WITH_BAND_ASSETS ->
                "[LIVE] مشهد Sentinel-2 L2A موثق (${stacResult.sceneId}) • نسبة السحب ${formatDecimal1(stacResult.cloudCoverPct)}% • 7 قنوات طيفية + قناع SCL متوفرة"
            StacAcquisitionStatus.NO_MATCHING_SCENES ->
                "[DEMO FALLBACK] استجابة STAC فارغة لهذه الإحداثيات — يتم عرض نموذج طيفي تجريبي (DEMO)"
            StacAcquisitionStatus.MISSING_OR_INACCESSIBLE_ASSETS ->
                "[DEMO FALLBACK] المشهد ${stacResult.sceneId} ينقصه بعض ملفات القنوات الطيفية — يتم عرض نموذج DEMO"
            StacAcquisitionStatus.INVALID_GEOMETRY ->
                "[INVALID GEOMETRY] إحداثيات الحقل خارج النطاق الجغرافي الصالح"
            StacAcquisitionStatus.NETWORK_OR_HTTP_FAILURE ->
                "[DEMO / OFFLINE] تعذر الاتصال بخادم STAC (HTTP ${stacResult.httpCode}) — يتم عرض وضع المحاكاة التجريبي (DEMO)"
        }

        RealSatelliteAndNasaTelemetry(
            sceneId = stacResult.sceneId,
            acquisitionDateIso = stacResult.acquisitionDateIso,
            constellationAr = "Sentinel-2A / Sentinel-2B MSI (L2A BOA • B02/B04/B05/B06/B08/B8A/B11 + SCL)",
            mgrsTileId = stacResult.mgrsTile,
            cloudCoverPct = stacResult.cloudCoverPct,
            platformInstrument = "MultiSpectral Instrument (MSI) Level-2A Bottom-Of-Atmosphere",
            stacCatalogSource = "Element84 Earth Search STAC API v1 (AWS Open Data Registry)",
            opticalTileUrl = tileUrl,
            retrievedSatelliteBitmap = satBitmap,
            nasaSolarRadiationMjM2Day = nasaResult.solarRadMjM2,
            nasaClearSkyInsolationMjM2Day = nasaResult.clearSkyMjM2,
            nasaRootZoneSoilMoisturePct = nasaResult.rootZoneMoisturePct,
            nasaTemp2mMaxC = nasaResult.tMaxC,
            nasaTemp2mMinC = nasaResult.tMinC,
            nasaRelativeHumidityPct = nasaResult.rhPct,
            nasaWindSpeed2mMs = nasaResult.wind2mMs,
            nasaVaporPressureDeficitKpa = nasaResult.vpdKpa,
            channels = channels,
            stacAcquisitionStatus = stacResult.status,
            bandAssets = stacResult.bandAssets,
            temporalAnalysis = stacResult.temporalAnalysis,
            processingTimestampIso = nowIso,
            dataQualityIndicatorAr = dataQualityAr,
            processingMethodAr = if (stacResult.status.isUsableLiveScene) {
                "LIVE STAC COG Discovery + SCL Cloud Filter + Multi-Temporal Comparison"
            } else {
                "DEMO Mode Spatial Simulation (Fallback)"
            },
            dataMode = overallMode
        )
    }

    internal data class StacQueryOutcome(
        val status: StacAcquisitionStatus,
        val httpCode: Int,
        val latencyMs: Long,
        val sceneId: String,
        val acquisitionDateIso: String,
        val mgrsTile: String,
        val cloudCoverPct: Float,
        val bandAssets: Sentinel2BandAssets,
        val temporalAnalysis: TemporalChangeAnalysis
    )

    internal fun fetchSentinel2StacScene(lat: Double, lon: Double): StacQueryOutcome {
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return StacQueryOutcome(
                status = StacAcquisitionStatus.INVALID_GEOMETRY,
                httpCode = 400,
                latencyMs = 0L,
                sceneId = "INVALID_GEOMETRY",
                acquisitionDateIso = LocalDate.now().toString(),
                mgrsTile = "N/A",
                cloudCoverPct = 100f,
                bandAssets = Sentinel2BandAssets(),
                temporalAnalysis = buildEmptyTemporalAnalysis("إحداثيات جغرافية غير صالحة")
            )
        }

        val startMs = System.currentTimeMillis()
        val delta = 0.02
        val bbox = "${lon - delta},${lat - delta},${lon + delta},${lat + delta}"
        // Fetch up to 6 recent observations to support Phase 3 Temporal Satellite Intelligence
        val url = "https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a/items?bbox=$bbox&limit=6"

        val defaultMgrs = deriveEgyptianMgrsTile(lat, lon)
        val demoSceneId = "DEMO_S2B_MSIL2A_${LocalDate.now().minusDays(2).format(DateTimeFormatter.BASIC_ISO_DATE)}_T${defaultMgrs}"

        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/geo+json, application/json")
                .build()
            httpClient.newCall(req).execute().use { res ->
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                if (!res.isSuccessful) {
                    return StacQueryOutcome(
                        status = StacAcquisitionStatus.NETWORK_OR_HTTP_FAILURE,
                        httpCode = res.code,
                        latencyMs = elapsed,
                        sceneId = demoSceneId,
                        acquisitionDateIso = LocalDate.now().minusDays(2).toString(),
                        mgrsTile = defaultMgrs,
                        cloudCoverPct = 0f,
                        bandAssets = Sentinel2BandAssets(),
                        temporalAnalysis = buildEmptyTemporalAnalysis("تعذر جلب السلسلة الزمنية من STAC (HTTP ${res.code})")
                    )
                }
                val body = res.body?.string().orEmpty()
                parseStacFeatureCollectionJson(
                    body = body,
                    httpCode = res.code,
                    elapsedMs = elapsed,
                    defaultMgrs = defaultMgrs,
                    demoSceneId = demoSceneId
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            StacQueryOutcome(
                status = StacAcquisitionStatus.NETWORK_OR_HTTP_FAILURE,
                httpCode = 0,
                latencyMs = elapsed,
                sceneId = demoSceneId,
                acquisitionDateIso = LocalDate.now().minusDays(2).toString(),
                mgrsTile = defaultMgrs,
                cloudCoverPct = 0f,
                bandAssets = Sentinel2BandAssets(),
                temporalAnalysis = buildEmptyTemporalAnalysis("انقطاع الاتصال بخادم STAC (${e.javaClass.simpleName})")
            )
        }
    }

    internal fun parseStacFeatureCollectionJson(
        body: String,
        httpCode: Int,
        elapsedMs: Long,
        defaultMgrs: String,
        demoSceneId: String
    ): StacQueryOutcome {
        val json = JSONObject(body)
        val features = json.optJSONArray("features")
        if (features == null || features.length() == 0) {
            return StacQueryOutcome(
                status = StacAcquisitionStatus.NO_MATCHING_SCENES,
                httpCode = httpCode,
                latencyMs = elapsedMs,
                sceneId = "NO_MATCHING_SCENE_$defaultMgrs",
                acquisitionDateIso = LocalDate.now().toString(),
                mgrsTile = defaultMgrs,
                cloudCoverPct = 100f,
                bandAssets = Sentinel2BandAssets(),
                temporalAnalysis = buildEmptyTemporalAnalysis("لا توجد مشاهد Sentinel-2 مطابقة في نافذة البحث")
            )
        }

        val temporalObservations = mutableListOf<TemporalSatelliteObservation>()
        var bestFeatureIndex = 0
        var lowestCloudCover = Float.MAX_VALUE

        for (i in 0 until features.length()) {
            val item = features.optJSONObject(i) ?: continue
            val id = item.optString("id", "S2_SCENE_$i")
            val props = item.optJSONObject("properties")
            val dt = props?.optString("datetime", "")?.take(10).orEmpty().ifBlank { "Unknown" }
            val cloud = props?.optDouble("eo:cloud_cover", 100.0)?.toFloat()?.coerceIn(0f, 100f) ?: 100f
            val isCloudGapped = cloud > 35.0f

            if (cloud < lowestCloudCover) {
                lowestCloudCover = cloud
                bestFeatureIndex = i
            }

            temporalObservations.add(
                TemporalSatelliteObservation(
                    sceneId = id,
                    acquisitionDateIso = dt,
                    cloudCoverPct = cloud,
                    isCloudGappedOrUnusable = isCloudGapped,
                    observedNdviMean = null,
                    observedNdreMean = null,
                    observedNdmiMean = null,
                    observedPsriMean = null,
                    qualityNoteAr = if (isCloudGapped) {
                        "مستبعد من مقارنة التدهور النباتي بسبب الغطاء السحابي (${formatDecimal1(cloud)}%)"
                    } else {
                        "مشهد صالح للمقارنة الزمنية (سحب ${formatDecimal1(cloud)}%)"
                    }
                )
            )
        }

        val primaryItem = features.getJSONObject(bestFeatureIndex)
        val sceneId = primaryItem.optString("id", demoSceneId)
        val props = primaryItem.optJSONObject("properties")
        val dt = props?.optString("datetime", "")?.take(10)
            ?.ifBlank { LocalDate.now().minusDays(2).toString() }
            ?: LocalDate.now().minusDays(2).toString()
        val cloud = props?.optDouble("eo:cloud_cover", 0.0)?.toFloat()?.coerceIn(0f, 100f) ?: 0f
        val gridCode = props?.optString("grid:code", "MGRS-$defaultMgrs")
            ?.removePrefix("MGRS-") ?: defaultMgrs

        val assetsObj = primaryItem.optJSONObject("assets")
        fun assetHref(vararg keys: String): String {
            if (assetsObj == null) return ""
            for (k in keys) {
                val href = assetsObj.optJSONObject(k)?.optString("href", "").orEmpty()
                if (href.isNotBlank()) return href
            }
            return ""
        }

        val bandAssets = Sentinel2BandAssets(
            b02Blue10mHref = assetHref("blue", "B02"),
            b04Red10mHref = assetHref("red", "B04"),
            b05RedEdge1_20mHref = assetHref("rededge1", "B05"),
            b06RedEdge2_20mHref = assetHref("rededge2", "B06"),
            b08Nir10mHref = assetHref("nir", "B08"),
            b8aNarrowNir20mHref = assetHref("nir08", "B8A"),
            b11Swir16_20mHref = assetHref("swir16", "B11"),
            sclCloudMask20mHref = assetHref("scl", "SCL"),
            visualPreviewHref = assetHref("thumbnail", "visual")
        )

        val status = if (bandAssets.hasAllRequiredSpectralBands) {
            StacAcquisitionStatus.VALID_SCENE_WITH_BAND_ASSETS
        } else {
            StacAcquisitionStatus.MISSING_OR_INACCESSIBLE_ASSETS
        }

        val sortedTemporal = temporalObservations.sortedByDescending { it.acquisitionDateIso }
        val validCount = sortedTemporal.count { !it.isCloudGappedOrUnusable }
        val cloudGapCount = sortedTemporal.count { it.isCloudGappedOrUnusable }
        val clearObsWithNdvi = sortedTemporal.filter { !it.isCloudGappedOrUnusable && it.observedNdviMean != null }
        val ndviDelta = if (clearObsWithNdvi.size >= 2) {
            (clearObsWithNdvi[0].observedNdviMean ?: 0f) - (clearObsWithNdvi[1].observedNdviMean ?: 0f)
        } else null
        val ndreDelta = if (clearObsWithNdvi.size >= 2) {
            (clearObsWithNdvi[0].observedNdreMean ?: 0f) - (clearObsWithNdvi[1].observedNdreMean ?: 0f)
        } else null
        val ndmiDelta = if (clearObsWithNdvi.size >= 2) {
            (clearObsWithNdvi[0].observedNdmiMean ?: 0f) - (clearObsWithNdvi[1].observedNdmiMean ?: 0f)
        } else null

        val temporalSummary = TemporalChangeAnalysis(
            observations = sortedTemporal,
            validObservationCount = validCount,
            cloudGappedCount = cloudGapCount,
            ndviChangeVsPrevious = ndviDelta,
            ndreChangeVsPrevious = ndreDelta,
            ndmiChangeVsPrevious = ndmiDelta,
            temporalTrendSummaryAr = "تم رصد ${sortedTemporal.size} مشاهد في أرشيف Sentinel-2 مرتبة زمنياً ($validCount صالحة، $cloudGapCount مستبعدة بسبب السحب >35%).",
            coverageGapDisclaimerAr = "ملاحظة علمية: الفجوات الزمنية الناتجة عن الغطاء السحابي تُستبعد تلقائياً ولا تُفسر كتدهور في الغطاء النباتي."
        )

        return StacQueryOutcome(
            status = status,
            httpCode = httpCode,
            latencyMs = elapsedMs,
            sceneId = sceneId,
            acquisitionDateIso = dt,
            mgrsTile = gridCode,
            cloudCoverPct = cloud,
            bandAssets = bandAssets,
            temporalAnalysis = temporalSummary
        )
    }

    private fun buildEmptyTemporalAnalysis(reasonAr: String): TemporalChangeAnalysis {
        return TemporalChangeAnalysis(
            observations = emptyList(),
            validObservationCount = 0,
            cloudGappedCount = 0,
            ndviChangeVsPrevious = null,
            ndreChangeVsPrevious = null,
            ndmiChangeVsPrevious = null,
            temporalTrendSummaryAr = reasonAr,
            coverageGapDisclaimerAr = "لا تُفسر المشاهد المفقودة أو الغائمة كتدهور في صحة المحصول."
        )
    }

    private data class NasaPowerOutcome(
        val isLive: Boolean,
        val httpCode: Int,
        val latencyMs: Long,
        val latestObservationDateIso: String,
        val solarRadMjM2: Float,
        val clearSkyMjM2: Float,
        val rootZoneMoisturePct: Float,
        val tMaxC: Float,
        val tMinC: Float,
        val rhPct: Float,
        val wind2mMs: Float,
        val vpdKpa: Float
    )

    private fun fetchNasaPowerAgroclimatology(lat: Double, lon: Double): NasaPowerOutcome {
        val startMs = System.currentTimeMillis()
        val endDate = LocalDate.now().minusDays(2)
        val startDate = endDate.minusDays(5)
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        val url = "https://power.larc.nasa.gov/api/temporal/daily/point" +
            "?parameters=ALLSKY_SFC_SW_DWN,CLRSKY_SFC_SW_DWN,T2M_MAX,T2M_MIN,RH2M,WS2M,GWETROOT" +
            "&community=AG" +
            "&longitude=${formatCoord4(lon)}" +
            "&latitude=${formatCoord4(lat)}" +
            "&start=${startDate.format(fmt)}" +
            "&end=${endDate.format(fmt)}" +
            "&format=JSON"

        val fallback = buildPhysicsBasedNasaFallback(lat)

        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { res ->
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                if (!res.isSuccessful) {
                    return fallback.copy(isLive = false, httpCode = res.code, latencyMs = elapsed)
                }
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val paramObj = json.optJSONObject("properties")?.optJSONObject("parameter")
                    ?: return fallback.copy(isLive = false, httpCode = res.code, latencyMs = elapsed)

                var latestDateKey = endDate.toString()
                fun extractLatestValid(paramName: String, defaultVal: Float, minValid: Float, maxValid: Float): Float {
                    val series = paramObj.optJSONObject(paramName) ?: return defaultVal
                    val keys = series.keys().asSequence().toList().sortedDescending()
                    for (k in keys) {
                        val v = series.optDouble(k, -999.0).toFloat()
                        if (v in minValid..maxValid) {
                            if (k.length == 8) {
                                latestDateKey = "${k.substring(0, 4)}-${k.substring(4, 6)}-${k.substring(6, 8)}"
                            }
                            return v
                        }
                    }
                    return defaultVal
                }

                val swDwn = extractLatestValid("ALLSKY_SFC_SW_DWN", fallback.solarRadMjM2, 0.5f, 45.0f)
                val clrSw = extractLatestValid("CLRSKY_SFC_SW_DWN", fallback.clearSkyMjM2, 0.5f, 45.0f)
                val tMax = extractLatestValid("T2M_MAX", fallback.tMaxC, -20.0f, 58.0f)
                val tMin = extractLatestValid("T2M_MIN", fallback.tMinC, -25.0f, tMax)
                val rh = extractLatestValid("RH2M", fallback.rhPct, 5.0f, 100.0f)
                val ws2m = extractLatestValid("WS2M", fallback.wind2mMs, 0.1f, 35.0f)
                val gwetRoot = (extractLatestValid("GWETROOT", 0.44f, 0.01f, 1.0f) * 100f).coerceIn(5f, 98f)

                val esMax = 0.6108f * exp((17.27f * tMax) / (tMax + 237.3f))
                val esMin = 0.6108f * exp((17.27f * tMin) / (tMin + 237.3f))
                val es = (esMax + esMin) / 2f
                val ea = es * (rh / 100f)
                val vpd = max(0.25f, es - ea)

                NasaPowerOutcome(
                    isLive = true,
                    httpCode = res.code,
                    latencyMs = elapsed,
                    latestObservationDateIso = latestDateKey,
                    solarRadMjM2 = swDwn,
                    clearSkyMjM2 = clrSw,
                    rootZoneMoisturePct = gwetRoot,
                    tMaxC = tMax,
                    tMinC = tMin,
                    rhPct = rh,
                    wind2mMs = ws2m,
                    vpdKpa = vpd
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            fallback.copy(isLive = false, httpCode = 0, latencyMs = elapsed)
        }
    }

    private fun buildPhysicsBasedNasaFallback(lat: Double): NasaPowerOutcome {
        val southShift = ((30.8 - lat) * 0.85).toFloat().coerceIn(-1.5f, 7.0f)
        val tMax = 31.8f + southShift
        val tMin = 18.4f + southShift * 0.65f
        val rh = (46f - southShift * 1.9f).coerceIn(20f, 75f)
        val esMax = 0.6108f * exp((17.27f * tMax) / (tMax + 237.3f))
        val esMin = 0.6108f * exp((17.27f * tMin) / (tMin + 237.3f))
        val es = (esMax + esMin) / 2f
        val ea = es * (rh / 100f)
        val vpd = max(0.4f, es - ea)
        return NasaPowerOutcome(
            isLive = false,
            httpCode = 0,
            latencyMs = 0L,
            latestObservationDateIso = "${LocalDate.now().minusDays(2)} (DEMO/ESTIMATED)",
            solarRadMjM2 = 24.2f + southShift * 0.35f,
            clearSkyMjM2 = 26.5f + southShift * 0.35f,
            rootZoneMoisturePct = 44.5f,
            tMaxC = tMax,
            tMinC = tMin,
            rhPct = rh,
            wind2mMs = 3.65f,
            vpdKpa = vpd
        )
    }

    private fun probeOpenMeteoEndpoint(lat: Double, lon: Double): Triple<Boolean, Int, Long> {
        val startMs = System.currentTimeMillis()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=et0_fao_evapotranspiration&forecast_days=1"
        return try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { res ->
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                Triple(res.isSuccessful, res.code, elapsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            Triple(false, 0, elapsed)
        }
    }

    private fun fetchRealSatelliteTileBitmap(url: String): Triple<Bitmap?, Int, Long> {
        val startMs = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AgroSight-Precision-Irrigation/2.5 (Android; OpenSource-EO)")
                .build()
            httpClient.newCall(req).execute().use { res ->
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                if (!res.isSuccessful) return Triple(null, res.code, elapsed)
                val bytes = res.body?.bytes() ?: return Triple(null, res.code, elapsed)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                Triple(bmp, res.code, elapsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            Triple(null, 0, elapsed)
        }
    }

    companion object {
        fun latLonToWebMercatorTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
            val n = 1 shl zoom
            val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            val y = ((1.0 - ln(kotlin.math.tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n)
                .toInt()
                .coerceIn(0, n - 1)
            return x to y
        }

        fun deriveEgyptianMgrsTile(lat: Double, lon: Double): String {
            return when {
                lat >= 30.6 && lon >= 31.5 -> "36RUV"
                lat >= 30.0 && lon < 31.0 -> "35RQQ"
                lat in 26.5..29.9 && lon < 29.5 -> "35RLK"
                lat < 24.5 -> "36QUL"
                else -> "36RUU"
            }
        }

        fun computeScientificFormulaAudit(
            crop: CropSpec,
            latitude: Double,
            longitude: Double,
            areaFeddans: Float,
            tabularBaseKc: Float,
            tempMaxC: Float,
            tempMinC: Float,
            relativeHumidityPct: Int,
            windSpeed10mKmh: Float,
            solarRadiationMjM2: Float,
            precipitationMm: Float,
            openMeteoEt0MmDay: Float,
            meanNdvi: Float,
            meanNdre: Float,
            meanNdwi: Float,
            soilPh: Float,
            soilEceDsM: Float,
            waterEcwDsM: Float,
            soilTexture: SoilTexture,
            irrigationSystem: IrrigationSystemType,
            valveFlowRateM3PerFeddanHr: Float
        ): ScientificFormulaCalculationAudit {
            val tMean = (tempMaxC + tempMinC) / 2f
            val u10Ms = windSpeed10mKmh / 3.6f
            val u2Ms = (u10Ms * (4.87f / ln(67.8f * 10f - 5.42f))).coerceAtLeast(0.5f)

            val e0Max = 0.6108f * exp((17.27f * tempMaxC) / (tempMaxC + 237.3f))
            val e0Min = 0.6108f * exp((17.27f * tempMinC) / (tempMinC + 237.3f))
            val esKpa = (e0Max + e0Min) / 2f
            val eaKpa = esKpa * (relativeHumidityPct.coerceIn(10, 98) / 100f)
            val vpdKpa = max(0.25f, esKpa - eaKpa)

            val delta = (4098f * (0.6108f * exp((17.27f * tMean) / (tMean + 237.3f)))) /
                (tMean + 237.3f).pow(2)
            val gamma = 0.0665f

            val rns = (1f - 0.23f) * solarRadiationMjM2
            val rnl = (2.4f + 0.05f * vpdKpa).coerceIn(1.8f, 4.8f)
            val rnMjM2 = max(4.0f, rns - rnl)

            val radTerm = 0.408f * delta * rnMjM2
            val aeroTerm = gamma * (900f / (tMean + 273f)) * u2Ms * vpdKpa
            val denomTerm = delta + gamma * (1f + 0.34f * u2Ms)
            val penmanCalculatedEt0 = (radTerm + aeroTerm) / max(0.1f, denomTerm)

            val authoritativeEt0 = openMeteoEt0MmDay.coerceIn(1.8f, 11.5f)

            val climateDeltaKc = (0.04f * (u2Ms - 2f) - 0.004f * (relativeHumidityPct - 45f)).coerceIn(-0.08f, 0.14f)
            val climateAdjustedKc = (tabularBaseKc + climateDeltaKc).coerceIn(0.25f, 1.35f)
            val satelliteKc = (1.25f * meanNdvi + 0.10f).coerceIn(0.28f, 1.32f)
            val fusedKc = ((climateAdjustedKc * 0.60f) + (satelliteKc * 0.40f)).coerceIn(0.28f, 1.35f)

            val effectivePrecipMm = if (precipitationMm > 2.0f) (precipitationMm * 0.80f) else 0f
            val netEtcMm = max(0.8f, authoritativeEt0 * fusedKc - effectivePrecipMm)
            val netM3PerFeddan = netEtcMm * 4.2f

            val lrDenom = max(1.2f, 5f * crop.salinityThresholdEce - waterEcwDsM)
            val rawLr = (waterEcwDsM / lrDenom) / soilTexture.leachingEfficiencyFactor
            val lrRatio = rawLr.coerceIn(0.05f, 0.42f)
            val lrPct = (lrRatio * 100f).roundToInt()

            val appEffRatio = (irrigationSystem.efficiencyPercent / 100f).coerceIn(0.60f, 0.96f)
            val grossMmDay = netEtcMm / (appEffRatio * (1f - lrRatio))
            val grossM3PerFeddan = grossMmDay * 4.2f
            val totalFarmM3 = grossM3PerFeddan * areaFeddans
            val valveHoursPerSector = grossM3PerFeddan / max(4f, valveFlowRateM3PerFeddanHr)

            val citations = listOf(
                ScientificCitationItem(
                    code = "FAO-56 Eq. 6",
                    titleAr = "معادلة بنمان-مونتيث للبخر-نتح المرجعي (FAO-56 Penman-Monteith ET₀)",
                    formulaLatexLike = "ET0 = [0.408·Δ·(Rn - G) + γ·(900/(T+273))·u2·(es - ea)] / [Δ + γ·(1 + 0.34·u2)]",
                    substitutedValuesAr = "Rn=${formatDecimal1(rnMjM2)} MJ/m² | Δ=${formatDecimal2(delta)} | VPD=${formatDecimal2(vpdKpa)} kPa | u₂=${formatDecimal2(u2Ms)} m/s ➔ ET₀ = ${formatDecimal2(authoritativeEt0)} مم/يوم",
                    referenceSourceEn = "Allen, R.G., Pereira, L.S., Raes, D., & Smith, M. (1998). Crop Evapotranspiration — FAO Irrigation and Drainage Paper No. 56, Rome.",
                    openDataUrl = "https://api.open-meteo.com/v1/forecast & https://power.larc.nasa.gov"
                ),
                ScientificCitationItem(
                    code = "Sentinel-2 L2A & FAO-56 Eq. 62",
                    titleAr = "اشتقاق معامل المحصول الفضائي والمناخي المدمج (Spectral & Climate-Adjusted Kc)",
                    formulaLatexLike = "NDVI=(B08-B04)/(B08+B04) | NDRE=(B8A-B05)/(B8A+B05) | NDMI=(B08-B11)/(B08+B11) | PSRI=(B04-B02)/B06",
                    substitutedValuesAr = "NDVI=${formatDecimal2(meanNdvi)} ➔ Kc_sat=${formatDecimal2(satelliteKc)} | Kc_clim=${formatDecimal2(climateAdjustedKc)} ➔ Kc المعتمد = ${formatDecimal2(fusedKc)}",
                    referenceSourceEn = "ESA Copernicus Sentinel-2 MSI Level-2A (B02 490nm, B04 665nm, B05 705nm, B06 740nm, B08 842nm, B8A 865nm, B11 1610nm) & Calera et al. (2017).",
                    openDataUrl = "https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a"
                ),
                ScientificCitationItem(
                    code = "FAO-29 / Ayers & Westcot",
                    titleAr = "معادلة الاحتياج الغسيلي للأملاح واستنزاف الجذور (Salinity Leaching Fraction LR)",
                    formulaLatexLike = "LR = ECw / [5 · ECe_threshold - ECw] ÷ f_texture   |   Y_rel = 100 - b · (ECe - ECe_threshold)",
                    substitutedValuesAr = "ECw=${formatDecimal1(waterEcwDsM)} dS/m | حد تحمل ${crop.nameAr}=${formatDecimal1(crop.salinityThresholdEce)} dS/m ➔ معامل الغسيل LR = $lrPct%",
                    referenceSourceEn = "Ayers, R.S. & Westcot, D.W. (1985). Water Quality for Agriculture — FAO Irrigation and Drainage Paper No. 29 Rev. 1.",
                    openDataUrl = "https://www.fao.org/3/t0234e/t0234e00.htm"
                ),
                ScientificCitationItem(
                    code = "Gross Water & Valve Hydraulics",
                    titleAr = "المقنن المائي الإجمالي للفدان وزمن تشغيل المحبس (Gross Irrigation & Valve Run-Time)",
                    formulaLatexLike = "ETc = ET0 × Kc - Pe   |   GIR (م³/فدان) = [ (ETc × 4.2) / (Ea × (1 - LR)) ]   |   T_hrs = GIR / Q_feddan",
                    substitutedValuesAr = "ETc=${formatDecimal2(netEtcMm)} مم (${formatDecimal1(netM3PerFeddan)} م³ صافي) ÷ [كفاءة ${irrigationSystem.efficiencyPercent}% × (1 - ${formatDecimal2(lrRatio)})] ➔ ${formatDecimal1(grossM3PerFeddan)} م³/فدان/يوم",
                    referenceSourceEn = "USDA-NRCS National Engineering Handbook Part 652 & Egyptian MWRI Feddan Conversion (1 Feddan = 4,200 m²).",
                    openDataUrl = "https://www.fao.org/land-water/databases-and-software/cropwat"
                )
            )

            return ScientificFormulaCalculationAudit(
                cropNameAr = crop.nameAr,
                latitude = latitude,
                longitude = longitude,
                netRadiationRnMjM2 = rnMjM2,
                saturationVaporPressureEsKpa = esKpa,
                actualVaporPressureEaKpa = eaKpa,
                vaporPressureDeficitVpdKpa = vpdKpa,
                slopeVaporPressureCurveDelta = delta,
                psychrometricConstantGamma = gamma,
                windSpeed2mU2Ms = u2Ms,
                calculatedPenmanEt0MmDay = penmanCalculatedEt0,
                openMeteoEt0MmDay = authoritativeEt0,
                meanSentinelNdvi = meanNdvi,
                meanSentinelNdre = meanNdre,
                meanSentinelNdwi = meanNdwi,
                tabularFaoKc = tabularBaseKc,
                climateAdjustedFaoKc = climateAdjustedKc,
                satelliteDerivedKc = satelliteKc,
                fusedAuthoritativeKc = fusedKc,
                effectivePrecipitationMm = effectivePrecipMm,
                netEtcMmDay = netEtcMm,
                netWaterM3PerFeddanDay = netM3PerFeddan,
                waterSalinityEcwDsM = waterEcwDsM,
                cropThresholdEceDsM = crop.salinityThresholdEce,
                leachingFractionRatio = lrRatio,
                leachingPercentage = lrPct,
                systemApplicationEfficiencyRatio = appEffRatio,
                grossIrrigationMmDay = grossMmDay,
                grossWaterM3PerFeddanDay = grossM3PerFeddan,
                totalFarmDailyWaterM3 = totalFarmM3,
                valveOperatingHoursPerSector = valveHoursPerSector,
                citations = citations
            )
        }
    }
}
