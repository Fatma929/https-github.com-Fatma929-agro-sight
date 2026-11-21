@file:SuppressLint("NewApi")

package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.AgroClimaticAlert
import com.example.model.AlertSeverity
import com.example.model.DailyMeteoRecord
import com.example.model.formatCoord4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

@Entity(tableName = "sql_field_audits")
data class SqlFieldAuditEntity(
    @PrimaryKey val id: String,
    val farmerId: String,
    val farmerName: String,
    val whatsappPhone: String,
    val gmailAddress: String,
    val nationalId: String,
    val fieldName: String,
    val cropType: String,
    val areaFeddans: Float,
    val soilPh: Float,
    val soilEce: Float,
    val et0MmDay: Float,
    val grossM3PerFeddan: Float,
    val valveRunHours: Float,
    val ndviMean: Float,
    val ndreMean: Float,
    val psriMean: Float,
    val detectedRiskName: String,
    val recordedAt: String
)

/**
 * Room Entity caching 7-day Open-Meteo FAO-56 Penman-Monteith weather forecasts and agro-climatic alerts
 * per field and coordinate pair so the application remains fully functional during poor or offline connectivity.
 */
@Entity(tableName = "cached_weather_forecasts")
data class CachedWeatherForecastEntity(
    @PrimaryKey val cacheKey: String,
    val fieldId: String,
    val fieldName: String,
    val latitude: Double,
    val longitude: Double,
    val baseKc: Float,
    val recordsJson: String,
    val alertsJson: String,
    val todayTempMaxC: Float,
    val todayTempMinC: Float,
    val todayEt0MmDay: Float,
    val todayRelativeHumidityPct: Int,
    val todayWindSpeedKmh: Float,
    val statusMessageAr: String,
    val httpStatusCode: Int,
    val latencyMs: Long,
    val lastSuccessfulSyncIso: String,
    val wasOriginallyLive: Boolean,
    val cachedAtIso: String,
    val cachedAtEpochMs: Long
)

/**
 * Room Entity caching Sentinel-2 L2A STAC scene metadata, NASA POWER agro-climatology telemetry,
 * multi-spectral indices (NDVI, NDRE, NDMI, PSRI), anomaly GeoJSON polygons, and georeferenced raster results
 * for offline resilience in rural agricultural areas.
 */
@Entity(tableName = "cached_satellite_analyses")
data class CachedSatelliteAnalysisEntity(
    @PrimaryKey val cacheKey: String,
    val fieldId: String,
    val fieldName: String,
    val cropNameAr: String,
    val latitude: Double,
    val longitude: Double,
    val areaFeddans: Float,
    val sceneId: String,
    val acquisitionDateIso: String,
    val constellationAr: String,
    val mgrsTileId: String,
    val cloudCoverPct: Float,
    val platformInstrument: String,
    val stacCatalogSource: String,
    val opticalTileUrl: String,
    val nasaSolarRadiationMjM2Day: Float,
    val nasaClearSkyInsolationMjM2Day: Float,
    val nasaRootZoneSoilMoisturePct: Float,
    val nasaTemp2mMaxC: Float,
    val nasaTemp2mMinC: Float,
    val nasaRelativeHumidityPct: Float,
    val nasaWindSpeed2mMs: Float,
    val nasaVaporPressureDeficitKpa: Float,
    val stacAcquisitionStatusName: String,
    val bandAssetsJson: String,
    val temporalAnalysisJson: String,
    val channelsJson: String,
    val ndviMean: Float,
    val ndreMean: Float,
    val ndmiMean: Float,
    val psriMean: Float,
    val detectedRiskNameAr: String,
    val anomalyZonesGeoJson: String,
    val rasterBackendResultJson: String,
    val dataQualityIndicatorAr: String,
    val processingMethodAr: String,
    val wasOriginallyLive: Boolean,
    val lastSyncedIso: String,
    val cachedAtIso: String,
    val cachedAtEpochMs: Long
)

@Dao
interface SqlFieldAuditDao {
    @Query("SELECT * FROM sql_field_audits ORDER BY recordedAt DESC LIMIT 50")
    fun observeAllAudits(): Flow<List<SqlFieldAuditEntity>>

    @Query("SELECT COUNT(*) FROM sql_field_audits")
    suspend fun getAuditCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(entity: SqlFieldAuditEntity)

    @Query("DELETE FROM sql_field_audits WHERE id = :id")
    suspend fun deleteAuditById(id: String)
}

@Dao
interface OfflineTelemetryCacheDao {
    @Query("SELECT * FROM cached_weather_forecasts ORDER BY cachedAtEpochMs DESC")
    fun observeAllCachedWeatherForecasts(): Flow<List<CachedWeatherForecastEntity>>

    @Query("SELECT * FROM cached_weather_forecasts WHERE fieldId = :fieldId ORDER BY cachedAtEpochMs DESC LIMIT 1")
    fun observeCachedWeatherForField(fieldId: String): Flow<CachedWeatherForecastEntity?>

    @Query("SELECT * FROM cached_weather_forecasts WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getCachedWeatherByKey(cacheKey: String): CachedWeatherForecastEntity?

    @Query("SELECT * FROM cached_weather_forecasts WHERE fieldId = :fieldId ORDER BY cachedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestCachedWeatherForField(fieldId: String): CachedWeatherForecastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedWeather(entity: CachedWeatherForecastEntity)

    @Query("DELETE FROM cached_weather_forecasts WHERE cacheKey = :cacheKey")
    suspend fun deleteCachedWeatherByKey(cacheKey: String)

    @Query("DELETE FROM cached_weather_forecasts")
    suspend fun clearAllCachedWeather()

    @Query("SELECT * FROM cached_satellite_analyses ORDER BY cachedAtEpochMs DESC")
    fun observeAllCachedSatelliteAnalyses(): Flow<List<CachedSatelliteAnalysisEntity>>

    @Query("SELECT * FROM cached_satellite_analyses WHERE fieldId = :fieldId ORDER BY cachedAtEpochMs DESC LIMIT 1")
    fun observeCachedSatelliteForField(fieldId: String): Flow<CachedSatelliteAnalysisEntity?>

    @Query("SELECT * FROM cached_satellite_analyses WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getCachedSatelliteByKey(cacheKey: String): CachedSatelliteAnalysisEntity?

    @Query("SELECT * FROM cached_satellite_analyses WHERE fieldId = :fieldId ORDER BY cachedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestCachedSatelliteForField(fieldId: String): CachedSatelliteAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedSatellite(entity: CachedSatelliteAnalysisEntity)

    @Query("DELETE FROM cached_satellite_analyses WHERE cacheKey = :cacheKey")
    suspend fun deleteCachedSatelliteByKey(cacheKey: String)

    @Query("DELETE FROM cached_satellite_analyses")
    suspend fun clearAllCachedSatellite()
}

@Database(
    entities = [
        SqlFieldAuditEntity::class,
        CachedWeatherForecastEntity::class,
        CachedSatelliteAnalysisEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AgroSightRoomDatabase : RoomDatabase() {
    abstract fun sqlFieldAuditDao(): SqlFieldAuditDao
    abstract fun offlineTelemetryCacheDao(): OfflineTelemetryCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AgroSightRoomDatabase? = null

        fun getInstance(context: Context): AgroSightRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AgroSightRoomDatabase::class.java,
                    "agrosight_relational_sql_v2.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Deterministic JSON serializer/deserializer for Room offline telemetry entities.
 */
object OfflineTelemetryCacheSerializer {

    fun buildWeatherCacheKey(fieldId: String, latitude: Double, longitude: Double): String {
        return "weather_${fieldId}_${formatCoord4(latitude)}_${formatCoord4(longitude)}"
    }

    fun buildSatelliteCacheKey(fieldId: String, latitude: Double, longitude: Double): String {
        return "sat_${fieldId}_${formatCoord4(latitude)}_${formatCoord4(longitude)}"
    }

    fun serializeDailyMeteoRecords(records: List<DailyMeteoRecord>): String {
        val arr = JSONArray()
        for (rec in records) {
            val obj = JSONObject()
                .put("dateIso", rec.dateIso)
                .put("dayLabelAr", rec.dayLabelAr)
                .put("tempMaxC", rec.tempMaxC.toDouble())
                .put("tempMinC", rec.tempMinC.toDouble())
                .put("relativeHumidityPct", rec.relativeHumidityPct)
                .put("windSpeedKmh", rec.windSpeedKmh.toDouble())
                .put("solarRadiationMjM2", rec.solarRadiationMjM2.toDouble())
                .put("precipitationMm", rec.precipitationMm.toDouble())
                .put("et0MmDay", rec.et0MmDay.toDouble())
                .put("adjustedKc", rec.adjustedKc.toDouble())
                .put("etcMmDay", rec.etcMmDay.toDouble())
                .put("waterDeficitM3PerFeddan", rec.waterDeficitM3PerFeddan.toDouble())
                .put("cumulativeDeficitM3PerFeddan", rec.cumulativeDeficitM3PerFeddan.toDouble())
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializeDailyMeteoRecords(jsonStr: String): List<DailyMeteoRecord> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<DailyMeteoRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    DailyMeteoRecord(
                        dateIso = obj.optString("dateIso", ""),
                        dayLabelAr = obj.optString("dayLabelAr", "اليوم"),
                        tempMaxC = obj.optDouble("tempMaxC", 31.0).toFloat(),
                        tempMinC = obj.optDouble("tempMinC", 18.0).toFloat(),
                        relativeHumidityPct = obj.optInt("relativeHumidityPct", 45),
                        windSpeedKmh = obj.optDouble("windSpeedKmh", 16.0).toFloat(),
                        solarRadiationMjM2 = obj.optDouble("solarRadiationMjM2", 23.5).toFloat(),
                        precipitationMm = obj.optDouble("precipitationMm", 0.0).toFloat(),
                        et0MmDay = obj.optDouble("et0MmDay", 5.4).toFloat(),
                        adjustedKc = obj.optDouble("adjustedKc", 1.0).toFloat(),
                        etcMmDay = obj.optDouble("etcMmDay", 5.4).toFloat(),
                        waterDeficitM3PerFeddan = obj.optDouble("waterDeficitM3PerFeddan", 22.68).toFloat(),
                        cumulativeDeficitM3PerFeddan = obj.optDouble("cumulativeDeficitM3PerFeddan", 22.68).toFloat()
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeAgroAlerts(alerts: List<AgroClimaticAlert>): String {
        val arr = JSONArray()
        for (a in alerts) {
            val obj = JSONObject()
                .put("titleAr", a.titleAr)
                .put("detailAr", a.detailAr)
                .put("severity", a.severity.name)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializeAgroAlerts(jsonStr: String): List<AgroClimaticAlert> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<AgroClimaticAlert>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sevName = obj.optString("severity", AlertSeverity.OPTIMAL_CLIMATE.name)
                val sev = AlertSeverity.entries.firstOrNull { it.name == sevName }
                    ?: AlertSeverity.OPTIMAL_CLIMATE
                list.add(
                    AgroClimaticAlert(
                        titleAr = obj.optString("titleAr", ""),
                        detailAr = obj.optString("detailAr", ""),
                        severity = sev
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeBandAssets(assets: Sentinel2BandAssets): String {
        return JSONObject()
            .put("b02Blue10mHref", assets.b02Blue10mHref)
            .put("b04Red10mHref", assets.b04Red10mHref)
            .put("b05RedEdge1_20mHref", assets.b05RedEdge1_20mHref)
            .put("b06RedEdge2_20mHref", assets.b06RedEdge2_20mHref)
            .put("b08Nir10mHref", assets.b08Nir10mHref)
            .put("b8aNarrowNir20mHref", assets.b8aNarrowNir20mHref)
            .put("b11Swir16_20mHref", assets.b11Swir16_20mHref)
            .put("sclCloudMask20mHref", assets.sclCloudMask20mHref)
            .put("visualPreviewHref", assets.visualPreviewHref)
            .toString()
    }

    fun deserializeBandAssets(jsonStr: String): Sentinel2BandAssets {
        if (jsonStr.isBlank()) return Sentinel2BandAssets()
        return try {
            val obj = JSONObject(jsonStr)
            Sentinel2BandAssets(
                b02Blue10mHref = obj.optString("b02Blue10mHref", ""),
                b04Red10mHref = obj.optString("b04Red10mHref", ""),
                b05RedEdge1_20mHref = obj.optString("b05RedEdge1_20mHref", ""),
                b06RedEdge2_20mHref = obj.optString("b06RedEdge2_20mHref", ""),
                b08Nir10mHref = obj.optString("b08Nir10mHref", ""),
                b8aNarrowNir20mHref = obj.optString("b8aNarrowNir20mHref", ""),
                b11Swir16_20mHref = obj.optString("b11Swir16_20mHref", ""),
                sclCloudMask20mHref = obj.optString("sclCloudMask20mHref", ""),
                visualPreviewHref = obj.optString("visualPreviewHref", "")
            )
        } catch (_: Exception) {
            Sentinel2BandAssets()
        }
    }

    fun serializeChannels(channels: List<OpenSourceChannelStatus>): String {
        val arr = JSONArray()
        for (ch in channels) {
            arr.put(
                JSONObject()
                    .put("id", ch.id)
                    .put("nameAr", ch.nameAr)
                    .put("platformNameEn", ch.platformNameEn)
                    .put("endpointUrl", ch.endpointUrl)
                    .put("protocolAr", ch.protocolAr)
                    .put("isConnected", ch.isConnected)
                    .put("httpStatusCode", ch.httpStatusCode)
                    .put("latencyMs", ch.latencyMs)
                    .put("lastSyncedIso", ch.lastSyncedIso)
                    .put("licenseAndCitationAr", ch.licenseAndCitationAr)
                    .put("dataMode", ch.dataMode.name)
            )
        }
        return arr.toString()
    }

    fun deserializeChannels(jsonStr: String): List<OpenSourceChannelStatus> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<OpenSourceChannelStatus>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    OpenSourceChannelStatus(
                        id = obj.optString("id", "channel_$i"),
                        nameAr = obj.optString("nameAr", ""),
                        platformNameEn = obj.optString("platformNameEn", ""),
                        endpointUrl = obj.optString("endpointUrl", ""),
                        protocolAr = obj.optString("protocolAr", ""),
                        isConnected = false,
                        httpStatusCode = obj.optInt("httpStatusCode", 200),
                        latencyMs = obj.optLong("latencyMs", 0L),
                        lastSyncedIso = obj.optString("lastSyncedIso", ""),
                        licenseAndCitationAr = obj.optString("licenseAndCitationAr", ""),
                        dataMode = TelemetryDataMode.CACHED
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeTemporalAnalysis(temporal: TemporalChangeAnalysis?): String {
        if (temporal == null) return ""
        val obsArr = JSONArray()
        for (obs in temporal.observations) {
            val o = JSONObject()
                .put("sceneId", obs.sceneId)
                .put("acquisitionDateIso", obs.acquisitionDateIso)
                .put("cloudCoverPct", obs.cloudCoverPct.toDouble())
                .put("isCloudGappedOrUnusable", obs.isCloudGappedOrUnusable)
                .put("qualityNoteAr", obs.qualityNoteAr)
            obs.observedNdviMean?.let { o.put("observedNdviMean", it.toDouble()) }
            obs.observedNdreMean?.let { o.put("observedNdreMean", it.toDouble()) }
            obs.observedNdmiMean?.let { o.put("observedNdmiMean", it.toDouble()) }
            obs.observedPsriMean?.let { o.put("observedPsriMean", it.toDouble()) }
            obsArr.put(o)
        }
        val root = JSONObject()
            .put("observations", obsArr)
            .put("validObservationCount", temporal.validObservationCount)
            .put("cloudGappedCount", temporal.cloudGappedCount)
            .put("temporalTrendSummaryAr", temporal.temporalTrendSummaryAr)
            .put("coverageGapDisclaimerAr", temporal.coverageGapDisclaimerAr)
        temporal.ndviChangeVsPrevious?.let { root.put("ndviChangeVsPrevious", it.toDouble()) }
        temporal.ndreChangeVsPrevious?.let { root.put("ndreChangeVsPrevious", it.toDouble()) }
        temporal.ndmiChangeVsPrevious?.let { root.put("ndmiChangeVsPrevious", it.toDouble()) }
        return root.toString()
    }

    fun deserializeTemporalAnalysis(jsonStr: String): TemporalChangeAnalysis? {
        if (jsonStr.isBlank()) return null
        return try {
            val root = JSONObject(jsonStr)
            val obsArr = root.optJSONArray("observations") ?: JSONArray()
            val obsList = mutableListOf<TemporalSatelliteObservation>()
            for (i in 0 until obsArr.length()) {
                val o = obsArr.optJSONObject(i) ?: continue
                obsList.add(
                    TemporalSatelliteObservation(
                        sceneId = o.optString("sceneId", ""),
                        acquisitionDateIso = o.optString("acquisitionDateIso", ""),
                        cloudCoverPct = o.optDouble("cloudCoverPct", 0.0).toFloat(),
                        isCloudGappedOrUnusable = o.optBoolean("isCloudGappedOrUnusable", false),
                        observedNdviMean = if (o.has("observedNdviMean")) o.optDouble("observedNdviMean").toFloat() else null,
                        observedNdreMean = if (o.has("observedNdreMean")) o.optDouble("observedNdreMean").toFloat() else null,
                        observedNdmiMean = if (o.has("observedNdmiMean")) o.optDouble("observedNdmiMean").toFloat() else null,
                        observedPsriMean = if (o.has("observedPsriMean")) o.optDouble("observedPsriMean").toFloat() else null,
                        qualityNoteAr = o.optString("qualityNoteAr", "")
                    )
                )
            }
            TemporalChangeAnalysis(
                observations = obsList,
                validObservationCount = root.optInt("validObservationCount", 0),
                cloudGappedCount = root.optInt("cloudGappedCount", 0),
                ndviChangeVsPrevious = if (root.has("ndviChangeVsPrevious")) root.optDouble("ndviChangeVsPrevious").toFloat() else null,
                ndreChangeVsPrevious = if (root.has("ndreChangeVsPrevious")) root.optDouble("ndreChangeVsPrevious").toFloat() else null,
                ndmiChangeVsPrevious = if (root.has("ndmiChangeVsPrevious")) root.optDouble("ndmiChangeVsPrevious").toFloat() else null,
                temporalTrendSummaryAr = root.optString("temporalTrendSummaryAr", ""),
                coverageGapDisclaimerAr = root.optString("coverageGapDisclaimerAr", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun serializeRasterBackendResult(result: GeoreferencedRasterBackendResult?): String {
        if (result == null) return ""
        val gridArr = JSONArray()
        for (cell in result.pixelGrid) {
            gridArr.put(
                JSONObject()
                    .put("row", cell.row)
                    .put("col", cell.col)
                    .put("valveNumber", cell.valveNumber)
                    .put("ndvi", cell.ndvi.toDouble())
                    .put("ndre", cell.ndre.toDouble())
                    .put("psri", cell.psri.toDouble())
                    .put("ndmi", cell.ndmi.toDouble())
                    .put("isHotspot", cell.isHotspot)
                    .put("isCloudMasked", cell.isCloudMasked)
            )
        }
        val historyArr = JSONArray()
        result.jobStatusHistory.forEach { historyArr.put(it) }

        return JSONObject()
            .put("resultId", result.resultId)
            .put("jobId", result.jobId)
            .put("farmId", result.farmId)
            .put("ownerUid", result.ownerUid)
            .put("sceneId", result.sceneId)
            .put("cropId", result.cropId)
            .put("executionState", result.executionState.name)
            .put("isLiveGeoreferencedRaster", result.isLiveGeoreferencedRaster)
            .put("crsEpsg", result.crsEpsg)
            .put("spatialResolutionMeters", result.spatialResolutionMeters.toDouble())
            .put("totalPixels", result.totalPixels)
            .put("polygonPixels", result.polygonPixels)
            .put("validPixels", result.validPixels)
            .put("maskedCloudPixels", result.maskedCloudPixels)
            .put("validPixelRatio", result.validPixelRatio.toDouble())
            .put("ndviMean", result.ndviMean.toDouble())
            .put("ndviStdDev", result.ndviStdDev.toDouble())
            .put("ndreMean", result.ndreMean.toDouble())
            .put("ndmiMean", result.ndmiMean.toDouble())
            .put("psriMean", result.psriMean.toDouble())
            .put("anomalyPixelCount", result.anomalyPixelCount)
            .put("anomalyAreaFeddans", result.anomalyAreaFeddans.toDouble())
            .put("anomalyZonesGeoJson", result.anomalyZonesGeoJson)
            .put("pixelGrid", gridArr)
            .put("vegetationStressDetectionAr", result.vegetationStressDetectionAr)
            .put("agriculturalRiskAssessmentAr", result.agriculturalRiskAssessmentAr)
            .put("confirmedDiseaseDiagnosisStatusAr", result.confirmedDiseaseDiagnosisStatusAr)
            .put("statusSummaryAr", result.statusSummaryAr)
            .put("jobStatusHistory", historyArr)
            .toString()
    }

    fun deserializeRasterBackendResult(jsonStr: String): GeoreferencedRasterBackendResult? {
        if (jsonStr.isBlank()) return null
        return try {
            val obj = JSONObject(jsonStr)
            val gridArr = obj.optJSONArray("pixelGrid") ?: JSONArray()
            val grid = mutableListOf<SpectralPixelCell>()
            for (i in 0 until gridArr.length()) {
                val c = gridArr.optJSONObject(i) ?: continue
                grid.add(
                    SpectralPixelCell(
                        row = c.optInt("row", 0),
                        col = c.optInt("col", 0),
                        valveNumber = c.optInt("valveNumber", 1),
                        ndvi = c.optDouble("ndvi", 0.65).toFloat(),
                        ndre = c.optDouble("ndre", 0.35).toFloat(),
                        psri = c.optDouble("psri", 0.08).toFloat(),
                        ndmi = c.optDouble("ndmi", 0.25).toFloat(),
                        isHotspot = c.optBoolean("isHotspot", false),
                        isCloudMasked = c.optBoolean("isCloudMasked", false)
                    )
                )
            }
            val histArr = obj.optJSONArray("jobStatusHistory") ?: JSONArray()
            val hist = mutableListOf<String>()
            for (i in 0 until histArr.length()) {
                hist.add(histArr.optString(i, ""))
            }
            val stateName = obj.optString("executionState", RasterPipelineExecutionState.COMPLETED_LIVE_GEOREFERENCED.name)
            val state = RasterPipelineExecutionState.entries.firstOrNull { it.name == stateName }
                ?: RasterPipelineExecutionState.COMPLETED_LIVE_GEOREFERENCED

            GeoreferencedRasterBackendResult(
                resultId = obj.optString("resultId", "cached_result"),
                jobId = obj.optString("jobId", "cached_job"),
                farmId = obj.optString("farmId", ""),
                ownerUid = obj.optString("ownerUid", ""),
                sceneId = obj.optString("sceneId", ""),
                cropId = obj.optString("cropId", "tomato"),
                executionState = state,
                isLiveGeoreferencedRaster = obj.optBoolean("isLiveGeoreferencedRaster", false),
                crsEpsg = obj.optInt("crsEpsg", 32636),
                spatialResolutionMeters = obj.optDouble("spatialResolutionMeters", 10.0).toFloat(),
                totalPixels = obj.optInt("totalPixels", 96),
                polygonPixels = obj.optInt("polygonPixels", 96),
                validPixels = obj.optInt("validPixels", 96),
                maskedCloudPixels = obj.optInt("maskedCloudPixels", 0),
                validPixelRatio = obj.optDouble("validPixelRatio", 1.0).toFloat(),
                ndviMean = obj.optDouble("ndviMean", 0.68).toFloat(),
                ndviStdDev = obj.optDouble("ndviStdDev", 0.05).toFloat(),
                ndreMean = obj.optDouble("ndreMean", 0.38).toFloat(),
                ndmiMean = obj.optDouble("ndmiMean", 0.28).toFloat(),
                psriMean = obj.optDouble("psriMean", 0.08).toFloat(),
                anomalyPixelCount = obj.optInt("anomalyPixelCount", 0),
                anomalyAreaFeddans = obj.optDouble("anomalyAreaFeddans", 0.0).toFloat(),
                anomalyZonesGeoJson = obj.optString("anomalyZonesGeoJson", """{"type":"FeatureCollection","features":[]}"""),
                pixelGrid = grid,
                vegetationStressDetectionAr = obj.optString("vegetationStressDetectionAr", ""),
                agriculturalRiskAssessmentAr = obj.optString("agriculturalRiskAssessmentAr", ""),
                confirmedDiseaseDiagnosisStatusAr = obj.optString("confirmedDiseaseDiagnosisStatusAr", ""),
                statusSummaryAr = "[CACHED • Room SQLite] " + obj.optString("statusSummaryAr", ""),
                jobStatusHistory = hist
            )
        } catch (_: Exception) {
            null
        }
    }

    fun toOpenMeteoResultFromCache(entity: CachedWeatherForecastEntity): OpenMeteoResult {
        val records = deserializeDailyMeteoRecords(entity.recordsJson)
        val alerts = deserializeAgroAlerts(entity.alertsJson)
        return OpenMeteoResult(
            records = records,
            isLiveConnected = false,
            statusMessageAr = "[CACHED • Room SQLite] بيانات أرصاد مخزنة محلياً للعمل بدون إنترنت (آخر مزامنة: ${entity.cachedAtIso})",
            alerts = alerts,
            httpStatusCode = entity.httpStatusCode,
            latencyMs = entity.latencyMs,
            lastSuccessfulSyncIso = entity.lastSuccessfulSyncIso,
            dataMode = TelemetryDataMode.CACHED,
            errorDetailAr = "تم الاسترجاع من قاعدة بيانات Room SQLite المحلية"
        )
    }

    fun toSatelliteTelemetryFromCache(entity: CachedSatelliteAnalysisEntity): RealSatelliteAndNasaTelemetry {
        val stacStatus = StacAcquisitionStatus.entries.firstOrNull { it.name == entity.stacAcquisitionStatusName }
            ?: StacAcquisitionStatus.VALID_SCENE_WITH_BAND_ASSETS
        return RealSatelliteAndNasaTelemetry(
            sceneId = entity.sceneId,
            acquisitionDateIso = entity.acquisitionDateIso,
            constellationAr = entity.constellationAr,
            mgrsTileId = entity.mgrsTileId,
            cloudCoverPct = entity.cloudCoverPct,
            platformInstrument = entity.platformInstrument,
            stacCatalogSource = entity.stacCatalogSource,
            opticalTileUrl = entity.opticalTileUrl,
            retrievedSatelliteBitmap = null,
            nasaSolarRadiationMjM2Day = entity.nasaSolarRadiationMjM2Day,
            nasaClearSkyInsolationMjM2Day = entity.nasaClearSkyInsolationMjM2Day,
            nasaRootZoneSoilMoisturePct = entity.nasaRootZoneSoilMoisturePct,
            nasaTemp2mMaxC = entity.nasaTemp2mMaxC,
            nasaTemp2mMinC = entity.nasaTemp2mMinC,
            nasaRelativeHumidityPct = entity.nasaRelativeHumidityPct,
            nasaWindSpeed2mMs = entity.nasaWindSpeed2mMs,
            nasaVaporPressureDeficitKpa = entity.nasaVaporPressureDeficitKpa,
            channels = deserializeChannels(entity.channelsJson),
            stacAcquisitionStatus = stacStatus,
            bandAssets = deserializeBandAssets(entity.bandAssetsJson),
            temporalAnalysis = deserializeTemporalAnalysis(entity.temporalAnalysisJson),
            processingTimestampIso = entity.cachedAtIso,
            dataQualityIndicatorAr = "[CACHED • Room SQLite] ${entity.dataQualityIndicatorAr} (مخزن محلياً: ${entity.cachedAtIso})",
            processingMethodAr = "${entity.processingMethodAr} • Room Offline Cache",
            dataMode = TelemetryDataMode.CACHED
        )
    }
}

/**
 * Repository contract abstracting Room SQLite offline caching for satellite analysis results and weather data.
 */
interface OfflineTelemetryCacheDataSource {
    val allCachedWeatherForecasts: Flow<List<CachedWeatherForecastEntity>>
    val allCachedSatelliteAnalyses: Flow<List<CachedSatelliteAnalysisEntity>>

    fun observeCachedWeatherForField(fieldId: String): Flow<CachedWeatherForecastEntity?>
    fun observeCachedSatelliteForField(fieldId: String): Flow<CachedSatelliteAnalysisEntity?>

    suspend fun cacheWeatherForecast(
        fieldId: String,
        fieldName: String,
        latitude: Double,
        longitude: Double,
        baseKc: Float,
        meteoResult: OpenMeteoResult
    ): CachedWeatherForecastEntity

    suspend fun cacheSatelliteAnalysis(
        fieldId: String,
        fieldName: String,
        cropNameAr: String,
        latitude: Double,
        longitude: Double,
        areaFeddans: Float,
        telemetry: RealSatelliteAndNasaTelemetry,
        epidemiologyReport: EpidemiologyDiagnosisReport,
        rasterBackendResult: GeoreferencedRasterBackendResult?
    ): CachedSatelliteAnalysisEntity

    suspend fun getCachedWeatherForecast(
        fieldId: String,
        latitude: Double,
        longitude: Double
    ): CachedWeatherForecastEntity?

    suspend fun getCachedSatelliteAnalysis(
        fieldId: String,
        latitude: Double,
        longitude: Double
    ): CachedSatelliteAnalysisEntity?

    suspend fun clearAllOfflineCache()
}

/**
 * Production Room SQLite Repository implementation for caching weather forecasts and satellite analysis results.
 */
class RoomOfflineTelemetryCacheRepository(
    private val dao: OfflineTelemetryCacheDao
) : OfflineTelemetryCacheDataSource {

    override val allCachedWeatherForecasts: Flow<List<CachedWeatherForecastEntity>> =
        dao.observeAllCachedWeatherForecasts()

    override val allCachedSatelliteAnalyses: Flow<List<CachedSatelliteAnalysisEntity>> =
        dao.observeAllCachedSatelliteAnalyses()

    override fun observeCachedWeatherForField(fieldId: String): Flow<CachedWeatherForecastEntity?> =
        dao.observeCachedWeatherForField(fieldId)

    override fun observeCachedSatelliteForField(fieldId: String): Flow<CachedSatelliteAnalysisEntity?> =
        dao.observeCachedSatelliteForField(fieldId)

    override suspend fun cacheWeatherForecast(
        fieldId: String,
        fieldName: String,
        latitude: Double,
        longitude: Double,
        baseKc: Float,
        meteoResult: OpenMeteoResult
    ): CachedWeatherForecastEntity {
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString().take(19).replace('T', ' ')
        val today = meteoResult.records.firstOrNull()
        val entity = CachedWeatherForecastEntity(
            cacheKey = OfflineTelemetryCacheSerializer.buildWeatherCacheKey(fieldId, latitude, longitude),
            fieldId = fieldId,
            fieldName = fieldName,
            latitude = latitude,
            longitude = longitude,
            baseKc = baseKc,
            recordsJson = OfflineTelemetryCacheSerializer.serializeDailyMeteoRecords(meteoResult.records),
            alertsJson = OfflineTelemetryCacheSerializer.serializeAgroAlerts(meteoResult.alerts),
            todayTempMaxC = today?.tempMaxC ?: 31.0f,
            todayTempMinC = today?.tempMinC ?: 18.0f,
            todayEt0MmDay = today?.et0MmDay ?: 5.4f,
            todayRelativeHumidityPct = today?.relativeHumidityPct ?: 45,
            todayWindSpeedKmh = today?.windSpeedKmh ?: 16.0f,
            statusMessageAr = meteoResult.statusMessageAr,
            httpStatusCode = meteoResult.httpStatusCode,
            latencyMs = meteoResult.latencyMs,
            lastSuccessfulSyncIso = meteoResult.lastSuccessfulSyncIso.ifBlank { nowIso },
            wasOriginallyLive = meteoResult.isLiveConnected,
            cachedAtIso = nowIso,
            cachedAtEpochMs = nowMs
        )
        dao.insertCachedWeather(entity)
        return entity
    }

    override suspend fun cacheSatelliteAnalysis(
        fieldId: String,
        fieldName: String,
        cropNameAr: String,
        latitude: Double,
        longitude: Double,
        areaFeddans: Float,
        telemetry: RealSatelliteAndNasaTelemetry,
        epidemiologyReport: EpidemiologyDiagnosisReport,
        rasterBackendResult: GeoreferencedRasterBackendResult?
    ): CachedSatelliteAnalysisEntity {
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString().take(19).replace('T', ' ')
        val ndvi = rasterBackendResult?.ndviMean ?: epidemiologyReport.ndviMean
        val ndre = rasterBackendResult?.ndreMean ?: epidemiologyReport.ndreMean
        val ndmi = rasterBackendResult?.ndmiMean ?: epidemiologyReport.ndmiMean
        val psri = rasterBackendResult?.psriMean ?: epidemiologyReport.psriMean
        val anomalyGeoJson = rasterBackendResult?.anomalyZonesGeoJson
            ?: buildFarmBoundaryGeoJsonPolygon(latitude, longitude, areaFeddans)

        val entity = CachedSatelliteAnalysisEntity(
            cacheKey = OfflineTelemetryCacheSerializer.buildSatelliteCacheKey(fieldId, latitude, longitude),
            fieldId = fieldId,
            fieldName = fieldName,
            cropNameAr = cropNameAr,
            latitude = latitude,
            longitude = longitude,
            areaFeddans = areaFeddans,
            sceneId = rasterBackendResult?.sceneId?.ifBlank { telemetry.sceneId } ?: telemetry.sceneId,
            acquisitionDateIso = telemetry.acquisitionDateIso,
            constellationAr = telemetry.constellationAr,
            mgrsTileId = telemetry.mgrsTileId,
            cloudCoverPct = telemetry.cloudCoverPct,
            platformInstrument = telemetry.platformInstrument,
            stacCatalogSource = telemetry.stacCatalogSource,
            opticalTileUrl = telemetry.opticalTileUrl,
            nasaSolarRadiationMjM2Day = telemetry.nasaSolarRadiationMjM2Day,
            nasaClearSkyInsolationMjM2Day = telemetry.nasaClearSkyInsolationMjM2Day,
            nasaRootZoneSoilMoisturePct = telemetry.nasaRootZoneSoilMoisturePct,
            nasaTemp2mMaxC = telemetry.nasaTemp2mMaxC,
            nasaTemp2mMinC = telemetry.nasaTemp2mMinC,
            nasaRelativeHumidityPct = telemetry.nasaRelativeHumidityPct,
            nasaWindSpeed2mMs = telemetry.nasaWindSpeed2mMs,
            nasaVaporPressureDeficitKpa = telemetry.nasaVaporPressureDeficitKpa,
            stacAcquisitionStatusName = telemetry.stacAcquisitionStatus.name,
            bandAssetsJson = OfflineTelemetryCacheSerializer.serializeBandAssets(telemetry.bandAssets),
            temporalAnalysisJson = OfflineTelemetryCacheSerializer.serializeTemporalAnalysis(telemetry.temporalAnalysis),
            channelsJson = OfflineTelemetryCacheSerializer.serializeChannels(telemetry.channels),
            ndviMean = ndvi,
            ndreMean = ndre,
            ndmiMean = ndmi,
            psriMean = psri,
            detectedRiskNameAr = epidemiologyReport.detectedRiskNameAr,
            anomalyZonesGeoJson = anomalyGeoJson,
            rasterBackendResultJson = OfflineTelemetryCacheSerializer.serializeRasterBackendResult(rasterBackendResult),
            dataQualityIndicatorAr = telemetry.dataQualityIndicatorAr,
            processingMethodAr = telemetry.processingMethodAr,
            wasOriginallyLive = telemetry.dataMode == TelemetryDataMode.LIVE || (rasterBackendResult?.isLiveGeoreferencedRaster == true),
            lastSyncedIso = telemetry.processingTimestampIso.ifBlank { nowIso },
            cachedAtIso = nowIso,
            cachedAtEpochMs = nowMs
        )
        dao.insertCachedSatellite(entity)
        return entity
    }

    override suspend fun getCachedWeatherForecast(
        fieldId: String,
        latitude: Double,
        longitude: Double
    ): CachedWeatherForecastEntity? {
        val exactKey = OfflineTelemetryCacheSerializer.buildWeatherCacheKey(fieldId, latitude, longitude)
        return dao.getCachedWeatherByKey(exactKey) ?: dao.getLatestCachedWeatherForField(fieldId)
    }

    override suspend fun getCachedSatelliteAnalysis(
        fieldId: String,
        latitude: Double,
        longitude: Double
    ): CachedSatelliteAnalysisEntity? {
        val exactKey = OfflineTelemetryCacheSerializer.buildSatelliteCacheKey(fieldId, latitude, longitude)
        return dao.getCachedSatelliteByKey(exactKey) ?: dao.getLatestCachedSatelliteForField(fieldId)
    }

    override suspend fun clearAllOfflineCache() {
        dao.clearAllCachedWeather()
        dao.clearAllCachedSatellite()
    }
}

/**
 * In-memory implementation of [OfflineTelemetryCacheDataSource] for lightweight unit test injection.
 */
class InMemoryOfflineTelemetryCacheRepository : OfflineTelemetryCacheDataSource {
    private val weatherState = MutableStateFlow<List<CachedWeatherForecastEntity>>(emptyList())
    private val satelliteState = MutableStateFlow<List<CachedSatelliteAnalysisEntity>>(emptyList())

    override val allCachedWeatherForecasts: Flow<List<CachedWeatherForecastEntity>> = weatherState.asStateFlow()
    override val allCachedSatelliteAnalyses: Flow<List<CachedSatelliteAnalysisEntity>> = satelliteState.asStateFlow()

    override fun observeCachedWeatherForField(fieldId: String): Flow<CachedWeatherForecastEntity?> =
        weatherState.map { list ->
            list.filter { it.fieldId == fieldId }.maxByOrNull { it.cachedAtEpochMs }
        }

    override fun observeCachedSatelliteForField(fieldId: String): Flow<CachedSatelliteAnalysisEntity?> =
        satelliteState.map { list ->
            list.filter { it.fieldId == fieldId }.maxByOrNull { it.cachedAtEpochMs }
        }

    override suspend fun cacheWeatherForecast(
        fieldId: String,
        fieldName: String,
        latitude: Double,
        longitude: Double,
        baseKc: Float,
        meteoResult: OpenMeteoResult
    ): CachedWeatherForecastEntity {
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString().take(19).replace('T', ' ')
        val today = meteoResult.records.firstOrNull()
        val entity = CachedWeatherForecastEntity(
            cacheKey = OfflineTelemetryCacheSerializer.buildWeatherCacheKey(fieldId, latitude, longitude),
            fieldId = fieldId,
            fieldName = fieldName,
            latitude = latitude,
            longitude = longitude,
            baseKc = baseKc,
            recordsJson = OfflineTelemetryCacheSerializer.serializeDailyMeteoRecords(meteoResult.records),
            alertsJson = OfflineTelemetryCacheSerializer.serializeAgroAlerts(meteoResult.alerts),
            todayTempMaxC = today?.tempMaxC ?: 31.0f,
            todayTempMinC = today?.tempMinC ?: 18.0f,
            todayEt0MmDay = today?.et0MmDay ?: 5.4f,
            todayRelativeHumidityPct = today?.relativeHumidityPct ?: 45,
            todayWindSpeedKmh = today?.windSpeedKmh ?: 16.0f,
            statusMessageAr = meteoResult.statusMessageAr,
            httpStatusCode = meteoResult.httpStatusCode,
            latencyMs = meteoResult.latencyMs,
            lastSuccessfulSyncIso = meteoResult.lastSuccessfulSyncIso.ifBlank { nowIso },
            wasOriginallyLive = meteoResult.isLiveConnected,
            cachedAtIso = nowIso,
            cachedAtEpochMs = nowMs
        )
        weatherState.update { list ->
            (list.filterNot { it.cacheKey == entity.cacheKey } + entity)
                .sortedByDescending { it.cachedAtEpochMs }
        }
        return entity
    }

    override suspend fun cacheSatelliteAnalysis(
        fieldId: String,
        fieldName: String,
        cropNameAr: String,
        latitude: Double,
        longitude: Double,
        areaFeddans: Float,
        telemetry: RealSatelliteAndNasaTelemetry,
        epidemiologyReport: EpidemiologyDiagnosisReport,
        rasterBackendResult: GeoreferencedRasterBackendResult?
    ): CachedSatelliteAnalysisEntity {
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString().take(19).replace('T', ' ')
        val ndvi = rasterBackendResult?.ndviMean ?: epidemiologyReport.ndviMean
        val ndre = rasterBackendResult?.ndreMean ?: epidemiologyReport.ndreMean
        val ndmi = rasterBackendResult?.ndmiMean ?: epidemiologyReport.ndmiMean
        val psri = rasterBackendResult?.psriMean ?: epidemiologyReport.psriMean
        val anomalyGeoJson = rasterBackendResult?.anomalyZonesGeoJson
            ?: buildFarmBoundaryGeoJsonPolygon(latitude, longitude, areaFeddans)

        val entity = CachedSatelliteAnalysisEntity(
            cacheKey = OfflineTelemetryCacheSerializer.buildSatelliteCacheKey(fieldId, latitude, longitude),
            fieldId = fieldId,
            fieldName = fieldName,
            cropNameAr = cropNameAr,
            latitude = latitude,
            longitude = longitude,
            areaFeddans = areaFeddans,
            sceneId = rasterBackendResult?.sceneId?.ifBlank { telemetry.sceneId } ?: telemetry.sceneId,
            acquisitionDateIso = telemetry.acquisitionDateIso,
            constellationAr = telemetry.constellationAr,
            mgrsTileId = telemetry.mgrsTileId,
            cloudCoverPct = telemetry.cloudCoverPct,
            platformInstrument = telemetry.platformInstrument,
            stacCatalogSource = telemetry.stacCatalogSource,
            opticalTileUrl = telemetry.opticalTileUrl,
            nasaSolarRadiationMjM2Day = telemetry.nasaSolarRadiationMjM2Day,
            nasaClearSkyInsolationMjM2Day = telemetry.nasaClearSkyInsolationMjM2Day,
            nasaRootZoneSoilMoisturePct = telemetry.nasaRootZoneSoilMoisturePct,
            nasaTemp2mMaxC = telemetry.nasaTemp2mMaxC,
            nasaTemp2mMinC = telemetry.nasaTemp2mMinC,
            nasaRelativeHumidityPct = telemetry.nasaRelativeHumidityPct,
            nasaWindSpeed2mMs = telemetry.nasaWindSpeed2mMs,
            nasaVaporPressureDeficitKpa = telemetry.nasaVaporPressureDeficitKpa,
            stacAcquisitionStatusName = telemetry.stacAcquisitionStatus.name,
            bandAssetsJson = OfflineTelemetryCacheSerializer.serializeBandAssets(telemetry.bandAssets),
            temporalAnalysisJson = OfflineTelemetryCacheSerializer.serializeTemporalAnalysis(telemetry.temporalAnalysis),
            channelsJson = OfflineTelemetryCacheSerializer.serializeChannels(telemetry.channels),
            ndviMean = ndvi,
            ndreMean = ndre,
            ndmiMean = ndmi,
            psriMean = psri,
            detectedRiskNameAr = epidemiologyReport.detectedRiskNameAr,
            anomalyZonesGeoJson = anomalyGeoJson,
            rasterBackendResultJson = OfflineTelemetryCacheSerializer.serializeRasterBackendResult(rasterBackendResult),
            dataQualityIndicatorAr = telemetry.dataQualityIndicatorAr,
            processingMethodAr = telemetry.processingMethodAr,
            wasOriginallyLive = telemetry.dataMode == TelemetryDataMode.LIVE || (rasterBackendResult?.isLiveGeoreferencedRaster == true),
            lastSyncedIso = telemetry.processingTimestampIso.ifBlank { nowIso },
            cachedAtIso = nowIso,
            cachedAtEpochMs = nowMs
        )
        satelliteState.update { list ->
            (list.filterNot { it.cacheKey == entity.cacheKey } + entity)
                .sortedByDescending { it.cachedAtEpochMs }
        }
        return entity
    }

    override suspend fun getCachedWeatherForecast(
        fieldId: String,
        latitude: Double,
        longitude: Double
    ): CachedWeatherForecastEntity? {
        val exactKey = OfflineTelemetryCacheSerializer.buildWeatherCacheKey(fieldId, latitude, longitude)
        return weatherState.value.firstOrNull { it.cacheKey == exactKey }
            ?: weatherState.value.filter { it.fieldId == fieldId }.maxByOrNull { it.cachedAtEpochMs }
    }

    override suspend fun getCachedSatelliteAnalysis(
        fieldId: String,
        latitude: Double,
        longitude: Double
    ): CachedSatelliteAnalysisEntity? {
        val exactKey = OfflineTelemetryCacheSerializer.buildSatelliteCacheKey(fieldId, latitude, longitude)
        return satelliteState.value.firstOrNull { it.cacheKey == exactKey }
            ?: satelliteState.value.filter { it.fieldId == fieldId }.maxByOrNull { it.cachedAtEpochMs }
    }

    override suspend fun clearAllOfflineCache() {
        weatherState.value = emptyList()
        satelliteState.value = emptyList()
    }
}
