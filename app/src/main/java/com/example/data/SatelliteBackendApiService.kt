package com.example.data

import com.example.BuildConfig
import com.example.model.formatCoord4
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Environment-based backend configuration for AgroSight Sentinel-2 Raster Backend.
 * Eliminates hardcoded 127.0.0.1 production dependence and strictly enforces HTTPS
 * in STAGING and PRODUCTION environments.
 */
enum class BackendEnvironment(
    val defaultBaseUrl: String,
    val requiresHttps: Boolean
) {
    LOCAL_EMULATOR(defaultBaseUrl = "http://10.0.2.2:8090/", requiresHttps = false),
    PHYSICAL_DEVICE(defaultBaseUrl = "http://192.168.1.100:8090/", requiresHttps = false),
    STAGING(defaultBaseUrl = "https://staging-api.agrosight.eg/", requiresHttps = true),
    PRODUCTION(defaultBaseUrl = "https://api.agrosight.eg/", requiresHttps = true);

    companion object {
        fun fromConfigString(rawEnv: String?): BackendEnvironment {
            val cleaned = rawEnv?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == cleaned } ?: LOCAL_EMULATOR
        }

        fun resolveBaseUrl(
            environment: BackendEnvironment,
            configuredUrl: String? = null
        ): String {
            val candidate = configuredUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: environment.defaultBaseUrl
            val normalized = if (candidate.endsWith("/")) candidate else "$candidate/"
            if (environment.requiresHttps && !normalized.startsWith("https://", ignoreCase = true)) {
                throw IllegalArgumentException(
                    "HTTPS is strictly required for ${environment.name} backend URL, but got: $normalized"
                )
            }
            if (!normalized.startsWith("http://", ignoreCase = true) &&
                !normalized.startsWith("https://", ignoreCase = true)
            ) {
                throw IllegalArgumentException("Invalid backend base URL scheme: $normalized")
            }
            return normalized
        }

        fun resolveFromBuildConfig(): Pair<BackendEnvironment, String> {
            val env = fromConfigString(runCatching { BuildConfig.AGROSIGHT_BACKEND_ENV }.getOrNull())
            val rawUrl = runCatching { BuildConfig.AGROSIGHT_BACKEND_BASE_URL }.getOrNull()?.trim()
            // Validate whether rawUrl is an actual HTTP/HTTPS URL rather than a placeholder or port number
            val hasValidScheme = !rawUrl.isNullOrBlank() && (
                rawUrl.startsWith("https://", ignoreCase = true) ||
                    (!env.requiresHttps && rawUrl.startsWith("http://", ignoreCase = true))
                )
            val effectiveConfiguredUrl = if (
                !hasValidScheme ||
                (env.requiresHttps && (rawUrl.startsWith("http://10.0.2.2") || rawUrl.startsWith("http://127.0.0.1")))
            ) {
                env.defaultBaseUrl
            } else {
                rawUrl
            }
            val safeBaseUrl = runCatching {
                resolveBaseUrl(env, effectiveConfiguredUrl)
            }.getOrElse {
                env.defaultBaseUrl
            }
            return env to safeBaseUrl
        }
    }
}

/**
 * Structured exception representing an HTTP or transport failure from the Satellite Raster Backend.
 */
class SatelliteBackendApiException(
    val httpStatusCode: Int,
    val errorCode: String,
    val userMessageAr: String,
    val serverMessage: String = "",
    cause: Throwable? = null
) : IllegalStateException(userMessageAr, cause) {

    companion object {
        fun fromHttpResponse(httpCode: Int, rawErrorBody: String?): SatelliteBackendApiException {
            var parsedCode = ""
            var parsedMessage = ""
            if (!rawErrorBody.isNullOrBlank()) {
                try {
                    val root = JSONObject(rawErrorBody)
                    val detailObj = root.optJSONObject("detail") ?: root
                    parsedCode = detailObj.optString("error_code", "")
                    parsedMessage = detailObj.optString("message", "")
                } catch (_: Exception) {
                    parsedMessage = rawErrorBody.take(200)
                }
            }

            val defaultCode = when (httpCode) {
                401 -> "UNAUTHENTICATED"
                403 -> "FORBIDDEN_FARM_ACCESS"
                404 -> "RESOURCE_NOT_FOUND"
                422 -> "VALIDATION_ERROR"
                429 -> "RATE_LIMIT_EXCEEDED"
                503 -> "BACKEND_SERVICE_UNAVAILABLE"
                else -> "BACKEND_HTTP_$httpCode"
            }
            val effectiveCode = parsedCode.ifBlank { defaultCode }

            val arabicMessage = when (httpCode) {
                401 -> "انتهت صلاحية جلسة المصادقة (HTTP 401: $effectiveCode) — يرجى إعادة تسجيل الدخول بحساب Google السحابي."
                403 -> "صلاحية مرفوضة للوصول إلى هذه المزرعة (HTTP 403: $effectiveCode) — المزرعة مملوكة لمستخدم آخر."
                404 -> "لم يتم العثور على مهمة المعالجة أو النتيجة الطيفية المطلوبة على الخادم (HTTP 404: $effectiveCode)."
                422 -> "بيانات حدود المزرعة GeoJSON أو نطاقات Sentinel-2 غير مكتملة أو غير صالحة (HTTP 422: $effectiveCode)."
                429 -> "تم تجاوز الحد الأقصى لطلبات المعالجة الطيفية (HTTP 429: $effectiveCode) — يرجى الانتظار قليلاً ثم المحاولة."
                503 -> "خادم المعالجة المكانية غير مهيأ أو غير متاح حالياً (HTTP 503: $effectiveCode)."
                in 500..599 -> "حدث خطأ داخلي في خادم معالجة الأقمار الصناعية (HTTP $httpCode: $effectiveCode)."
                else -> "فشل طلب خادم الأقمار الصناعية (HTTP $httpCode: $effectiveCode)."
            }

            return SatelliteBackendApiException(
                httpStatusCode = httpCode,
                errorCode = effectiveCode,
                userMessageAr = arabicMessage,
                serverMessage = parsedMessage
            )
        }

        fun fromTransportException(e: Throwable): SatelliteBackendApiException {
            if (e is SatelliteBackendApiException) return e
            val isNetwork = e is IOException
            val code = if (isNetwork) "NETWORK_INTERRUPTED" else "BACKEND_UNAVAILABLE"
            val msgAr = if (isNetwork) {
                "انقطع الاتصال بالشبكة أثناء التواصل مع خادم معالجة الأقمار الصناعية Sentinel-2 ($code)."
            } else {
                "تعذر الاتصال بخادم معالجة الأقمار الصناعية Sentinel-2 ($code: ${e.message ?: "خطأ غير معروف"})."
            }
            return SatelliteBackendApiException(
                httpStatusCode = 0,
                errorCode = code,
                userMessageAr = msgAr,
                serverMessage = e.message.orEmpty(),
                cause = e
            )
        }
    }
}

/**
 * Abstraction for obtaining and force-refreshing Firebase ID tokens.
 */
fun interface FirebaseIdTokenProvider {
    suspend fun getIdToken(forceRefresh: Boolean): String?
}

object DefaultFirebaseIdTokenProvider : FirebaseIdTokenProvider {
    override suspend fun getIdToken(forceRefresh: Boolean): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser ?: return null
            user.getIdToken(forceRefresh).await()?.token
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Retrofit API contract for the AgroSight Python FastAPI Sentinel-2 Raster Processing Backend.
 * Endpoints:
 * - POST   /api/v1/satellite/scenes
 * - POST   /api/v1/satellite/process
 * - GET    /api/v1/satellite/jobs/{job_id}
 * - GET    /api/v1/satellite/results/{result_id}
 * - GET    /api/v1/satellite/files/{file_id}/signed-url
 * - DELETE /api/v1/satellite/farms/{farm_id}
 * - GET    /api/v1/health
 */
interface SatelliteRasterBackendRetrofitService {

    @GET("api/v1/health")
    suspend fun getHealth(): Response<ResponseBody>

    @POST("api/v1/satellite/scenes")
    suspend fun searchScenes(
        @Header("Authorization") bearerToken: String,
        @Body requestBody: RequestBody
    ): Response<ResponseBody>

    @POST("api/v1/satellite/process")
    suspend fun submitRasterProcessingJob(
        @Header("Authorization") bearerToken: String,
        @Body requestBody: RequestBody
    ): Response<ResponseBody>

    @GET("api/v1/satellite/jobs/{jobId}")
    suspend fun getJobStatus(
        @Header("Authorization") bearerToken: String,
        @Path("jobId") jobId: String
    ): Response<ResponseBody>

    @GET("api/v1/satellite/results/{resultId}")
    suspend fun getProcessingResult(
        @Header("Authorization") bearerToken: String,
        @Path("resultId") resultId: String
    ): Response<ResponseBody>

    @GET("api/v1/satellite/files/{fileId}/signed-url")
    suspend fun getOutputFileSignedUrl(
        @Header("Authorization") bearerToken: String,
        @Path("fileId") fileId: String
    ): Response<ResponseBody>

    @DELETE("api/v1/satellite/farms/{farmId}")
    suspend fun deleteFarmData(
        @Header("Authorization") bearerToken: String,
        @Path("farmId") farmId: String
    ): Response<ResponseBody>
}

enum class RasterPipelineExecutionState(val badgeAr: String, val defaultProgressPct: Int) {
    IDLE("في انتظار بدء المعالجة الطيفية", 0),
    QUEUED("في قائمة الانتظار للمعالجة الطيفية (QUEUED)...", 5),
    DISCOVERING_SCENES("جاري البحث في كاتالوج STAC عن مشاهد Sentinel-2 L2A...", 15),
    DISCOVERING_STAC_SCENES("جاري البحث في كاتالوج STAC عن مشاهد Sentinel-2 L2A...", 15),
    DOWNLOADING_ASSETS("جاري تحميل نوافذ نطاقات Sentinel-2 L2A COG (B02..B11 + SCL)...", 30),
    PROCESSING_RASTERS("جاري محاذاة النطاقات المكانية (10m/20m) وتطبيق قناع السحب SCL...", 55),
    PROCESSING_GEOTIFF_BANDS("جاري معالجة نطاقات GeoTIFF (B02..B11 + SCL) وقص مضلع المزرعة...", 55),
    CALCULATING_INDICES("جاري حساب المؤشرات الطيفية (NDVI, NDRE, NDMI, PSRI) لكل بكسل...", 75),
    GENERATING_OUTPUTS("جاري توليد ملفات GeoTIFF المرجعية ومضلعات الشذوذ GeoJSON...", 90),
    COMPLETED_LIVE_GEOREFERENCED("مكتمل — تحليل طيفي مرجعي مكاني حي (LIVE GEOREFERENCED RASTER)", 100),
    FAILED("فشلت المعالجة الطيفية على الخادم (FAILED)", 0),
    CANCELLED("تم إلغاء مهمة المعالجة الطيفية (CANCELLED)", 0),
    FALLBACK_DEMO_MODE("وضع المحاكاة الاسترشادية (DEMO FALLBACK — لم يتم الاتصال بخادم المعالجة المكانية)", 0);

    companion object {
        fun fromBackendJobStatus(status: String): RasterPipelineExecutionState {
            return when (status.trim().uppercase()) {
                "QUEUED" -> QUEUED
                "DISCOVERING_SCENES" -> DISCOVERING_SCENES
                "DOWNLOADING_ASSETS" -> DOWNLOADING_ASSETS
                "PROCESSING_RASTERS", "PROCESSING" -> PROCESSING_RASTERS
                "CALCULATING_INDICES" -> CALCULATING_INDICES
                "GENERATING_OUTPUTS" -> GENERATING_OUTPUTS
                "COMPLETED" -> COMPLETED_LIVE_GEOREFERENCED
                "FAILED" -> FAILED
                "CANCELLED" -> CANCELLED
                else -> PROCESSING_RASTERS
            }
        }
    }
}

data class GeoreferencedRasterBackendResult(
    val resultId: String,
    val jobId: String,
    val farmId: String,
    val ownerUid: String,
    val sceneId: String,
    val cropId: String,
    val executionState: RasterPipelineExecutionState,
    val isLiveGeoreferencedRaster: Boolean,
    val crsEpsg: Int,
    val spatialResolutionMeters: Float,
    val totalPixels: Int,
    val polygonPixels: Int,
    val validPixels: Int,
    val maskedCloudPixels: Int,
    val validPixelRatio: Float,
    val ndviMean: Float,
    val ndviStdDev: Float,
    val ndreMean: Float,
    val ndmiMean: Float,
    val psriMean: Float,
    val anomalyPixelCount: Int,
    val anomalyAreaFeddans: Float,
    val anomalyZonesGeoJson: String,
    val pixelGrid: List<SpectralPixelCell>,
    val vegetationStressDetectionAr: String,
    val agriculturalRiskAssessmentAr: String,
    val confirmedDiseaseDiagnosisStatusAr: String,
    val statusSummaryAr: String,
    val jobStatusHistory: List<String> = emptyList()
)

interface SatelliteRasterBackendRepository {
    suspend fun checkBackendHealth(): Result<Boolean>

    suspend fun runFullGeoreferencedSatelliteAnalysis(
        farmId: String,
        ownerUid: String,
        cropId: String,
        growthStageIndex: Int,
        centerLat: Double,
        centerLon: Double,
        areaFeddans: Float,
        customPolygonGeoJson: String? = null,
        explicitAuthToken: String? = null
    ): Result<GeoreferencedRasterBackendResult>

    suspend fun runFullGeoreferencedSatelliteAnalysis(
        farmId: String,
        ownerUid: String,
        cropId: String,
        growthStageIndex: Int,
        centerLat: Double,
        centerLon: Double,
        areaFeddans: Float,
        customPolygonGeoJson: String? = null,
        explicitAuthToken: String? = null,
        dateStartIso: String? = null,
        dateEndIso: String? = null,
        onProgressUpdate: ((RasterPipelineExecutionState, Int, String) -> Unit)? = null
    ): Result<GeoreferencedRasterBackendResult> = runFullGeoreferencedSatelliteAnalysis(
        farmId = farmId,
        ownerUid = ownerUid,
        cropId = cropId,
        growthStageIndex = growthStageIndex,
        centerLat = centerLat,
        centerLon = centerLon,
        areaFeddans = areaFeddans,
        customPolygonGeoJson = customPolygonGeoJson,
        explicitAuthToken = explicitAuthToken
    )
}

/**
 * Builds a closed WGS84 GeoJSON Polygon representing the farm boundary from its center
 * coordinates and area in feddans (1 feddan = 4200 m²) when no custom vertices are supplied.
 */
fun buildFarmBoundaryGeoJsonPolygon(
    centerLat: Double,
    centerLon: Double,
    areaFeddans: Float
): String {
    val areaM2 = (areaFeddans.coerceIn(1f, 10000f) * 4200.0)
    val halfSideMeters = sqrt(areaM2) / 2.0
    val dLat = halfSideMeters / 111320.0
    val cosLat = max(0.2, cos(Math.toRadians(centerLat)))
    val dLon = halfSideMeters / (111320.0 * cosLat)

    val lonW = formatCoord4(centerLon - dLon)
    val lonE = formatCoord4(centerLon + dLon)
    val latS = formatCoord4(centerLat - dLat)
    val latN = formatCoord4(centerLat + dLat)

    return """{"type":"Polygon","coordinates":[[[$lonW,$latS],[$lonE,$latS],[$lonE,$latN],[$lonW,$latN],[$lonW,$latS]]]}"""
}

/**
 * Pure Kotlin binary GeoTIFF / Cloud-Optimized GeoTIFF (COG) reader & writer for verifying
 * and reading real georeferenced single-band raster files (tags 256, 257, 258, 259, 273,
 * 278, 279, 339, 33550, 33922, 34735, 42113).
 */
object KotlinGeoTiffCogProcessor {
    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_MODEL_PIXEL_SCALE = 33550
    private const val TAG_MODEL_TIEPOINT = 33922
    private const val TAG_GEO_KEY_DIRECTORY = 34735

    data class GeoTiffBand(
        val bandName: String,
        val width: Int,
        val height: Int,
        val crsEpsg: Int,
        val originX: Double,
        val originY: Double,
        val pixelWidth: Double,
        val pixelHeight: Double,
        val grid: Array<FloatArray>
    )

    fun writeGeoTiffBytes(
        width: Int,
        height: Int,
        crsEpsg: Int,
        originX: Double,
        originY: Double,
        pixelWidth: Double,
        pixelHeight: Double,
        grid: Array<FloatArray>,
        isFloat32: Boolean = false
    ): ByteArray {
        val rawPixels = ByteBuffer.allocate(width * height * (if (isFloat32) 4 else 2))
            .order(ByteOrder.LITTLE_ENDIAN)
        for (r in 0 until height) {
            for (c in 0 until width) {
                val v = grid[r][c]
                if (isFloat32) {
                    rawPixels.putFloat(v)
                } else {
                    val iv = if (v.isNaN()) 0 else v.toInt().coerceIn(0, 65535)
                    rawPixels.putShort(iv.toShort())
                }
            }
        }
        val deflater = Deflater()
        deflater.setInput(rawPixels.array())
        deflater.finish()
        val compressedBuf = ByteArray(rawPixels.capacity() + 256)
        val compressedLen = deflater.deflate(compressedBuf)
        deflater.end()
        val stripBytes = compressedBuf.copyOf(compressedLen)

        val scaleBuf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(kotlin.math.abs(pixelWidth))
            .putDouble(kotlin.math.abs(pixelHeight))
            .putDouble(0.0)
            .array()
        val tieBuf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(0.0).putDouble(0.0).putDouble(0.0)
            .putDouble(originX).putDouble(originY).putDouble(0.0)
            .array()
        val gtModel = if (crsEpsg == 4326) 2 else 1
        val crsKey = if (crsEpsg == 4326) 2048 else 3072
        val geoKeysBuf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putShort(0).putShort(3)
            .putShort(1024).putShort(0).putShort(1).putShort(gtModel.toShort())
            .putShort(1025).putShort(0).putShort(1).putShort(1)
            .putShort(crsKey.toShort()).putShort(0).putShort(1).putShort(crsEpsg.toShort())
            .array()

        val stripOffset = 8
        val paddedStripLen = if (stripBytes.size % 2 == 1) stripBytes.size + 1 else stripBytes.size
        val scaleOffset = stripOffset + paddedStripLen
        val tieOffset = scaleOffset + scaleBuf.size
        val geoKeyOffset = tieOffset + tieBuf.size
        val ifdOffset = geoKeyOffset + geoKeysBuf.size

        val entries = listOf(
            intArrayOf(TAG_IMAGE_WIDTH, 4, 1, width),
            intArrayOf(TAG_IMAGE_LENGTH, 4, 1, height),
            intArrayOf(TAG_BITS_PER_SAMPLE, 3, 1, if (isFloat32) 32 else 16),
            intArrayOf(TAG_COMPRESSION, 3, 1, 8),
            intArrayOf(TAG_PHOTOMETRIC, 3, 1, 1),
            intArrayOf(TAG_STRIP_OFFSETS, 4, 1, stripOffset),
            intArrayOf(TAG_SAMPLES_PER_PIXEL, 3, 1, 1),
            intArrayOf(TAG_ROWS_PER_STRIP, 4, 1, height),
            intArrayOf(TAG_STRIP_BYTE_COUNTS, 4, 1, stripBytes.size),
            intArrayOf(TAG_SAMPLE_FORMAT, 3, 1, if (isFloat32) 3 else 1),
            intArrayOf(TAG_MODEL_PIXEL_SCALE, 12, 3, scaleOffset),
            intArrayOf(TAG_MODEL_TIEPOINT, 12, 6, tieOffset),
            intArrayOf(TAG_GEO_KEY_DIRECTORY, 3, 16, geoKeyOffset)
        )

        val totalSize = ifdOffset + 2 + entries.size * 12 + 4
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte()).put('I'.code.toByte())
        out.putShort(42)
        out.putInt(ifdOffset)
        out.put(stripBytes)
        if (stripBytes.size % 2 == 1) out.put(0)
        out.put(scaleBuf)
        out.put(tieBuf)
        out.put(geoKeysBuf)

        out.putShort(entries.size.toShort())
        for (e in entries) {
            out.putShort(e[0].toShort())
            out.putShort(e[1].toShort())
            out.putInt(e[2])
            out.putInt(e[3])
        }
        out.putInt(0)
        return out.array()
    }

    fun readGeoTiffBytes(bytes: ByteArray, bandName: String = "BAND"): GeoTiffBand {
        require(bytes.size >= 8) { "Invalid GeoTIFF: smaller than 8-byte header" }
        val order = if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            ByteOrder.LITTLE_ENDIAN
        } else if (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            ByteOrder.BIG_ENDIAN
        } else {
            throw IllegalArgumentException("Invalid TIFF byte order marker")
        }
        val buf = ByteBuffer.wrap(bytes).order(order)
        val magic = buf.getShort(2).toInt() and 0xFFFF
        require(magic == 42) { "Unsupported TIFF magic: $magic" }
        val ifdOffset = buf.getInt(4)
        val numEntries = buf.getShort(ifdOffset).toInt() and 0xFFFF

        var width = 0
        var height = 0
        var bitsPerSample = 16
        var compression = 1
        var stripOffset = 0
        var stripByteCount = 0
        var sampleFormat = 1
        var scaleX = 10.0
        var scaleY = 10.0
        var originX = 0.0
        var originY = 0.0
        var crsEpsg = 4326

        var pos = ifdOffset + 2
        for (i in 0 until numEntries) {
            val tag = buf.getShort(pos).toInt() and 0xFFFF
            val ttype = buf.getShort(pos + 2).toInt() and 0xFFFF
            val count = buf.getInt(pos + 4)
            val valOrOff = buf.getInt(pos + 8)
            val inlineShort = buf.getShort(pos + 8).toInt() and 0xFFFF

            when (tag) {
                TAG_IMAGE_WIDTH -> width = if (ttype == 3) inlineShort else valOrOff
                TAG_IMAGE_LENGTH -> height = if (ttype == 3) inlineShort else valOrOff
                TAG_BITS_PER_SAMPLE -> bitsPerSample = if (ttype == 3 && count == 1) inlineShort else buf.getShort(valOrOff).toInt() and 0xFFFF
                TAG_COMPRESSION -> compression = inlineShort
                TAG_STRIP_OFFSETS -> stripOffset = if (count == 1) valOrOff else buf.getInt(valOrOff)
                TAG_STRIP_BYTE_COUNTS -> stripByteCount = if (count == 1) valOrOff else buf.getInt(valOrOff)
                TAG_SAMPLE_FORMAT -> sampleFormat = inlineShort
                TAG_MODEL_PIXEL_SCALE -> {
                    scaleX = buf.getDouble(valOrOff)
                    scaleY = buf.getDouble(valOrOff + 8)
                }
                TAG_MODEL_TIEPOINT -> {
                    val tpI = buf.getDouble(valOrOff)
                    val tpJ = buf.getDouble(valOrOff + 8)
                    val tpX = buf.getDouble(valOrOff + 24)
                    val tpY = buf.getDouble(valOrOff + 32)
                    originX = tpX - tpI * scaleX
                    originY = tpY + tpJ * scaleY
                }
                TAG_GEO_KEY_DIRECTORY -> {
                    if (count >= 4) {
                        val numKeys = buf.getShort(valOrOff + 6).toInt() and 0xFFFF
                        var kPos = valOrOff + 8
                        for (k in 0 until numKeys) {
                            if (kPos + 8 <= bytes.size) {
                                val kId = buf.getShort(kPos).toInt() and 0xFFFF
                                val kLoc = buf.getShort(kPos + 2).toInt() and 0xFFFF
                                val kVal = buf.getShort(kPos + 6).toInt() and 0xFFFF
                                if ((kId == 2048 || kId == 3072) && kLoc == 0 && kVal > 0) {
                                    crsEpsg = kVal
                                }
                                kPos += 8
                            }
                        }
                    }
                }
            }
            pos += 12
        }

        require(width > 0 && height > 0) { "GeoTIFF missing width/height" }
        val compressedChunk = bytes.copyOfRange(stripOffset, stripOffset + stripByteCount)
        val rawChunk = if (compression == 8 || compression == 32946) {
            val inflater = Inflater()
            inflater.setInput(compressedChunk)
            val expectedBytes = width * height * (bitsPerSample / 8)
            val outBytes = ByteArray(expectedBytes)
            inflater.inflate(outBytes)
            inflater.end()
            outBytes
        } else {
            compressedChunk
        }

        val pxBuf = ByteBuffer.wrap(rawChunk).order(order)
        val grid = Array(height) { FloatArray(width) }
        for (r in 0 until height) {
            for (c in 0 until width) {
                grid[r][c] = if (bitsPerSample == 32 && sampleFormat == 3) {
                    pxBuf.getFloat()
                } else if (bitsPerSample == 16) {
                    (pxBuf.getShort().toInt() and 0xFFFF).toFloat()
                } else {
                    (pxBuf.get().toInt() and 0xFF).toFloat()
                }
            }
        }

        return GeoTiffBand(
            bandName = bandName,
            width = width,
            height = height,
            crsEpsg = crsEpsg,
            originX = originX,
            originY = originY,
            pixelWidth = scaleX,
            pixelHeight = -scaleY,
            grid = grid
        )
    }
}

class RetrofitSatelliteRasterBackendRepository(
    val environment: BackendEnvironment = BackendEnvironment.resolveFromBuildConfig().first,
    baseUrl: String = BackendEnvironment.resolveFromBuildConfig().second,
    okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val stacClient: OpenSourceSatelliteClient = OpenSourceSatelliteClient(okHttpClient),
    private val tokenProvider: FirebaseIdTokenProvider = DefaultFirebaseIdTokenProvider,
    private val pollIntervalMs: Long = 120L,
    private val maxPollAttempts: Int = 40
) : SatelliteRasterBackendRepository {

    val normalizedBaseUrl: String = BackendEnvironment.resolveBaseUrl(environment, baseUrl)

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(okHttpClient)
        .build()

    val apiService: SatelliteRasterBackendRetrofitService =
        retrofit.create(SatelliteRasterBackendRetrofitService::class.java)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun resolveBearerHeader(
        explicitToken: String?,
        forceRefresh: Boolean = false
    ): String {
        if (!explicitToken.isNullOrBlank() && !forceRefresh) {
            return if (explicitToken.startsWith("Bearer ")) explicitToken else "Bearer $explicitToken"
        }
        val token = tokenProvider.getIdToken(forceRefresh)
        if (!token.isNullOrBlank()) {
            return if (token.startsWith("Bearer ")) token else "Bearer $token"
        }
        if (!explicitToken.isNullOrBlank()) {
            return if (explicitToken.startsWith("Bearer ")) explicitToken else "Bearer $explicitToken"
        }
        return ""
    }

    /**
     * Executes an authenticated Retrofit request and automatically retries once with a
     * force-refreshed Firebase ID token (`getIdToken(true)`) if the server returns HTTP 401.
     */
    private suspend fun executeAuthenticatedCall(
        explicitToken: String?,
        block: suspend (bearerHeader: String) -> Response<ResponseBody>
    ): Pair<Response<ResponseBody>, String> {
        var bearer = resolveBearerHeader(explicitToken, forceRefresh = false)
        var response = block(bearer)
        if (response.code() == 401) {
            val refreshedBearer = resolveBearerHeader(explicitToken = null, forceRefresh = true)
            if (refreshedBearer.isNotBlank() && refreshedBearer != bearer) {
                bearer = refreshedBearer
                response = block(bearer)
            }
        }
        return response to bearer
    }

    override suspend fun checkBackendHealth(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val resp = apiService.getHealth()
            if (!resp.isSuccessful) {
                val errBody = resp.errorBody()?.string()
                return@withContext Result.failure(
                    SatelliteBackendApiException.fromHttpResponse(resp.code(), errBody)
                )
            }
            val bodyStr = resp.body()?.string().orEmpty()
            val status = JSONObject(bodyStr).optString("status")
            Result.success(status == "HEALTHY")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(SatelliteBackendApiException.fromTransportException(e))
        }
    }

    override suspend fun runFullGeoreferencedSatelliteAnalysis(
        farmId: String,
        ownerUid: String,
        cropId: String,
        growthStageIndex: Int,
        centerLat: Double,
        centerLon: Double,
        areaFeddans: Float,
        customPolygonGeoJson: String?,
        explicitAuthToken: String?
    ): Result<GeoreferencedRasterBackendResult> = runFullGeoreferencedSatelliteAnalysis(
        farmId = farmId,
        ownerUid = ownerUid,
        cropId = cropId,
        growthStageIndex = growthStageIndex,
        centerLat = centerLat,
        centerLon = centerLon,
        areaFeddans = areaFeddans,
        customPolygonGeoJson = customPolygonGeoJson,
        explicitAuthToken = explicitAuthToken,
        dateStartIso = null,
        dateEndIso = null,
        onProgressUpdate = null
    )

    override suspend fun runFullGeoreferencedSatelliteAnalysis(
        farmId: String,
        ownerUid: String,
        cropId: String,
        growthStageIndex: Int,
        centerLat: Double,
        centerLon: Double,
        areaFeddans: Float,
        customPolygonGeoJson: String?,
        explicitAuthToken: String?,
        dateStartIso: String?,
        dateEndIso: String?,
        onProgressUpdate: ((RasterPipelineExecutionState, Int, String) -> Unit)?
    ): Result<GeoreferencedRasterBackendResult> = withContext(Dispatchers.IO) {
        try {
            val farmPolygonStr = customPolygonGeoJson?.takeIf { it.isNotBlank() }
                ?: buildFarmBoundaryGeoJsonPolygon(centerLat, centerLon, areaFeddans)
            val farmPolygonObj = JSONObject(farmPolygonStr)

            onProgressUpdate?.invoke(
                RasterPipelineExecutionState.DISCOVERING_SCENES,
                RasterPipelineExecutionState.DISCOVERING_SCENES.defaultProgressPct,
                RasterPipelineExecutionState.DISCOVERING_SCENES.badgeAr
            )

            // 1. Discover scenes via backend POST /api/v1/satellite/scenes
            val scenesReqJson = JSONObject().apply {
                put("farm_id", farmId)
                put("owner_uid", ownerUid)
                put("farm_polygon_geojson", farmPolygonObj)
                put("max_cloud_cover", 35.0)
                put("limit", 5)
                if (!dateStartIso.isNullOrBlank()) put("date_start_iso", dateStartIso)
                if (!dateEndIso.isNullOrBlank()) put("date_end_iso", dateEndIso)
            }
            val (scenesResp, activeBearer) = executeAuthenticatedCall(explicitAuthToken) { bearer ->
                apiService.searchScenes(
                    bearerToken = bearer,
                    requestBody = scenesReqJson.toString().toRequestBody(jsonMediaType)
                )
            }
            if (!scenesResp.isSuccessful) {
                val errBody = scenesResp.errorBody()?.string()
                return@withContext Result.failure(
                    SatelliteBackendApiException.fromHttpResponse(scenesResp.code(), errBody)
                )
            }

            val scenesData = JSONObject(scenesResp.body()?.string().orEmpty())
            val scenesArray = scenesData.optJSONArray("scenes") ?: JSONArray()
            if (scenesArray.length() == 0) {
                return@withContext Result.failure(
                    SatelliteBackendApiException(
                        httpStatusCode = 404,
                        errorCode = "NO_SENTINEL2_SCENES_FOUND",
                        userMessageAr = "لم يتم العثور على مشاهد Sentinel-2 L2A خالية من السحب تغطي مضلع المزرعة في الفترة المحددة."
                    )
                )
            }

            val primaryScene = scenesArray.getJSONObject(0)
            val sceneId = primaryScene.optString("scene_id", "S2_SCENE")
            val bandAssetsObj = primaryScene.optJSONObject("band_assets") ?: JSONObject()
            val scaleFactor = primaryScene.optDouble("scale_factor", 0.0001)
            val boaOffset = primaryScene.optDouble("boa_add_offset", 0.0)

            // 2. Submit raster processing job via POST /api/v1/satellite/process
            val processReqJson = JSONObject().apply {
                put("farm_id", farmId)
                put("owner_uid", ownerUid)
                put("crop_id", cropId)
                put("growth_stage_index", growthStageIndex)
                put("scene_id", sceneId)
                put("scale_factor", scaleFactor)
                put("boa_add_offset", boaOffset)
                put("farm_polygon_geojson", farmPolygonObj)
                put("band_assets", bandAssetsObj)
            }
            val (processResp, bearerAfterSubmit) = executeAuthenticatedCall(activeBearer) { bearer ->
                apiService.submitRasterProcessingJob(
                    bearerToken = bearer,
                    requestBody = processReqJson.toString().toRequestBody(jsonMediaType)
                )
            }
            if (!processResp.isSuccessful) {
                val errBody = processResp.errorBody()?.string()
                return@withContext Result.failure(
                    SatelliteBackendApiException.fromHttpResponse(processResp.code(), errBody)
                )
            }

            val jobJson = JSONObject(processResp.body()?.string().orEmpty())
            val jobId = jobJson.getString("job_id")
            var resultId = jobJson.getString("result_id")
            var jobStatus = jobJson.optString("status", "QUEUED")
            var jobProgressPct = jobJson.optInt("progress_pct", 5)
            val observedStatuses = mutableListOf(jobStatus)

            val initialState = RasterPipelineExecutionState.fromBackendJobStatus(jobStatus)
            onProgressUpdate?.invoke(initialState, jobProgressPct, initialState.badgeAr)

            // 3. Poll GET /api/v1/satellite/jobs/{job_id} across all active lifecycle stages
            val activeInFlightStatuses = setOf(
                "QUEUED",
                "DISCOVERING_SCENES",
                "DOWNLOADING_ASSETS",
                "PROCESSING_RASTERS",
                "CALCULATING_INDICES",
                "GENERATING_OUTPUTS",
                "PROCESSING"
            )
            var pollAttempts = 0
            var currentBearer = bearerAfterSubmit
            var lastErrorCode = jobJson.optString("error_code", "")
            var lastErrorMessage = jobJson.optString("error_message", "")

            while (jobStatus in activeInFlightStatuses && pollAttempts < maxPollAttempts) {
                delay(pollIntervalMs)
                pollAttempts++
                val (jobStatusResp, nextBearer) = executeAuthenticatedCall(currentBearer) { bearer ->
                    apiService.getJobStatus(bearerToken = bearer, jobId = jobId)
                }
                currentBearer = nextBearer
                if (!jobStatusResp.isSuccessful) {
                    val errBody = jobStatusResp.errorBody()?.string()
                    return@withContext Result.failure(
                        SatelliteBackendApiException.fromHttpResponse(jobStatusResp.code(), errBody)
                    )
                }
                val polledObj = JSONObject(jobStatusResp.body()?.string().orEmpty())
                jobStatus = polledObj.optString("status", "FAILED")
                resultId = polledObj.optString("result_id", resultId)
                jobProgressPct = polledObj.optInt("progress_pct", jobProgressPct)
                lastErrorCode = polledObj.optString("error_code", lastErrorCode)
                lastErrorMessage = polledObj.optString("error_message", lastErrorMessage)
                if (observedStatuses.lastOrNull() != jobStatus) {
                    observedStatuses.add(jobStatus)
                }

                val mappedState = RasterPipelineExecutionState.fromBackendJobStatus(jobStatus)
                onProgressUpdate?.invoke(
                    mappedState,
                    if (jobProgressPct > 0) jobProgressPct else mappedState.defaultProgressPct,
                    mappedState.badgeAr
                )
            }

            if (jobStatus == "FAILED" || jobStatus == "CANCELLED") {
                val errCode = lastErrorCode.ifBlank { "RASTER_JOB_$jobStatus" }
                val userMsgAr = "فشلت مهمة معالجة القمر الصناعي ($jobId — $errCode): ${lastErrorMessage.ifBlank { "خطأ في معالجة نطاقات GeoTIFF" }}"
                return@withContext Result.failure(
                    SatelliteBackendApiException(
                        httpStatusCode = 500,
                        errorCode = errCode,
                        userMessageAr = userMsgAr,
                        serverMessage = lastErrorMessage
                    )
                )
            }

            if (jobStatus != "COMPLETED") {
                return@withContext Result.failure(
                    SatelliteBackendApiException(
                        httpStatusCode = 504,
                        errorCode = "RASTER_JOB_TIMEOUT",
                        userMessageAr = "انتهت مهلة انتظار مهمة معالجة القمر الصناعي ($jobId) عند الحالة: $jobStatus"
                    )
                )
            }

            // 4. Retrieve georeferenced result via GET /api/v1/satellite/results/{result_id}
            val (resultResp, _) = executeAuthenticatedCall(currentBearer) { bearer ->
                apiService.getProcessingResult(bearerToken = bearer, resultId = resultId)
            }
            if (!resultResp.isSuccessful) {
                val errBody = resultResp.errorBody()?.string()
                return@withContext Result.failure(
                    SatelliteBackendApiException.fromHttpResponse(resultResp.code(), errBody)
                )
            }

            val resObj = JSONObject(resultResp.body()?.string().orEmpty())
            val statsObj = resObj.getJSONObject("statistics")
            val sciObj = resObj.optJSONObject("scientific_assessment") ?: JSONObject()
            val anomalyGeoJsonObj = resObj.optJSONObject("anomaly_zones_geojson")
                ?: JSONObject("""{"type":"FeatureCollection","features":[]}""")
            val pxArr = resObj.optJSONArray("pixel_evaluations") ?: JSONArray()

            val parsedPixels = mutableListOf<SpectralPixelCell>()
            for (i in 0 until pxArr.length()) {
                val p = pxArr.getJSONObject(i)
                val r = p.optInt("row", i / 12)
                val c = p.optInt("col", i % 12)
                val valveNum = ((r / 4) * 3) + (c / 4) + 1
                parsedPixels.add(
                    SpectralPixelCell(
                        row = r,
                        col = c,
                        valveNumber = valveNum.coerceIn(1, 6),
                        ndvi = p.optDouble("ndvi", 0.0).toFloat(),
                        ndre = p.optDouble("ndre", 0.0).toFloat(),
                        psri = p.optDouble("psri", 0.0).toFloat(),
                        ndmi = p.optDouble("ndmi", 0.0).toFloat(),
                        isHotspot = p.optBoolean("is_anomalous", false),
                        isCloudMasked = p.optBoolean("is_cloud_masked", false)
                    )
                )
            }

            val validPixels = statsObj.optInt("valid_pixel_count", 0)
            val validRatio = statsObj.optDouble("valid_pixel_ratio", 0.0).toFloat()
            val crsEpsg = resObj.optInt("crs_epsg", 32636)
            val resMeters = resObj.optDouble("spatial_resolution_m", 10.0).toFloat()
            val anomCount = statsObj.optInt("anomaly_pixel_count", 0)
            val anomFeddans = statsObj.optDouble("anomaly_area_feddans", 0.0).toFloat()

            onProgressUpdate?.invoke(
                RasterPipelineExecutionState.COMPLETED_LIVE_GEOREFERENCED,
                100,
                RasterPipelineExecutionState.COMPLETED_LIVE_GEOREFERENCED.badgeAr
            )

            Result.success(
                GeoreferencedRasterBackendResult(
                    resultId = resultId,
                    jobId = jobId,
                    farmId = farmId,
                    ownerUid = ownerUid,
                    sceneId = sceneId,
                    cropId = cropId,
                    executionState = RasterPipelineExecutionState.COMPLETED_LIVE_GEOREFERENCED,
                    isLiveGeoreferencedRaster = validPixels > 0,
                    crsEpsg = crsEpsg,
                    spatialResolutionMeters = resMeters,
                    totalPixels = statsObj.optInt("total_pixel_count", 0),
                    polygonPixels = statsObj.optInt("polygon_pixel_count", 0),
                    validPixels = validPixels,
                    maskedCloudPixels = statsObj.optInt("masked_cloud_or_nodata_count", 0),
                    validPixelRatio = validRatio,
                    ndviMean = statsObj.optDouble("ndvi_mean", 0.0).toFloat(),
                    ndviStdDev = statsObj.optDouble("ndvi_std_dev", 0.0).toFloat(),
                    ndreMean = statsObj.optDouble("ndre_mean", 0.0).toFloat(),
                    ndmiMean = statsObj.optDouble("ndmi_mean", 0.0).toFloat(),
                    psriMean = statsObj.optDouble("psri_mean", 0.0).toFloat(),
                    anomalyPixelCount = anomCount,
                    anomalyAreaFeddans = anomFeddans,
                    anomalyZonesGeoJson = anomalyGeoJsonObj.toString(),
                    pixelGrid = parsedPixels,
                    vegetationStressDetectionAr = sciObj.optString("vegetation_stress_detection_ar"),
                    agriculturalRiskAssessmentAr = sciObj.optString("agricultural_risk_assessment_ar"),
                    confirmedDiseaseDiagnosisStatusAr = sciObj.optString("confirmed_disease_diagnosis_status_ar"),
                    statusSummaryAr = "تم تحليل $validPixels بكسل مرجعي (EPSG:$crsEpsg • دقة ${formatDecimal1(resMeters)}م) من المشهد $sceneId؛ بؤر الشذوذ: $anomCount بكسل (${formatDecimal2(anomFeddans)} فدان).",
                    jobStatusHistory = observedStatuses
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(SatelliteBackendApiException.fromTransportException(e))
        }
    }
}
