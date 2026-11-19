package com.example

import com.example.data.AnomalyPattern
import com.example.data.AuthLoginChannel
import com.example.data.DatabaseService
import com.example.data.OpenMeteoClient
import com.example.data.OpenSourceSatelliteClient
import com.example.data.RecalculateAgronomicDerivedStateUseCase
import com.example.data.SatelliteEpidemiologyEngine
import com.example.data.redactPathForLog
import com.example.data.redactUidForLog
import com.example.model.AccountStatus
import com.example.model.IrrigationDayStatus
import com.example.model.IrrigationSystemType
import com.example.model.SUPPORTED_CROPS
import com.example.model.SoilTexture
import com.example.model.UserRole
import com.example.model.WhatIfSimulatorInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AgroSight agricultural formulas, Egyptian National ID / mobile / Gmail validation,
 * PII log redaction, Open-Meteo JSON parsing, RBAC role guards, and derived-state calculations.
 */
class ExampleUnitTest {

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
    fun `OpenMeteoClient buildFallbackForecast builds 7-day agro forecast with offline baseline status`() {
        val result = OpenMeteoClient().buildFallbackForecast(30.3833, 30.3500, 1.15f, isLive = false)
        assertFalse(result.isLiveConnected)
        assertEquals(7, result.records.size)
        assertTrue(result.records.all { it.et0MmDay > 2.0f })
        assertTrue(result.statusMessageAr.contains("Offline Baseline"))
    }

    @Test
    fun `SatelliteEpidemiologyEngine differentiates focal disease vs uniform valve fault vs soil pH lockup`() {
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
}
