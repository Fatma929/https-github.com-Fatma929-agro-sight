@file:SuppressLint("NewApi")

package com.example.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgroAiCopilotDataSource
import com.example.data.AgroSightCloudWorker
import com.example.data.AgroSightDependencies
import com.example.data.AgroSightFirestoreRepository
import com.example.data.AgroSightRoomDatabase
import com.example.data.AnomalyPattern
import com.example.data.AuthLoginChannel
import com.example.data.CachedSatelliteAnalysisEntity
import com.example.data.CachedWeatherForecastEntity
import com.example.data.CloudChatLog
import com.example.data.CloudWorkspaceRepository
import com.example.data.DailyDispatchBatchSummary
import com.example.data.DatabaseService
import com.example.data.MeteoForecastDataSource
import com.example.data.OfflineTelemetryCacheDataSource
import com.example.data.OfflineTelemetryCacheSerializer
import com.example.data.RecalculateAgronomicDerivedStateUseCase
import com.example.data.SatelliteTelemetryDataSource
import com.example.data.EpidemiologyDiagnosisReport
import com.example.data.FarmerCloudWorkspace
import com.example.data.FarmerEntity
import com.example.data.GeminiAgroService
import com.example.data.GeminiAgronomyChatMessage
import com.example.data.GeoreferencedRasterBackendResult
import com.example.data.GroundingCitation
import com.example.data.IrrigationAndTreatmentLogEntity
import com.example.data.OpenMeteoClient
import com.example.data.OpenSourceSatelliteClient
import com.example.data.RealSatelliteAndNasaTelemetry
import com.example.data.RelationalDatabaseSnapshot
import com.example.data.SatelliteEpidemiologyEngine
import com.example.data.SatelliteRasterBackendRepository
import com.example.data.ScientificFormulaCalculationAudit
import com.example.data.SpectralRadarLayer
import com.example.data.SqlFieldAuditEntity
import com.example.data.TelemetryDataMode
import com.example.data.WhatsAppAlertCategory
import com.example.data.WhatsAppDirection
import com.example.data.WhatsAppMessageEntity
import com.example.data.buildFarmBoundaryGeoJsonPolygon
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import com.example.model.AccountStatus
import com.example.model.AgroClimaticAlert
import com.example.model.AppScreen
import com.example.model.CropSpec
import com.example.model.DailyMeteoRecord
import com.example.model.EGYPTIAN_ZONE_PRESETS
import com.example.model.EgyptianZonePreset
import com.example.model.GrowthStageEvaluation
import com.example.model.IrrigationDayStatus
import com.example.model.IrrigationSystemType
import com.example.model.SUPPORTED_CROPS
import com.example.model.SatelliteLayer
import com.example.model.SoilChemistryDiagnostics
import com.example.model.SoilTexture
import com.example.model.SystemWideAnalyticsSummary
import com.example.model.UserRole
import com.example.model.ValveSectorTelemetry
import com.example.model.WeeklyIrrigationDayItem
import com.example.model.WhatIfSimulationResult
import com.example.model.WhatIfSimulatorInput
import com.example.model.analyzeSoilChemistry
import com.example.model.buildSatelliteValveSectors
import com.example.model.buildWeeklyIrrigationSchedule
import com.example.model.evaluateCropGrowthStage
import com.example.model.evaluateWhatIfSimulation
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.model.formatHoursMinutesClean
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class AgroSightUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    // Multi-Tenant Relational DB Snapshot & Auth Modals
    val dbSnapshot: RelationalDatabaseSnapshot,
    val activeFarmer: FarmerEntity,
    val isAuthModalVisible: Boolean = false,
    val authFeedbackMessageAr: String? = null,
    val authErrorMessageAr: String? = null,
    val isDatabaseSchemaModalVisible: Boolean = false,
    // 1. Field & Location
    val fieldId: String = "field_1",
    val fieldName: String = "مزرعة الأمل النموذجية - وادي النطرون",
    val areaFeddans: Float = 60.0f,
    val selectedZoneId: String = EGYPTIAN_ZONE_PRESETS.first().id,
    val latitude: Double = EGYPTIAN_ZONE_PRESETS.first().latitude,
    val longitude: Double = EGYPTIAN_ZONE_PRESETS.first().longitude,
    // 2. Crop & Phenology
    val selectedCrop: CropSpec = SUPPORTED_CROPS.first(),
    val daysAfterSowing: Int = 68,
    val plantingDateIso: String = LocalDate.now().minusDays(68).toString(),
    val growthEvaluation: GrowthStageEvaluation = evaluateCropGrowthStage(SUPPORTED_CROPS.first(), 68),
    // 3. Soil Chemistry & Salinity
    val soilTexture: SoilTexture = SoilTexture.SANDY,
    val soilPh: Float = 7.8f,
    val soilEce: Float = 2.4f,
    val waterEcw: Float = 1.4f,
    val soilDiagnostics: SoilChemistryDiagnostics = analyzeSoilChemistry(
        ph = 7.8f,
        ece = 2.4f,
        ecw = 1.4f,
        crop = SUPPORTED_CROPS.first(),
        soilTexture = SoilTexture.SANDY
    ),
    // 4. Irrigation System
    val irrigationSystem: IrrigationSystemType = IrrigationSystemType.DRIP,
    val valveFlowRateM3PerFeddanHr: Float = 16.0f,
    // 5. Meteorology & Open-Meteo FAO-56
    val isLoadingMeteo: Boolean = false,
    val isLiveMeteoConnected: Boolean = true,
    val meteoStatusAr: String = "بث حي للأرصاد Open-Meteo FAO-56",
    val forecastDays: List<DailyMeteoRecord> = emptyList(),
    val selectedForecastDayIndex: Int = 0,
    val climaticAlerts: List<AgroClimaticAlert> = emptyList(),
    // 6. Satellite Valve Map (Manna & Sentinel-2)
    val activeSatelliteLayer: SatelliteLayer = SatelliteLayer.NDVI,
    val valveSectors: List<ValveSectorTelemetry> = emptyList(),
    val selectedValveNumber: Int = 3,
    // 7. Weekly Actionable Irrigation Timetable & Status Toggles
    val dayStatusOverrides: Map<Int, IrrigationDayStatus> = emptyMap(),
    val weeklySchedule: List<WeeklyIrrigationDayItem> = emptyList(),
    val isPrintReportModalVisible: Boolean = false,
    // 8. Interactive What-If Simulator
    val whatIfInput: WhatIfSimulatorInput = WhatIfSimulatorInput(
        simEfficiencyPercent = 90,
        simSoilPh = 6.8f,
        simTempDeltaC = 2.0f
    ),
    val whatIfResult: WhatIfSimulationResult,
    // 9. Satellite Epidemiology & Disease Radar (100% Camera-Free)
    val activeSpectralRadarLayer: SpectralRadarLayer = SpectralRadarLayer.NDRE,
    val activeAnomalyPattern: AnomalyPattern = AnomalyPattern.FOCAL_RADIAL_PATCH,
    val epidemiologyReport: EpidemiologyDiagnosisReport,
    val isGeneratingGeminiDiagnosis: Boolean = false,
    val geminiEpidemiologyAnalysisText: String = "",
    // 10. Direct WhatsApp Links (https://wa.me/) & Logged Message History & Cloud Worker
    val isSendingWhatsAppAiReply: Boolean = false,
    val lastWhatsAppBannerNotificationAr: String? = null,
    val lastDirectWaMeUrl: String = "",
    val isRunningDailyCronDispatch: Boolean = false,
    val cronProgressStatusAr: String = "",
    val lastCronDispatchSummary: DailyDispatchBatchSummary? = null,
    // 11. Open-Source Satellite Imagery (Sentinel-2 STAC + WMTS Tile + Georeferenced Raster Engine) & NASA POWER + Cited Formula Audit
    val isSyncingOpenSourceSatellite: Boolean = false,
    val isRunningGeoreferencedRasterJob: Boolean = false,
    val rasterJobStatusAr: String = "",
    val rasterJobProgressPct: Int = 0,
    val rasterBackendErrorAr: String? = null,
    val showRealOpticalSatelliteUnderlay: Boolean = true,
    val satelliteAndNasaTelemetry: RealSatelliteAndNasaTelemetry? = null,
    val rasterBackendResult: GeoreferencedRasterBackendResult? = null,
    val formulaAudit: ScientificFormulaCalculationAudit,
    // 12. Firebase Auth + Firestore Cloud Persistence & Room SQL Database
    val firebaseUserUid: String? = null,
    val firebaseUserEmail: String? = null,
    val firebaseUserDisplayName: String? = null,
    val isFirebaseSyncing: Boolean = false,
    val firebaseCloudStatusAr: String = "غير متصل بحساب Google السحابي (متاح المزامنة الفورية مع Firestore)",
    val cloudWorkspace: FarmerCloudWorkspace? = null,
    val cloudChatLogs: List<CloudChatLog> = emptyList(),
    val roomSqlAudits: List<SqlFieldAuditEntity> = emptyList(),
    val cachedWeatherForecasts: List<CachedWeatherForecastEntity> = emptyList(),
    val cachedSatelliteAnalyses: List<CachedSatelliteAnalysisEntity> = emptyList(),
    val isServingFromRoomOfflineCache: Boolean = false,
    val roomOfflineCacheStatusAr: String = "ذاكرة التخزين المؤقت المحلية (Room SQLite) جاهزة للعمل بدون إنترنت",
    val lastOfflineCacheSyncIso: String = "",
    // 13. Gemini Multi-Turn Agronomy Chatbot + Voice Conversation + Google Search & Maps Grounding
    val geminiChatMessages: List<GeminiAgronomyChatMessage> = emptyList(),
    val isGeminiChatLoading: Boolean = false,
    val useGoogleSearchGrounding: Boolean = true,
    val useGoogleMapsGrounding: Boolean = true,
    val isVoiceListening: Boolean = false,
    val isVoiceSpeakingTts: Boolean = false,
    val autoSpeakGeminiReplies: Boolean = true,
    // 14. Agro-Acoustic Soundscape / Bio-Frequency Music Generator
    val isAcousticGeneratorPlaying: Boolean = false,
    val selectedAcousticPresetId: String = "SOLAR_CHLOROPHYLL_432HZ",
    // 15. Admin Portal & Role-Based Access Control (RBAC)
    val systemAnalytics: SystemWideAnalyticsSummary,
    val adminSearchQuery: String = "",
    val adminRoleFilter: UserRole? = null
)

class AgroSightViewModel(
    application: Application,
    dependencies: AgroSightDependencies
) : AndroidViewModel(application) {

    @androidx.annotation.Keep
    constructor(application: Application) : this(
        application = application,
        dependencies = AgroSightDependencies.createDefault(application)
    )

    private val databaseService: DatabaseService = dependencies.databaseService
    private val firestoreRepository: CloudWorkspaceRepository = dependencies.firestoreRepository
    private val sqlAuditDao = dependencies.sqlAuditDao
    private val offlineTelemetryCacheRepository: OfflineTelemetryCacheDataSource = dependencies.offlineTelemetryCacheRepository
    private val openMeteoClient: MeteoForecastDataSource = dependencies.openMeteoClient
    private val openSourceSatelliteClient: SatelliteTelemetryDataSource = dependencies.openSourceSatelliteClient
    private val satelliteRasterBackendRepository: SatelliteRasterBackendRepository = dependencies.satelliteRasterBackendRepository
    private val geminiAgroService: AgroAiCopilotDataSource = dependencies.geminiAgroService
    private val cloudWorker: AgroSightCloudWorker = dependencies.cloudWorker
    private val defaultFallbackMeteo = OpenMeteoClient()
    private var fetchJob: Job? = null
    private var rasterJob: Job? = null
    private var firestoreWorkspaceJob: Job? = null
    private var firestoreChatLogsJob: Job? = null

    private val _uiState: MutableStateFlow<AgroSightUiState>
    val uiState: StateFlow<AgroSightUiState>

    init {
        val initSnap = databaseService.getSnapshot()
        val initFarmer = initSnap.farmers.find { it.id == initSnap.activeFarmerId } ?: initSnap.farmers.first()
        val initField = initSnap.fields.find { it.farmerId == initFarmer.id } ?: initSnap.fields.first()
        val initSoil = initSnap.soilProfiles.find { it.fieldId == initField.id } ?: initSnap.soilProfiles.first()
        val initScan = initSnap.satelliteSpectralScans.find { it.fieldId == initField.id } ?: initSnap.satelliteSpectralScans.first()

        val cropSpec = SUPPORTED_CROPS.find { it.nameAr == initField.cropType } ?: SUPPORTED_CROPS.first()
        val growth = evaluateCropGrowthStage(cropSpec, 68)
        val fallbackMeteo = defaultFallbackMeteo.buildFallbackForecast(
            latitude = initField.lat,
            longitude = initField.lon,
            baseKc = growth.currentBaseKc
        )
        val todayRec = fallbackMeteo.records.first()

        val initEpiReport = SatelliteEpidemiologyEngine.evaluateFieldEpidemiology(
            fieldId = initField.id,
            cropNameAr = cropSpec.nameAr,
            areaFeddans = initField.areaFeddans,
            soilPh = initSoil.soilPh,
            soilEc = initSoil.soilEc,
            tempMaxC = todayRec.tempMaxC,
            relativeHumidityPct = todayRec.relativeHumidityPct,
            windSpeedKmh = todayRec.windSpeedKmh,
            selectedPattern = initScan.anomalyPattern
        )

        val initSimInput = WhatIfSimulatorInput(
            simEfficiencyPercent = 90,
            simSoilPh = 6.8f,
            simTempDeltaC = 2.0f
        )

        val initFormulaAudit = OpenSourceSatelliteClient.computeScientificFormulaAudit(
            crop = cropSpec,
            latitude = initField.lat,
            longitude = initField.lon,
            areaFeddans = initField.areaFeddans,
            tabularBaseKc = growth.currentBaseKc,
            tempMaxC = todayRec.tempMaxC,
            tempMinC = todayRec.tempMinC,
            relativeHumidityPct = todayRec.relativeHumidityPct,
            windSpeed10mKmh = todayRec.windSpeedKmh,
            solarRadiationMjM2 = todayRec.solarRadiationMjM2,
            precipitationMm = todayRec.precipitationMm,
            openMeteoEt0MmDay = todayRec.et0MmDay,
            meanNdvi = initEpiReport.ndviMean,
            meanNdre = initEpiReport.ndreMean,
            meanNdwi = initEpiReport.ndmiMean,
            soilPh = initSoil.soilPh,
            soilEceDsM = initSoil.soilEc,
            waterEcwDsM = initSoil.waterEc,
            soilTexture = SoilTexture.SANDY,
            irrigationSystem = IrrigationSystemType.DRIP,
            valveFlowRateM3PerFeddanHr = initField.flowRateM3Hr
        )

        val initialState = AgroSightUiState(
            currentScreen = AppScreen.HOME,
            dbSnapshot = initSnap,
            activeFarmer = initFarmer,
            fieldId = initField.id,
            fieldName = initField.fieldName,
            areaFeddans = initField.areaFeddans,
            latitude = initField.lat,
            longitude = initField.lon,
            selectedCrop = cropSpec,
            growthEvaluation = growth,
            soilPh = initSoil.soilPh,
            soilEce = initSoil.soilEc,
            waterEcw = initSoil.waterEc,
            forecastDays = fallbackMeteo.records,
            climaticAlerts = fallbackMeteo.alerts,
            whatIfInput = initSimInput,
            whatIfResult = evaluateWhatIfSimulation(
                baseTodayEt0 = todayRec.et0MmDay,
                baseKc = growth.currentBaseKc,
                areaFeddans = initField.areaFeddans,
                baseEfficiencyPct = 90,
                baseSoilPh = initSoil.soilPh,
                soilEce = initSoil.soilEc,
                waterEcw = initSoil.waterEc,
                crop = cropSpec,
                soilTexture = SoilTexture.SANDY,
                valveFlowRateM3PerFeddanHr = initField.flowRateM3Hr,
                simInput = initSimInput
            ),
            activeAnomalyPattern = initScan.anomalyPattern,
            epidemiologyReport = initEpiReport,
            formulaAudit = initFormulaAudit,
            geminiChatMessages = listOf(
                GeminiAgronomyChatMessage(
                    id = "welcome_gemini_1",
                    role = "ASSISTANT",
                    channel = "SEARCH_GROUNDED",
                    text = "مرحباً بك يا ${initFarmer.fullName} في المساعد الزراعي الصوتي والتوأم الرقمي (AgroSight Copilot) 🛰️🎙️\n" +
                        "أنا متصل الآن بقراءات القمر الصناعي Sentinel-2 لحقل (${initField.fieldName}) ومحطة أرصاد Open-Meteo FAO-56 وبحث Google وخرائط Google Maps.\n" +
                        "يمكنك التحدث معي بالصوت أو الكتابة للسؤال عن جدولة الري، معادلة قلوية التربة (pH ${formatDecimal1(initSoil.soilPh)})، أو تفسير البصمة الطيفية للأمراض.",
                    timestamp = DatabaseService.currentTimeFormatted(),
                    citations = listOf(
                        GroundingCitation(
                            title = "Copernicus Sentinel-2 L2A STAC (${initField.lat}, ${initField.lon})",
                            url = "https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a",
                            sourceType = "SENTINEL_2_STAC"
                        ),
                        GroundingCitation(
                            title = "Google Maps Field Telemetry (${initFarmer.region})",
                            url = "https://www.google.com/maps/@${initField.lat},${initField.lon},16z",
                            sourceType = "GOOGLE_MAPS"
                        )
                    )
                )
            ),
            systemAnalytics = databaseService.computeSystemWideAnalytics()
        )

        _uiState = MutableStateFlow(recalculateDerivedState(initialState))
        uiState = _uiState.asStateFlow()

        observeFirebaseAuthAndCloudStreams()
        observeRoomSqlDatabaseStream()
        refreshOpenMeteoTelemetry()
    }

    fun selectScreen(screen: AppScreen) {
        _uiState.update { state ->
            if (screen == AppScreen.ADMIN_PORTAL && !state.activeFarmer.role.canAccessAdminPortal) {
                state.copy(
                    lastWhatsAppBannerNotificationAr = "⛔ صلاحية مرفوضة: بوابة الإدارة متاحة فقط للمشرفين (Admin / Super Admin)"
                )
            } else {
                state.copy(currentScreen = screen)
            }
        }
    }

    // Multi-Tenant Farmer Account Switching & Registration (Mobile Number + Gmail + National ID)
    fun switchFarmerAccount(farmerId: String) {
        val snap = databaseService.switchFarmerAccount(farmerId)
        applyTenantSnapshotToUiState(snap)
        _uiState.update {
            val safeScreen = if (it.currentScreen == AppScreen.ADMIN_PORTAL && !it.activeFarmer.role.canAccessAdminPortal) {
                AppScreen.HOME
            } else {
                it.currentScreen
            }
            it.copy(
                currentScreen = safeScreen,
                authFeedbackMessageAr = "تم التبديل إلى حساب (${it.activeFarmer.fullName})",
                authErrorMessageAr = null
            )
        }
        refreshOpenMeteoTelemetry()
    }

    fun selectFieldById(fieldId: String) {
        val targetField = _uiState.value.dbSnapshot.fields.find { it.id == fieldId } ?: return
        if (targetField.farmerId != _uiState.value.activeFarmer.id) {
            switchFarmerAccount(targetField.farmerId)
        }
    }

    fun authenticateBySingleChannel(
        channel: AuthLoginChannel,
        identifierInput: String
    ) {
        val (success, msg, snap) = databaseService.authenticateBySingleIdentifier(
            channel = channel,
            rawIdentifier = identifierInput
        )
        if (success) {
            applyTenantSnapshotToUiState(snap)
            _uiState.update {
                it.copy(
                    isAuthModalVisible = false,
                    authFeedbackMessageAr = msg,
                    authErrorMessageAr = null,
                    lastWhatsAppBannerNotificationAr = msg
                )
            }
            refreshOpenMeteoTelemetry()
        } else {
            _uiState.update {
                it.copy(
                    authErrorMessageAr = msg,
                    authFeedbackMessageAr = null
                )
            }
        }
    }

    fun registerOrLoginFarmer(
        fullName: String,
        whatsappPhone: String,
        gmailAddress: String,
        nationalId: String,
        regionName: String,
        cropNameAr: String,
        areaFeddans: Float
    ) {
        val natDigits = nationalId.filter { it.isDigit() }
        if (natDigits.isNotEmpty() && natDigits.length != 14) {
            _uiState.update {
                it.copy(
                    authErrorMessageAr = "الرقم القومي المصري يجب أن يتكون من 14 رقماً صحيحاً (${natDigits.length}/14)",
                    authFeedbackMessageAr = null
                )
            }
            return
        }
        val cleanGmail = gmailAddress.trim()
        if (cleanGmail.isNotEmpty() && !cleanGmail.contains("@")) {
            _uiState.update {
                it.copy(
                    authErrorMessageAr = "يرجى إدخال بريد Gmail صحيح يحتوي على @gmail.com",
                    authFeedbackMessageAr = null
                )
            }
            return
        }

        val snap = databaseService.loginOrRegisterByWhatsApp(
            fullName = fullName,
            whatsappPhoneInput = whatsappPhone,
            gmailInput = gmailAddress,
            nationalIdInput = nationalId,
            regionName = regionName,
            cropNameAr = cropNameAr,
            areaFeddans = areaFeddans
        )
        applyTenantSnapshotToUiState(snap)
        _uiState.update {
            it.copy(
                isAuthModalVisible = false,
                authErrorMessageAr = null,
                authFeedbackMessageAr = "تم توثيق الحساب بنجاح (موبايل + Gmail + الرقم القومي) ✅",
                lastWhatsAppBannerNotificationAr = "تم تسجيل وتوثيق حساب ${it.activeFarmer.fullName} بنجاح ✅"
            )
        }
        refreshOpenMeteoTelemetry()
    }

    fun setAuthModalVisible(visible: Boolean) {
        _uiState.update {
            it.copy(
                isAuthModalVisible = visible,
                authErrorMessageAr = if (!visible) null else it.authErrorMessageAr
            )
        }
    }

    fun setDatabaseSchemaModalVisible(visible: Boolean) {
        _uiState.update { it.copy(isDatabaseSchemaModalVisible = visible) }
    }

    fun exportSqlSupabaseSchema(): String = databaseService.exportSqlSupabaseSchema()

    private fun applyTenantSnapshotToUiState(snap: RelationalDatabaseSnapshot) {
        val farmer = snap.farmers.find { it.id == snap.activeFarmerId } ?: snap.farmers.first()
        val field = snap.fields.find { it.farmerId == farmer.id } ?: snap.fields.first()
        val soil = snap.soilProfiles.find { it.fieldId == field.id } ?: snap.soilProfiles.first()
        val scan = snap.satelliteSpectralScans.find { it.fieldId == field.id } ?: snap.satelliteSpectralScans.first()

        val cropSpec = SUPPORTED_CROPS.find { it.nameAr == field.cropType } ?: SUPPORTED_CROPS.first()
        val texture = SoilTexture.entries.find { field.irrigationType.contains(it.nameAr.take(4)) }
            ?: when {
                soil.soilTexture.contains("طين") -> SoilTexture.CLAY
                soil.soilTexture.contains("طمي") -> SoilTexture.LOAMY
                else -> SoilTexture.SANDY
            }
        val irrigSys = when {
            field.irrigationType.contains("بيفوت") -> IrrigationSystemType.CENTER_PIVOT
            field.irrigationType.contains("رشاش") -> IrrigationSystemType.SPRINKLER
            else -> IrrigationSystemType.DRIP
        }
        val das = try {
            val planted = LocalDate.parse(field.plantingDate)
            ChronoUnit.DAYS.between(planted, LocalDate.now()).toInt().coerceIn(1, cropSpec.totalCycleDays)
        } catch (_: Exception) {
            65
        }
        val zonePreset = EGYPTIAN_ZONE_PRESETS.find { it.nameAr == farmer.region }

        _uiState.update { state ->
            recalculateDerivedState(
                state.copy(
                    dbSnapshot = snap,
                    activeFarmer = farmer,
                    fieldId = field.id,
                    fieldName = field.fieldName,
                    areaFeddans = field.areaFeddans,
                    selectedZoneId = zonePreset?.id ?: "custom",
                    latitude = field.lat,
                    longitude = field.lon,
                    selectedCrop = cropSpec,
                    daysAfterSowing = das,
                    plantingDateIso = field.plantingDate,
                    soilTexture = texture,
                    soilPh = soil.soilPh,
                    soilEce = soil.soilEc,
                    waterEcw = soil.waterEc,
                    irrigationSystem = irrigSys,
                    valveFlowRateM3PerFeddanHr = field.flowRateM3Hr,
                    activeAnomalyPattern = scan.anomalyPattern,
                    geminiEpidemiologyAnalysisText = ""
                )
            )
        }
    }

    // Satellite Disease Radar Controls
    fun selectSpectralRadarLayer(layer: SpectralRadarLayer) {
        _uiState.update { it.copy(activeSpectralRadarLayer = layer) }
    }

    fun selectAnomalyPattern(pattern: AnomalyPattern) {
        _uiState.update { state ->
            val updated = recalculateDerivedState(
                state.copy(
                    activeAnomalyPattern = pattern,
                    rasterBackendResult = null,
                    geminiEpidemiologyAnalysisText = ""
                )
            )
            // Persist updated scan to relational database
            val newScanEntity = SatelliteEpidemiologyEngine.buildScanEntityFromReport(
                fieldId = updated.fieldId,
                report = updated.epidemiologyReport
            )
            val newSnap = databaseService.recordSatelliteScan(newScanEntity)
            updated.copy(dbSnapshot = newSnap)
        }
        runGeoreferencedRasterAnalysis()
    }

    fun runGeminiEpidemiologyAnalysis() {
        val snapshot = _uiState.value
        if (snapshot.isGeneratingGeminiDiagnosis) return
        _uiState.update { it.copy(isGeneratingGeminiDiagnosis = true) }
        viewModelScope.launch {
            try {
                val today = snapshot.forecastDays.firstOrNull()
                val resultText = geminiAgroService.generateEpidemiologicalInsight(
                    farmerName = snapshot.activeFarmer.fullName,
                    fieldName = snapshot.fieldName,
                    cropNameAr = snapshot.selectedCrop.nameAr,
                    report = snapshot.epidemiologyReport,
                    soilPh = snapshot.soilPh,
                    soilEc = snapshot.soilEce,
                    tempMaxC = today?.tempMaxC ?: 32f,
                    humidityPct = today?.relativeHumidityPct ?: 68,
                    et0MmDay = today?.et0MmDay ?: 5.6f
                )
                _uiState.update {
                    it.copy(
                        geminiEpidemiologyAnalysisText = resultText
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update { it.copy(isGeneratingGeminiDiagnosis = false) }
            }
        }
    }

    // Proactive WhatsApp Bot Broadcasts & Quick Actions
    fun sendProactiveDiseaseWhatsAppAlert() {
        val st = _uiState.value
        val rep = st.epidemiologyReport
        val today = st.forecastDays.firstOrNull()
        val rh = today?.relativeHumidityPct ?: 74
        val affectedSector = st.valveSectors.find { it.valveNumber == rep.affectedValveNumber } ?: st.valveSectors.firstOrNull()
        val baseHours = affectedSector?.operatingHours ?: 2.0f
        val adjustedHours = (baseHours * (1f + rep.valveAdjustmentPercent / 100f)).coerceAtLeast(0.5f)

        val alertText = when (rep.anomalyPattern) {
            AnomalyPattern.FOCAL_RADIAL_PATCH -> {
                "🛰️ *إنذار مبكر من القمر الصناعي (AgroSight) - حقل ${st.fieldName}*\n" +
                    "يا ${st.activeFarmer.fullName}، رصد القمر الصناعي Sentinel-2 اليوم تغيراً طيفياً في مؤشر الكلوروفيل (`NDRE=${formatDecimal2(rep.ndreMean)}`) وتلف الأنسجة (`PSRI=${formatDecimal2(rep.psriMean)}`) في:\n" +
                    "📍 *الموقع:* ${rep.hotspotLocationAr}.\n" +
                    "🦠 *التشخيص المرجح (بنسبة ${rep.confidenceScorePct}%):* بداية نشاط بؤرة *${rep.detectedRiskNameAr}* نتيجة وصول الرطوبة الجوية إلى $rh% مع زيادة رطوبة التربة.\n" +
                    "💧 *تعديل الري الفوري:* خفض مقنن الري في المحبس رقم ${rep.affectedValveNumber} اليوم بنسبة 25% (تشغيل ${formatDecimal1(adjustedHours)} ساعة فقط) لمنع انتشار الفطر.\n" +
                    "🛡️ *الإجراء الوقائي المستهدف:* ${rep.targetedPreventiveActionAr}"
            }

            AnomalyPattern.UNIFORM_VALVE -> {
                "🛰️ *إنذار هندسي من القمر الصناعي (AgroSight) - حقل ${st.fieldName}*\n" +
                    "يا ${st.activeFarmer.fullName}، رصد القمر الصناعي Sentinel-2 هبوطاً خطياً منتظماً في مؤشر الرطوبة (`NDMI=${formatDecimal2(rep.ndmiMean)}`) مطابقاً لحدود *المحبس رقم 2* بالكامل مع بقاء مؤشر تلف الأنسجة الفطري منخفضاً (`PSRI=${formatDecimal2(rep.psriMean)}`).\n" +
                    "🔧 *التشخيص المؤكد (${rep.confidenceScorePct}%):* ${rep.detectedRiskNameAr}.\n" +
                    "💧 *تعديل الري الفوري:* فحص الفلاتر وضغط المحبس رقم 2 وزيادة التشغيل بنسبة +20% (${formatDecimal1(adjustedHours)} ساعة) لتعويض العطش.\n" +
                    "🛡️ *تنبيه توفير التكلفة:* لا تقم برش أي مبيدات فطرية؛ السبب هيدروليكي في الشبكة."
            }

            AnomalyPattern.SOIL_PH_LOCKUP -> {
                "🛰️ *تشخيص طيفي وكيميائي (AgroSight) - حقل ${st.fieldName}*\n" +
                    "يا ${st.activeFarmer.fullName}، رصد القمر الصناعي Sentinel-2 انخفاضاً عاماً في الكلوروفيل (`NDRE=${formatDecimal2(rep.ndreMean)}`) مع رطوبة طبيعية (`NDMI=${formatDecimal2(rep.ndmiMean)}`) وقلوية تربة مرتفعة (`pH=${formatDecimal1(st.soilPh)}`).\n" +
                    "🧪 *التشخيص المؤكد (${rep.confidenceScorePct}%):* ${rep.detectedRiskNameAr}.\n" +
                    "💧 *قرار السمادة اليوم:* حقن *${formatDecimal1(rep.acidDoseLitersPerFeddan)} لتر/فدان حامض فوسفوريك 85%* + *1.5 كجم حديد مخلبي EDDHA 6%*.\n" +
                    "🛡️ *تنبيه:* لا ترش مبيدات فطرية؛ الاصفرار فسيولوجي بسبب القلوية."
            }

            AnomalyPattern.HEALTHY -> {
                "🛰️ *تقرير الفحص الطيفي المطمئن (AgroSight) - حقل ${st.fieldName}*\n" +
                    "يا ${st.activeFarmer.fullName}، جميع قطاعات المزرعة الـ 6 في حالة ممتازة (`NDVI=${formatDecimal2(rep.ndviMean)}` | `NDRE=${formatDecimal2(rep.ndreMean)}`) ولا توجد أي بؤر مرضية أو أعطال ري."
            }
        }

        val waMeUrl = AgroSightCloudWorker.buildDirectWaMeLink(st.activeFarmer.whatsappPhone, alertText)
        val msgEntity = WhatsAppMessageEntity(
            id = "wa_${System.currentTimeMillis()}",
            farmerId = st.activeFarmer.id,
            fieldId = st.fieldId,
            timestamp = DatabaseService.currentTimeFormatted(),
            direction = WhatsAppDirection.OUTBOUND_ALERT,
            alertCategory = WhatsAppAlertCategory.SATELLITE_DISEASE_WARNING,
            messageText = alertText,
            status = "WA_ME_SENT"
        )
        val snap = databaseService.appendWhatsAppMessage(msgEntity)
        _uiState.update {
            it.copy(
                dbSnapshot = snap,
                currentScreen = AppScreen.WHATSAPP_BOT,
                lastDirectWaMeUrl = waMeUrl,
                lastWhatsAppBannerNotificationAr = "تم فتح واتساب مباشرة (${st.activeFarmer.whatsappPhone}) وتوثيق الرسالة في قاعدة البيانات"
            )
        }
        launchDirectWhatsAppIntent(waMeUrl)
    }

    fun sendMorningIrrigationAndAcidWhatsAppAlert() {
        val st = _uiState.value
        val today = st.forecastDays.firstOrNull()
        val et0 = today?.et0MmDay ?: 5.6f
        val kc = today?.adjustedKc ?: st.growthEvaluation.currentBaseKc
        val avgM3 = st.valveSectors.map { it.grossWaterM3PerFeddan }.average().toFloat()
        val avgHours = st.valveSectors.map { it.operatingHours }.average().toFloat()
        val acidLiters = st.epidemiologyReport.acidDoseLitersPerFeddan
        val acidTypeAr = if (st.soilPh > 7.5f) "حامض فوسفوريك 85% (أو كبريتيك زراعي)" else "حامض نيتريك لتسليك الشبكة"

        val text = "☀️ *نشرة الري الصباحية ومعادلة التربة (AgroSight) - ${LocalDate.now()}*\n" +
            "صباح الخير يا ${st.activeFarmer.fullName} 🌿\n" +
            "بيانات حقل *${st.fieldName}* (${st.selectedCrop.nameAr} - عمر ${st.daysAfterSowing} يوم):\n" +
            "• *البخر-نتح اليوم (ET0):* `${formatDecimal2(et0)} مم/يوم` | *معامل المحصول (Kc):* `${formatDecimal2(kc)}`\n" +
            "• *المقنن المائي اليومي:* `${formatDecimal1(avgM3)} م³/فدان` (إجمالي المزرعة: *${(avgM3 * st.areaFeddans).roundToInt()} م³*)\n" +
            "• *زمن تشغيل المحبس القياسي:* *${formatHoursMinutesClean(avgHours)}*\n" +
            "🧪 *وصفة معادلة قلوية التربة (`pH = ${formatDecimal1(st.soilPh)}`):*\n" +
            "احقن اليوم *${formatDecimal1(acidLiters)} لتر/فدان* من ($acidTypeAr) في آخر 25 دقيقة من الري لخفض رقم الحموضة حول الجذور وتيسير الفوسفور والحديد."

        val waMeUrl = AgroSightCloudWorker.buildDirectWaMeLink(st.activeFarmer.whatsappPhone, text)
        val msgEntity = WhatsAppMessageEntity(
            id = "wa_${System.currentTimeMillis()}",
            farmerId = st.activeFarmer.id,
            fieldId = st.fieldId,
            timestamp = DatabaseService.currentTimeFormatted(),
            direction = WhatsAppDirection.OUTBOUND_ALERT,
            alertCategory = WhatsAppAlertCategory.IRRIGATION_DAILY,
            messageText = text,
            status = "WA_ME_SENT"
        )
        val snap = databaseService.appendWhatsAppMessage(msgEntity)
        _uiState.update {
            it.copy(
                dbSnapshot = snap,
                currentScreen = AppScreen.WHATSAPP_BOT,
                lastDirectWaMeUrl = waMeUrl,
                lastWhatsAppBannerNotificationAr = "تم فتح واتساب مباشرة (${st.activeFarmer.whatsappPhone}) وتوثيق جدول الري في قاعدة البيانات"
            )
        }
        launchDirectWhatsAppIntent(waMeUrl)
    }

    fun openDirectWhatsAppWaMeLink(
        phone: String,
        messageText: String,
        logNewRecord: Boolean = false
    ) {
        val waMeUrl = AgroSightCloudWorker.buildDirectWaMeLink(phone, messageText)
        if (logNewRecord) {
            val st = _uiState.value
            val msgEntity = WhatsAppMessageEntity(
                id = "wa_${System.currentTimeMillis()}",
                farmerId = st.activeFarmer.id,
                fieldId = st.fieldId,
                timestamp = DatabaseService.currentTimeFormatted(),
                direction = WhatsAppDirection.OUTBOUND_ALERT,
                alertCategory = WhatsAppAlertCategory.IRRIGATION_DAILY,
                messageText = messageText,
                status = "WA_ME_SENT"
            )
            val snap = databaseService.appendWhatsAppMessage(msgEntity)
            _uiState.update {
                it.copy(
                    dbSnapshot = snap,
                    lastDirectWaMeUrl = waMeUrl,
                    lastWhatsAppBannerNotificationAr = "تم فتح رابط واتساب المباشر (wa.me) وتوثيق الرسالة في السجل"
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    lastDirectWaMeUrl = waMeUrl,
                    lastWhatsAppBannerNotificationAr = "تم فتح رابط واتساب المباشر (wa.me) لرقم $phone"
                )
            }
        }
        launchDirectWhatsAppIntent(waMeUrl)
    }

    private fun launchDirectWhatsAppIntent(waMeUrl: String) {
        try {
            val uri = waMeUrl.toUri()
            // Security Guard: Only allow HTTPS wa.me outbound links
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals("wa.me", ignoreCase = true)
            ) {
                return
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().applicationContext.startActivity(intent)
        } catch (_: Exception) {
            // Safe no-op if running in headless JVM unit test or device without browser handler
        }
    }

    fun confirmValveIrrigationAdjustmentFromWhatsApp() {
        val st = _uiState.value
        val rep = st.epidemiologyReport
        val today = st.forecastDays.firstOrNull()
        val affectedSector = st.valveSectors.find { it.valveNumber == rep.affectedValveNumber } ?: st.valveSectors.firstOrNull()
        val baseM3 = affectedSector?.grossWaterM3PerFeddan ?: 28f
        val adjFactor = 1f + (rep.valveAdjustmentPercent / 100f)
        val newM3 = (baseM3 * adjFactor).coerceAtLeast(8f)
        val newMinutes = (((affectedSector?.operatingHours ?: 2f) * adjFactor) * 60f).roundToInt().coerceAtLeast(25)

        val farmerReply = WhatsAppMessageEntity(
            id = "wa_in_${System.currentTimeMillis()}",
            farmerId = st.activeFarmer.id,
            fieldId = st.fieldId,
            timestamp = DatabaseService.currentTimeFormatted(),
            direction = WhatsAppDirection.INBOUND_FARMER,
            alertCategory = WhatsAppAlertCategory.SATELLITE_DISEASE_WARNING,
            messageText = "✅ تأكيد تعديل الري للمحبس المصاب (محبس ${rep.affectedValveNumber})",
            status = "SENT"
        )
        databaseService.appendWhatsAppMessage(farmerReply)

        val newLog = IrrigationAndTreatmentLogEntity(
            id = "log_${System.currentTimeMillis()}",
            fieldId = st.fieldId,
            date = LocalDate.now().toString(),
            et0 = today?.et0MmDay ?: 5.6f,
            kc = today?.adjustedKc ?: st.growthEvaluation.currentBaseKc,
            waterM3PerFeddan = newM3,
            valveRunMinutes = newMinutes,
            acidDoseLiters = rep.acidDoseLitersPerFeddan,
            preventiveSprayAction = rep.targetedPreventiveActionAr,
            status = "CONFIRMED_VIA_WHATSAPP"
        )
        databaseService.appendIrrigationTreatmentLog(newLog)

        val confirmText = "✅ *تم اعتماد التعديل وتسجيله في سجل العمليات (AgroSight DB):*\n" +
            "• *المحبس المعدل:* محبس رقم ${rep.affectedValveNumber}\n" +
            "• *المقنن الجديد:* `${formatDecimal1(newM3)} م³/فدان` (زمن التشغيل: *$newMinutes دقيقة*)\n" +
            "• *جرعة الحامض المدمجة:* `${formatDecimal1(rep.acidDoseLitersPerFeddan)} لتر/فدان`\n" +
            "• *الإجراء الوقائي المسجل:* ${rep.targetedPreventiveActionAr}"

        val waMeUrl = AgroSightCloudWorker.buildDirectWaMeLink(st.activeFarmer.whatsappPhone, confirmText)
        val botConfirm = WhatsAppMessageEntity(
            id = "wa_out_${System.currentTimeMillis() + 1}",
            farmerId = st.activeFarmer.id,
            fieldId = st.fieldId,
            timestamp = DatabaseService.currentTimeFormatted(),
            direction = WhatsAppDirection.OUTBOUND_ALERT,
            alertCategory = WhatsAppAlertCategory.SATELLITE_DISEASE_WARNING,
            messageText = confirmText,
            status = "WA_ME_LOGGED"
        )
        val snap = databaseService.appendWhatsAppMessage(botConfirm)
        _uiState.update {
            it.copy(
                dbSnapshot = snap,
                lastDirectWaMeUrl = waMeUrl
            )
        }
    }

    fun openHotspotThermalMapFromWhatsApp() {
        _uiState.update { state ->
            state.copy(
                currentScreen = AppScreen.DISEASE_RADAR,
                activeSpectralRadarLayer = SpectralRadarLayer.PSRI
            )
        }
    }

    fun requestDailyIrrigationAndSoilReportInWhatsApp() {
        val st = _uiState.value
        val farmerReq = WhatsAppMessageEntity(
            id = "wa_in_${System.currentTimeMillis()}",
            farmerId = st.activeFarmer.id,
            fieldId = st.fieldId,
            timestamp = DatabaseService.currentTimeFormatted(),
            direction = WhatsAppDirection.INBOUND_FARMER,
            alertCategory = WhatsAppAlertCategory.IRRIGATION_DAILY,
            messageText = "📊 طلب تقرير الري والتربة اليومي",
            status = "SENT"
        )
        databaseService.appendWhatsAppMessage(farmerReq)
        sendMorningIrrigationAndAcidWhatsAppAlert()
    }

    fun sendFarmerWhatsAppQuestion(questionText: String) {
        val cleanQ = questionText.trim()
        if (cleanQ.isBlank() || _uiState.value.isSendingWhatsAppAiReply) return

        val st = _uiState.value
        val inboundMsg = WhatsAppMessageEntity(
            id = "wa_in_${System.currentTimeMillis()}",
            farmerId = st.activeFarmer.id,
            fieldId = st.fieldId,
            timestamp = DatabaseService.currentTimeFormatted(),
            direction = WhatsAppDirection.INBOUND_FARMER,
            alertCategory = WhatsAppAlertCategory.IRRIGATION_DAILY,
            messageText = cleanQ,
            status = "SENT"
        )
        val snapAfterInbound = databaseService.appendWhatsAppMessage(inboundMsg)
        _uiState.update {
            it.copy(
                dbSnapshot = snapAfterInbound,
                isSendingWhatsAppAiReply = true
            )
        }

        viewModelScope.launch {
            try {
                val current = _uiState.value
                val today = current.forecastDays.firstOrNull()
                val avgM3 = current.valveSectors.map { it.grossWaterM3PerFeddan }.average().toFloat()
                val avgHours = current.valveSectors.map { it.operatingHours }.average().toFloat()

                val aiReplyText = geminiAgroService.replyToFarmerWhatsAppMessage(
                    farmerQuestion = cleanQ,
                    farmerName = current.activeFarmer.fullName,
                    fieldName = current.fieldName,
                    cropNameAr = current.selectedCrop.nameAr,
                    areaFeddans = current.areaFeddans,
                    soilPh = current.soilPh,
                    soilEc = current.soilEce,
                    et0MmDay = today?.et0MmDay ?: 5.6f,
                    kcValue = today?.adjustedKc ?: current.growthEvaluation.currentBaseKc,
                    dailyM3PerFeddan = avgM3,
                    valveRunHours = avgHours,
                    report = current.epidemiologyReport
                )

                val waMeUrl = AgroSightCloudWorker.buildDirectWaMeLink(current.activeFarmer.whatsappPhone, aiReplyText)
                val outboundReply = WhatsAppMessageEntity(
                    id = "wa_out_${System.currentTimeMillis()}",
                    farmerId = current.activeFarmer.id,
                    fieldId = current.fieldId,
                    timestamp = DatabaseService.currentTimeFormatted(),
                    direction = WhatsAppDirection.OUTBOUND_ALERT,
                    alertCategory = WhatsAppAlertCategory.SATELLITE_DISEASE_WARNING,
                    messageText = aiReplyText,
                    status = "WA_ME_LOGGED"
                )
                val updatedSnap = databaseService.appendWhatsAppMessage(outboundReply)
                _uiState.update {
                    it.copy(
                        dbSnapshot = updatedSnap,
                        lastDirectWaMeUrl = waMeUrl
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update { it.copy(isSendingWhatsAppAiReply = false) }
            }
        }
    }

    fun exportEdgeFunctionCode(): String = AgroSightCloudWorker.SUPABASE_EDGE_FUNCTION_TS_CODE

    fun triggerDailyMultiTenantCronDispatch() {
        if (_uiState.value.isRunningDailyCronDispatch) return
        _uiState.update {
            it.copy(
                isRunningDailyCronDispatch = true,
                cronProgressStatusAr = "بدء المسح الصباحي الآلي (POST /daily-dispatch) لجميع الحقول..."
            )
        }
        viewModelScope.launch {
            try {
                val summary = cloudWorker.executeDailyMultiTenantDispatch { stepMsg ->
                    _uiState.update { it.copy(cronProgressStatusAr = stepMsg) }
                }
                val latestSnap = databaseService.getSnapshot()
                _uiState.update {
                    it.copy(
                        dbSnapshot = latestSnap,
                        cronProgressStatusAr = "اكتمل المسح الصباحي الآلي وإرسال تقارير الواتساب لـ ${summary.totalFieldsProcessed} حقول بنجاح ✅",
                        lastCronDispatchSummary = summary,
                        lastWhatsAppBannerNotificationAr = "تم تنفيذ Cron Job الصباحي وإرسال ${summary.totalFieldsProcessed} تقارير واتساب"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update { it.copy(isRunningDailyCronDispatch = false) }
            }
        }
    }

    // Existing Farm & Soil Studio Controls (auto-synced to DatabaseService)
    fun updateFieldName(name: String) {
        _uiState.update { state ->
            val next = state.copy(fieldName = name)
            syncActiveFieldToDb(next)
        }
    }

    fun updateAreaFeddans(areaText: String) {
        val parsed = areaText.toFloatOrNull()?.coerceIn(1f, 5000f) ?: return
        _uiState.update { state ->
            val next = recalculateDerivedState(state.copy(areaFeddans = parsed))
            syncActiveFieldToDb(next)
        }
    }

    fun applyEgyptianZonePreset(preset: EgyptianZonePreset) {
        _uiState.update { state ->
            val next = recalculateDerivedState(
                state.copy(
                    selectedZoneId = preset.id,
                    latitude = preset.latitude,
                    longitude = preset.longitude,
                    soilTexture = preset.defaultSoilTexture,
                    soilPh = preset.defaultPh,
                    soilEce = preset.defaultEce,
                    waterEcw = preset.defaultEcw
                )
            )
            syncActiveFieldToDb(next)
        }
        refreshOpenMeteoTelemetry()
    }

    fun updateCoordinates(latText: String, lonText: String) {
        val lat = latText.toDoubleOrNull()?.coerceIn(20.0, 33.0) ?: _uiState.value.latitude
        val lon = lonText.toDoubleOrNull()?.coerceIn(24.0, 37.0) ?: _uiState.value.longitude
        _uiState.update { state ->
            val next = state.copy(latitude = lat, longitude = lon, selectedZoneId = "custom")
            syncActiveFieldToDb(next)
        }
        refreshOpenMeteoTelemetry()
    }

    fun selectCrop(crop: CropSpec) {
        _uiState.update { state ->
            val clampedDas = state.daysAfterSowing.coerceIn(1, crop.totalCycleDays)
            val newPlantingIso = LocalDate.now().minusDays(clampedDas.toLong()).toString()
            val next = recalculateDerivedState(
                state.copy(
                    selectedCrop = crop,
                    daysAfterSowing = clampedDas,
                    plantingDateIso = newPlantingIso
                )
            )
            syncActiveFieldToDb(next)
        }
        refreshOpenMeteoTelemetry()
    }

    fun updateDaysAfterSowing(das: Int) {
        _uiState.update { state ->
            val clamped = das.coerceIn(1, state.selectedCrop.totalCycleDays)
            val plantingIso = LocalDate.now().minusDays(clamped.toLong()).toString()
            val next = recalculateDerivedState(
                state.copy(
                    daysAfterSowing = clamped,
                    plantingDateIso = plantingIso
                )
            )
            syncActiveFieldToDb(next)
        }
        refreshOpenMeteoTelemetry()
    }

    fun selectSoilTexture(texture: SoilTexture) {
        _uiState.update { state ->
            val next = recalculateDerivedState(state.copy(soilTexture = texture))
            syncActiveFieldToDb(next)
        }
    }

    fun updateSoilPh(ph: Float) {
        val rounded = ((ph * 10f).toInt() / 10f).coerceIn(4.0f, 9.5f)
        _uiState.update { state ->
            val next = recalculateDerivedState(state.copy(soilPh = rounded))
            syncActiveFieldToDb(next)
        }
    }

    fun updateSoilSalinityEce(ece: Float) {
        val rounded = ((ece * 10f).toInt() / 10f).coerceIn(0.4f, 12.0f)
        _uiState.update { state ->
            val next = recalculateDerivedState(state.copy(soilEce = rounded))
            syncActiveFieldToDb(next)
        }
    }

    fun updateWaterSalinityEcw(ecw: Float) {
        val rounded = ((ecw * 10f).toInt() / 10f).coerceIn(0.3f, 8.0f)
        _uiState.update { state ->
            val next = recalculateDerivedState(state.copy(waterEcw = rounded))
            syncActiveFieldToDb(next)
        }
    }

    fun selectIrrigationSystem(system: IrrigationSystemType) {
        _uiState.update { state ->
            val next = recalculateDerivedState(
                state.copy(
                    irrigationSystem = system,
                    valveFlowRateM3PerFeddanHr = system.defaultFlowRateM3PerFeddanHr
                )
            )
            syncActiveFieldToDb(next)
        }
    }

    fun updateValveFlowRate(flowRate: Float) {
        val clamped = flowRate.coerceIn(5.0f, 60.0f)
        _uiState.update { state ->
            val next = recalculateDerivedState(state.copy(valveFlowRateM3PerFeddanHr = clamped))
            syncActiveFieldToDb(next)
        }
    }

    fun selectForecastDay(index: Int) {
        _uiState.update { state ->
            state.copy(selectedForecastDayIndex = index.coerceIn(0, (state.forecastDays.size - 1).coerceAtLeast(0)))
        }
    }

    fun selectSatelliteLayer(layer: SatelliteLayer) {
        _uiState.update { it.copy(activeSatelliteLayer = layer) }
    }

    fun selectValveSector(valveNumber: Int) {
        _uiState.update { it.copy(selectedValveNumber = valveNumber.coerceIn(1, 6)) }
    }

    fun updateDayIrrigationStatus(dayIndex: Int, newStatus: IrrigationDayStatus) {
        _uiState.update { state ->
            val updatedMap = state.dayStatusOverrides.toMutableMap().apply {
                put(dayIndex, newStatus)
            }
            recalculateDerivedState(state.copy(dayStatusOverrides = updatedMap))
        }
    }

    fun updateWhatIfEfficiency(efficiencyPct: Int) {
        _uiState.update { state ->
            val newSim = state.whatIfInput.copy(simEfficiencyPercent = efficiencyPct.coerceIn(60, 98))
            recalculateDerivedState(state.copy(whatIfInput = newSim))
        }
    }

    fun updateWhatIfSoilPh(ph: Float) {
        val rounded = ((ph * 10f).roundToInt() / 10f).coerceIn(4.5f, 9.2f)
        _uiState.update { state ->
            val newSim = state.whatIfInput.copy(simSoilPh = rounded)
            recalculateDerivedState(state.copy(whatIfInput = newSim))
        }
    }

    fun updateWhatIfTempDelta(deltaC: Float) {
        val rounded = ((deltaC * 2f).roundToInt() / 2f).coerceIn(-3.0f, 8.0f)
        _uiState.update { state ->
            val newSim = state.whatIfInput.copy(simTempDeltaC = rounded)
            recalculateDerivedState(state.copy(whatIfInput = newSim))
        }
    }

    fun applyWhatIfPreset(efficiencyPct: Int, targetPh: Float, tempDeltaC: Float) {
        _uiState.update { state ->
            val newSim = WhatIfSimulatorInput(
                simEfficiencyPercent = efficiencyPct.coerceIn(60, 98),
                simSoilPh = targetPh.coerceIn(4.5f, 9.2f),
                simTempDeltaC = tempDeltaC.coerceIn(-3.0f, 8.0f)
            )
            recalculateDerivedState(state.copy(whatIfInput = newSim))
        }
    }

    fun setPrintReportModalVisible(visible: Boolean) {
        _uiState.update { it.copy(isPrintReportModalVisible = visible) }
    }

    fun toggleRealOpticalSatelliteUnderlay() {
        _uiState.update { it.copy(showRealOpticalSatelliteUnderlay = !it.showRealOpticalSatelliteUnderlay) }
    }

    fun refreshOpenMeteoTelemetry() {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            val snapshot = _uiState.value
            _uiState.update {
                it.copy(
                    isLoadingMeteo = true,
                    isSyncingOpenSourceSatellite = true
                )
            }
            try {
                val result = openMeteoClient.fetch7DayAgroForecast(
                    latitude = snapshot.latitude,
                    longitude = snapshot.longitude,
                    baseKc = snapshot.growthEvaluation.currentBaseKc
                )
                val satAndNasa = openSourceSatelliteClient.fetchLiveOpenSourceSatelliteAndNasaData(
                    latitude = snapshot.latitude,
                    longitude = snapshot.longitude
                )
                val polygonGeoJson = buildFarmBoundaryGeoJsonPolygon(
                    centerLat = snapshot.latitude,
                    centerLon = snapshot.longitude,
                    areaFeddans = snapshot.areaFeddans
                )
                val stageIdx = when {
                    snapshot.daysAfterSowing < 25 -> 0
                    snapshot.daysAfterSowing < 50 -> 1
                    snapshot.daysAfterSowing < 90 -> 2
                    else -> 3
                }
                val rasterOutcome = satelliteRasterBackendRepository.runFullGeoreferencedSatelliteAnalysis(
                    farmId = snapshot.fieldId,
                    ownerUid = snapshot.firebaseUserUid ?: snapshot.activeFarmer.id,
                    cropId = snapshot.selectedCrop.id,
                    growthStageIndex = stageIdx,
                    centerLat = snapshot.latitude,
                    centerLon = snapshot.longitude,
                    areaFeddans = snapshot.areaFeddans,
                    customPolygonGeoJson = polygonGeoJson,
                    onProgressUpdate = { _, pct, badgeAr ->
                        _uiState.update {
                            it.copy(
                                rasterJobStatusAr = badgeAr,
                                rasterJobProgressPct = pct
                            )
                        }
                    }
                )
                val rasterResult = rasterOutcome.getOrNull()
                val rasterErr = rasterOutcome.exceptionOrNull()?.message

                // 1. Weather Data Room SQLite Caching & Offline Fallback
                var effectiveMeteoResult = result
                var usedRoomWeatherCache = false
                var latestCacheIso = result.lastSuccessfulSyncIso
                try {
                    val existingWeatherCache = offlineTelemetryCacheRepository.getCachedWeatherForecast(
                        fieldId = snapshot.fieldId,
                        latitude = snapshot.latitude,
                        longitude = snapshot.longitude
                    )
                    if (result.isLiveConnected) {
                        val saved = offlineTelemetryCacheRepository.cacheWeatherForecast(
                            fieldId = snapshot.fieldId,
                            fieldName = snapshot.fieldName,
                            latitude = snapshot.latitude,
                            longitude = snapshot.longitude,
                            baseKc = snapshot.growthEvaluation.currentBaseKc,
                            meteoResult = result
                        )
                        latestCacheIso = saved.cachedAtIso
                    } else if (existingWeatherCache != null) {
                        val restored = OfflineTelemetryCacheSerializer.toOpenMeteoResultFromCache(existingWeatherCache)
                        if (restored.records.isNotEmpty()) {
                            effectiveMeteoResult = restored
                            usedRoomWeatherCache = true
                            latestCacheIso = existingWeatherCache.cachedAtIso
                        }
                    } else {
                        val saved = offlineTelemetryCacheRepository.cacheWeatherForecast(
                            fieldId = snapshot.fieldId,
                            fieldName = snapshot.fieldName,
                            latitude = snapshot.latitude,
                            longitude = snapshot.longitude,
                            baseKc = snapshot.growthEvaluation.currentBaseKc,
                            meteoResult = result
                        )
                        latestCacheIso = saved.cachedAtIso
                    }
                } catch (_: Exception) {
                }

                // 2. Satellite Analysis & Georeferenced Raster Room SQLite Caching & Offline Fallback
                var effectiveSatAndNasa = satAndNasa
                var effectiveRasterResult = rasterResult
                var usedRoomSatelliteCache = false
                val isLiveSat = satAndNasa.dataMode == TelemetryDataMode.LIVE || rasterResult != null
                try {
                    val existingSatCache = offlineTelemetryCacheRepository.getCachedSatelliteAnalysis(
                        fieldId = snapshot.fieldId,
                        latitude = snapshot.latitude,
                        longitude = snapshot.longitude
                    )
                    if (isLiveSat) {
                        val savedSat = offlineTelemetryCacheRepository.cacheSatelliteAnalysis(
                            fieldId = snapshot.fieldId,
                            fieldName = snapshot.fieldName,
                            cropNameAr = snapshot.selectedCrop.nameAr,
                            latitude = snapshot.latitude,
                            longitude = snapshot.longitude,
                            areaFeddans = snapshot.areaFeddans,
                            telemetry = satAndNasa,
                            epidemiologyReport = snapshot.epidemiologyReport,
                            rasterBackendResult = rasterResult
                        )
                        latestCacheIso = savedSat.cachedAtIso
                    } else if (existingSatCache != null) {
                        effectiveSatAndNasa = OfflineTelemetryCacheSerializer.toSatelliteTelemetryFromCache(existingSatCache)
                        if (existingSatCache.rasterBackendResultJson.isNotBlank()) {
                            effectiveRasterResult = OfflineTelemetryCacheSerializer.deserializeRasterBackendResult(
                                existingSatCache.rasterBackendResultJson
                            )
                        }
                        usedRoomSatelliteCache = true
                        latestCacheIso = existingSatCache.cachedAtIso
                    } else {
                        val savedSat = offlineTelemetryCacheRepository.cacheSatelliteAnalysis(
                            fieldId = snapshot.fieldId,
                            fieldName = snapshot.fieldName,
                            cropNameAr = snapshot.selectedCrop.nameAr,
                            latitude = snapshot.latitude,
                            longitude = snapshot.longitude,
                            areaFeddans = snapshot.areaFeddans,
                            telemetry = satAndNasa,
                            epidemiologyReport = snapshot.epidemiologyReport,
                            rasterBackendResult = rasterResult
                        )
                        latestCacheIso = savedSat.cachedAtIso
                    }
                } catch (_: Exception) {
                }

                val isUsingRoomOfflineCache = usedRoomWeatherCache || usedRoomSatelliteCache
                val cacheStatusText = if (isUsingRoomOfflineCache) {
                    "وضع العمل بدون إنترنت (Room SQLite Cache): تم استرجاع الأرصاد وتحليل القمر الصناعي من الذاكرة المحلية ($latestCacheIso)"
                } else {
                    "تم تحديث وحفظ بيانات الأرصاد ونتائج القمر الصناعي في Room SQLite للعمل بدون إنترنت ($latestCacheIso)"
                }

                _uiState.update { current ->
                    recalculateDerivedState(
                        current.copy(
                            isLiveMeteoConnected = effectiveMeteoResult.isLiveConnected,
                            meteoStatusAr = effectiveMeteoResult.statusMessageAr,
                            forecastDays = effectiveMeteoResult.records,
                            climaticAlerts = effectiveMeteoResult.alerts,
                            satelliteAndNasaTelemetry = effectiveSatAndNasa,
                            rasterBackendResult = effectiveRasterResult,
                            rasterBackendErrorAr = if (effectiveRasterResult != null) null else rasterErr,
                            rasterJobStatusAr = effectiveRasterResult?.executionState?.badgeAr ?: (rasterErr ?: ""),
                            isServingFromRoomOfflineCache = isUsingRoomOfflineCache,
                            roomOfflineCacheStatusAr = cacheStatusText,
                            lastOfflineCacheSyncIso = latestCacheIso
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update {
                    it.copy(
                        isLoadingMeteo = false,
                        isSyncingOpenSourceSatellite = false
                    )
                }
            }
        }
    }

    fun runGeoreferencedRasterAnalysis(
        customPolygonGeoJson: String? = null,
        explicitAuthToken: String? = null,
        dateStartIso: String? = null,
        dateEndIso: String? = null
    ) {
        rasterJob?.cancel()
        rasterJob = viewModelScope.launch {
            val snapshot = _uiState.value
            _uiState.update {
                it.copy(
                    isRunningGeoreferencedRasterJob = true,
                    rasterBackendErrorAr = null,
                    rasterJobProgressPct = 5,
                    rasterJobStatusAr = "في قائمة الانتظار للمعالجة الطيفية (QUEUED)..."
                )
            }
            try {
                val polygonGeoJson = customPolygonGeoJson ?: buildFarmBoundaryGeoJsonPolygon(
                    centerLat = snapshot.latitude,
                    centerLon = snapshot.longitude,
                    areaFeddans = snapshot.areaFeddans
                )
                val stageIdx = when {
                    snapshot.daysAfterSowing < 25 -> 0
                    snapshot.daysAfterSowing < 50 -> 1
                    snapshot.daysAfterSowing < 90 -> 2
                    else -> 3
                }
                val rasterOutcome = satelliteRasterBackendRepository.runFullGeoreferencedSatelliteAnalysis(
                    farmId = snapshot.fieldId,
                    ownerUid = snapshot.firebaseUserUid ?: snapshot.activeFarmer.id,
                    cropId = snapshot.selectedCrop.id,
                    growthStageIndex = stageIdx,
                    centerLat = snapshot.latitude,
                    centerLon = snapshot.longitude,
                    areaFeddans = snapshot.areaFeddans,
                    customPolygonGeoJson = polygonGeoJson,
                    explicitAuthToken = explicitAuthToken,
                    dateStartIso = dateStartIso,
                    dateEndIso = dateEndIso,
                    onProgressUpdate = { _, pct, badgeAr ->
                        _uiState.update {
                            it.copy(
                                rasterJobStatusAr = badgeAr,
                                rasterJobProgressPct = pct
                            )
                        }
                    }
                )
                rasterOutcome.onSuccess { rasterRes ->
                    var savedIso = _uiState.value.lastOfflineCacheSyncIso
                    try {
                        val currentSt = _uiState.value
                        val telemetry = currentSt.satelliteAndNasaTelemetry
                        if (telemetry != null) {
                            val cachedEntity = offlineTelemetryCacheRepository.cacheSatelliteAnalysis(
                                fieldId = currentSt.fieldId,
                                fieldName = currentSt.fieldName,
                                cropNameAr = currentSt.selectedCrop.nameAr,
                                latitude = currentSt.latitude,
                                longitude = currentSt.longitude,
                                areaFeddans = currentSt.areaFeddans,
                                telemetry = telemetry,
                                epidemiologyReport = currentSt.epidemiologyReport,
                                rasterBackendResult = rasterRes
                            )
                            savedIso = cachedEntity.cachedAtIso
                        }
                    } catch (_: Exception) {
                    }
                    _uiState.update { current ->
                        recalculateDerivedState(
                            current.copy(
                                rasterBackendResult = rasterRes,
                                rasterBackendErrorAr = null,
                                rasterJobProgressPct = 100,
                                rasterJobStatusAr = rasterRes.executionState.badgeAr,
                                isServingFromRoomOfflineCache = false,
                                lastOfflineCacheSyncIso = savedIso,
                                roomOfflineCacheStatusAr = "تم حفظ نتيجة التحليل الطيفي المرجعي (${rasterRes.sceneId}) في Room SQLite للعمل بدون إنترنت"
                            )
                        )
                    }
                }.onFailure { err ->
                    val errMsg = err.message ?: "تعذر إكمال المعالجة الطيفية على الخادم"
                    val cachedSat = runCatching {
                        offlineTelemetryCacheRepository.getCachedSatelliteAnalysis(
                            fieldId = snapshot.fieldId,
                            latitude = snapshot.latitude,
                            longitude = snapshot.longitude
                        )
                    }.getOrNull()
                    val cachedRaster = cachedSat?.rasterBackendResultJson
                        ?.takeIf { it.isNotBlank() }
                        ?.let { OfflineTelemetryCacheSerializer.deserializeRasterBackendResult(it) }

                    _uiState.update { current ->
                        recalculateDerivedState(
                            current.copy(
                                rasterBackendResult = cachedRaster,
                                rasterBackendErrorAr = errMsg,
                                rasterJobProgressPct = if (cachedRaster != null) 100 else 0,
                                rasterJobStatusAr = if (cachedRaster != null) {
                                    "[CACHED • Room SQLite] ${cachedRaster.executionState.badgeAr}"
                                } else {
                                    errMsg
                                },
                                isServingFromRoomOfflineCache = cachedRaster != null,
                                roomOfflineCacheStatusAr = if (cachedRaster != null) {
                                    "تعذر الاتصال بالخادم — تم استعادة نتيجة القمر الصناعي من ذاكرة Room SQLite (${cachedSat.cachedAtIso})"
                                } else {
                                    current.roomOfflineCacheStatusAr
                                },
                                lastWhatsAppBannerNotificationAr = "⚠️ تنبيه خادم الأقمار الصناعية: $errMsg"
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update { it.copy(isRunningGeoreferencedRasterJob = false) }
            }
        }
    }

    private fun syncActiveFieldToDb(state: AgroSightUiState): AgroSightUiState {
        databaseService.updateActiveFieldAndSoil(
            fieldName = state.fieldName,
            lat = state.latitude,
            lon = state.longitude,
            areaFeddans = state.areaFeddans,
            cropType = state.selectedCrop.nameAr,
            plantingDate = state.plantingDateIso,
            irrigationType = state.irrigationSystem.nameAr,
            flowRateM3Hr = state.valveFlowRateM3PerFeddanHr,
            soilTexture = state.soilTexture.nameAr,
            soilPh = state.soilPh,
            soilEc = state.soilEce,
            waterEc = state.waterEcw
        )
        return state.copy(dbSnapshot = databaseService.getSnapshot())
    }

    private fun recalculateDerivedState(state: AgroSightUiState): AgroSightUiState {
        val derived = RecalculateAgronomicDerivedStateUseCase.execute(
            fieldId = state.fieldId,
            selectedCrop = state.selectedCrop,
            daysAfterSowing = state.daysAfterSowing,
            latitude = state.latitude,
            longitude = state.longitude,
            areaFeddans = state.areaFeddans,
            soilPh = state.soilPh,
            soilEce = state.soilEce,
            waterEcw = state.waterEcw,
            soilTexture = state.soilTexture,
            irrigationSystem = state.irrigationSystem,
            valveFlowRateM3PerFeddanHr = state.valveFlowRateM3PerFeddanHr,
            forecastDays = state.forecastDays,
            satelliteAndNasaTelemetry = state.satelliteAndNasaTelemetry,
            dayStatusOverrides = state.dayStatusOverrides,
            whatIfInput = state.whatIfInput,
            activeAnomalyPattern = state.activeAnomalyPattern,
            databaseService = databaseService
        )
        val rasterRes = state.rasterBackendResult
        val mergedEpiReport = if (rasterRes != null && rasterRes.farmId == state.fieldId && rasterRes.pixelGrid.isNotEmpty()) {
            derived.epidemiologyReport.copy(
                ndviMean = rasterRes.ndviMean,
                ndreMean = rasterRes.ndreMean,
                ndmiMean = rasterRes.ndmiMean,
                psriMean = rasterRes.psriMean,
                pixelGrid = rasterRes.pixelGrid,
                vegetationStressDetectionAr = rasterRes.vegetationStressDetectionAr,
                agriculturalRiskAssessmentAr = rasterRes.agriculturalRiskAssessmentAr,
                confirmedDiseaseDiagnosisStatusAr = rasterRes.confirmedDiseaseDiagnosisStatusAr,
                dataModeDesignation = if (rasterRes.isLiveGeoreferencedRaster) {
                    "LIVE_GEOREFERENCED_RASTER (EPSG:${rasterRes.crsEpsg} • ${rasterRes.spatialResolutionMeters.toInt()}m)"
                } else {
                    rasterRes.executionState.badgeAr
                }
            )
        } else {
            derived.epidemiologyReport
        }

        return state.copy(
            growthEvaluation = derived.growthEvaluation,
            soilDiagnostics = derived.soilDiagnostics,
            valveSectors = derived.valveSectors,
            weeklySchedule = derived.weeklySchedule,
            whatIfResult = derived.whatIfResult,
            epidemiologyReport = mergedEpiReport,
            formulaAudit = derived.formulaAudit,
            systemAnalytics = derived.systemAnalytics
        )
    }

    // =========================================================================
    // 15. ADMIN PORTAL & ROLE-BASED ACCESS CONTROL (RBAC) MANAGEMENT
    // =========================================================================

    fun updateAdminSearchQuery(query: String) {
        _uiState.update { it.copy(adminSearchQuery = query) }
    }

    fun selectAdminRoleFilter(role: UserRole?) {
        _uiState.update { it.copy(adminRoleFilter = role) }
    }

    fun adminChangeUserRole(targetFarmerId: String, newRole: UserRole) {
        val currentRole = _uiState.value.activeFarmer.role
        if (!currentRole.canManageUsersAndRoles) {
            _uiState.update {
                it.copy(lastWhatsAppBannerNotificationAr = "⛔ صلاحية مرفوضة: تعديل الأدوار متاح فقط لمدير النظام العام (Super Admin)")
            }
            return
        }
        val snap = databaseService.adminUpdateUserRole(targetFarmerId, newRole)
        applyTenantSnapshotToUiState(snap)
        _uiState.update {
            it.copy(
                lastWhatsAppBannerNotificationAr = "تم تحديث صلاحية المستخدم إلى (${newRole.titleAr}) وتوثيق الحدث في سجل المراجعة ✅"
            )
        }
    }

    fun adminChangeUserStatus(targetFarmerId: String, newStatus: AccountStatus) {
        val currentRole = _uiState.value.activeFarmer.role
        if (!currentRole.canAccessAdminPortal) {
            _uiState.update {
                it.copy(lastWhatsAppBannerNotificationAr = "⛔ صلاحية مرفوضة: إدارة حالة الحسابات تتطلب صلاحية Admin")
            }
            return
        }
        val snap = databaseService.adminUpdateUserStatus(targetFarmerId, newStatus)
        applyTenantSnapshotToUiState(snap)
        _uiState.update {
            it.copy(
                lastWhatsAppBannerNotificationAr = "تم تحديث حالة الحساب إلى (${newStatus.labelAr}) ✅"
            )
        }
    }

    fun adminDeleteUserAccount(targetFarmerId: String) {
        val currentRole = _uiState.value.activeFarmer.role
        if (!currentRole.canManageUsersAndRoles) {
            _uiState.update {
                it.copy(lastWhatsAppBannerNotificationAr = "⛔ صلاحية مرفوضة: حذف الحسابات متاح فقط لمدير النظام العام (Super Admin)")
            }
            return
        }
        val snap = databaseService.adminDeleteUserAccount(targetFarmerId)
        applyTenantSnapshotToUiState(snap)
        _uiState.update {
            it.copy(
                lastWhatsAppBannerNotificationAr = "تم حذف الحساب والسجلات المرتبطة به وتحديث سجل المراجعة الأمنية ✅"
            )
        }
    }

    fun adminCreateManagedUserAccount(
        fullName: String,
        whatsappPhone: String,
        gmailAddress: String,
        nationalId: String,
        regionName: String,
        cropNameAr: String,
        areaFeddans: Float,
        role: UserRole
    ) {
        val currentRole = _uiState.value.activeFarmer.role
        if (!currentRole.canManageUsersAndRoles) {
            _uiState.update {
                it.copy(
                    lastWhatsAppBannerNotificationAr = "⛔ صلاحية مرفوضة: إنشاء حسابات بصلاحيات مخصصة متاح فقط لمدير النظام العام (Super Admin)"
                )
            }
            return
        }
        val snap = databaseService.loginOrRegisterByWhatsApp(
            fullName = fullName,
            whatsappPhoneInput = whatsappPhone,
            gmailInput = gmailAddress,
            nationalIdInput = nationalId,
            regionName = regionName,
            cropNameAr = cropNameAr,
            areaFeddans = areaFeddans,
            requestedRole = role
        )
        applyTenantSnapshotToUiState(snap)
        _uiState.update {
            it.copy(
                lastWhatsAppBannerNotificationAr = "تم إنشاء حساب ($fullName) بصلاحية [${role.titleAr}] بنجاح ✅"
            )
        }
    }

    fun adminDeleteTreatmentLogRecord(logId: String) {
        if (!_uiState.value.activeFarmer.role.canEditAllFieldsAndRecords) return
        val snap = databaseService.adminDeleteTreatmentLog(logId)
        _uiState.update {
            it.copy(
                dbSnapshot = snap,
                systemAnalytics = databaseService.computeSystemWideAnalytics(),
                lastWhatsAppBannerNotificationAr = "تم حذف سجل العملية ($logId) من قاعدة البيانات"
            )
        }
    }

    fun adminDeleteWhatsAppMessageRecord(msgId: String) {
        if (!_uiState.value.activeFarmer.role.canEditAllFieldsAndRecords) return
        val snap = databaseService.adminDeleteWhatsAppMessage(msgId)
        _uiState.update {
            it.copy(
                dbSnapshot = snap,
                systemAnalytics = databaseService.computeSystemWideAnalytics(),
                lastWhatsAppBannerNotificationAr = "تم حذف سجل الرسالة ($msgId) من قاعدة البيانات"
            )
        }
    }

    fun adminEscalateActiveAccountToSuperAdmin() {
        val st = _uiState.value
        val isVerifiedOwnerEmail = st.activeFarmer.gmailAddress.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) ||
            st.firebaseUserEmail.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
        if (!isVerifiedOwnerEmail) {
            // Switch safely to the bootstrapped Owner Super Admin account instead of escalating an unverified farmer account
            val ownerAccount = st.dbSnapshot.farmers.find {
                it.gmailAddress.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
            }
            if (ownerAccount != null) {
                switchFarmerAccount(ownerAccount.id)
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.ADMIN_PORTAL,
                        lastWhatsAppBannerNotificationAr = "تم التحقق والتبديل الآمن إلى حساب المالك الأساسي (${UserRole.BOOTSTRAPPED_ADMIN_EMAIL}) 🛡️"
                    )
                }
            }
            return
        }
        val snap = databaseService.adminUpdateUserRole(st.activeFarmer.id, UserRole.SUPER_ADMIN)
        applyTenantSnapshotToUiState(snap)
        _uiState.update {
            it.copy(
                lastWhatsAppBannerNotificationAr = "تم تأكيد صلاحية مدير النظام العام (Super Admin) لحساب المالك ✅"
            )
        }
    }

    // =========================================================================
    // 12. FIREBASE AUTHENTICATION (CREDENTIAL MANAGER) + FIRESTORE + ROOM SQL
    // =========================================================================

    private fun observeFirebaseAuthAndCloudStreams() {
        viewModelScope.launch {
            firestoreRepository.observeAuthState()
                .catch { e ->
                    if (e is CancellationException) throw e
                }
                .collect { user ->
                firestoreWorkspaceJob?.cancel()
                firestoreChatLogsJob?.cancel()

                if (user == null) {
                    _uiState.update {
                        it.copy(
                            firebaseUserUid = null,
                            firebaseUserEmail = null,
                            firebaseUserDisplayName = null,
                            cloudWorkspace = null,
                            cloudChatLogs = emptyList(),
                            firebaseCloudStatusAr = "غير متصل بحساب Google السحابي (اضغط لتسجيل الدخول عبر Google)"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            firebaseUserUid = user.uid,
                            firebaseUserEmail = user.email ?: it.activeFarmer.gmailAddress,
                            firebaseUserDisplayName = user.displayName ?: it.activeFarmer.fullName,
                            firebaseCloudStatusAr = "متصل بالسحابة Firebase Firestore (${user.email ?: user.uid.take(8)}) ✅"
                        )
                    }
                    syncCurrentWorkspaceToFirestore()

                    firestoreWorkspaceJob = viewModelScope.launch {
                        firestoreRepository.observeWorkspace(user.uid)
                            .catch { e ->
                                val fsErr = generateSequence(e) { it.cause }
                                    .filterIsInstance<FirebaseFirestoreException>()
                                    .firstOrNull()
                                if (fsErr == null && e is CancellationException) throw e
                            }
                            .collect { ws ->
                                if (ws != null) {
                                    _uiState.update { it.copy(cloudWorkspace = ws) }
                                }
                            }
                    }

                    firestoreChatLogsJob = viewModelScope.launch {
                        firestoreRepository.observeCloudChatLogs(user.uid)
                            .catch { e ->
                                val fsErr = generateSequence(e) { it.cause }
                                    .filterIsInstance<FirebaseFirestoreException>()
                                    .firstOrNull()
                                if (fsErr == null && e is CancellationException) throw e
                            }
                            .collect { logs ->
                                _uiState.update { it.copy(cloudChatLogs = logs) }
                            }
                    }
                }
            }
        }
    }

    private fun observeRoomSqlDatabaseStream() {
        viewModelScope.launch {
            try {
                if (sqlAuditDao.getAuditCount() == 0) {
                    recordSnapshotToRoomSqlDatabase()
                }
            } catch (_: Exception) {
            }
            sqlAuditDao.observeAllAudits()
                .catch { e ->
                    if (e is CancellationException) throw e
                }
                .collect { audits ->
                    _uiState.update { it.copy(roomSqlAudits = audits) }
                }
        }
        viewModelScope.launch {
            offlineTelemetryCacheRepository.allCachedWeatherForecasts
                .catch { e ->
                    if (e is CancellationException) throw e
                }
                .collect { weatherList ->
                    _uiState.update { state ->
                        val latestSync = weatherList.firstOrNull()?.cachedAtIso ?: state.lastOfflineCacheSyncIso
                        state.copy(
                            cachedWeatherForecasts = weatherList,
                            lastOfflineCacheSyncIso = latestSync
                        )
                    }
                }
        }
        viewModelScope.launch {
            offlineTelemetryCacheRepository.allCachedSatelliteAnalyses
                .catch { e ->
                    if (e is CancellationException) throw e
                }
                .collect { satList ->
                    _uiState.update { state ->
                        val latestSync = satList.firstOrNull()?.cachedAtIso ?: state.lastOfflineCacheSyncIso
                        state.copy(
                            cachedSatelliteAnalyses = satList,
                            lastOfflineCacheSyncIso = latestSync
                        )
                    }
                }
        }
    }

    fun cacheCurrentTelemetryToRoomForOffline() {
        val st = _uiState.value
        viewModelScope.launch {
            try {
                val meteoRes = com.example.data.OpenMeteoResult(
                    records = st.forecastDays,
                    isLiveConnected = st.isLiveMeteoConnected,
                    statusMessageAr = st.meteoStatusAr,
                    alerts = st.climaticAlerts,
                    httpStatusCode = if (st.isLiveMeteoConnected) 200 else 0,
                    latencyMs = 1L,
                    lastSuccessfulSyncIso = st.lastOfflineCacheSyncIso.ifBlank { DatabaseService.currentTimeFormatted() }
                )
                val cachedWeather = offlineTelemetryCacheRepository.cacheWeatherForecast(
                    fieldId = st.fieldId,
                    fieldName = st.fieldName,
                    latitude = st.latitude,
                    longitude = st.longitude,
                    baseKc = st.growthEvaluation.currentBaseKc,
                    meteoResult = meteoRes
                )
                val telemetry = st.satelliteAndNasaTelemetry ?: openSourceSatelliteClient.fetchLiveOpenSourceSatelliteAndNasaData(
                    latitude = st.latitude,
                    longitude = st.longitude
                )
                val cachedSat = offlineTelemetryCacheRepository.cacheSatelliteAnalysis(
                    fieldId = st.fieldId,
                    fieldName = st.fieldName,
                    cropNameAr = st.selectedCrop.nameAr,
                    latitude = st.latitude,
                    longitude = st.longitude,
                    areaFeddans = st.areaFeddans,
                    telemetry = telemetry,
                    epidemiologyReport = st.epidemiologyReport,
                    rasterBackendResult = st.rasterBackendResult
                )
                _uiState.update {
                    it.copy(
                        lastOfflineCacheSyncIso = cachedSat.cachedAtIso,
                        roomOfflineCacheStatusAr = "تم تثبيت بيانات الأرصاد (7 أيام) وتحليل القمر الصناعي (${cachedSat.sceneId}) في Room SQLite للعمل بدون إنترنت (${cachedWeather.cachedAtIso})",
                        lastWhatsAppBannerNotificationAr = "تم حفظ نتائج القمر الصناعي والأرصاد في قاعدة بيانات Room SQLite للعمل بدون إنترنت ✅"
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun restoreTelemetryFromRoomOfflineCache() {
        val st = _uiState.value
        viewModelScope.launch {
            try {
                val cachedWeather = offlineTelemetryCacheRepository.getCachedWeatherForecast(
                    fieldId = st.fieldId,
                    latitude = st.latitude,
                    longitude = st.longitude
                )
                val cachedSat = offlineTelemetryCacheRepository.getCachedSatelliteAnalysis(
                    fieldId = st.fieldId,
                    latitude = st.latitude,
                    longitude = st.longitude
                )
                if (cachedWeather == null && cachedSat == null) {
                    _uiState.update {
                        it.copy(
                            roomOfflineCacheStatusAr = "لا توجد بيانات مخزنة مؤقتاً لهذا الحقل بعد — اضغط حفظ لتخزين القراءات الحالية",
                            lastWhatsAppBannerNotificationAr = "ℹ️ لا توجد نسخة مخزنة في Room SQLite لهذا الحقل حالياً"
                        )
                    }
                    return@launch
                }
                val restoredMeteo = cachedWeather?.let {
                    OfflineTelemetryCacheSerializer.toOpenMeteoResultFromCache(it)
                }
                val restoredSat = cachedSat?.let {
                    OfflineTelemetryCacheSerializer.toSatelliteTelemetryFromCache(it)
                }
                val restoredRaster = cachedSat?.rasterBackendResultJson
                    ?.takeIf { it.isNotBlank() }
                    ?.let { OfflineTelemetryCacheSerializer.deserializeRasterBackendResult(it) }
                val syncTime = cachedSat?.cachedAtIso ?: cachedWeather?.cachedAtIso.orEmpty()

                _uiState.update { current ->
                    recalculateDerivedState(
                        current.copy(
                            isLiveMeteoConnected = false,
                            meteoStatusAr = restoredMeteo?.statusMessageAr ?: current.meteoStatusAr,
                            forecastDays = restoredMeteo?.records?.takeIf { it.isNotEmpty() } ?: current.forecastDays,
                            climaticAlerts = restoredMeteo?.alerts?.takeIf { it.isNotEmpty() } ?: current.climaticAlerts,
                            satelliteAndNasaTelemetry = restoredSat ?: current.satelliteAndNasaTelemetry,
                            rasterBackendResult = restoredRaster ?: current.rasterBackendResult,
                            isServingFromRoomOfflineCache = true,
                            lastOfflineCacheSyncIso = syncTime,
                            roomOfflineCacheStatusAr = "تم تفعيل وضع عدم الاتصال: القراءات معروضة من ذاكرة Room SQLite المحلية ($syncTime)",
                            lastWhatsAppBannerNotificationAr = "📡 تم استرجاع تحليل القمر الصناعي والأرصاد من ذاكرة Room SQLite المحلية بنجاح"
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun clearOfflineTelemetryCache() {
        viewModelScope.launch {
            try {
                offlineTelemetryCacheRepository.clearAllOfflineCache()
                _uiState.update {
                    it.copy(
                        isServingFromRoomOfflineCache = false,
                        lastOfflineCacheSyncIso = "",
                        roomOfflineCacheStatusAr = "تم مسح ذاكرة التخزين المؤقت المحلية (Room SQLite) للأرصاد والأقمار الصناعية",
                        lastWhatsAppBannerNotificationAr = "تم مسح ذاكرة التخزين المؤقت (Room SQLite) بنجاح 🗑️"
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun recordSnapshotToRoomSqlDatabase() {
        val st = _uiState.value
        val todayEt0 = st.forecastDays.firstOrNull()?.et0MmDay ?: 5.6f
        val avgM3 = if (st.valveSectors.isNotEmpty()) {
            st.valveSectors.map { it.grossWaterM3PerFeddan }.average().toFloat()
        } else 25.0f
        val avgHours = if (st.valveSectors.isNotEmpty()) {
            st.valveSectors.map { it.operatingHours }.average().toFloat()
        } else 2.0f

        val entity = SqlFieldAuditEntity(
            id = "sql_${System.currentTimeMillis()}",
            farmerId = st.activeFarmer.id,
            farmerName = st.activeFarmer.fullName,
            whatsappPhone = st.activeFarmer.whatsappPhone,
            gmailAddress = st.activeFarmer.gmailAddress,
            nationalId = st.activeFarmer.nationalId,
            fieldName = st.fieldName,
            cropType = st.selectedCrop.nameAr,
            areaFeddans = st.areaFeddans,
            soilPh = st.soilPh,
            soilEce = st.soilEce,
            et0MmDay = todayEt0,
            grossM3PerFeddan = avgM3,
            valveRunHours = avgHours,
            ndviMean = st.epidemiologyReport.ndviMean,
            ndreMean = st.epidemiologyReport.ndreMean,
            psriMean = st.epidemiologyReport.psriMean,
            detectedRiskName = st.epidemiologyReport.detectedRiskNameAr,
            recordedAt = DatabaseService.currentTimeFormatted()
        )
        viewModelScope.launch {
            try {
                sqlAuditDao.insertAudit(entity)
                _uiState.update {
                    it.copy(lastWhatsAppBannerNotificationAr = "تم حفظ لقطة الحقل والمؤشرات في قاعدة بيانات SQL (Room SQLite) بنجاح ✅")
                }
            } catch (_: Exception) {
            }
        }
    }

    fun deleteRoomSqlAudit(id: String) {
        viewModelScope.launch {
            try {
                sqlAuditDao.deleteAuditById(id)
            } catch (_: Exception) {
            }
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        _uiState.update {
            it.copy(
                isFirebaseSyncing = true,
                firebaseCloudStatusAr = "جاري المصادقة مع Firebase عبر Google Credential Manager..."
            )
        }
        viewModelScope.launch {
            val result = firestoreRepository.signInWithGoogleIdToken(idToken)
            result.onSuccess { user ->
                _uiState.update {
                    it.copy(
                        isFirebaseSyncing = false,
                        firebaseUserUid = user.uid,
                        firebaseUserEmail = user.email,
                        firebaseUserDisplayName = user.displayName,
                        firebaseCloudStatusAr = "تم تسجيل الدخول بحساب Google (${user.email ?: user.displayName}) ومزامنة Firestore ✅",
                        lastWhatsAppBannerNotificationAr = "تم ربط حساب Google (${user.email ?: ""}) مع قاعدة بيانات Firebase Firestore بنجاح"
                    )
                }
                syncCurrentWorkspaceToFirestore()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isFirebaseSyncing = false,
                        firebaseCloudStatusAr = "تعذر تسجيل الدخول السحابي: ${err.localizedMessage ?: "خطأ في المصادقة"}"
                    )
                }
            }
        }
    }

    fun onGoogleSignInError(errorMessageAr: String) {
        _uiState.update {
            it.copy(
                isFirebaseSyncing = false,
                firebaseCloudStatusAr = errorMessageAr
            )
        }
    }

    fun signOutFirebaseCloud() {
        firestoreWorkspaceJob?.cancel()
        firestoreChatLogsJob?.cancel()
        firestoreRepository.signOut()
        _uiState.update {
            it.copy(
                firebaseUserUid = null,
                firebaseUserEmail = null,
                firebaseUserDisplayName = null,
                cloudWorkspace = null,
                cloudChatLogs = emptyList(),
                firebaseCloudStatusAr = "تم تسجيل الخروج من سحابة Firebase"
            )
        }
    }

    fun syncCurrentWorkspaceToFirestore() {
        val st = _uiState.value
        val uid = st.firebaseUserUid ?: return
        if (st.isFirebaseSyncing) return
        _uiState.update { it.copy(isFirebaseSyncing = true) }
        viewModelScope.launch {
            try {
                val res = firestoreRepository.upsertFarmerWorkspace(
                    userId = uid,
                    farmerName = st.activeFarmer.fullName,
                    whatsappPhone = st.activeFarmer.whatsappPhone,
                    gmailAddress = st.firebaseUserEmail ?: st.activeFarmer.gmailAddress,
                    nationalId = st.activeFarmer.nationalId,
                    fieldName = st.fieldName,
                    region = st.activeFarmer.region,
                    cropType = st.selectedCrop.nameAr,
                    areaFeddans = st.areaFeddans,
                    latitude = st.latitude,
                    longitude = st.longitude,
                    soilPh = st.soilPh,
                    soilEce = st.soilEce,
                    waterEcw = st.waterEcw,
                    ndviMean = st.epidemiologyReport.ndviMean,
                    ndreMean = st.epidemiologyReport.ndreMean,
                    psriMean = st.epidemiologyReport.psriMean,
                    ndmiMean = st.epidemiologyReport.ndmiMean,
                    detectedRiskName = st.epidemiologyReport.detectedRiskNameAr
                )
                _uiState.update {
                    it.copy(
                        firebaseCloudStatusAr = if (res.isSuccess) {
                            "تمت مزامنة بيانات المزرعة والتربة والأقمار الصناعية مع Firestore بنجاح ✅"
                        } else {
                            "تنبيه مزامنة Firestore: ${res.exceptionOrNull()?.localizedMessage ?: "خطأ"}"
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update { it.copy(isFirebaseSyncing = false) }
            }
        }
    }

    fun deleteCurrentCloudWorkspace(targetUserId: String? = null) {
        val st = _uiState.value
        val uidToDelete = targetUserId ?: st.firebaseUserUid ?: return
        if (st.isFirebaseSyncing) return
        _uiState.update { it.copy(isFirebaseSyncing = true) }
        viewModelScope.launch {
            try {
                val res = firestoreRepository.deleteCloudWorkspace(uidToDelete)
                _uiState.update {
                    if (res.isSuccess) {
                        it.copy(
                            cloudWorkspace = null,
                            cloudChatLogs = emptyList(),
                            firebaseCloudStatusAr = "تم حذف مساحة العمل السحابية وسجلات المحادثة بالكامل بنجاح ✅"
                        )
                    } else {
                        it.copy(
                            firebaseCloudStatusAr = "رفض حذف مساحة العمل: ${res.exceptionOrNull()?.message ?: "غير مصرح"}"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update { it.copy(isFirebaseSyncing = false) }
            }
        }
    }

    // =========================================================================
    // 13. GEMINI AGRONOMY CHATBOT + SEARCH & MAPS GROUNDING + VOICE CONVERSATION
    // =========================================================================

    fun toggleGoogleSearchGrounding() {
        _uiState.update { it.copy(useGoogleSearchGrounding = !it.useGoogleSearchGrounding) }
    }

    fun toggleGoogleMapsGrounding() {
        _uiState.update { it.copy(useGoogleMapsGrounding = !it.useGoogleMapsGrounding) }
    }

    fun toggleAutoSpeakGeminiReplies() {
        _uiState.update { it.copy(autoSpeakGeminiReplies = !it.autoSpeakGeminiReplies) }
    }

    fun setVoiceListeningState(listening: Boolean) {
        _uiState.update { it.copy(isVoiceListening = listening) }
    }

    fun setVoiceSpeakingTtsState(speaking: Boolean) {
        _uiState.update { it.copy(isVoiceSpeakingTts = speaking) }
    }

    fun clearGeminiChatHistory() {
        val st = _uiState.value
        val welcomeMsg = GeminiAgronomyChatMessage(
            id = "chat_welcome_${System.currentTimeMillis()}",
            role = "ASSISTANT",
            channel = "GEMINI_CHAT",
            text = "أهلاً بك يا ${st.activeFarmer.fullName} 🌿\nتم بدء جلسة استشارية جديدة مربوطة ببيانات حقل *${st.fieldName}* (${st.selectedCrop.nameAr} - ${st.areaFeddans.toInt()} فدان).\nاسألني عن المقنن المائي، مؤشرات القمر الصناعي Sentinel-2، أو معادلة حموضة التربة.",
            timestamp = DatabaseService.currentTimeFormatted(),
            isLiveAiGenerated = false
        )
        _uiState.update {
            it.copy(
                geminiChatMessages = listOf(welcomeMsg),
                isGeminiChatLoading = false
            )
        }
    }

    fun sendGeminiAgronomyChatMessage(
        rawMessage: String,
        isVoiceInput: Boolean = false,
        onAssistantReplyReadyForTts: ((String) -> Unit)? = null
    ) {
        val cleanText = rawMessage.trim()
        if (cleanText.isBlank() || _uiState.value.isGeminiChatLoading) return

        val st = _uiState.value
        val channel = when {
            isVoiceInput -> "VOICE_CONVERSATION"
            st.useGoogleSearchGrounding -> "SEARCH_GROUNDED"
            st.useGoogleMapsGrounding -> "MAPS_GROUNDED"
            else -> "GEMINI_CHAT"
        }

        val userMsg = GeminiAgronomyChatMessage(
            id = "chat_u_${System.currentTimeMillis()}",
            role = "USER",
            channel = channel,
            text = cleanText,
            timestamp = DatabaseService.currentTimeFormatted()
        )

        val updatedHistory = st.geminiChatMessages + userMsg
        _uiState.update {
            it.copy(
                geminiChatMessages = updatedHistory,
                isGeminiChatLoading = true
            )
        }

        val uid = st.firebaseUserUid
        if (uid != null) {
            viewModelScope.launch {
                firestoreRepository.appendCloudChatLog(
                    userId = uid,
                    role = "USER",
                    channel = channel,
                    messageText = cleanText,
                    groundingSources = "Farmer Input ($channel)"
                )
            }
        }

        viewModelScope.launch {
            try {
                val current = _uiState.value
                val today = current.forecastDays.firstOrNull()
                val avgM3 = if (current.valveSectors.isNotEmpty()) {
                    current.valveSectors.map { it.grossWaterM3PerFeddan }.average().toFloat()
                } else 25f
                val avgHours = if (current.valveSectors.isNotEmpty()) {
                    current.valveSectors.map { it.operatingHours }.average().toFloat()
                } else 2f

                val groundedResp = geminiAgroService.generateMultiTurnGroundedAgronomyReply(
                    chatHistory = current.geminiChatMessages,
                    userMessage = cleanText,
                    useGoogleSearchGrounding = current.useGoogleSearchGrounding,
                    useGoogleMapsGrounding = current.useGoogleMapsGrounding,
                    farmerName = current.activeFarmer.fullName,
                    fieldName = current.fieldName,
                    regionName = current.activeFarmer.region,
                    latitude = current.latitude,
                    longitude = current.longitude,
                    cropNameAr = current.selectedCrop.nameAr,
                    areaFeddans = current.areaFeddans,
                    soilPh = current.soilPh,
                    soilEc = current.soilEce,
                    waterEc = current.waterEcw,
                    et0MmDay = today?.et0MmDay ?: 5.6f,
                    kcValue = today?.adjustedKc ?: current.growthEvaluation.currentBaseKc,
                    dailyM3PerFeddan = avgM3,
                    valveRunHours = avgHours,
                    report = current.epidemiologyReport
                )

                val assistantMsg = GeminiAgronomyChatMessage(
                    id = "chat_a_${System.currentTimeMillis()}",
                    role = "ASSISTANT",
                    channel = channel,
                    text = groundedResp.replyText,
                    timestamp = DatabaseService.currentTimeFormatted(),
                    citations = groundedResp.citations,
                    isLiveAiGenerated = groundedResp.isLiveAiGenerated
                )

                _uiState.update {
                    it.copy(
                        geminiChatMessages = it.geminiChatMessages + assistantMsg
                    )
                }

                if (current.autoSpeakGeminiReplies || isVoiceInput) {
                    onAssistantReplyReadyForTts?.invoke(groundedResp.replyText)
                }

                val activeUid = _uiState.value.firebaseUserUid
                if (activeUid != null) {
                    firestoreRepository.appendCloudChatLog(
                        userId = activeUid,
                        role = "ASSISTANT",
                        channel = channel,
                        messageText = groundedResp.replyText,
                        groundingSources = groundedResp.citations.joinToString(" | ") { it.title }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update { it.copy(isGeminiChatLoading = false) }
            }
        }
    }

    // =========================================================================
    // 14. AGRO-ACOUSTIC SOUNDSCAPE / BIO-FREQUENCY MUSIC GENERATOR
    // =========================================================================

    fun toggleAcousticGeneratorPlayback() {
        _uiState.update { it.copy(isAcousticGeneratorPlaying = !it.isAcousticGeneratorPlaying) }
    }

    fun selectAcousticPreset(presetId: String) {
        _uiState.update { it.copy(selectedAcousticPresetId = presetId) }
    }
}
