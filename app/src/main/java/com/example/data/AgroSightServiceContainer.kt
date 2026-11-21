package com.example.data

import android.content.Context
import com.example.model.CropSpec
import com.example.model.DailyMeteoRecord
import com.example.model.GrowthStageEvaluation
import com.example.model.IrrigationDayStatus
import com.example.model.IrrigationSystemType
import com.example.model.SoilChemistryDiagnostics
import com.example.model.SoilTexture
import com.example.model.SystemWideAnalyticsSummary
import com.example.model.ValveSectorTelemetry
import com.example.model.WeeklyIrrigationDayItem
import com.example.model.WhatIfSimulationResult
import com.example.model.WhatIfSimulatorInput
import com.example.model.analyzeSoilChemistry
import com.example.model.buildSatelliteValveSectors
import com.example.model.buildWeeklyIrrigationSchedule
import com.example.model.evaluateCropGrowthStage
import com.example.model.evaluateWhatIfSimulation

/**
 * Lightweight, testable Dependency Injection container for AgroSight data sources and domain use-cases.
 * Keeps Firebase, Room SQLite, Open-Meteo, Sentinel-2/NASA telemetry, and Gemini AI Copilot decoupled
 * from presentation state so unit and Robolectric tests can substitute fakes without network or emulator I/O.
 */
data class AgroSightDependencies(
    val databaseService: DatabaseService,
    val openMeteoClient: MeteoForecastDataSource,
    val openSourceSatelliteClient: SatelliteTelemetryDataSource,
    val geminiAgroService: AgroAiCopilotDataSource,
    val cloudWorker: AgroSightCloudWorker,
    val firestoreRepository: CloudWorkspaceRepository,
    val sqlAuditDao: SqlFieldAuditDao,
    val satelliteRasterBackendRepository: SatelliteRasterBackendRepository = RetrofitSatelliteRasterBackendRepository(),
    val offlineTelemetryCacheRepository: OfflineTelemetryCacheDataSource = InMemoryOfflineTelemetryCacheRepository()
) {
    companion object {
        fun createDefault(context: Context): AgroSightDependencies {
            val appContext = context.applicationContext
            val dbService = DatabaseService(appContext)
            val meteo = OpenMeteoClient()
            val sat = OpenSourceSatelliteClient()
            val gemini = GeminiAgroService()
            val worker = AgroSightCloudWorker(
                databaseService = dbService,
                geminiAgroService = gemini
            )
            val firestoreRepo = AgroSightFirestoreRepository(appContext)
            val roomDb = AgroSightRoomDatabase.getInstance(appContext)
            val roomDao = roomDb.sqlFieldAuditDao()
            val offlineCacheRepo = RoomOfflineTelemetryCacheRepository(roomDb.offlineTelemetryCacheDao())
            val rasterBackendRepo = RetrofitSatelliteRasterBackendRepository()
            return AgroSightDependencies(
                databaseService = dbService,
                openMeteoClient = meteo,
                openSourceSatelliteClient = sat,
                geminiAgroService = gemini,
                cloudWorker = worker,
                firestoreRepository = firestoreRepo,
                sqlAuditDao = roomDao,
                satelliteRasterBackendRepository = rasterBackendRepo,
                offlineTelemetryCacheRepository = offlineCacheRepo
            )
        }
    }
}

/**
 * Encapsulates deterministic agronomic, hydrological, and spectral calculations
 * (FAO-56 Penman-Monteith, FAO-29 Salinity Leaching, What-If Simulator, and Sentinel-2 Epidemiology)
 * separated from ViewModel coroutine & UI state management.
 */
data class DerivedAgronomicSnapshot(
    val growthEvaluation: GrowthStageEvaluation,
    val soilDiagnostics: SoilChemistryDiagnostics,
    val valveSectors: List<ValveSectorTelemetry>,
    val weeklySchedule: List<WeeklyIrrigationDayItem>,
    val whatIfResult: WhatIfSimulationResult,
    val epidemiologyReport: EpidemiologyDiagnosisReport,
    val formulaAudit: ScientificFormulaCalculationAudit,
    val systemAnalytics: SystemWideAnalyticsSummary
)

object RecalculateAgronomicDerivedStateUseCase {

    fun execute(
        fieldId: String,
        selectedCrop: CropSpec,
        daysAfterSowing: Int,
        latitude: Double,
        longitude: Double,
        areaFeddans: Float,
        soilPh: Float,
        soilEce: Float,
        waterEcw: Float,
        soilTexture: SoilTexture,
        irrigationSystem: IrrigationSystemType,
        valveFlowRateM3PerFeddanHr: Float,
        forecastDays: List<DailyMeteoRecord>,
        satelliteAndNasaTelemetry: RealSatelliteAndNasaTelemetry?,
        dayStatusOverrides: Map<Int, IrrigationDayStatus>,
        whatIfInput: WhatIfSimulatorInput,
        activeAnomalyPattern: AnomalyPattern,
        databaseService: DatabaseService
    ): DerivedAgronomicSnapshot {
        val growth = evaluateCropGrowthStage(selectedCrop, daysAfterSowing)
        val diagnostics = analyzeSoilChemistry(
            ph = soilPh,
            ece = soilEce,
            ecw = waterEcw,
            crop = selectedCrop,
            soilTexture = soilTexture
        )
        val todayRec = forecastDays.firstOrNull()
        val todayEt0 = todayRec?.et0MmDay ?: 5.6f
        val todayTempMax = todayRec?.tempMaxC ?: 32.0f
        val todayTempMin = todayRec?.tempMinC ?: 18.5f
        val todayRh = todayRec?.relativeHumidityPct ?: 68
        val todayWind = todayRec?.windSpeedKmh ?: 18.0f
        val todaySolar = satelliteAndNasaTelemetry?.nasaSolarRadiationMjM2Day
            ?: todayRec?.solarRadiationMjM2
            ?: 24.0f
        val todayPrecip = todayRec?.precipitationMm ?: 0f

        val updatedSectors = buildSatelliteValveSectors(
            totalAreaFeddans = areaFeddans,
            baseKc = growth.currentBaseKc,
            todayEt0 = todayEt0,
            soilEce = soilEce,
            leachingRequirementPct = diagnostics.leachingRequirementPercent,
            irrigationSystem = irrigationSystem,
            valveFlowRateM3PerFeddanHr = valveFlowRateM3PerFeddanHr
        )
        val updatedSchedule = buildWeeklyIrrigationSchedule(
            forecastDays = forecastDays,
            areaFeddans = areaFeddans,
            irrigationSystem = irrigationSystem,
            leachingRequirementPct = diagnostics.leachingRequirementPercent,
            soilPh = soilPh,
            valveFlowRateM3PerFeddanHr = valveFlowRateM3PerFeddanHr,
            statusOverrides = dayStatusOverrides
        )
        val updatedWhatIf = evaluateWhatIfSimulation(
            baseTodayEt0 = todayEt0,
            baseKc = growth.currentBaseKc,
            areaFeddans = areaFeddans,
            baseEfficiencyPct = irrigationSystem.efficiencyPercent,
            baseSoilPh = soilPh,
            soilEce = soilEce,
            waterEcw = waterEcw,
            crop = selectedCrop,
            soilTexture = soilTexture,
            valveFlowRateM3PerFeddanHr = valveFlowRateM3PerFeddanHr,
            simInput = whatIfInput
        )
        val updatedEpiReport = SatelliteEpidemiologyEngine.evaluateFieldEpidemiology(
            fieldId = fieldId,
            cropNameAr = selectedCrop.nameAr,
            areaFeddans = areaFeddans,
            soilPh = soilPh,
            soilEc = soilEce,
            tempMaxC = todayTempMax,
            relativeHumidityPct = todayRh,
            windSpeedKmh = todayWind,
            selectedPattern = activeAnomalyPattern
        )
        val avgNdvi = if (updatedSectors.isNotEmpty()) {
            updatedSectors.map { it.ndvi }.average().toFloat()
        } else {
            updatedEpiReport.ndviMean
        }
        val avgNdwi = if (updatedSectors.isNotEmpty()) {
            updatedSectors.map { it.ndwi }.average().toFloat()
        } else {
            updatedEpiReport.ndmiMean
        }

        val updatedFormulaAudit = OpenSourceSatelliteClient.computeScientificFormulaAudit(
            crop = selectedCrop,
            latitude = latitude,
            longitude = longitude,
            areaFeddans = areaFeddans,
            tabularBaseKc = growth.currentBaseKc,
            tempMaxC = todayTempMax,
            tempMinC = todayTempMin,
            relativeHumidityPct = todayRh,
            windSpeed10mKmh = todayWind,
            solarRadiationMjM2 = todaySolar,
            precipitationMm = todayPrecip,
            openMeteoEt0MmDay = todayEt0,
            meanNdvi = avgNdvi,
            meanNdre = updatedEpiReport.ndreMean,
            meanNdwi = avgNdwi,
            soilPh = soilPh,
            soilEceDsM = soilEce,
            waterEcwDsM = waterEcw,
            soilTexture = soilTexture,
            irrigationSystem = irrigationSystem,
            valveFlowRateM3PerFeddanHr = valveFlowRateM3PerFeddanHr
        )

        return DerivedAgronomicSnapshot(
            growthEvaluation = growth,
            soilDiagnostics = diagnostics,
            valveSectors = updatedSectors,
            weeklySchedule = updatedSchedule,
            whatIfResult = updatedWhatIf,
            epidemiologyReport = updatedEpiReport,
            formulaAudit = updatedFormulaAudit,
            systemAnalytics = databaseService.computeSystemWideAnalytics()
        )
    }
}
