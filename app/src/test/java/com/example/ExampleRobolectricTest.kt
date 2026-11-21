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

    private class FakeCloudWorkspaceRepository(
        private val authenticatedUid: String? = null
    ) : CloudWorkspaceRepository {
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

        override suspend fun deleteCloudWorkspace(userId: String): Result<Unit> {
            if (userId.isBlank()) {
                return Result.failure(IllegalArgumentException("Blank userId"))
            }
            val uid = authenticatedUid ?: return Result.failure(IllegalStateException("Not signed in"))
            if (uid != userId) {
                return Result.failure(
                    SecurityException("Unauthorized workspace deletion: authenticated UID does not match requested userId")
                )
            }
            return Result.success(Unit)
        }
    }

    private class FakeSqlFieldAuditDao : SqlFieldAuditDao {
        override fun observeAllAudits(): Flow<List<SqlFieldAuditEntity>> = flowOf(emptyList())
        override suspend fun getAuditCount(): Int = 0
        override suspend fun insertAudit(entity: SqlFieldAuditEntity) {}
        override suspend fun deleteAuditById(id: String) {}
    }

    private fun buildTestViewModel(
        app: Application,
        throwCancellation: Boolean = false,
        authenticatedUid: String? = null
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
            satelliteRasterBackendRepository = com.example.data.RetrofitSatelliteRasterBackendRepository(
                baseUrl = "http://127.0.0.1:1/",
                stacClient = OpenSourceSatelliteClient()
            ),
            geminiAgroService = fakeGemini,
            cloudWorker = AgroSightCloudWorker(dbService, fakeGemini),
            firestoreRepository = FakeCloudWorkspaceRepository(authenticatedUid = authenticatedUid),
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
        // Verify standard ViewModelProvider.AndroidViewModelFactory reflection instantiation succeeds
        val reflectedVm = androidx.lifecycle.ViewModelProvider(
            androidx.lifecycle.ViewModelStore(),
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[AgroSightViewModel::class.java]
        assertNotNull("AndroidViewModelFactory must instantiate AgroSightViewModel via reflection", reflectedVm)

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

    private class FakeSatelliteRasterBackendRepository : com.example.data.SatelliteRasterBackendRepository {
        override suspend fun checkBackendHealth(): Result<Boolean> = Result.success(true)

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
        ): Result<com.example.data.GeoreferencedRasterBackendResult> {
            val cells = (0 until 96).map { idx ->
                val r = idx / 12
                val c = idx % 12
                com.example.data.SpectralPixelCell(
                    row = r,
                    col = c,
                    valveNumber = (((r / 4) * 3) + (c / 4) + 1).coerceIn(1, 6),
                    ndvi = 0.72f,
                    ndre = 0.39f,
                    psri = 0.08f,
                    ndmi = 0.36f,
                    isHotspot = (r == 2 && c == 8),
                    isCloudMasked = false
                )
            }
            return Result.success(
                com.example.data.GeoreferencedRasterBackendResult(
                    resultId = "res_test_001",
                    jobId = "job_test_001",
                    farmId = farmId,
                    ownerUid = ownerUid,
                    sceneId = "S2B_35RQQ_20260501_0_L2A",
                    cropId = cropId,
                    executionState = com.example.data.RasterPipelineExecutionState.COMPLETED_LIVE_GEOREFERENCED,
                    isLiveGeoreferencedRaster = true,
                    crsEpsg = 32636,
                    spatialResolutionMeters = 10.0f,
                    totalPixels = 96,
                    polygonPixels = 96,
                    validPixels = 94,
                    maskedCloudPixels = 2,
                    validPixelRatio = 94f / 96f,
                    ndviMean = 0.72f,
                    ndviStdDev = 0.04f,
                    ndreMean = 0.39f,
                    ndmiMean = 0.36f,
                    psriMean = 0.08f,
                    anomalyPixelCount = 3,
                    anomalyAreaFeddans = 0.07f,
                    anomalyZonesGeoJson = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[30.34,30.38],[30.35,30.38],[30.35,30.39],[30.34,30.39],[30.34,30.38]]]},"properties":{"cluster_id":1}}]}""",
                    pixelGrid = cells,
                    vegetationStressDetectionAr = "رصد شذوذ طيفي موضعي في 3 بكسلات مرجعية داخل حدود المزرعة",
                    agriculturalRiskAssessmentAr = "احتمال إجهاد فسيولوجي مبكر يتطلب فحص المحبس رقم 3",
                    confirmedDiseaseDiagnosisStatusAr = "غير مؤكد مختبرياً — الشذوذ الطيفي من القمر الصناعي لا يغني عن المعاينة الحقلية",
                    statusSummaryAr = "تم تحليل 94 بكسل مرجعي (EPSG:32636 • دقة 10.0م)"
                )
            )
        }
    }

    @Test
    fun `AgroSightViewModel runGeoreferencedRasterAnalysis and RetrofitSatelliteRasterBackendRepository update 3-tier scientific state`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dbService = DatabaseService(app)
        val fakeGemini = FakeAiCopilotDataSource()
        val viewModel = AgroSightViewModel(
            app,
            AgroSightDependencies(
                databaseService = dbService,
                openMeteoClient = FakeMeteoDataSource(),
                openSourceSatelliteClient = FakeSatelliteDataSource(),
                satelliteRasterBackendRepository = FakeSatelliteRasterBackendRepository(),
                geminiAgroService = fakeGemini,
                cloudWorker = AgroSightCloudWorker(dbService, fakeGemini),
                firestoreRepository = FakeCloudWorkspaceRepository(authenticatedUid = "farmer_admin_owner"),
                sqlAuditDao = FakeSqlFieldAuditDao()
            )
        )

        viewModel.runGeoreferencedRasterAnalysis()
        val rasterRes = viewModel.uiState.value.rasterBackendResult
        assertNotNull("Georeferenced raster backend result must be populated", rasterRes)
        assertTrue(rasterRes!!.isLiveGeoreferencedRaster)
        assertEquals(32636, rasterRes.crsEpsg)
        assertEquals(10.0f, rasterRes.spatialResolutionMeters, 0.01f)
        assertEquals(96, rasterRes.pixelGrid.size)
        assertTrue(rasterRes.anomalyZonesGeoJson.contains("\"FeatureCollection\""))
        assertTrue(viewModel.uiState.value.epidemiologyReport.dataModeDesignation.contains("LIVE_GEOREFERENCED_RASTER"))
        assertTrue(viewModel.uiState.value.epidemiologyReport.confirmedDiseaseDiagnosisStatusAr.contains("غير مؤكد"))
    }

    @Test
    fun `deleteCloudWorkspace enforces UID ownership verification and blocks cross-user deletion`() {
        val repo = FakeCloudWorkspaceRepository(authenticatedUid = "farmer_alice_123")

        // 1. Blank userId is rejected
        val blankRes = kotlinx.coroutines.runBlocking { repo.deleteCloudWorkspace("") }
        assertTrue(blankRes.isFailure)
        assertTrue(blankRes.exceptionOrNull() is IllegalArgumentException)

        // 2. Unauthorized cross-user deletion (Alice trying to delete Bob's workspace) is rejected with SecurityException
        val crossUserRes = kotlinx.coroutines.runBlocking { repo.deleteCloudWorkspace("farmer_bob_456") }
        assertTrue(crossUserRes.isFailure)
        assertTrue(crossUserRes.exceptionOrNull() is SecurityException)

        // 3. Owner deleting their own workspace succeeds
        val ownerRes = kotlinx.coroutines.runBlocking { repo.deleteCloudWorkspace("farmer_alice_123") }
        assertTrue(ownerRes.isSuccess)
    }

    @Test
    fun `RetrofitSatelliteRasterBackendRepository executes token refresh on 401, submits job, polls 9-stage lifecycle, and retrieves georeferenced result`() {
        val jsonMediaType = with(okhttp3.MediaType) { "application/json".toMediaType() }
        var tokenRefreshRequested = false
        val tokenProvider = com.example.data.FirebaseIdTokenProvider { forceRefresh ->
            if (forceRefresh) {
                tokenRefreshRequested = true
                "fresh_firebase_id_token_v2"
            } else {
                "expired_firebase_id_token_v1"
            }
        }

        var jobPollCount = 0
        val pollStates = listOf(
            "QUEUED" to 10,
            "DISCOVERING_SCENES" to 20,
            "DOWNLOADING_ASSETS" to 35,
            "PROCESSING_RASTERS" to 55,
            "CALCULATING_INDICES" to 75,
            "GENERATING_OUTPUTS" to 90,
            "COMPLETED" to 100
        )

        val mockInterceptor = okhttp3.Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val authHeader = req.header("Authorization").orEmpty()

            if (authHeader == "Bearer expired_firebase_id_token_v1") {
                return@Interceptor okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body(
                        with(okhttp3.ResponseBody) {
                            """{"detail":{"error_code":"TOKEN_EXPIRED","message":"Firebase ID token has expired"}}"""
                                .toResponseBody(jsonMediaType)
                        }
                    )
                    .build()
            }

            assertEquals("Bearer fresh_firebase_id_token_v2", authHeader)

            val responseJson = when {
                path.endsWith("/api/v1/satellite/scenes") -> {
                    """
                    {
                      "farm_id": "farm_wadi_1",
                      "scene_count": 1,
                      "scenes": [
                        {
                          "scene_id": "S2A_36RTU_20261118_1_L2A",
                          "scale_factor": 0.0001,
                          "boa_add_offset": -1000.0,
                          "band_assets": {"B04": "https://sentinel-cogs.s3.us-west-2.amazonaws.com/B04.tif"}
                        }
                      ]
                    }
                    """.trimIndent()
                }
                path.endsWith("/api/v1/satellite/process") -> {
                    """
                    {
                      "job_id": "job_live_001",
                      "result_id": "res_live_001",
                      "farm_id": "farm_wadi_1",
                      "owner_uid": "farmer_uid_1",
                      "status": "QUEUED",
                      "progress_pct": 5
                    }
                    """.trimIndent()
                }
                path.contains("/api/v1/satellite/jobs/") -> {
                    val idx = jobPollCount.coerceAtMost(pollStates.lastIndex)
                    jobPollCount++
                    val (st, pct) = pollStates[idx]
                    """
                    {
                      "job_id": "job_live_001",
                      "result_id": "res_live_001",
                      "status": "$st",
                      "progress_pct": $pct
                    }
                    """.trimIndent()
                }
                path.contains("/api/v1/satellite/results/") -> {
                    """
                    {
                      "result_id": "res_live_001",
                      "job_id": "job_live_001",
                      "farm_id": "farm_wadi_1",
                      "owner_uid": "farmer_uid_1",
                      "scene_id": "S2A_36RTU_20261118_1_L2A",
                      "crs_epsg": 32636,
                      "spatial_resolution_m": 10.0,
                      "statistics": {
                        "total_pixel_count": 64,
                        "polygon_pixel_count": 48,
                        "valid_pixel_count": 44,
                        "masked_cloud_or_nodata_count": 4,
                        "valid_pixel_ratio": 0.9167,
                        "ndvi_mean": 0.68,
                        "ndvi_std_dev": 0.05,
                        "ndre_mean": 0.41,
                        "ndmi_mean": 0.31,
                        "psri_mean": 0.04,
                        "anomaly_pixel_count": 6,
                        "anomaly_area_feddans": 0.14
                      },
                      "scientific_assessment": {
                        "vegetation_stress_detection_ar": "رصد إجهاد نباتي طيفي في 6 بكسل",
                        "agricultural_risk_assessment_ar": "مؤشر خطر زراعي متوسط",
                        "confirmed_disease_diagnosis_status_ar": "غير مؤكد من القمر الصناعي وحده"
                      },
                      "anomaly_zones_geojson": {"type":"FeatureCollection","features":[]},
                      "pixel_evaluations": [
                        {"row": 0, "col": 0, "ndvi": 0.68, "ndre": 0.41, "ndmi": 0.31, "psri": 0.04, "is_anomalous": false, "is_cloud_masked": false}
                      ]
                    }
                    """.trimIndent()
                }
                else -> """{"status":"HEALTHY"}"""
            }

            okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(with(okhttp3.ResponseBody) { responseJson.toResponseBody(jsonMediaType) })
                .build()
        }

        val okHttp = okhttp3.OkHttpClient.Builder()
            .addInterceptor(mockInterceptor)
            .build()

        val repo = com.example.data.RetrofitSatelliteRasterBackendRepository(
            environment = com.example.data.BackendEnvironment.STAGING,
            baseUrl = "https://staging-api.agrosight.eg/",
            okHttpClient = okHttp,
            tokenProvider = tokenProvider,
            pollIntervalMs = 1L
        )

        val progressTransitions = mutableListOf<com.example.data.RasterPipelineExecutionState>()
        val result = kotlinx.coroutines.runBlocking {
            repo.runFullGeoreferencedSatelliteAnalysis(
                farmId = "farm_wadi_1",
                ownerUid = "farmer_uid_1",
                cropId = "tomato",
                growthStageIndex = 2,
                centerLat = 30.3833,
                centerLon = 30.3500,
                areaFeddans = 15f,
                dateStartIso = "2026-10-01",
                dateEndIso = "2026-11-20",
                onProgressUpdate = { state, _, _ ->
                    progressTransitions.add(state)
                }
            )
        }

        assertTrue("Repository call must succeed after automatic token refresh", result.isSuccess)
        assertTrue("Token force-refresh must have been triggered on HTTP 401", tokenRefreshRequested)
        val data = result.getOrThrow()
        assertEquals("res_live_001", data.resultId)
        assertEquals("S2A_36RTU_20261118_1_L2A", data.sceneId)
        assertEquals(32636, data.crsEpsg)
        assertEquals(44, data.validPixels)
        assertEquals(0.68f, data.ndviMean, 0.001f)
        assertTrue(
            "All 7 polled lifecycle states must be recorded",
            data.jobStatusHistory.containsAll(
                listOf(
                    "QUEUED",
                    "DISCOVERING_SCENES",
                    "DOWNLOADING_ASSETS",
                    "PROCESSING_RASTERS",
                    "CALCULATING_INDICES",
                    "GENERATING_OUTPUTS",
                    "COMPLETED"
                )
            )
        )
        assertTrue(progressTransitions.contains(com.example.data.RasterPipelineExecutionState.COMPLETED_LIVE_GEOREFERENCED))
    }

    @Test
    fun `RetrofitSatelliteRasterBackendRepository handles 401 expiration, 403 unauthorized farm, network interruption, 503 unavailable, and FAILED job`() {
        val jsonMediaType = with(okhttp3.MediaType) { "application/json".toMediaType() }
        fun buildRepoForMock(handler: (okhttp3.Request) -> okhttp3.Response): com.example.data.RetrofitSatelliteRasterBackendRepository {
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain -> handler(chain.request()) }
                .build()
            return com.example.data.RetrofitSatelliteRasterBackendRepository(
                environment = com.example.data.BackendEnvironment.PRODUCTION,
                baseUrl = "https://api.agrosight.eg/",
                okHttpClient = client,
                tokenProvider = com.example.data.FirebaseIdTokenProvider { "still_invalid_token" },
                pollIntervalMs = 1L
            )
        }

        // 1. Persistent HTTP 401 Authentication Expiration
        val repo401 = buildRepoForMock { req ->
            okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(
                    with(okhttp3.ResponseBody) {
                        """{"detail":{"error_code":"TOKEN_EXPIRED","message":"Expired token"}}"""
                            .toResponseBody(jsonMediaType)
                    }
                )
                .build()
        }
        val res401 = kotlinx.coroutines.runBlocking {
            repo401.runFullGeoreferencedSatelliteAnalysis("f1", "u1", "tomato", 2, 30.38, 30.35, 10f)
        }
        assertTrue(res401.isFailure)
        val err401 = res401.exceptionOrNull() as com.example.data.SatelliteBackendApiException
        assertEquals(401, err401.httpStatusCode)
        assertEquals("TOKEN_EXPIRED", err401.errorCode)

        // 2. HTTP 403 Unauthorized Farm Access
        val repo403 = buildRepoForMock { req ->
            okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body(
                    with(okhttp3.ResponseBody) {
                        """{"detail":{"error_code":"FORBIDDEN_FARM_ACCESS","message":"Caller does not own farm"}}"""
                            .toResponseBody(jsonMediaType)
                    }
                )
                .build()
        }
        val res403 = kotlinx.coroutines.runBlocking {
            repo403.runFullGeoreferencedSatelliteAnalysis("farm_other_user", "u1", "tomato", 2, 30.38, 30.35, 10f)
        }
        assertTrue(res403.isFailure)
        val err403 = res403.exceptionOrNull() as com.example.data.SatelliteBackendApiException
        assertEquals(403, err403.httpStatusCode)
        assertEquals("FORBIDDEN_FARM_ACCESS", err403.errorCode)

        // 3. Network Interruption (IOException)
        val repoNet = buildRepoForMock {
            throw java.io.IOException("Socket closed mid-transfer")
        }
        val resNet = kotlinx.coroutines.runBlocking {
            repoNet.runFullGeoreferencedSatelliteAnalysis("f1", "u1", "tomato", 2, 30.38, 30.35, 10f)
        }
        assertTrue(resNet.isFailure)
        val errNet = resNet.exceptionOrNull() as com.example.data.SatelliteBackendApiException
        assertEquals("NETWORK_INTERRUPTED", errNet.errorCode)

        // 4. Backend Unavailable (HTTP 503 FIREBASE_AUTH_NOT_CONFIGURED)
        val repo503 = buildRepoForMock { req ->
            okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body(
                    with(okhttp3.ResponseBody) {
                        """{"detail":{"error_code":"FIREBASE_AUTH_NOT_CONFIGURED","message":"Missing Firebase Admin credentials"}}"""
                            .toResponseBody(jsonMediaType)
                    }
                )
                .build()
        }
        val res503 = kotlinx.coroutines.runBlocking {
            repo503.runFullGeoreferencedSatelliteAnalysis("f1", "u1", "tomato", 2, 30.38, 30.35, 10f)
        }
        assertTrue(res503.isFailure)
        val err503 = res503.exceptionOrNull() as com.example.data.SatelliteBackendApiException
        assertEquals(503, err503.httpStatusCode)
        assertEquals("FIREBASE_AUTH_NOT_CONFIGURED", err503.errorCode)

        // 5. Processing Failure during Job Execution (status = FAILED)
        val repoFailedJob = buildRepoForMock { req ->
            val path = req.url.encodedPath
            val bodyStr = when {
                path.endsWith("/api/v1/satellite/scenes") ->
                    """{"scenes":[{"scene_id":"S2A_TEST","band_assets":{"B04":"https://example.com/B04.tif"}}]}"""
                path.endsWith("/api/v1/satellite/process") ->
                    """{"job_id":"job_fail_99","result_id":"res_fail_99","status":"PROCESSING_RASTERS","progress_pct":45}"""
                else ->
                    """{"job_id":"job_fail_99","result_id":"res_fail_99","status":"FAILED","error_code":"CORRUPTED_RASTER_TIFF","error_message":"Truncated GeoTIFF tile strip"}"""
            }
            okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(with(okhttp3.ResponseBody) { bodyStr.toResponseBody(jsonMediaType) })
                .build()
        }
        val resFailedJob = kotlinx.coroutines.runBlocking {
            repoFailedJob.runFullGeoreferencedSatelliteAnalysis("f1", "u1", "tomato", 2, 30.38, 30.35, 10f)
        }
        assertTrue(resFailedJob.isFailure)
        val errFailedJob = resFailedJob.exceptionOrNull() as com.example.data.SatelliteBackendApiException
        assertEquals("CORRUPTED_RASTER_TIFF", errFailedJob.errorCode)
    }

    @Test
    fun `ChatbotScreen route, grounded Gemini conversation flow, and AgroSight logo Material3 palette are consistent`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = buildTestViewModel(app)

        // 1. Verify AgroSight Logo Material3 Theme Palette
        assertEquals(
            com.example.ui.theme.AgroSightBotanicalGreen,
            com.example.ui.theme.AgroSightLogoLightColorScheme.primary
        )
        assertEquals(
            com.example.ui.theme.AgroSightPixelBlue,
            com.example.ui.theme.AgroSightLogoLightColorScheme.secondary
        )
        assertEquals(
            com.example.ui.theme.AgroSightIvory,
            com.example.ui.theme.AgroSightLogoLightColorScheme.background
        )
        assertEquals(
            com.example.ui.theme.AgroSightBotanicalSage,
            com.example.ui.theme.AgroSightLogoLightColorScheme.primaryContainer
        )
        assertEquals(
            androidx.compose.ui.text.style.TextAlign.Right,
            com.example.ui.theme.Typography.bodyLarge.textAlign
        )
        assertEquals(
            androidx.compose.ui.text.style.TextDirection.Rtl,
            com.example.ui.theme.Typography.bodyLarge.textDirection
        )

        // 2. Navigate to AppScreen.CHATBOT and send a farm-grounded query
        viewModel.selectScreen(AppScreen.CHATBOT)
        assertEquals(AppScreen.CHATBOT, viewModel.uiState.value.currentScreen)

        val initialCount = viewModel.uiState.value.geminiChatMessages.size
        viewModel.sendGeminiAgronomyChatMessage("ما المقنن المائي وحالة Sentinel-2 لحقلي اليوم؟", false)
        val afterSend = viewModel.uiState.value.geminiChatMessages
        assertTrue("Chatbot history should append user query and grounded assistant reply", afterSend.size >= initialCount + 2)
        val lastReply = afterSend.last()
        assertEquals("ASSISTANT", lastReply.role)
        assertTrue(lastReply.text.contains("إجابة استرشادية للسؤال"))
        assertTrue(lastReply.isLiveAiGenerated)

        // 3. Clear chat history resets to grounded welcome message
        viewModel.clearGeminiChatHistory()
        assertEquals(1, viewModel.uiState.value.geminiChatMessages.size)
        assertEquals("ASSISTANT", viewModel.uiState.value.geminiChatMessages.first().role)
    }

    @Test
    fun `RoomOfflineTelemetryCacheRepository persists weather data and satellite analysis results and serves them when connectivity fails`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            app,
            com.example.data.AgroSightRoomDatabase::class.java
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        try {
            val cacheRepo = com.example.data.RoomOfflineTelemetryCacheRepository(
                roomDb.offlineTelemetryCacheDao()
            )
            val dbService = DatabaseService(app)
            val fakeGemini = FakeAiCopilotDataSource()
            var isNetworkOnline = true

            val toggleableMeteoSource = object : MeteoForecastDataSource {
                override suspend fun fetch7DayAgroForecast(
                    latitude: Double,
                    longitude: Double,
                    baseKc: Float
                ): OpenMeteoResult {
                    return OpenMeteoClient().buildFallbackForecast(
                        latitude = latitude,
                        longitude = longitude,
                        baseKc = baseKc,
                        isLive = isNetworkOnline,
                        httpStatusCode = if (isNetworkOnline) 200 else 0,
                        errorReasonAr = if (isNetworkOnline) null else "Offline Test Disconnect"
                    )
                }
            }

            val toggleableSatelliteSource = object : SatelliteTelemetryDataSource {
                override suspend fun fetchLiveOpenSourceSatelliteAndNasaData(
                    latitude: Double,
                    longitude: Double
                ): RealSatelliteAndNasaTelemetry {
                    return RealSatelliteAndNasaTelemetry(
                        sceneId = if (isNetworkOnline) "S2A_LIVE_CACHED_SCENE_36RTU" else "DEMO_OFFLINE_FALLBACK",
                        acquisitionDateIso = "2026-11-18",
                        constellationAr = "Sentinel-2 L2A",
                        mgrsTileId = "36RTU",
                        cloudCoverPct = 1.2f,
                        platformInstrument = "MSI L2A",
                        stacCatalogSource = "Element84 STAC",
                        opticalTileUrl = "https://example.com/tile.png",
                        retrievedSatelliteBitmap = null,
                        nasaSolarRadiationMjM2Day = 24.8f,
                        nasaClearSkyInsolationMjM2Day = 26.5f,
                        nasaRootZoneSoilMoisturePct = 46.0f,
                        nasaTemp2mMaxC = 31.5f,
                        nasaTemp2mMinC = 18.0f,
                        nasaRelativeHumidityPct = 50.0f,
                        nasaWindSpeed2mMs = 3.2f,
                        nasaVaporPressureDeficitKpa = 1.7f,
                        channels = emptyList(),
                        stacAcquisitionStatus = if (isNetworkOnline) {
                            StacAcquisitionStatus.VALID_SCENE_WITH_BAND_ASSETS
                        } else {
                            StacAcquisitionStatus.NETWORK_OR_HTTP_FAILURE
                        },
                        dataMode = if (isNetworkOnline) {
                            com.example.data.TelemetryDataMode.LIVE
                        } else {
                            com.example.data.TelemetryDataMode.DEMO_ESTIMATED
                        }
                    )
                }
            }

            val viewModel = AgroSightViewModel(
                app,
                AgroSightDependencies(
                    databaseService = dbService,
                    openMeteoClient = toggleableMeteoSource,
                    openSourceSatelliteClient = toggleableSatelliteSource,
                    satelliteRasterBackendRepository = FakeSatelliteRasterBackendRepository(),
                    geminiAgroService = fakeGemini,
                    cloudWorker = AgroSightCloudWorker(dbService, fakeGemini),
                    firestoreRepository = FakeCloudWorkspaceRepository(),
                    sqlAuditDao = roomDb.sqlFieldAuditDao(),
                    offlineTelemetryCacheRepository = cacheRepo
                )
            )

            // 1. Online telemetry refresh caches both weather forecast and satellite analysis in Room SQLite
            val activeFieldId = viewModel.uiState.value.fieldId
            val activeLat = viewModel.uiState.value.latitude
            val activeLon = viewModel.uiState.value.longitude
            val cachedWeather = kotlinx.coroutines.runBlocking {
                cacheRepo.getCachedWeatherForecast(activeFieldId, activeLat, activeLon)
            }
            val cachedSatellite = kotlinx.coroutines.runBlocking {
                cacheRepo.getCachedSatelliteAnalysis(activeFieldId, activeLat, activeLon)
            }
            assertNotNull("Weather forecast must be persisted in Room SQLite", cachedWeather)
            assertNotNull("Satellite analysis must be persisted in Room SQLite", cachedSatellite)
            assertEquals("S2B_35RQQ_20260501_0_L2A", cachedSatellite!!.sceneId)
            assertTrue(cachedWeather!!.wasOriginallyLive)
            assertTrue(cachedSatellite.wasOriginallyLive)

            // 2. Simulate loss of internet connectivity and refresh telemetry -> falls back to Room SQLite cache
            isNetworkOnline = false
            viewModel.restoreTelemetryFromRoomOfflineCache()

            val offlineState = viewModel.uiState.value
            assertTrue("App should serve telemetry from Room offline cache when offline", offlineState.isServingFromRoomOfflineCache)
            assertTrue(offlineState.meteoStatusAr.contains("CACHED • Room SQLite"))
            assertNotNull(offlineState.satelliteAndNasaTelemetry)
            assertEquals(
                com.example.data.TelemetryDataMode.CACHED,
                offlineState.satelliteAndNasaTelemetry?.dataMode
            )
            assertNotNull("Georeferenced raster analysis must remain available from Room cache while offline", offlineState.rasterBackendResult)
            assertEquals("S2B_35RQQ_20260501_0_L2A", offlineState.rasterBackendResult?.sceneId)

            // 3. Clearing offline cache empties Room tables
            viewModel.clearOfflineTelemetryCache()
            val afterClearWeather = kotlinx.coroutines.runBlocking {
                cacheRepo.getCachedWeatherForecast(activeFieldId, activeLat, activeLon)
            }
            val afterClearSat = kotlinx.coroutines.runBlocking {
                cacheRepo.getCachedSatelliteAnalysis(activeFieldId, activeLat, activeLon)
            }
            assertEquals(null, afterClearWeather)
            assertEquals(null, afterClearSat)
        } finally {
            roomDb.close()
        }
    }
}

