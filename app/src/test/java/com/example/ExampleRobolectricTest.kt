package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AgroAiCopilotDataSource
import com.example.data.AgroSightCloudWorker
import com.example.data.AgroSightDependencies
import com.example.data.CloudChatLog
import com.example.data.CloudWorkspaceRepository
import com.example.data.DatabaseService
import com.example.data.EpidemiologyDiagnosisReport
import com.example.data.FarmerCloudWorkspace
import com.example.data.GeminiAgronomyChatMessage
import com.example.data.GeminiGroundedResponse
import com.example.data.MeteoForecastDataSource
import com.example.data.OpenMeteoClient
import com.example.data.OpenMeteoResult
import com.example.data.OpenSourceSatelliteClient
import com.example.data.RealSatelliteAndNasaTelemetry
import com.example.data.SatelliteTelemetryDataSource
import com.example.data.SqlFieldAuditDao
import com.example.data.SqlFieldAuditEntity
import com.example.data.StacAcquisitionStatus
import com.example.model.AccountStatus
import com.example.model.AppScreen
import com.example.model.IrrigationDayStatus
import com.example.model.UserRole
import com.example.viewmodel.AgroSightViewModel
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private class FakeMeteoDataSource : MeteoForecastDataSource {
        override suspend fun fetch7DayAgroForecast(
            latitude: Double,
            longitude: Double,
            baseKc: Float
        ): OpenMeteoResult {
            return OpenMeteoClient().buildFallbackForecast(
                latitude = latitude,
                longitude = longitude,
                baseKc = baseKc,
                isLive = true
            )
        }
    }

    private class FakeSatelliteDataSource : SatelliteTelemetryDataSource {
        override suspend fun fetchLiveOpenSourceSatelliteAndNasaData(
            latitude: Double,
            longitude: Double
        ): RealSatelliteAndNasaTelemetry {
            return RealSatelliteAndNasaTelemetry(
                sceneId = "S2B_TEST_SCENE",
                acquisitionDateIso = "2026-05-01",
                constellationAr = "Sentinel-2 Test",
                mgrsTileId = "35RQQ",
                cloudCoverPct = 0.5f,
                platformInstrument = "MSI L2A",
                stacCatalogSource = "Element84 Test",
                opticalTileUrl = "https://example.com/tile.png",
                retrievedSatelliteBitmap = null,
                nasaSolarRadiationMjM2Day = 24.5f,
                nasaClearSkyInsolationMjM2Day = 26.2f,
                nasaRootZoneSoilMoisturePct = 44.0f,
                nasaTemp2mMaxC = 32.0f,
                nasaTemp2mMinC = 19.0f,
                nasaRelativeHumidityPct = 52.0f,
                nasaWindSpeed2mMs = 3.5f,
                nasaVaporPressureDeficitKpa = 1.8f,
                channels = emptyList()
            )
        }
    }

    private class FakeAiCopilotDataSource(
        private val throwCancellation: Boolean = false
    ) : AgroAiCopilotDataSource {
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
        ): GeminiGroundedResponse {
            if (throwCancellation) throw CancellationException("Test coroutine cancellation")
            return GeminiGroundedResponse(
                replyText = "إجابة استرشادية للسؤال: $userMessage",
                citations = emptyList(),
                isLiveAiGenerated = true
            )
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
        ): String {
            if (throwCancellation) throw CancellationException("Test coroutine cancellation")
            return "توصية استرشادية اختبارية لمحصول $cropNameAr"
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
        ): String {
            if (throwCancellation) throw CancellationException("Test coroutine cancellation")
            return "رد واتساب استرشادي: $farmerQuestion"
        }

        override suspend fun generateCustomWorkerMessage(
            prompt: String,
            fallbackText: String
        ): String {
            if (throwCancellation) throw CancellationException("Test coroutine cancellation")
            return fallbackText
        }
    }

    private class FakeCloudWorkspaceRepository : CloudWorkspaceRepository {
        override val currentUser: FirebaseUser? = null

        override fun observeAuthState(): Flow<FirebaseUser?> = flowOf(null)

        override suspend fun signInWithGoogleIdToken(idToken: String): Result<FirebaseUser> =
            Result.failure(IllegalStateException("Fake Auth"))

        override fun signOut() {}

        override fun observeWorkspace(userId: String): Flow<FarmerCloudWorkspace?> = flowOf(null)

        override fun observeCloudChatLogs(userId: String): Flow<List<CloudChatLog>> = flowOf(emptyList())

        override suspend fun upsertFarmerWorkspace(
            userId: String,
            farmerName: String,
            whatsappPhone: String,
            gmailAddress: String,
            nationalId: String,
            fieldName: String,
            region: String,
            cropType: String,
            areaFeddans: Float,
            latitude: Double,
            longitude: Double,
            soilPh: Float,
            soilEce: Float,
            waterEcw: Float,
            ndviMean: Float,
            ndreMean: Float,
            psriMean: Float,
            ndmiMean: Float,
            detectedRiskName: String
        ): Result<Unit> = Result.success(Unit)

        override suspend fun adminUpdateCloudUserRoleAndStatus(
            targetUserId: String,
            newRole: String,
            newStatus: String
        ): Result<Unit> = Result.success(Unit)

        override suspend fun appendCloudChatLog(
            userId: String,
            role: String,
            channel: String,
            messageText: String,
            groundingSources: String
        ): Result<Unit> = Result.success(Unit)
    }

    private class FakeSqlFieldAuditDao : SqlFieldAuditDao {
        override fun observeAllAudits(): Flow<List<SqlFieldAuditEntity>> = flowOf(emptyList())
        override suspend fun getAuditCount(): Int = 0
        override suspend fun insertAudit(entity: SqlFieldAuditEntity) {}
        override suspend fun deleteAuditById(id: String) {}
    }

    private fun buildTestViewModel(
        app: Application,
        throwCancellation: Boolean = false
    ): AgroSightViewModel {
        app.getSharedPreferences("agrosight_multi_tenant_db", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val dbService = DatabaseService(app)
        val fakeGemini = FakeAiCopilotDataSource(throwCancellation = throwCancellation)
        val deps = AgroSightDependencies(
            databaseService = dbService,
            openMeteoClient = FakeMeteoDataSource(),
            openSourceSatelliteClient = FakeSatelliteDataSource(),
            geminiAgroService = fakeGemini,
            cloudWorker = AgroSightCloudWorker(dbService, fakeGemini),
            firestoreRepository = FakeCloudWorkspaceRepository(),
            sqlAuditDao = FakeSqlFieldAuditDao()
        )
        return AgroSightViewModel(app, deps)
    }

    @Test
    fun `read app name and parse OpenMeteo JSON with Robolectric`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AgroSight", appName)

        val sampleJson = """
            {
              "daily": {
                "time": ["2026-05-01", "2026-05-02", "2026-05-03"],
                "et0_fao_evapotranspiration": [5.4, 5.8, 6.1],
                "temperature_2m_max": [31.5, 33.0, 35.2],
                "temperature_2m_min": [18.0, 19.2, 20.1],
                "wind_speed_10m_max": [14.0, 18.5, 27.0],
                "precipitation_sum": [0.0, 0.0, 0.2],
                "shortwave_radiation_sum": [24.1, 25.0, 25.8]
              }
            }
        """.trimIndent()
        val parsed = OpenMeteoClient().parseOpenMeteoJson(sampleJson, 1.15f)
        assertTrue(parsed.isLiveConnected)
        assertEquals(3, parsed.records.size)
        assertEquals(5.4f, parsed.records[0].et0MmDay, 0.01f)
    }

    @Test
    fun `parseStacFeatureCollectionJson distinguishes valid scene with band assets vs empty vs missing assets`() {
        val satClient = OpenSourceSatelliteClient()

        // 1. Valid scene with required spectral band assets (B02, B04, B05, B06, B08, B8A, B11, SCL)
        val validStacJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "id": "S2B_35RQQ_20260501_0_L2A",
                  "properties": {
                    "datetime": "2026-05-01T08:42:11Z",
                    "eo:cloud_cover": 2.4,
                    "platform": "sentinel-2b",
                    "mgrs:utm_zone": 35,
                    "mgrs:latitude_band": "R",
                    "mgrs:grid_square": "QQ"
                  },
                  "assets": {
                    "blue": { "href": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/B02.tif" },
                    "red": { "href": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/B04.tif" },
                    "rededge1": { "href": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/B05.tif" },
                    "rededge2": { "href": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/B06.tif" },
                    "nir": { "href": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/B08.tif" },
                    "nir08": { "href": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/B8A.tif" },
                    "swir16": { "href": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/B11.tif" },
                    "scl": { "href": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/SCL.tif" }
                  }
                }
              ]
            }
        """.trimIndent()
        val validParsed = satClient.parseStacFeatureCollectionJson(
            body = validStacJson,
            httpCode = 200,
            elapsedMs = 115L,
            defaultMgrs = "35RQQ",
            demoSceneId = "DEMO_SCENE"
        )
        assertEquals(StacAcquisitionStatus.VALID_SCENE_WITH_BAND_ASSETS, validParsed.status)
        assertEquals("S2B_35RQQ_20260501_0_L2A", validParsed.sceneId)
        assertTrue(validParsed.bandAssets.hasAllRequiredSpectralBands)
        assertTrue(validParsed.bandAssets.b08Nir10mHref.isNotBlank())

        // 2. Empty FeatureCollection (HTTP 200 but no matching scenes)
        val emptyStacJson = """{"type": "FeatureCollection", "features": []}"""
        val emptyParsed = satClient.parseStacFeatureCollectionJson(
            body = emptyStacJson,
            httpCode = 200,
            elapsedMs = 90L,
            defaultMgrs = "35RQQ",
            demoSceneId = "DEMO_SCENE"
        )
        assertEquals(StacAcquisitionStatus.NO_MATCHING_SCENES, emptyParsed.status)

        // 3. Scene present but missing spectral band assets
        val missingAssetsJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "id": "S2A_INCOMPLETE_ASSETS",
                  "properties": { "datetime": "2026-05-01T08:42:11Z", "eo:cloud_cover": 1.0 },
                  "assets": {
                    "visual": { "href": "https://example.com/TCI.tif" }
                  }
                }
              ]
            }
        """.trimIndent()
        val missingParsed = satClient.parseStacFeatureCollectionJson(
            body = missingAssetsJson,
            httpCode = 200,
            elapsedMs = 88L,
            defaultMgrs = "35RQQ",
            demoSceneId = "DEMO_SCENE"
        )
        assertEquals(StacAcquisitionStatus.MISSING_OR_INACCESSIBLE_ASSETS, missingParsed.status)
        assertFalse(missingParsed.bandAssets.hasAllRequiredSpectralBands)
    }

    @Test
    fun `AgroSightViewModel initializes with 7-day irrigation schedule and enforces RBAC on admin actions`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = buildTestViewModel(app)

        val initialState = viewModel.uiState.value
        assertEquals(AppScreen.HOME, initialState.currentScreen)
        assertEquals(7, initialState.weeklySchedule.size)
        assertEquals(7, initialState.forecastDays.size)
        assertEquals(UserRole.SUPER_ADMIN, initialState.activeFarmer.role)

        // Super Admin can access Admin Portal
        viewModel.selectScreen(AppScreen.ADMIN_PORTAL)
        assertEquals(AppScreen.ADMIN_PORTAL, viewModel.uiState.value.currentScreen)

        // Switch to a regular FARMER account ("farmer_1")
        viewModel.switchFarmerAccount("farmer_1")
        assertEquals(UserRole.FARMER, viewModel.uiState.value.activeFarmer.role)
        // Regular farmer is automatically redirected away from Admin Portal to HOME
        assertEquals(AppScreen.HOME, viewModel.uiState.value.currentScreen)

        // Attempt by regular FARMER to navigate to Admin Portal is rejected
        viewModel.selectScreen(AppScreen.ADMIN_PORTAL)
        assertEquals(AppScreen.HOME, viewModel.uiState.value.currentScreen)

        // Attempt by regular FARMER to promote themselves to SUPER_ADMIN is blocked
        viewModel.adminChangeUserRole("farmer_1", UserRole.SUPER_ADMIN)
        assertEquals(UserRole.FARMER, viewModel.uiState.value.activeFarmer.role)
        assertTrue(viewModel.uiState.value.lastWhatsAppBannerNotificationAr?.contains("مرفوضة") == true)

        // Attempt by regular FARMER to change status is blocked
        viewModel.adminChangeUserStatus("farmer_2", AccountStatus.SUSPENDED)
        val farmer2 = viewModel.uiState.value.dbSnapshot.farmers.first { it.id == "farmer_2" }
        assertEquals(AccountStatus.ACTIVE, farmer2.accountStatus)
    }

    @Test
    fun `AgroSightViewModel updates 7-day timetable status and What-If simulator parameters`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = buildTestViewModel(app)

        viewModel.updateDayIrrigationStatus(1, IrrigationDayStatus.POSTPONED_WIND)
        assertEquals(
            IrrigationDayStatus.POSTPONED_WIND,
            viewModel.uiState.value.weeklySchedule[1].status
        )

        viewModel.applyWhatIfPreset(efficiencyPct = 95, targetPh = 6.5f, tempDeltaC = 4.0f)
        val whatIf = viewModel.uiState.value.whatIfInput
        assertEquals(95, whatIf.simEfficiencyPercent)
        assertEquals(6.5f, whatIf.simSoilPh, 0.01f)
        assertEquals(4.0f, whatIf.simTempDeltaC, 0.01f)
    }

    @Test
    fun `AgroSightViewModel resets loading flags cleanly when coroutine is cancelled`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = buildTestViewModel(app, throwCancellation = true)

        viewModel.runGeminiEpidemiologyAnalysis()
        assertFalse(
            "isGeneratingGeminiDiagnosis must not remain stuck after cancellation",
            viewModel.uiState.value.isGeneratingGeminiDiagnosis
        )
    }
}
