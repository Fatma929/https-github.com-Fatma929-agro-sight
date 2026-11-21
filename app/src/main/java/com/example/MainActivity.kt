package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppScreen
import com.example.ui.components.AgroSightBottomBar
import com.example.ui.components.AgroSightDrawerContent
import com.example.ui.components.AgroSightHeader
import com.example.ui.components.ExecutiveCommandBar
import com.example.ui.screens.AdminPortalScreen
import com.example.ui.screens.AiDigitalTwinHubScreen
import com.example.ui.screens.ChatbotScreen
import com.example.ui.screens.FarmSoilStudioScreen
import com.example.ui.screens.FarmerWhatsAppAuthDialog
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.IrrigationScheduleScreen
import com.example.ui.screens.MeteoAnalyticsScreen
import com.example.ui.screens.PrintableIrrigationReportDialog
import com.example.ui.screens.RelationalDatabaseSchemaDialog
import com.example.ui.screens.SatelliteDiseaseRadarScreen
import com.example.ui.screens.SatelliteMapScreen
import com.example.ui.screens.WhatsAppBotAndDatabaseScreen
import com.example.ui.theme.AgroSightTheme
import com.example.ui.theme.ForestBgDark
import com.example.ui.theme.Slate50
import com.example.ui.theme.Slate900
import com.example.viewmodel.AgroSightViewModel
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.launch

private const val APP_CHECK_PREFS = "com.google.firebase.appcheck.debug.store"
private const val KEY_DEBUG_TOKEN =
    "com.google.firebase.appcheck.debug.irable.FD2837F7-5241-4A1E-9843-17F9E50208D0"
private const val EXTRA_DEBUG_TOKEN = "FIREBASE_APPCHECK_DEBUG_TOKEN"

@SuppressLint("ApplySharedPref", "UseKtx")
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (BuildConfig.DEBUG) {
            val token = intent?.getStringExtra(EXTRA_DEBUG_TOKEN)
            if (!token.isNullOrEmpty()) {
                // Must use .commit() (synchronous) so token is written before installAppCheckProviderFactory reads it
                context.getSharedPreferences(APP_CHECK_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DEBUG_TOKEN, token)
                    .commit()
            }
            Firebase.appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        } else {
            val siteKey = context.getString(R.string.recaptcha_site_key)
            if (siteKey.isNotBlank()) {
                Firebase.appCheck.installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
            }
        }
    } catch (_: Exception) {
        // Safe fallback in unit/Robolectric environments when FirebaseApp isn't initialized yet
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
        window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            AgroSightTheme {
                AgroSightApp()
            }
        }
    }
}

@Composable
fun AgroSightApp(
    viewModel: AgroSightViewModel = run {
        val context = androidx.compose.ui.platform.LocalContext.current
        val application = context.applicationContext as android.app.Application
        viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return AgroSightViewModel(application) as T
                }
            }
        )
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    BackHandler(
        enabled = uiState.isAuthModalVisible ||
            uiState.isDatabaseSchemaModalVisible ||
            uiState.isPrintReportModalVisible ||
            drawerState.isOpen ||
            uiState.currentScreen != AppScreen.HOME
    ) {
        when {
            uiState.isAuthModalVisible -> viewModel.setAuthModalVisible(false)
            uiState.isDatabaseSchemaModalVisible -> viewModel.setDatabaseSchemaModalVisible(false)
            uiState.isPrintReportModalVisible -> viewModel.setPrintReportModalVisible(false)
            drawerState.isOpen -> scope.launch { drawerState.close() }
            else -> viewModel.selectScreen(AppScreen.HOME)
        }
    }

    if (uiState.isAuthModalVisible) {
        FarmerWhatsAppAuthDialog(
            uiState = uiState,
            onSwitchFarmer = viewModel::switchFarmerAccount,
            onAuthenticateSingleChannel = viewModel::authenticateBySingleChannel,
            onRegisterOrLogin = viewModel::registerOrLoginFarmer,
            onDismiss = { viewModel.setAuthModalVisible(false) }
        )
    }

    if (uiState.isDatabaseSchemaModalVisible) {
        RelationalDatabaseSchemaDialog(
            uiState = uiState,
            sqlSchemaText = viewModel.exportSqlSupabaseSchema(),
            edgeFunctionCodeText = viewModel.exportEdgeFunctionCode(),
            onDismiss = { viewModel.setDatabaseSchemaModalVisible(false) }
        )
    }

    if (uiState.isPrintReportModalVisible && uiState.currentScreen != AppScreen.IRRIGATION_SCHEDULE) {
        PrintableIrrigationReportDialog(
            uiState = uiState,
            onDismiss = { viewModel.setPrintReportModalVisible(false) }
        )
    }

    val todayEt0 = uiState.forecastDays.firstOrNull()?.et0MmDay ?: 5.6f

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                drawerContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
            ) {
                AgroSightDrawerContent(
                    currentScreen = uiState.currentScreen,
                    activeFarmer = uiState.activeFarmer,
                    fieldName = uiState.fieldName,
                    areaFeddans = uiState.areaFeddans,
                    cropNameAr = uiState.selectedCrop.nameAr,
                    soilPh = uiState.soilPh,
                    todayEt0 = todayEt0,
                    onSelectScreen = { viewModel.selectScreen(it) },
                    onOpenAuthModal = { viewModel.setAuthModalVisible(true) },
                    onOpenDbSchemaModal = { viewModel.setDatabaseSchemaModalVisible(true) },
                    onOpenPrintReport = { viewModel.setPrintReportModalVisible(true) },
                    onCloseDrawer = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                AgroSightHeader(
                    currentScreen = uiState.currentScreen,
                    farmers = uiState.dbSnapshot.farmers,
                    activeFarmer = uiState.activeFarmer,
                    fieldName = uiState.fieldName,
                    areaFeddans = uiState.areaFeddans,
                    cropNameAr = uiState.selectedCrop.nameAr,
                    kcValue = uiState.growthEvaluation.currentBaseKc,
                    isLoadingMeteo = uiState.isLoadingMeteo,
                    onSwitchFarmer = viewModel::switchFarmerAccount,
                    onOpenAuthModal = { viewModel.setAuthModalVisible(true) },
                    onOpenDbSchemaModal = { viewModel.setDatabaseSchemaModalVisible(true) },
                    onOpenAdminPortal = { viewModel.selectScreen(AppScreen.ADMIN_PORTAL) },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onRefreshMeteo = { viewModel.refreshOpenMeteoTelemetry() },
                    onOpenPrintReport = { viewModel.setPrintReportModalVisible(true) },
                    onNavigateHome = { viewModel.selectScreen(AppScreen.HOME) }
                )
            },
            bottomBar = {
                AgroSightBottomBar(
                    currentScreen = uiState.currentScreen,
                    onSelectScreen = { viewModel.selectScreen(it) },
                    canAccessAdminPortal = uiState.activeFarmer.role.canAccessAdminPortal,
                    onOpenAuthModal = { viewModel.setAuthModalVisible(true) },
                    onOpenPrintModal = { viewModel.setPrintReportModalVisible(true) }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (uiState.currentScreen != AppScreen.HOME) {
                    ExecutiveCommandBar(
                        uiState = uiState,
                        onSelectScreen = viewModel::selectScreen,
                        onOpenPrintReport = { viewModel.setPrintReportModalVisible(true) },
                        onSendInstantWhatsAppReport = viewModel::sendMorningIrrigationAndAcidWhatsAppAlert
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    AnimatedContent(
                        targetState = uiState.currentScreen,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(240)) + scaleIn(initialScale = 0.985f, animationSpec = tween(240)))
                                .togetherWith(fadeOut(animationSpec = tween(180)))
                        },
                        label = "screen_transition"
                    ) { screen ->
                    when (screen) {
                        AppScreen.HOME -> {
                            HomeDashboardScreen(
                                uiState = uiState,
                                onNavigateToScreen = viewModel::selectScreen,
                                onSelectField = viewModel::selectFieldById,
                                onOpenAuthModal = { viewModel.setAuthModalVisible(true) },
                                onSendMorningWhatsAppReport = viewModel::sendMorningIrrigationAndAcidWhatsAppAlert,
                                onRefreshTelemetry = viewModel::refreshOpenMeteoTelemetry,
                                onSelectSatelliteLayer = viewModel::selectSatelliteLayer,
                                onSelectValveSector = viewModel::selectValveSector,
                                onAskGeminiAboutSector = { prompt ->
                                    viewModel.selectScreen(AppScreen.CHATBOT)
                                    viewModel.sendGeminiAgronomyChatMessage(prompt, false)
                                },
                                onCacheCurrentTelemetryToRoom = viewModel::cacheCurrentTelemetryToRoomForOffline,
                                onRestoreTelemetryFromRoomCache = viewModel::restoreTelemetryFromRoomOfflineCache,
                                onClearOfflineTelemetryCache = viewModel::clearOfflineTelemetryCache
                            )
                        }

                        AppScreen.CHATBOT -> {
                            ChatbotScreen(
                                uiState = uiState,
                                onSendMessage = { msg ->
                                    viewModel.sendGeminiAgronomyChatMessage(msg, false)
                                },
                                onToggleSearchGrounding = viewModel::toggleGoogleSearchGrounding,
                                onToggleMapsGrounding = viewModel::toggleGoogleMapsGrounding,
                                onSelectField = viewModel::selectFieldById,
                                onSelectValveSector = viewModel::selectValveSector,
                                onClearChatHistory = viewModel::clearGeminiChatHistory,
                                onNavigateToScreen = viewModel::selectScreen,
                                onOpenWhatsAppWithMessage = { phone, msg ->
                                    viewModel.openDirectWhatsAppWaMeLink(phone, msg, logNewRecord = true)
                                }
                            )
                        }

                        AppScreen.DISEASE_RADAR -> {
                            SatelliteDiseaseRadarScreen(
                                uiState = uiState,
                                onSelectSpectralLayer = viewModel::selectSpectralRadarLayer,
                                onSelectAnomalyPattern = viewModel::selectAnomalyPattern,
                                onSendProactiveWhatsAppAlert = viewModel::sendProactiveDiseaseWhatsAppAlert,
                                onRunGeminiDiagnosis = viewModel::runGeminiEpidemiologyAnalysis,
                                onToggleRealOpticalUnderlay = viewModel::toggleRealOpticalSatelliteUnderlay,
                                onRefreshOpenSourceTelemetry = viewModel::refreshOpenMeteoTelemetry
                            )
                        }

                        AppScreen.WHATSAPP_BOT -> {
                            WhatsAppBotAndDatabaseScreen(
                                uiState = uiState,
                                onSendDiseaseAlertToWhatsApp = viewModel::sendProactiveDiseaseWhatsAppAlert,
                                onSendMorningScheduleToWhatsApp = viewModel::sendMorningIrrigationAndAcidWhatsAppAlert,
                                onConfirmValveAdjustmentQuickAction = viewModel::confirmValveIrrigationAdjustmentFromWhatsApp,
                                onOpenHotspotMapQuickAction = viewModel::openHotspotThermalMapFromWhatsApp,
                                onRequestDailyReportQuickAction = viewModel::requestDailyIrrigationAndSoilReportInWhatsApp,
                                onSendFarmerChatQuestion = viewModel::sendFarmerWhatsAppQuestion,
                                onOpenAuthModal = { viewModel.setAuthModalVisible(true) },
                                onOpenDatabaseSchemaModal = { viewModel.setDatabaseSchemaModalVisible(true) },
                                onRunDailyCronDispatchAllFields = viewModel::triggerDailyMultiTenantCronDispatch,
                                onOpenDirectWaMeLink = { phone, msg ->
                                    viewModel.openDirectWhatsAppWaMeLink(phone, msg)
                                }
                            )
                        }

                        AppScreen.FARM_SOIL_STUDIO -> {
                            FarmSoilStudioScreen(
                                uiState = uiState,
                                onUpdateFieldName = viewModel::updateFieldName,
                                onUpdateAreaFeddans = viewModel::updateAreaFeddans,
                                onSelectZonePreset = viewModel::applyEgyptianZonePreset,
                                onUpdateCoordinates = viewModel::updateCoordinates,
                                onSelectCrop = viewModel::selectCrop,
                                onUpdateDaysAfterSowing = viewModel::updateDaysAfterSowing,
                                onSelectSoilTexture = viewModel::selectSoilTexture,
                                onUpdateSoilPh = viewModel::updateSoilPh,
                                onUpdateSoilEce = viewModel::updateSoilSalinityEce,
                                onUpdateWaterEcw = viewModel::updateWaterSalinityEcw,
                                onSelectIrrigationSystem = viewModel::selectIrrigationSystem,
                                onUpdateValveFlowRate = viewModel::updateValveFlowRate
                            )
                        }

                        AppScreen.METEO_ANALYTICS -> {
                            MeteoAnalyticsScreen(
                                uiState = uiState,
                                onSelectDayIndex = viewModel::selectForecastDay,
                                onRefreshMeteo = viewModel::refreshOpenMeteoTelemetry
                            )
                        }

                        AppScreen.SATELLITE_NDVI_MAP -> {
                            SatelliteMapScreen(
                                uiState = uiState,
                                onSelectLayer = viewModel::selectSatelliteLayer,
                                onSelectValve = viewModel::selectValveSector,
                                onOpenScheduleAndSimulator = {
                                    viewModel.selectScreen(AppScreen.IRRIGATION_SCHEDULE)
                                },
                                onOpenPrintReport = {
                                    viewModel.setPrintReportModalVisible(true)
                                },
                                onToggleRealOpticalUnderlay = viewModel::toggleRealOpticalSatelliteUnderlay,
                                onRefreshOpenSourceTelemetry = viewModel::refreshOpenMeteoTelemetry,
                                onAskGeminiChatbot = { prompt ->
                                    viewModel.selectScreen(AppScreen.CHATBOT)
                                    viewModel.sendGeminiAgronomyChatMessage(prompt, false)
                                },
                                onOpenWhatsAppChatbot = { prompt ->
                                    viewModel.selectScreen(AppScreen.WHATSAPP_BOT)
                                    viewModel.sendFarmerWhatsAppQuestion(prompt)
                                }
                            )
                        }

                        AppScreen.IRRIGATION_SCHEDULE -> {
                            IrrigationScheduleScreen(
                                uiState = uiState,
                                onUpdateDayStatus = viewModel::updateDayIrrigationStatus,
                                onUpdateWhatIfEfficiency = viewModel::updateWhatIfEfficiency,
                                onUpdateWhatIfSoilPh = viewModel::updateWhatIfSoilPh,
                                onUpdateWhatIfTempDelta = viewModel::updateWhatIfTempDelta,
                                onApplyWhatIfPreset = viewModel::applyWhatIfPreset,
                                onSetPrintModalVisible = viewModel::setPrintReportModalVisible
                            )
                        }

                        AppScreen.AI_DIGITAL_TWIN_HUB -> {
                            AiDigitalTwinHubScreen(
                                uiState = uiState,
                                onGoogleSignInTokenReceived = viewModel::signInWithGoogleIdToken,
                                onGoogleSignInError = viewModel::onGoogleSignInError,
                                onSignOutFirebase = viewModel::signOutFirebaseCloud,
                                onSyncWorkspaceToFirestore = viewModel::syncCurrentWorkspaceToFirestore,
                                onRecordRoomSqlSnapshot = viewModel::recordSnapshotToRoomSqlDatabase,
                                onDeleteRoomSqlAudit = viewModel::deleteRoomSqlAudit,
                                onOpenFullSqlSchemaModal = { viewModel.setDatabaseSchemaModalVisible(true) },
                                onSendGeminiChatMessage = viewModel::sendGeminiAgronomyChatMessage,
                                onToggleSearchGrounding = viewModel::toggleGoogleSearchGrounding,
                                onToggleMapsGrounding = viewModel::toggleGoogleMapsGrounding,
                                onToggleAutoSpeakTts = viewModel::toggleAutoSpeakGeminiReplies,
                                onSetVoiceListeningState = viewModel::setVoiceListeningState,
                                onSetVoiceSpeakingTtsState = viewModel::setVoiceSpeakingTtsState,
                                onSelectValveSector = viewModel::selectValveSector,
                                onSelectZonePreset = viewModel::applyEgyptianZonePreset,
                                onToggleAcousticPlayback = viewModel::toggleAcousticGeneratorPlayback,
                                onSelectAcousticPreset = viewModel::selectAcousticPreset,
                                onOpenDirectWaMeLink = { phone, msg ->
                                    viewModel.openDirectWhatsAppWaMeLink(phone, msg, logNewRecord = true)
                                }
                            )
                        }

                        AppScreen.ADMIN_PORTAL -> {
                            AdminPortalScreen(
                                uiState = uiState,
                                onUpdateSearchQuery = viewModel::updateAdminSearchQuery,
                                onSelectRoleFilter = viewModel::selectAdminRoleFilter,
                                onChangeUserRole = viewModel::adminChangeUserRole,
                                onChangeUserStatus = viewModel::adminChangeUserStatus,
                                onDeleteUserAccount = viewModel::adminDeleteUserAccount,
                                onCreateManagedUser = viewModel::adminCreateManagedUserAccount,
                                onSwitchToUserDashboard = { farmerId, targetScreen ->
                                    viewModel.switchFarmerAccount(farmerId)
                                    viewModel.selectScreen(targetScreen)
                                },
                                onDeleteTreatmentLog = viewModel::adminDeleteTreatmentLogRecord,
                                onDeleteWhatsAppMessage = viewModel::adminDeleteWhatsAppMessageRecord,
                                onDeleteRoomSqlAudit = viewModel::deleteRoomSqlAudit,
                                onTriggerDailyCronDispatchAllFields = viewModel::triggerDailyMultiTenantCronDispatch,
                                onOpenSqlSchemaModal = { viewModel.setDatabaseSchemaModalVisible(true) },
                                onEscalateCurrentAccountToSuperAdmin = viewModel::adminEscalateActiveAccountToSuperAdmin
                            )
                        }
                    }
                    }
                }
            }
        }
    }
    }
}
