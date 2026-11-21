package com.example

import com.example.data.AnomalyPattern
import com.example.data.AuthLoginChannel
import com.example.data.DatabaseService
import com.example.data.OpenMeteoClient
import com.example.data.OpenSourceSatelliteClient
import com.example.data.RecalculateAgronomicDerivedStateUseCase
import com.example.data.SatelliteEpidemiologyEngine
import com.example.data.Sentinel2BandMathEngine
import com.example.data.Sentinel2PixelReflectance
import com.example.data.TelemetryDataMode
import com.example.data.redactPathForLog
import com.example.data.redactUidForLog
import com.example.model.AccountStatus
import com.example.model.IrrigationDayStatus
import com.example.model.IrrigationSystemType
import com.example.model.SUPPORTED_CROPS
import com.example.model.SoilDataProvenance
import com.example.model.SoilLaboratorySample
import com.example.model.SoilTexture
import com.example.model.UserRole
import com.example.model.WhatIfSimulatorInput
import com.example.model.buildWeeklyIrrigationSchedule
import com.example.model.computeWaterSavingsAnalytics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AgroSight:
 * - Sentinel-2 L2A spectral band mathematics (NDVI, NDRE, NDMI, PSRI, SCL cloud masking, NoData)
 * - 3-tier Epidemiology distinction (Vegetation Stress vs Risk Assessment vs Confirmed Diagnosis)
 * - FAO-56 Penman-Monteith & Water Savings Analytics (Potential vs Measured)
 * - Egyptian National ID / mobile validation, PII redaction, and RBAC guards
 */
class ExampleUnitTest {

    @Test
    fun `Sentinel2BandMathEngine computes accurate NDVI, NDRE, NDMI, and PSRI from spectral bands`() {
        // Healthy vegetation reflectances:
        // B02=0.04, B04=0.06, B05=0.12, B06=0.28, B08=0.42, B8A=0.40, B11=0.18, SCL=4
        val sample = Sentinel2PixelReflectance(
            row = 0,
            col = 0,
            b02Blue = 0.04f,
            b04Red = 0.06f,
            b05RedEdge1 = 0.12f,
            b06RedEdge2 = 0.28f,
            b08Nir = 0.42f,
            b8aNarrowNir = 0.40f,
            b11Swir1 = 0.18f,
            sclClass = 4
        )
        val px = Sentinel2BandMathEngine.evaluatePixel(sample)
        assertTrue(px.isValidUnmasked)
        assertNull(px.maskReason)
        // NDVI = (0.42 - 0.06) / (0.42 + 0.06) = 0.75
        assertEquals(0.75f, px.ndvi!!, 0.001f)
        // NDRE = (0.40 - 0.12) / (0.40 + 0.12) = 0.53846
        assertEquals(0.28f / 0.52f, px.ndre!!, 0.001f)
        // NDMI = (0.42 - 0.18) / (0.42 + 0.18) = 0.40
        assertEquals(0.40f, px.ndmi!!, 0.001f)
        // PSRI = (0.06 - 0.04) / 0.28 = 0.0714
        assertEquals(0.02f / 0.28f, px.psri!!, 0.001f)
    }

    @Test
    fun `Sentinel2BandMathEngine masks SCL cloud and shadow classes and handles zero denominators safely`() {
        val maskedClasses = listOf(0, 1, 3, 8, 9, 10, 11)
        for (scl in maskedClasses) {
            assertTrue("SCL $scl should be masked", Sentinel2BandMathEngine.isCloudOrNoDataScl(scl))
            val px = Sentinel2BandMathEngine.evaluatePixel(
                Sentinel2PixelReflectance(
                    row = 0,
                    col = 0,
                    b02Blue = 0.04f,
                    b04Red = 0.06f,
                    b05RedEdge1 = 0.12f,
                    b06RedEdge2 = 0.28f,
                    b08Nir = 0.42f,
                    b8aNarrowNir = 0.40f,
                    b11Swir1 = 0.18f,
                    sclClass = scl
                )
            )
            assertFalse(px.isValidUnmasked)
            assertNotNull(px.maskReason)
            assertNull(px.ndvi)
        }

        // Zero denominator protection
        assertNull(Sentinel2BandMathEngine.computeNormalizedDifference(0f, 0f))
        assertNull(Sentinel2BandMathEngine.computePsri(0.06f, 0.04f, 0f))
        // NoData DN protection
        assertTrue(Sentinel2BandMathEngine.convertDnToReflectance(0).isNaN())
        assertEquals(0.50f, Sentinel2BandMathEngine.convertDnToReflectance(5000), 0.001f)
    }

    @Test
    fun `Sentinel2BandMathEngine aggregates field statistics and produces valid GeoJSON anomaly polygon`() {
        val healthy = Sentinel2PixelReflectance(0, 0, 0.04f, 0.06f, 0.12f, 0.28f, 0.42f, 0.40f, 0.18f, 4)
        val stressed = Sentinel2PixelReflectance(0, 1, 0.05f, 0.16f, 0.18f, 0.21f, 0.24f, 0.22f, 0.25f, 5)
        val cloudy = Sentinel2PixelReflectance(0, 2, 0.35f, 0.36f, 0.37f, 0.38f, 0.40f, 0.39f, 0.34f, 9)

        val stats = Sentinel2BandMathEngine.aggregateRasterGrid(
            samples = listOf(healthy, healthy, stressed, cloudy),
            fieldLat = 30.3833,
            fieldLon = 30.3500
        )

        assertEquals(4, stats.totalPixels)
        assertEquals(3, stats.validPixels)
        assertEquals(1, stats.cloudOrNoDataMaskedPixels)
        assertEquals(0.75f, stats.validPixelRatio, 0.001f)
        assertTrue(stats.geoJsonAnomalyPolygon.contains("\"FeatureCollection\""))
        assertTrue(stats.geoJsonAnomalyPolygon.contains("\"Polygon\""))
    }

    @Test
    fun `FAO56 scientific formula audit calculates valid ET0, Kc, Leaching Fraction, and Gross Water`() {
        val tomatoCrop = SUPPORTED_CROPS.first { it.id == "tomato" }
        val audit = OpenSourceSatelliteClient.computeScientificFormulaAudit(
            crop = tomatoCrop,
            latitude = 30.3833,
            longitude = 30.3500,
            areaFeddans = 60f,
            tabularBaseKc = 1.15f,
            tempMaxC = 33.0f,
            tempMinC = 19.0f,
            relativeHumidityPct = 55,
            windSpeed10mKmh = 16.0f,
            solarRadiationMjM2 = 24.5f,
            precipitationMm = 0.0f,
            openMeteoEt0MmDay = 5.6f,
            meanNdvi = 0.68f,
            meanNdre = 0.35f,
            meanNdwi = 0.32f,
            soilPh = 7.8f,
            soilEceDsM = 2.4f,
            waterEcwDsM = 1.4f,
            soilTexture = SoilTexture.SANDY,
            irrigationSystem = IrrigationSystemType.DRIP,
            valveFlowRateM3PerFeddanHr = 16.0f
        )

        assertEquals(5.6f, audit.openMeteoEt0MmDay, 0.01f)
        assertTrue("Calculated Penman-Monteith ET0 should be positive", audit.calculatedPenmanEt0MmDay > 2.0f)
        assertTrue("Fused Kc should be within realistic bounds", audit.fusedAuthoritativeKc in 0.5f..1.35f)
        assertTrue("Leaching fraction percentage should be between 5% and 42%", audit.leachingPercentage in 5..42)
        assertTrue(
            "Gross irrigation water should exceed net crop water due to application efficiency and leaching",
            audit.grossWaterM3PerFeddanDay > audit.netWaterM3PerFeddanDay
        )
        assertEquals(audit.grossWaterM3PerFeddanDay * 60f, audit.totalFarmDailyWaterM3, 0.5f)
        assertEquals(4, audit.citations.size)
    }

    @Test
    fun `WaterSavingsAnalyticsSummary distinguishes potential modeled savings vs measured logged savings`() {
        val forecast = OpenMeteoClient().buildFallbackForecast(30.3833, 30.3500, 1.15f).records
        val schedule = buildWeeklyIrrigationSchedule(
            forecastDays = forecast,
            areaFeddans = 50f,
            irrigationSystem = IrrigationSystemType.DRIP,
            leachingRequirementPct = 14,
            soilPh = 7.4f,
            valveFlowRateM3PerFeddanHr = 16.0f,
            statusOverrides = emptyMap()
        )

        val unloggedSummary = computeWaterSavingsAnalytics(
            weeklySchedule = schedule,
            areaFeddans = 50f,
            loggedAppliedVolumesM3 = emptyList()
        )
        assertFalse(unloggedSummary.hasMeasuredExecutionLogs)
        assertNull(unloggedSummary.measuredSavingsM3)
        assertTrue(unloggedSummary.potentialModeledSavingsWeeklyM3 > 0f)

        val loggedSummary = computeWaterSavingsAnalytics(
            weeklySchedule = schedule,
            areaFeddans = 50f,
            loggedAppliedVolumesM3 = listOf(1200f, 1150f, 1180f)
        )
        assertTrue(loggedSummary.hasMeasuredExecutionLogs)
        assertNotNull(loggedSummary.measuredSavingsM3)

        val sample = SoilLaboratorySample(
            sampleId = "LAB-2026-01",
            sampleDateIso = "2026-05-01",
            provenance = SoilDataProvenance.CERTIFIED_LAB_ANALYSIS,
            ph = 7.5f,
            eceDsM = 2.1f,
            ecwDsM = 1.1f,
            soilTexture = SoilTexture.LOAMY
        )
        assertEquals(SoilDataProvenance.CERTIFIED_LAB_ANALYSIS, sample.provenance)
    }

    @Test
    fun `Egyptian 14-digit National ID validation accepts valid IDs and rejects malformed ones`() {
        val validBeheira = DatabaseService.validateEgyptianNationalId("27805141801492")
        assertTrue(validBeheira.isValid)
        assertEquals("البحيرة", validBeheira.governorateAr)
        assertEquals("27805141801492", validBeheira.normalized14Digits)

        val tooShort = DatabaseService.validateEgyptianNationalId("2780514180")
        assertFalse(tooShort.isValid)

        val invalidCentury = DatabaseService.validateEgyptianNationalId("17805141801492")
        assertFalse(invalidCentury.isValid)

        val invalidMonth = DatabaseService.validateEgyptianNationalId("27815141801492")
        assertFalse(invalidMonth.isValid)
    }

    @Test
    fun `normalizeEgyptianWhatsApp normalizes local and international Egyptian numbers`() {
        assertEquals("+201012345678", DatabaseService.normalizeEgyptianWhatsApp("01012345678"))
        assertEquals("+201123456789", DatabaseService.normalizeEgyptianWhatsApp("201123456789"))
        assertEquals("+201234567890", DatabaseService.normalizeEgyptianWhatsApp("1234567890"))
    }

    @Test
    fun `redactUidForLog and redactPathForLog mask sensitive identifiers`() {
        assertEquals("anonymous", redactUidForLog(null))
        assertEquals("abcd***", redactUidForLog("abcdef123456789"))
        assertEquals("***", redactUidForLog("1234"))

        val redactedPath = redactPathForLog("users/secretUid12345/chatLogs/log_987")
        assertEquals("users/secr***/chatLogs/log_987", redactedPath)
    }

    @Test
    fun `OpenMeteoClient buildFallbackForecast labels offline fallback explicitly as DEMO_ESTIMATED`() {
        val result = OpenMeteoClient().buildFallbackForecast(30.3833, 30.3500, 1.15f, isLive = false)
        assertFalse(result.isLiveConnected)
        assertEquals(TelemetryDataMode.DEMO_ESTIMATED, result.dataMode)
        assertEquals(7, result.records.size)
        assertTrue(result.records.all { it.et0MmDay > 2.0f })
        assertTrue(result.statusMessageAr.contains("Offline Baseline"))
    }

    @Test
    fun `SatelliteEpidemiologyEngine separates Vegetation Stress, Risk Assessment, and Confirmed Diagnosis`() {
        val focalReport = SatelliteEpidemiologyEngine.evaluateFieldEpidemiology(
            fieldId = "field_1",
            cropNameAr = "طماطم",
            areaFeddans = 60f,
            soilPh = 7.8f,
            soilEc = 2.4f,
            tempMaxC = 31f,
            relativeHumidityPct = 76,
            windSpeedKmh = 14f,
            selectedPattern = AnomalyPattern.FOCAL_RADIAL_PATCH
        )
        assertTrue(focalReport.isBiologicalDisease)
        assertEquals(3, focalReport.affectedValveNumber)
        assertEquals(96, focalReport.pixelGrid.size)
        // Verify 3-tier scientific separation
        assertTrue(focalReport.vegetationStressDetectionAr.isNotBlank())
        assertTrue(focalReport.agriculturalRiskAssessmentAr.isNotBlank())
        assertTrue(focalReport.confirmedDiseaseDiagnosisStatusAr.contains("غير مؤكد"))
        assertTrue(focalReport.supportingObservationsAr.isNotEmpty())
        assertTrue(focalReport.possibleCausesAr.isNotEmpty())
        assertTrue(focalReport.uncertaintyAndLimitationsAr.isNotEmpty())
        assertTrue(focalReport.pixelGrid.all { it.ndvi in 0.1f..0.95f })

        val valveFaultReport = SatelliteEpidemiologyEngine.evaluateFieldEpidemiology(
            fieldId = "field_1",
            cropNameAr = "طماطم",
            areaFeddans = 60f,
            soilPh = 7.3f,
            soilEc = 1.6f,
            tempMaxC = 32f,
            relativeHumidityPct = 52,
            windSpeedKmh = 16f,
            selectedPattern = AnomalyPattern.UNIFORM_VALVE
        )
        assertFalse(valveFaultReport.isBiologicalDisease)
        assertEquals(2, valveFaultReport.affectedValveNumber)

        val phLockupReport = SatelliteEpidemiologyEngine.evaluateFieldEpidemiology(
            fieldId = "field_1",
            cropNameAr = "قمح",
            areaFeddans = 100f,
            soilPh = 8.3f,
            soilEc = 3.0f,
            tempMaxC = 30f,
            relativeHumidityPct = 50,
            windSpeedKmh = 15f,
            selectedPattern = AnomalyPattern.SOIL_PH_LOCKUP
        )
        assertFalse(phLockupReport.isBiologicalDisease)
        assertTrue(phLockupReport.acidDoseLitersPerFeddan >= 4.0f)
    }

    @Test
    fun `DatabaseService RBAC protects bootstrapped Super Admin from demotion, suspension, or deletion`() {
        val db = DatabaseService(context = null)
        val initialSnap = db.getSnapshot()
        val superAdmin = initialSnap.farmers.find {
            it.gmailAddress.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
        }
        assertNotNull("Bootstrapped Super Admin must exist", superAdmin)
        assertEquals(UserRole.SUPER_ADMIN, superAdmin!!.role)

        // Attempt to demote Super Admin to FARMER -> must be blocked
        val afterDemoteAttempt = db.adminUpdateUserRole(superAdmin.id, UserRole.FARMER)
        val reloadedAdmin = afterDemoteAttempt.farmers.first { it.id == superAdmin.id }
        assertEquals(UserRole.SUPER_ADMIN, reloadedAdmin.role)

        // Attempt to suspend Super Admin -> must be blocked
        val afterSuspendAttempt = db.adminUpdateUserStatus(superAdmin.id, AccountStatus.SUSPENDED)
        val reloadedAdminStatus = afterSuspendAttempt.farmers.first { it.id == superAdmin.id }
        assertEquals(AccountStatus.ACTIVE, reloadedAdminStatus.accountStatus)

        // Attempt to delete Super Admin -> must be blocked
        val afterDeleteAttempt = db.adminDeleteUserAccount(superAdmin.id)
        assertTrue(afterDeleteAttempt.farmers.any { it.id == superAdmin.id })

        // Suspending a regular farmer blocks login
        val regularFarmer = initialSnap.farmers.first { it.role == UserRole.FARMER }
        db.adminUpdateUserStatus(regularFarmer.id, AccountStatus.SUSPENDED)
        val (loginSuccess, loginMsg, _) = db.authenticateBySingleIdentifier(
            AuthLoginChannel.GMAIL_ACCOUNT,
            regularFarmer.gmailAddress
        )
        assertFalse("Suspended farmer must not be allowed to log in", loginSuccess)
        assertTrue(loginMsg.contains("SUSPENDED"))
    }

    @Test
    fun `RecalculateAgronomicDerivedStateUseCase computes 7-day schedule and What-If comparison`() {
        val crop = SUPPORTED_CROPS.first()
        val forecast = OpenMeteoClient().buildFallbackForecast(30.3833, 30.3500, 1.15f).records
        val db = DatabaseService(context = null)
        val computed = RecalculateAgronomicDerivedStateUseCase.execute(
            fieldId = "field_1",
            selectedCrop = crop,
            daysAfterSowing = 68,
            latitude = 30.3833,
            longitude = 30.3500,
            areaFeddans = 60f,
            soilPh = 7.8f,
            soilEce = 2.4f,
            waterEcw = 1.4f,
            soilTexture = SoilTexture.SANDY,
            irrigationSystem = IrrigationSystemType.DRIP,
            valveFlowRateM3PerFeddanHr = 16.0f,
            forecastDays = forecast,
            satelliteAndNasaTelemetry = null,
            dayStatusOverrides = mapOf(0 to IrrigationDayStatus.COMPLETED),
            whatIfInput = WhatIfSimulatorInput(
                simEfficiencyPercent = 92,
                simSoilPh = 6.5f,
                simTempDeltaC = 3.0f
            ),
            activeAnomalyPattern = AnomalyPattern.FOCAL_RADIAL_PATCH,
            databaseService = db
        )

        assertEquals(7, computed.weeklySchedule.size)
        assertEquals(IrrigationDayStatus.COMPLETED, computed.weeklySchedule[0].status)
        assertEquals(6, computed.valveSectors.size)
        assertTrue(computed.whatIfResult.simulatedNutrientIndexPct > computed.whatIfResult.baselineNutrientIndexPct)
    }

    @Test
    fun `KotlinGeoTiffCogProcessor writes and parses georeferenced float32 GeoTIFF with CRS and 10m resolution`() {
        val grid = arrayOf(
            floatArrayOf(0.74f, 0.72f, 0.69f, -9999f),
            floatArrayOf(0.71f, 0.25f, 0.23f, 0.68f),
            floatArrayOf(0.70f, 0.24f, 0.22f, 0.67f)
        )
        val tiffBytes = com.example.data.KotlinGeoTiffCogProcessor.writeGeoTiffBytes(
            width = 4,
            height = 3,
            crsEpsg = 32636,
            originX = 245000.0,
            originY = 3365000.0,
            pixelWidth = 10.0,
            pixelHeight = 10.0,
            grid = grid,
            isFloat32 = true
        )
        assertTrue("GeoTIFF binary header must start with little-endian II (0x49 0x49 0x2A 0x00)", tiffBytes.size > 64)
        assertEquals('I'.code.toByte(), tiffBytes[0])
        assertEquals('I'.code.toByte(), tiffBytes[1])
        assertEquals(42.toByte(), tiffBytes[2])

        val parsed = com.example.data.KotlinGeoTiffCogProcessor.readGeoTiffBytes(tiffBytes, "NDVI")
        assertNotNull(parsed)
        assertEquals(4, parsed.width)
        assertEquals(3, parsed.height)
        assertEquals(32636, parsed.crsEpsg)
        assertEquals(10.0, parsed.pixelWidth, 0.001)
        assertEquals(-10.0, parsed.pixelHeight, 0.001)
        assertEquals(245000.0, parsed.originX, 0.01)
        assertEquals(3365000.0, parsed.originY, 0.01)
        assertEquals(0.74f, parsed.grid[0][0], 0.001f)
        assertEquals(0.25f, parsed.grid[1][1], 0.001f)
        assertEquals(-9999f, parsed.grid[0][3], 0.001f)
    }

    @Test
    fun `Sentinel2BandMathEngine supports BOA offset, 20m-to-10m alignment, crop thresholds, and connected-component GeoJSON`() {
        // 1. Processing Baseline 04.00+ BOA offset (-1000 DN)
        val reflLegacy = Sentinel2BandMathEngine.convertDnToReflectance(3500, scaleFactor = 0.0001f, offset = 0.0f)
        val reflPb04 = Sentinel2BandMathEngine.convertDnToReflectance(3500, scaleFactor = 0.0001f, offset = -1000.0f)
        assertEquals(0.35f, reflLegacy, 0.0001f)
        assertEquals(0.25f, reflPb04, 0.0001f)

        // 2. 20m to 10m multi-resolution band alignment
        val scl20m = arrayOf(
            floatArrayOf(4f, 9f),
            floatArrayOf(4f, 4f)
        )
        val scl10m = Sentinel2BandMathEngine.align20mGridTo10m(scl20m, targetRows = 4, targetCols = 4, isCategorical = true)
        assertEquals(4f, scl10m[0][0], 0.001f)
        assertEquals(9f, scl10m[0][3], 0.001f)
        assertEquals(9f, scl10m[1][2], 0.001f)
        assertEquals(4f, scl10m[3][3], 0.001f)

        // 3. Crop-specific anomaly thresholds differ by crop type and growth stage
        val potatoThresh = Sentinel2BandMathEngine.getCropThresholds("potato", growthStageIndex = 2)
        val palmThresh = Sentinel2BandMathEngine.getCropThresholds("date_palm", growthStageIndex = 2)
        assertTrue(
            "Potato mid-season NDRE threshold must exceed arid Date Palm threshold",
            potatoThresh.stressedNdreThreshold > palmThresh.stressedNdreThreshold
        )
        assertTrue(
            "Potato water stress NDMI threshold must exceed Date Palm threshold",
            potatoThresh.waterStressNdmiThreshold > palmThresh.waterStressNdmiThreshold
        )

        // 4. Connected-component GeoJSON vectorization: 0 anomalies -> empty features[], never a fixed center square
        val healthySamples = listOf(
            Sentinel2PixelReflectance(0, 0, 0.04f, 0.05f, 0.11f, 0.30f, 0.46f, 0.44f, 0.17f, 4),
            Sentinel2PixelReflectance(0, 1, 0.04f, 0.05f, 0.11f, 0.30f, 0.45f, 0.43f, 0.17f, 4)
        )
        val healthyStats = Sentinel2BandMathEngine.aggregateRasterGrid(
            samples = healthySamples,
            fieldLat = 30.3833,
            fieldLon = 30.3500,
            cropId = "potato"
        )
        assertTrue(
            "Healthy field must emit empty features array, not a fixed center square",
            healthyStats.geoJsonAnomalyPolygon.contains("\"features\": []") ||
                healthyStats.geoJsonAnomalyPolygon.contains("\"features\":[]")
        )
    }

    @Test
    fun `DatabaseService never fabricates National IDs, phone numbers, or emails when registering or bootstrapping`() {
        val db = DatabaseService(context = null)
        val snap = db.getSnapshot()

        // 1. Bootstrapped Super Admin has no fabricated phone or National ID
        val admin = snap.farmers.first { it.id == "farmer_admin_owner" }
        assertEquals("", admin.whatsappPhone)
        assertEquals("", admin.nationalId)
        assertFalse(admin.isDemoAccount)

        // 2. Pre-seeded demo farms are explicitly isolated with isDemoAccount = true and blank nationalId
        val demoFarmers = snap.farmers.filter { it.id in setOf("farmer_1", "farmer_2", "farmer_3") }
        assertEquals(3, demoFarmers.size)
        assertTrue(demoFarmers.all { it.isDemoAccount })
        assertTrue(demoFarmers.all { it.nationalId.isEmpty() })

        // 3. Logging in with ONLY a mobile number must NOT fabricate a fake Gmail or fake 14-digit National ID
        val (okPhone, _, snapAfterPhone) = db.authenticateBySingleIdentifier(
            AuthLoginChannel.MOBILE_NUMBER,
            "01055667788"
        )
        assertTrue(okPhone)
        val phoneUser = snapAfterPhone.farmers.first { it.id == snapAfterPhone.activeFarmerId }
        assertEquals("+201055667788", phoneUser.whatsappPhone)
        assertEquals("Missing email must remain empty, never fabricated", "", phoneUser.gmailAddress)
        assertEquals("Missing nationalId must remain empty, never fabricated", "", phoneUser.nationalId)
        assertFalse(phoneUser.isDemoAccount)

        // 4. Logging in with ONLY a Gmail address must NOT fabricate a fake phone or fake National ID
        val (okGmail, _, snapAfterGmail) = db.authenticateBySingleIdentifier(
            AuthLoginChannel.GMAIL_ACCOUNT,
            "real.agronomist@gmail.com"
        )
        assertTrue(okGmail)
        val gmailUser = snapAfterGmail.farmers.first { it.id == snapAfterGmail.activeFarmerId }
        assertEquals("real.agronomist@gmail.com", gmailUser.gmailAddress)
        assertEquals("", gmailUser.whatsappPhone)
        assertEquals("", gmailUser.nationalId)
    }

    @Test
    fun `BackendEnvironment enforces HTTPS in STAGING and PRODUCTION and supports emulator and device environments`() {
        val emulatorUrl = com.example.data.BackendEnvironment.resolveBaseUrl(
            com.example.data.BackendEnvironment.LOCAL_EMULATOR,
            "http://10.0.2.2:8090"
        )
        assertEquals("http://10.0.2.2:8090/", emulatorUrl)

        val deviceUrl = com.example.data.BackendEnvironment.resolveBaseUrl(
            com.example.data.BackendEnvironment.PHYSICAL_DEVICE,
            "http://192.168.1.45:8090/"
        )
        assertEquals("http://192.168.1.45:8090/", deviceUrl)

        val stagingHttps = com.example.data.BackendEnvironment.resolveBaseUrl(
            com.example.data.BackendEnvironment.STAGING,
            "https://staging-api.agrosight.eg"
        )
        assertEquals("https://staging-api.agrosight.eg/", stagingHttps)

        val prodHttps = com.example.data.BackendEnvironment.resolveBaseUrl(
            com.example.data.BackendEnvironment.PRODUCTION,
            "https://api.agrosight.eg/"
        )
        assertEquals("https://api.agrosight.eg/", prodHttps)

        // Plain HTTP in STAGING or PRODUCTION must throw IllegalArgumentException
        var stagingHttpRejected = false
        try {
            com.example.data.BackendEnvironment.resolveBaseUrl(
                com.example.data.BackendEnvironment.STAGING,
                "http://staging-api.agrosight.eg/"
            )
        } catch (_: IllegalArgumentException) {
            stagingHttpRejected = true
        }
        assertTrue("STAGING must reject plain HTTP URLs", stagingHttpRejected)

        var prodHttpRejected = false
        try {
            com.example.data.BackendEnvironment.resolveBaseUrl(
                com.example.data.BackendEnvironment.PRODUCTION,
                "http://api.agrosight.eg/"
            )
        } catch (_: IllegalArgumentException) {
            prodHttpRejected = true
        }
        assertTrue("PRODUCTION must reject plain HTTP URLs", prodHttpRejected)
    }
}



