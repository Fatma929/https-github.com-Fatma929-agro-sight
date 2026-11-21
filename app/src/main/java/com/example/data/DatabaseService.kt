@file:SuppressLint("NewApi")

package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import com.example.model.AccountStatus
import com.example.model.SystemAuditEvent
import com.example.model.SystemWideAnalyticsSummary
import com.example.model.UserRole
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class AnomalyPattern(val code: String, val labelAr: String, val caseBadgeAr: String) {
    FOCAL_RADIAL_PATCH(
        "FOCAL_RADIAL_PATCH",
        "بؤرة مرضية دائرية (Case A: إصابة فطرية/حشرية)",
        "Case A • بؤرة مرضية فطرية"
    ),
    UNIFORM_VALVE(
        "UNIFORM_VALVE",
        "هبوط خطي منتظم بكامل المحبس (Case B: عطل شبكة ري)",
        "Case B • عطل ميكانيكي بالمحبس"
    ),
    SOIL_PH_LOCKUP(
        "SOIL_PH_LOCKUP",
        "اصفرار عام بالحقل (Case C: قلوية التربة وتثبيت العناصر)",
        "Case C • قلوية التربة وتثبيت الحديد"
    ),
    HEALTHY(
        "HEALTHY",
        "غطاء نباتي سليم ومتجانس طيفياً",
        "حالة مثالية • خالٍ من البؤر"
    );

    companion object {
        fun fromCode(code: String): AnomalyPattern =
            entries.find { it.code == code } ?: FOCAL_RADIAL_PATCH
    }
}

enum class SpectralRadarLayer(
    val code: String,
    val shortTitleAr: String,
    val fullTitleAr: String,
    val bandFormula: String,
    val descriptionAr: String
) {
    NDRE(
        "NDRE",
        "NDRE الكلوروفيل المبكر",
        "NDRE (مؤشر الكلوروفيل المبكر - Red Edge B8A/B5)",
        "(B8A - B5) / (B8A + B5)",
        "يكشف الإجهاد الفسيولوجي وتراجع الكلوروفيل قبل ظهور الأعراض بالعين بـ 7 أيام"
    ),
    PSRI(
        "PSRI",
        "PSRI تلف الأنسجة",
        "PSRI (مؤشر تلف وشيخوخة الأنسجة - B4-B3/B6)",
        "(B4 - B3) / B6",
        "يرصد البؤر الفطرية والبكتيرية وتراكم الكاروتينات وموت الأنسجة باللون القرمزي"
    ),
    NDMI(
        "NDMI",
        "NDMI الرطوبة والتغدق",
        "NDMI (مؤشر الرطوبة والتغدق - B8/B11)",
        "(B8 - B11) / (B8 + B11)",
        "يحدد مناطق التغدق المسببة لأعفان الجذور مقابل مناطق العطش وانسداد النقاطات"
    ),
    NDVI(
        "NDVI",
        "NDVI الغطاء العام",
        "NDVI (كثافة الغطاء النباتي العام - B8/B4)",
        "(B8 - B4) / (B8 + B4)",
        "يقيس الكتلة الحيوية الكلية وكثافة المجموع الخضري للمحصول"
    )
}

enum class WhatsAppDirection(val code: String) {
    OUTBOUND_ALERT("OUTBOUND_ALERT"),
    INBOUND_FARMER("INBOUND_FARMER")
}

enum class WhatsAppAlertCategory(val code: String, val badgeAr: String) {
    IRRIGATION_DAILY("IRRIGATION_DAILY", "جدول الري اليومي"),
    SATELLITE_DISEASE_WARNING("SATELLITE_DISEASE_WARNING", "إنذار مبكر بالأمراض Sentinel-2"),
    SOIL_PH_ALERT("SOIL_PH_ALERT", "معالجة قلوية التربة والأحماض")
}

// 1. Table: farmers (Users with RBAC role, account status, and explicit demo isolation)
data class FarmerEntity(
    val id: String,
    val fullName: String,
    val whatsappPhone: String, // +201xxxxxxxxx (Mobile Number, or blank if not provided)
    val gmailAddress: String = "", // Gmail account (blank if not provided)
    val nationalId: String = "", // 14-digit Egyptian National ID (never fabricated; blank unless explicitly provided)
    val region: String,
    val preferredAlertTime: String,
    val role: UserRole = UserRole.FARMER,
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,
    val lastLoginAt: String = "08:00 ص",
    val isDemoAccount: Boolean = false
)

enum class AuthLoginChannel(val labelAr: String, val hintAr: String) {
    MOBILE_NUMBER("رقم الموبايل / واتساب", "+201012345678 أو 01012345678"),
    GMAIL_ACCOUNT("حساب Gmail الإلكتروني", "mahmoud.amalfarm@gmail.com"),
    NATIONAL_ID("الرقم القومي المصري (14 رقم)", "27805141801492")
}

data class EgyptianNationalIdValidation(
    val isValid: Boolean,
    val normalized14Digits: String,
    val birthYearSummaryAr: String,
    val governorateAr: String,
    val statusMessageAr: String
)

// 2. Table: fields
data class FieldEntity(
    val id: String,
    val farmerId: String,
    val fieldName: String,
    val lat: Double,
    val lon: Double,
    val areaFeddans: Float,
    val cropType: String,
    val plantingDate: String,
    val irrigationType: String,
    val flowRateM3Hr: Float
)

// 3. Table: soilProfiles
data class SoilProfileEntity(
    val id: String,
    val fieldId: String,
    val soilTexture: String,
    val soilPh: Float,
    val soilEc: Float,
    val waterEc: Float
)

// 4. Table: satelliteSpectralScans
data class SatelliteSpectralScanEntity(
    val id: String,
    val fieldId: String,
    val scanDate: String,
    val ndviMean: Float,
    val ndreMean: Float,
    val psriMean: Float,
    val ndmiMean: Float,
    val anomalyPattern: AnomalyPattern,
    val infectedAreaFeddans: Float,
    val hotspotCoordinates: String,
    val detectedRiskName: String,
    val confidenceScore: Int
)

// 5. Table: irrigationAndTreatmentLogs
data class IrrigationAndTreatmentLogEntity(
    val id: String,
    val fieldId: String,
    val date: String,
    val et0: Float,
    val kc: Float,
    val waterM3PerFeddan: Float,
    val valveRunMinutes: Int,
    val acidDoseLiters: Float,
    val preventiveSprayAction: String,
    val status: String
)

// 6. Table: whatsappMessages
data class WhatsAppMessageEntity(
    val id: String,
    val farmerId: String,
    val fieldId: String,
    val timestamp: String,
    val direction: WhatsAppDirection,
    val alertCategory: WhatsAppAlertCategory,
    val messageText: String,
    val status: String
)

data class RelationalDatabaseSnapshot(
    val farmers: List<FarmerEntity>,
    val fields: List<FieldEntity>,
    val soilProfiles: List<SoilProfileEntity>,
    val satelliteSpectralScans: List<SatelliteSpectralScanEntity>,
    val irrigationAndTreatmentLogs: List<IrrigationAndTreatmentLogEntity>,
    val whatsappMessages: List<WhatsAppMessageEntity>,
    val systemAuditEvents: List<SystemAuditEvent> = emptyList(),
    val activeFarmerId: String
)

class DatabaseService(context: Context? = null) {

    private val prefs = context?.getSharedPreferences("agrosight_multi_tenant_db", Context.MODE_PRIVATE)

    private var farmers = mutableListOf<FarmerEntity>()
    private var fields = mutableListOf<FieldEntity>()
    private var soilProfiles = mutableListOf<SoilProfileEntity>()
    private var spectralScans = mutableListOf<SatelliteSpectralScanEntity>()
    private var treatmentLogs = mutableListOf<IrrigationAndTreatmentLogEntity>()
    private var whatsappMessages = mutableListOf<WhatsAppMessageEntity>()
    private var systemAuditEvents = mutableListOf<SystemAuditEvent>()
    private var activeFarmerId: String = "farmer_admin_owner"

    init {
        val loaded = loadFromPersistence()
        if (!loaded || farmers.isEmpty()) {
            seedDefaultEgyptianTenants()
            saveToPersistence()
        } else {
            ensureBootstrappedSuperAdminPresent()
        }
    }

    private fun ensureBootstrappedSuperAdminPresent() {
        val adminEmail = UserRole.BOOTSTRAPPED_ADMIN_EMAIL
        val existingIdx = farmers.indexOfFirst { it.gmailAddress.equals(adminEmail, ignoreCase = true) }
        if (existingIdx < 0) {
            val adminFarmer = FarmerEntity(
                id = "farmer_admin_owner",
                fullName = "م. فاطمة الزهراء (Super Admin)",
                whatsappPhone = "",
                gmailAddress = adminEmail,
                nationalId = "",
                region = "وادي النطرون",
                preferredAlertTime = "06:00 ص",
                role = UserRole.SUPER_ADMIN,
                accountStatus = AccountStatus.ACTIVE,
                lastLoginAt = currentTimeFormatted(),
                isDemoAccount = false
            )
            farmers.add(0, adminFarmer)
            if (fields.none { it.farmerId == adminFarmer.id }) {
                val adminField = FieldEntity(
                    id = "field_admin_1",
                    farmerId = adminFarmer.id,
                    fieldName = "محطة أبحاث أجرو سايت المركزية - وادي النطرون",
                    lat = 30.4021,
                    lon = 30.3385,
                    areaFeddans = 120.0f,
                    cropType = "بطاطس",
                    plantingDate = LocalDate.now().minusDays(70).toString(),
                    irrigationType = "تنقيط ذكي + بيفوت (Drip & Pivot)",
                    flowRateM3Hr = 18.0f
                )
                val adminSoil = SoilProfileEntity(
                    id = "soil_admin_1",
                    fieldId = adminField.id,
                    soilTexture = "رملية طميية (Sandy Loam)",
                    soilPh = 7.4f,
                    soilEc = 1.9f,
                    waterEc = 1.1f
                )
                val adminScan = SatelliteSpectralScanEntity(
                    id = "scan_admin_1",
                    fieldId = adminField.id,
                    scanDate = LocalDate.now().toString(),
                    ndviMean = 0.77f,
                    ndreMean = 0.42f,
                    psriMean = 0.09f,
                    ndmiMean = 0.41f,
                    anomalyPattern = AnomalyPattern.FOCAL_RADIAL_PATCH,
                    infectedAreaFeddans = 0.25f,
                    hotspotCoordinates = "محبس 3 - القطاع التجريبي (30.4028°N, 30.3392°E)",
                    detectedRiskName = "رصد مبكر لبؤرة الندوة المتأخرة (Phytophthora infestans)",
                    confidenceScore = 93
                )
                fields.add(0, adminField)
                soilProfiles.add(0, adminSoil)
                spectralScans.add(0, adminScan)
            }
            saveToPersistence()
        } else if (farmers[existingIdx].role != UserRole.SUPER_ADMIN) {
            farmers[existingIdx] = farmers[existingIdx].copy(role = UserRole.SUPER_ADMIN, accountStatus = AccountStatus.ACTIVE)
            saveToPersistence()
        }
        if (systemAuditEvents.isEmpty()) {
            seedDefaultAuditEvents()
        }
    }

    fun getSnapshot(): RelationalDatabaseSnapshot {
        return RelationalDatabaseSnapshot(
            farmers = farmers.toList(),
            fields = fields.toList(),
            soilProfiles = soilProfiles.toList(),
            satelliteSpectralScans = spectralScans.toList(),
            irrigationAndTreatmentLogs = treatmentLogs.toList(),
            whatsappMessages = whatsappMessages.toList(),
            systemAuditEvents = systemAuditEvents.toList(),
            activeFarmerId = activeFarmerId
        )
    }

    fun computeSystemWideAnalytics(): SystemWideAnalyticsSummary {
        val totalUsers = farmers.size
        val activeUsers = farmers.count { it.accountStatus == AccountStatus.ACTIVE }
        val suspendedUsers = farmers.count { it.accountStatus == AccountStatus.SUSPENDED }
        val superAdmins = farmers.count { it.role == UserRole.SUPER_ADMIN }
        val agronomists = farmers.count { it.role == UserRole.AGRONOMIST_ADMIN }
        val farmerCount = farmers.count { it.role == UserRole.FARMER }
        val totalArea = fields.sumOf { it.areaFeddans.toDouble() }.toFloat()
        val totalDailyWater = fields.sumOf { (it.areaFeddans * 24.5).toDouble() }.toFloat()
        val avgNdvi = if (spectralScans.isNotEmpty()) {
            spectralScans.map { it.ndviMean }.average().toFloat()
        } else 0.72f
        val avgPh = if (soilProfiles.isNotEmpty()) {
            soilProfiles.map { it.soilPh }.average().toFloat()
        } else 7.7f
        val activeAlerts = spectralScans.count { it.anomalyPattern != AnomalyPattern.HEALTHY }

        return SystemWideAnalyticsSummary(
            totalUsersCount = totalUsers,
            activeUsersCount = activeUsers,
            suspendedUsersCount = suspendedUsers,
            superAdminsCount = superAdmins,
            agronomistsCount = agronomists,
            farmersCount = farmerCount,
            totalFieldsCount = fields.size,
            totalAreaFeddans = totalArea,
            totalDailyWaterM3 = totalDailyWater,
            avgSystemNdvi = avgNdvi,
            avgSystemSoilPh = avgPh,
            activeDiseaseAlertsCount = activeAlerts,
            totalWhatsAppLogsCount = whatsappMessages.size,
            totalTreatmentLogsCount = treatmentLogs.size,
            totalAuditEventsCount = systemAuditEvents.size
        )
    }

    fun recordSystemAuditEvent(
        actionCategory: String,
        summaryAr: String,
        targetEntityId: String = activeFarmerId
    ): RelationalDatabaseSnapshot {
        val actor = farmers.find { it.id == activeFarmerId } ?: farmers.firstOrNull()
        val evt = SystemAuditEvent(
            id = "audit_${System.currentTimeMillis()}_${systemAuditEvents.size}",
            timestamp = currentTimeFormatted(),
            actorName = actor?.fullName ?: "نظام أجرو سايت",
            actorEmail = actor?.gmailAddress ?: UserRole.BOOTSTRAPPED_ADMIN_EMAIL,
            actorRole = actor?.role ?: UserRole.SUPER_ADMIN,
            actionCategory = actionCategory,
            summaryAr = summaryAr,
            targetEntityId = targetEntityId
        )
        systemAuditEvents.add(0, evt)
        if (systemAuditEvents.size > 100) {
            systemAuditEvents = systemAuditEvents.take(100).toMutableList()
        }
        saveToPersistence()
        return getSnapshot()
    }

    // =========================================================================
    // ADMIN PORTAL & RBAC MANAGEMENT OPERATIONS
    // =========================================================================

    fun adminUpdateUserRole(targetFarmerId: String, newRole: UserRole): RelationalDatabaseSnapshot {
        val idx = farmers.indexOfFirst { it.id == targetFarmerId }
        if (idx >= 0) {
            val target = farmers[idx]
            // Prevent demoting the bootstrapped owner Super Admin account
            if (target.gmailAddress.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) &&
                newRole != UserRole.SUPER_ADMIN
            ) {
                recordSystemAuditEvent(
                    actionCategory = "SECURITY_GUARD",
                    summaryAr = "رفض محاولة خفض صلاحية المالك الأساسي (${UserRole.BOOTSTRAPPED_ADMIN_EMAIL})",
                    targetEntityId = targetFarmerId
                )
                return getSnapshot()
            }
            farmers[idx] = target.copy(role = newRole)
            recordSystemAuditEvent(
                actionCategory = "RBAC_ADMIN",
                summaryAr = "تعديل صلاحية المستخدم (${target.fullName}) إلى [${newRole.titleAr}]",
                targetEntityId = targetFarmerId
            )
            saveToPersistence()
        }
        return getSnapshot()
    }

    fun adminUpdateUserStatus(targetFarmerId: String, newStatus: AccountStatus): RelationalDatabaseSnapshot {
        val idx = farmers.indexOfFirst { it.id == targetFarmerId }
        if (idx >= 0) {
            val target = farmers[idx]
            // Prevent suspending the bootstrapped owner Super Admin account
            if (target.gmailAddress.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) &&
                newStatus != AccountStatus.ACTIVE
            ) {
                recordSystemAuditEvent(
                    actionCategory = "SECURITY_GUARD",
                    summaryAr = "رفض محاولة إيقاف حساب المالك الأساسي (${UserRole.BOOTSTRAPPED_ADMIN_EMAIL})",
                    targetEntityId = targetFarmerId
                )
                return getSnapshot()
            }
            farmers[idx] = target.copy(accountStatus = newStatus)
            recordSystemAuditEvent(
                actionCategory = "RBAC_ADMIN",
                summaryAr = "تغيير حالة حساب (${target.fullName}) إلى [${newStatus.labelAr}]",
                targetEntityId = targetFarmerId
            )
            saveToPersistence()
        }
        return getSnapshot()
    }

    fun adminDeleteUserAccount(targetFarmerId: String): RelationalDatabaseSnapshot {
        val target = farmers.find { it.id == targetFarmerId } ?: return getSnapshot()
        // Protect bootstrapped owner Super Admin from accidental self-deletion
        if (target.gmailAddress.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) || farmers.size <= 1) {
            return getSnapshot()
        }
        val removedFieldIds = fields.filter { it.farmerId == targetFarmerId }.map { it.id }.toSet()
        farmers.removeAll { it.id == targetFarmerId }
        fields.removeAll { it.farmerId == targetFarmerId }
        soilProfiles.removeAll { it.fieldId in removedFieldIds }
        spectralScans.removeAll { it.fieldId in removedFieldIds }
        treatmentLogs.removeAll { it.fieldId in removedFieldIds }
        whatsappMessages.removeAll { it.farmerId == targetFarmerId }

        if (activeFarmerId == targetFarmerId) {
            activeFarmerId = farmers.first().id
        }
        recordSystemAuditEvent(
            actionCategory = "RBAC_ADMIN",
            summaryAr = "حذف حساب المستخدم (${target.fullName}) وجميع سجلات الحقول المرتبطة به",
            targetEntityId = targetFarmerId
        )
        saveToPersistence()
        return getSnapshot()
    }

    fun adminDeleteTreatmentLog(logId: String): RelationalDatabaseSnapshot {
        treatmentLogs.removeAll { it.id == logId }
        recordSystemAuditEvent(
            actionCategory = "RBAC_ADMIN",
            summaryAr = "حذف سجل عملية ري ومعالجة ($logId) من قِبل الإدارة",
            targetEntityId = logId
        )
        saveToPersistence()
        return getSnapshot()
    }

    fun adminDeleteWhatsAppMessage(messageId: String): RelationalDatabaseSnapshot {
        whatsappMessages.removeAll { it.id == messageId }
        recordSystemAuditEvent(
            actionCategory = "RBAC_ADMIN",
            summaryAr = "حذف سجل رسالة واتساب ($messageId) من قاعدة البيانات",
            targetEntityId = messageId
        )
        saveToPersistence()
        return getSnapshot()
    }

    fun switchFarmerAccount(farmerId: String): RelationalDatabaseSnapshot {
        val idx = farmers.indexOfFirst { it.id == farmerId }
        if (idx >= 0) {
            activeFarmerId = farmerId
            farmers[idx] = farmers[idx].copy(lastLoginAt = currentTimeFormatted())
            recordSystemAuditEvent(
                actionCategory = "AUTH_LOGIN",
                summaryAr = "تبديل الجلسة النشطة إلى حساب (${farmers[idx].fullName} - ${farmers[idx].role.badgeAr})",
                targetEntityId = farmerId
            )
            saveToPersistence()
        }
        return getSnapshot()
    }

    /**
     * Authenticates an existing farmer or auto-creates a verified profile using:
     * - Mobile Number (+201xxxxxxxxx / 01xxxxxxxxx)
     * - Gmail Address (user@gmail.com)
     * - 14-digit Egyptian National ID (الرقم القومي)
     */
    fun authenticateBySingleIdentifier(
        channel: AuthLoginChannel,
        rawIdentifier: String
    ): Triple<Boolean, String, RelationalDatabaseSnapshot> {
        val trimmed = rawIdentifier.trim()
        if (trimmed.isBlank()) {
            return Triple(false, "يرجى إدخال ${channel.labelAr} للمتابعة", getSnapshot())
        }

        val matchedFarmer: FarmerEntity? = when (channel) {
            AuthLoginChannel.MOBILE_NUMBER -> {
                val normPhone = normalizeEgyptianWhatsApp(trimmed)
                farmers.find {
                    it.whatsappPhone == normPhone ||
                        it.whatsappPhone.filter { c -> c.isDigit() }.endsWith(trimmed.filter { c -> c.isDigit() }.takeLast(10))
                }
            }
            AuthLoginChannel.GMAIL_ACCOUNT -> {
                val cleanEmail = normalizeGmailAddress(trimmed)
                farmers.find { it.gmailAddress.equals(cleanEmail, ignoreCase = true) }
            }
            AuthLoginChannel.NATIONAL_ID -> {
                val digitsOnly = trimmed.filter { it.isDigit() }
                farmers.find { it.nationalId == digitsOnly }
            }
        }

        if (matchedFarmer != null) {
            if (matchedFarmer.accountStatus == AccountStatus.SUSPENDED) {
                recordSystemAuditEvent(
                    actionCategory = "AUTH_BLOCKED",
                    summaryAr = "حظر محاولة تسجيل دخول لحساب موقوف (${matchedFarmer.fullName}) عبر ${channel.labelAr}",
                    targetEntityId = matchedFarmer.id
                )
                return Triple(
                    false,
                    "⛔ هذا الحساب موقوف مؤقتاً من قِبل إدارة النظام (SUSPENDED). يرجى التواصل مع مدير النظام.",
                    getSnapshot()
                )
            }
            activeFarmerId = matchedFarmer.id
            saveToPersistence()
            val msg = "تم تسجيل الدخول بنجاح باسم (${matchedFarmer.fullName}) عبر ${channel.labelAr} ✅"
            return Triple(true, msg, getSnapshot())
        }

        // Validate input format before auto-registering a profile if not found (never fabricate missing PII)
        return when (channel) {
            AuthLoginChannel.MOBILE_NUMBER -> {
                val digits = trimmed.filter { it.isDigit() }
                if (digits.length < 10) {
                    Triple(false, "رقم الموبايل غير مكتمل (يجب أن يتكون من 11 رقماً مصرياً يبدأ بـ 01)", getSnapshot())
                } else {
                    val normPhone = normalizeEgyptianWhatsApp(trimmed)
                    val snap = loginOrRegisterByWhatsApp(
                        fullName = "المزارع (${normPhone.takeLast(4)})",
                        whatsappPhoneInput = normPhone,
                        gmailInput = "",
                        nationalIdInput = "",
                        regionName = "وادي النطرون",
                        cropNameAr = "طماطم",
                        areaFeddans = 50f
                    )
                    Triple(true, "تم تفعيل وتسجيل الدخول برقم الموبايل ($normPhone) بنجاح ✅", snap)
                }
            }

            AuthLoginChannel.GMAIL_ACCOUNT -> {
                val cleanGmail = normalizeGmailAddress(trimmed)
                if (!cleanGmail.contains("@") || !cleanGmail.endsWith(".com", ignoreCase = true)) {
                    Triple(false, "يرجى إدخال بريد Gmail صحيح (مثال: name@gmail.com)", getSnapshot())
                } else {
                    val localPart = cleanGmail.substringBefore("@").replace(".", " ")
                    val snap = loginOrRegisterByWhatsApp(
                        fullName = "مزارع ($localPart)",
                        whatsappPhoneInput = "",
                        gmailInput = cleanGmail,
                        nationalIdInput = "",
                        regionName = "الصالحية الجديدة",
                        cropNameAr = "بطاطس",
                        areaFeddans = 60f
                    )
                    Triple(true, "تم مصادقة حساب Google/Gmail ($cleanGmail) وتسجيل الدخول بنجاح ✅", snap)
                }
            }

            AuthLoginChannel.NATIONAL_ID -> {
                val natValidation = validateEgyptianNationalId(trimmed)
                if (!natValidation.isValid) {
                    Triple(false, natValidation.statusMessageAr, getSnapshot())
                } else {
                    val last4 = natValidation.normalized14Digits.takeLast(4)
                    val snap = loginOrRegisterByWhatsApp(
                        fullName = "مزارع موثق (${natValidation.governorateAr} #$last4)",
                        whatsappPhoneInput = "",
                        gmailInput = "",
                        nationalIdInput = natValidation.normalized14Digits,
                        regionName = "وادي النطرون",
                        cropNameAr = "قمح",
                        areaFeddans = 65f
                    )
                    Triple(true, "تم التحقق من الرقم القومي المصري (${natValidation.governorateAr}) وتسجيل الدخول ✅", snap)
                }
            }
        }
    }

    fun loginOrRegisterByWhatsApp(
        fullName: String,
        whatsappPhoneInput: String,
        gmailInput: String = "",
        nationalIdInput: String = "",
        regionName: String,
        cropNameAr: String,
        areaFeddans: Float,
        requestedRole: UserRole? = null
    ): RelationalDatabaseSnapshot {
        val normalizedPhone = if (whatsappPhoneInput.isBlank()) "" else normalizeEgyptianWhatsApp(whatsappPhoneInput)
        val normalizedGmail = if (gmailInput.isBlank()) "" else normalizeGmailAddress(gmailInput)
        val normalizedNationalId = if (nationalIdInput.isBlank()) "" else normalizeEgyptianNationalId(nationalIdInput)

        val existingIdx = farmers.indexOfFirst {
            (normalizedPhone.isNotBlank() && it.whatsappPhone == normalizedPhone) ||
                (normalizedGmail.isNotBlank() && it.gmailAddress.equals(normalizedGmail, ignoreCase = true)) ||
                (normalizedNationalId.length == 14 && it.nationalId == normalizedNationalId)
        }
        if (existingIdx >= 0) {
            val existing = farmers[existingIdx]
            val updatedFarmer = existing.copy(
                fullName = if (fullName.isNotBlank()) fullName.trim() else existing.fullName,
                whatsappPhone = if (normalizedPhone.isNotBlank()) normalizedPhone else existing.whatsappPhone,
                gmailAddress = if (normalizedGmail.isNotBlank()) normalizedGmail else existing.gmailAddress,
                nationalId = if (normalizedNationalId.length == 14) normalizedNationalId else existing.nationalId,
                region = regionName.ifBlank { existing.region },
                isDemoAccount = false
            )
            farmers[existingIdx] = updatedFarmer
            activeFarmerId = updatedFarmer.id
            saveToPersistence()
            return getSnapshot()
        }

        val newFarmerId = "farmer_${farmers.size + 1}_${UUID.randomUUID().toString().take(4)}"
        val newFieldId = "field_${fields.size + 1}"
        val cleanName = if (fullName.isBlank()) {
            val suffix = normalizedPhone.takeLast(4).ifBlank { normalizedNationalId.takeLast(4).ifBlank { "جديد" } }
            "المزارع الذكي ($suffix)"
        } else {
            fullName.trim()
        }

        val resolvedRole = if (normalizedGmail.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)) {
            UserRole.SUPER_ADMIN
        } else {
            requestedRole ?: UserRole.FARMER
        }
        val newFarmer = FarmerEntity(
            id = newFarmerId,
            fullName = cleanName,
            whatsappPhone = normalizedPhone,
            gmailAddress = normalizedGmail,
            nationalId = normalizedNationalId,
            region = regionName,
            preferredAlertTime = "06:30 ص",
            role = resolvedRole,
            accountStatus = AccountStatus.ACTIVE,
            lastLoginAt = currentTimeFormatted(),
            isDemoAccount = false
        )
        val newField = FieldEntity(
            id = newFieldId,
            farmerId = newFarmerId,
            fieldName = "مزرعة $cleanName - $regionName",
            lat = 30.3833,
            lon = 30.3500,
            areaFeddans = areaFeddans.coerceIn(5f, 2000f),
            cropType = cropNameAr,
            plantingDate = LocalDate.now().minusDays(65).toString(),
            irrigationType = "تنقيط (Drip)",
            flowRateM3Hr = 16.0f
        )
        val newSoil = SoilProfileEntity(
            id = "soil_${soilProfiles.size + 1}",
            fieldId = newFieldId,
            soilTexture = "رملية (Sandy)",
            soilPh = 7.7f,
            soilEc = 2.3f,
            waterEc = 1.3f
        )
        val newScan = SatelliteSpectralScanEntity(
            id = "scan_${spectralScans.size + 1}",
            fieldId = newFieldId,
            scanDate = LocalDate.now().toString(),
            ndviMean = 0.68f,
            ndreMean = 0.34f,
            psriMean = 0.19f,
            ndmiMean = 0.36f,
            anomalyPattern = AnomalyPattern.FOCAL_RADIAL_PATCH,
            infectedAreaFeddans = 0.35f,
            hotspotCoordinates = "محبس 3 - الركن الشمالي الشرقي (30.3841°N, 30.3512°E)",
            detectedRiskName = "بؤرة مبكرة لمرض اللفحة المتأخرة (Phytophthora infestans)",
            confidenceScore = 89
        )
        val welcomeMsg = WhatsAppMessageEntity(
            id = "wa_${whatsappMessages.size + 1}",
            farmerId = newFarmerId,
            fieldId = newFieldId,
            timestamp = currentTimeFormatted(),
            direction = WhatsAppDirection.OUTBOUND_ALERT,
            alertCategory = WhatsAppAlertCategory.SATELLITE_DISEASE_WARNING,
            messageText = buildProactiveDiseaseWhatsAppText(
                farmerName = cleanName,
                fieldName = newField.fieldName,
                scan = newScan,
                humidityPct = 76,
                reducedHours = 1.6f,
                preventiveRecommendation = "رش بؤرة المحبس 3 فقط بمبيد مانكوزيب + ميتالاكسيل بمعدل وقائي مع وقف التسميد الآزوتي لمدة 48 ساعة"
            ),
            status = "DELIVERED"
        )

        farmers.add(newFarmer)
        fields.add(newField)
        soilProfiles.add(newSoil)
        spectralScans.add(0, newScan)
        whatsappMessages.add(welcomeMsg)
        activeFarmerId = newFarmerId
        saveToPersistence()
        return getSnapshot()
    }

    fun updateActiveFieldAndSoil(
        fieldName: String,
        lat: Double,
        lon: Double,
        areaFeddans: Float,
        cropType: String,
        plantingDate: String,
        irrigationType: String,
        flowRateM3Hr: Float,
        soilTexture: String,
        soilPh: Float,
        soilEc: Float,
        waterEc: Float
    ) {
        val fieldIdx = fields.indexOfFirst { it.farmerId == activeFarmerId }
        if (fieldIdx >= 0) {
            val existingField = fields[fieldIdx]
            fields[fieldIdx] = existingField.copy(
                fieldName = fieldName,
                lat = lat,
                lon = lon,
                areaFeddans = areaFeddans,
                cropType = cropType,
                plantingDate = plantingDate,
                irrigationType = irrigationType,
                flowRateM3Hr = flowRateM3Hr
            )
            val soilIdx = soilProfiles.indexOfFirst { it.fieldId == existingField.id }
            if (soilIdx >= 0) {
                soilProfiles[soilIdx] = soilProfiles[soilIdx].copy(
                    soilTexture = soilTexture,
                    soilPh = soilPh,
                    soilEc = soilEc,
                    waterEc = waterEc
                )
            }
            saveToPersistence()
        }
    }

    fun recordSatelliteScan(scan: SatelliteSpectralScanEntity): RelationalDatabaseSnapshot {
        spectralScans.removeAll { it.fieldId == scan.fieldId }
        spectralScans.add(0, scan)
        saveToPersistence()
        return getSnapshot()
    }

    fun appendIrrigationTreatmentLog(log: IrrigationAndTreatmentLogEntity): RelationalDatabaseSnapshot {
        treatmentLogs.add(0, log)
        saveToPersistence()
        return getSnapshot()
    }

    fun appendWhatsAppMessage(msg: WhatsAppMessageEntity): RelationalDatabaseSnapshot {
        whatsappMessages.add(msg)
        saveToPersistence()
        return getSnapshot()
    }

    private fun seedDefaultEgyptianTenants() {
        farmers.clear()
        fields.clear()
        soilProfiles.clear()
        spectralScans.clear()
        treatmentLogs.clear()
        whatsappMessages.clear()
        systemAuditEvents.clear()

        // 0. Bootstrapped Owner Super Admin (elzahraafatma288@gmail.com) - no fabricated phone or National ID
        val fAdmin = FarmerEntity(
            id = "farmer_admin_owner",
            fullName = "م. فاطمة الزهراء (Super Admin)",
            whatsappPhone = "",
            gmailAddress = UserRole.BOOTSTRAPPED_ADMIN_EMAIL,
            nationalId = "",
            region = "وادي النطرون",
            preferredAlertTime = "06:00 ص",
            role = UserRole.SUPER_ADMIN,
            accountStatus = AccountStatus.ACTIVE,
            lastLoginAt = "08:00 ص",
            isDemoAccount = false
        )
        val fieldAdmin = FieldEntity(
            id = "field_admin_1",
            farmerId = "farmer_admin_owner",
            fieldName = "محطة أبحاث أجرو سايت المركزية - وادي النطرون",
            lat = 30.4021,
            lon = 30.3385,
            areaFeddans = 120.0f,
            cropType = "بطاطس",
            plantingDate = LocalDate.now().minusDays(70).toString(),
            irrigationType = "تنقيط ذكي + بيفوت (Drip & Pivot)",
            flowRateM3Hr = 18.0f
        )
        val soilAdmin = SoilProfileEntity(
            id = "soil_admin_1",
            fieldId = "field_admin_1",
            soilTexture = "رملية طميية (Sandy Loam)",
            soilPh = 7.4f,
            soilEc = 1.9f,
            waterEc = 1.1f
        )
        val scanAdmin = SatelliteSpectralScanEntity(
            id = "scan_admin_1",
            fieldId = "field_admin_1",
            scanDate = LocalDate.now().toString(),
            ndviMean = 0.77f,
            ndreMean = 0.42f,
            psriMean = 0.09f,
            ndmiMean = 0.41f,
            anomalyPattern = AnomalyPattern.FOCAL_RADIAL_PATCH,
            infectedAreaFeddans = 0.25f,
            hotspotCoordinates = "محبس 3 - القطاع التجريبي (30.4028°N, 30.3392°E)",
            detectedRiskName = "رصد مبكر لبؤرة الندوة المتأخرة (Phytophthora infestans)",
            confidenceScore = 93
        )
        farmers.add(fAdmin)
        fields.add(fieldAdmin)
        soilProfiles.add(soilAdmin)
        spectralScans.add(scanAdmin)

        // Demo Sample Account 1: Wadi El Natrun (Tomato - Case A: Focal Late Blight Outbreak)
        val f1 = FarmerEntity(
            id = "farmer_1",
            fullName = "الحاج محمود عبد الرحمن (نموذج استرشادي)",
            whatsappPhone = "+201012345678",
            gmailAddress = "mahmoud.amalfarm@gmail.com",
            nationalId = "",
            region = "وادي النطرون",
            preferredAlertTime = "06:00 ص",
            role = UserRole.FARMER,
            accountStatus = AccountStatus.ACTIVE,
            lastLoginAt = "07:30 ص",
            isDemoAccount = true
        )
        val field1 = FieldEntity(
            id = "field_1",
            farmerId = "farmer_1",
            fieldName = "مزرعة الأمل النموذجية - وادي النطرون",
            lat = 30.3833,
            lon = 30.3500,
            areaFeddans = 60.0f,
            cropType = "طماطم",
            plantingDate = LocalDate.now().minusDays(68).toString(),
            irrigationType = "تنقيط (Drip)",
            flowRateM3Hr = 16.0f
        )
        val soil1 = SoilProfileEntity(
            id = "soil_1",
            fieldId = "field_1",
            soilTexture = "رملية (Sandy)",
            soilPh = 7.8f,
            soilEc = 2.4f,
            waterEc = 1.4f
        )
        val scan1 = SatelliteSpectralScanEntity(
            id = "scan_1",
            fieldId = "field_1",
            scanDate = LocalDate.now().toString(),
            ndviMean = 0.67f,
            ndreMean = 0.31f,
            psriMean = 0.22f,
            ndmiMean = 0.39f,
            anomalyPattern = AnomalyPattern.FOCAL_RADIAL_PATCH,
            infectedAreaFeddans = 0.40f,
            hotspotCoordinates = "محبس 3 - الركن الشمالي الشرقي (30.3842°N, 30.3514°E)",
            detectedRiskName = "اللفحة المتأخرة في الطماطم (Phytophthora infestans)",
            confidenceScore = 89
        )

        // Demo Sample Account 2: New Salhia (Potato - Case B: Uniform Valve Irrigation Blockage)
        val f2 = FarmerEntity(
            id = "farmer_2",
            fullName = "المهندس طارق السعيد (نموذج استرشادي)",
            whatsappPhone = "+201123456789",
            gmailAddress = "tarek.salhia.agro@gmail.com",
            nationalId = "",
            region = "الصالحية الجديدة",
            preferredAlertTime = "06:30 ص",
            role = UserRole.AGRONOMIST_ADMIN,
            accountStatus = AccountStatus.ACTIVE,
            lastLoginAt = "07:45 ص",
            isDemoAccount = true
        )
        val field2 = FieldEntity(
            id = "field_2",
            farmerId = "farmer_2",
            fieldName = "مزرعة النور التصديرية - الصالحية الجديدة",
            lat = 30.6300,
            lon = 31.9600,
            areaFeddans = 90.0f,
            cropType = "بطاطس",
            plantingDate = LocalDate.now().minusDays(58).toString(),
            irrigationType = "بيفوت محوري (Center Pivot)",
            flowRateM3Hr = 24.0f
        )
        val soil2 = SoilProfileEntity(
            id = "soil_2",
            fieldId = "field_2",
            soilTexture = "طميية صفراء (Loamy)",
            soilPh = 7.3f,
            soilEc = 1.6f,
            waterEc = 1.0f
        )
        val scan2 = SatelliteSpectralScanEntity(
            id = "scan_2",
            fieldId = "field_2",
            scanDate = LocalDate.now().toString(),
            ndviMean = 0.61f,
            ndreMean = 0.42f,
            psriMean = 0.08f,
            ndmiMean = 0.11f,
            anomalyPattern = AnomalyPattern.UNIFORM_VALVE,
            infectedAreaFeddans = 15.0f,
            hotspotCoordinates = "محبس 2 بالكامل - نمط هندسي منتظم (30.6311°N, 31.9615°E)",
            detectedRiskName = "انسداد ميكانيكي أو هبوط ضغط في المحبس رقم 2 (ليس مرضاً فطرياً)",
            confidenceScore = 94
        )

        // Demo Sample Account 3: Farafra Oasis (Wheat - Case C: Soil pH > 7.5 Nutrient Lock-up)
        val f3 = FarmerEntity(
            id = "farmer_3",
            fullName = "الحاج عبد السلام الفرافرة (نموذج استرشادي)",
            whatsappPhone = "+201234567890",
            gmailAddress = "abdelsalam.farafra@gmail.com",
            nationalId = "",
            region = "الفرافرة",
            preferredAlertTime = "07:00 ص",
            isDemoAccount = true
        )
        val field3 = FieldEntity(
            id = "field_3",
            farmerId = "farmer_3",
            fieldName = "حقول سنابل الوادي - الفرافرة",
            lat = 27.0567,
            lon = 27.9703,
            areaFeddans = 120.0f,
            cropType = "قمح",
            plantingDate = LocalDate.now().minusDays(75).toString(),
            irrigationType = "بيفوت محوري (Center Pivot)",
            flowRateM3Hr = 24.0f
        )
        val soil3 = SoilProfileEntity(
            id = "soil_3",
            fieldId = "field_3",
            soilTexture = "رملية (Sandy)",
            soilPh = 8.3f,
            soilEc = 3.1f,
            waterEc = 1.7f
        )
        val scan3 = SatelliteSpectralScanEntity(
            id = "scan_3",
            fieldId = "field_3",
            scanDate = LocalDate.now().toString(),
            ndviMean = 0.58f,
            ndreMean = 0.26f,
            psriMean = 0.09f,
            ndmiMean = 0.29f,
            anomalyPattern = AnomalyPattern.SOIL_PH_LOCKUP,
            infectedAreaFeddans = 120.0f,
            hotspotCoordinates = "امتداد الحقل بالكامل (pH = 8.3 قلوية مرتفعة)",
            detectedRiskName = "اصفرار فسيولوجي ناتج عن قلوية التربة وتثبيت الحديد والزنك والفوسفور",
            confidenceScore = 92
        )

        farmers.addAll(listOf(f1, f2, f3))
        fields.addAll(listOf(field1, field2, field3))
        soilProfiles.addAll(listOf(soil1, soil2, soil3))
        spectralScans.addAll(listOf(scan1, scan2, scan3))

        // Initial Treatment Logs
        treatmentLogs.add(
            IrrigationAndTreatmentLogEntity(
                id = "log_1",
                fieldId = "field_1",
                date = LocalDate.now().toString(),
                et0 = 5.6f,
                kc = 1.15f,
                waterM3PerFeddan = 28.4f,
                valveRunMinutes = 106,
                acidDoseLiters = 3.5f,
                preventiveSprayAction = "خفض ري المحبس 3 بنسبة 25% + رش بؤري (0.4 فدان) ضد اللفحة المتأخرة",
                status = "ACTIVE_PROACTIVE"
            )
        )
        treatmentLogs.add(
            IrrigationAndTreatmentLogEntity(
                id = "log_2",
                fieldId = "field_2",
                date = LocalDate.now().toString(),
                et0 = 5.4f,
                kc = 1.15f,
                waterM3PerFeddan = 31.2f,
                valveRunMinutes = 78,
                acidDoseLiters = 2.0f,
                preventiveSprayAction = "فحص فلاتر وضغط المحبس رقم 2 وغسيل الخطوط بالحامض (لا ترش مبيد فطري)",
                status = "VALVE_CHECK_REQUIRED"
            )
        )
        treatmentLogs.add(
            IrrigationAndTreatmentLogEntity(
                id = "log_3",
                fieldId = "field_3",
                date = LocalDate.now().toString(),
                et0 = 6.3f,
                kc = 1.15f,
                waterM3PerFeddan = 36.8f,
                valveRunMinutes = 92,
                acidDoseLiters = 6.0f,
                preventiveSprayAction = "حقن حامض فوسفوريك + حديد مخلبي EDDHA 6% (تنبيه: لا ترش مبيدات فطرية)",
                status = "SOIL_PH_TREATMENT"
            )
        )

        // Initial Proactive WhatsApp Alerts for each Farmer
        whatsappMessages.add(
            WhatsAppMessageEntity(
                id = "wa_1",
                farmerId = "farmer_1",
                fieldId = "field_1",
                timestamp = "06:05 ص",
                direction = WhatsAppDirection.OUTBOUND_ALERT,
                alertCategory = WhatsAppAlertCategory.SATELLITE_DISEASE_WARNING,
                messageText = buildProactiveDiseaseWhatsAppText(
                    farmerName = f1.fullName,
                    fieldName = field1.fieldName,
                    scan = scan1,
                    humidityPct = 78,
                    reducedHours = 1.4f,
                    preventiveRecommendation = "رش بؤرة المحبس 3 فقط (0.4 فدان) بمبيد فطري متخصص (ميتالاكسيل + مانكوزيب) وتجنب رش الحقل بالكامل لتوفير 85% من تكلفة المبيدات."
                ),
                status = "READ"
            )
        )
        whatsappMessages.add(
            WhatsAppMessageEntity(
                id = "wa_2",
                farmerId = "farmer_2",
                fieldId = "field_2",
                timestamp = "06:32 ص",
                direction = WhatsAppDirection.OUTBOUND_ALERT,
                alertCategory = WhatsAppAlertCategory.IRRIGATION_DAILY,
                messageText = "🛰️ *تنبيه ذكي من القمر الصناعي (AgroSight) - ${field2.fieldName}*\n" +
                    "يا باشمهندس ${f2.fullName}، رصد القمر الصناعي Sentinel-2 هبوطاً خطياً منتظماً في مؤشر الرطوبة (NDMI = 0.11) مطابقاً تماماً لحدود *المحبس رقم 2* بالكامل مع ثبات مؤشر تلف الأنسجة (PSRI = 0.08).\n" +
                    "🔧 *التشخيص المؤكد (94%):* عطل ميكانيكي أو انسداد جزئي في فلاتر/رشاشات المحبس رقم 2 وليس إصابة مرضية فطرية.\n" +
                    "💧 *الإجراء الفوري:* مراجعة ضغط التشغيل للمحبس رقم 2 وغسيل الخطوط، وعدم رش أي مبيدات فطرية.",
                status = "READ"
            )
        )
        whatsappMessages.add(
            WhatsAppMessageEntity(
                id = "wa_3",
                farmerId = "farmer_3",
                fieldId = "field_3",
                timestamp = "07:02 ص",
                direction = WhatsAppDirection.OUTBOUND_ALERT,
                alertCategory = WhatsAppAlertCategory.SOIL_PH_ALERT,
                messageText = "🛰️ *تشخيص طيفي وكيميائي (AgroSight) - ${field3.fieldName}*\n" +
                    "يا ${f3.fullName}، رصد القمر الصناعي Sentinel-2 انخفاضاً عاماً في مؤشر الكلوروفيل (NDRE = 0.26) بطول الحقل مع استقرار رطوبة الأوراق (NDMI = 0.29) وارتفاع قلوية التربة (pH = 8.3).\n" +
                    "🧪 *التشخيص المؤكد (92%):* اصفرار ناتج عن قلوية التربة المرتفعة وتثبيت عناصر الحديد والزنك والفوسفور (وليس مرض الصدأ).\n" +
                    "💧 *توصية التسميد الحقني اليوم:* حقن 6 لتر/فدان حامض فوسفوريك 85% مع مياه الري + 1.5 كجم حديد مخلبي EDDHA 6%، ونحذر من رش مبيدات فطرية غير ضرورية.",
                status = "READ"
            )
        )

        seedDefaultAuditEvents()
    }

    private fun seedDefaultAuditEvents() {
        systemAuditEvents.clear()
        systemAuditEvents.addAll(
            listOf(
                SystemAuditEvent(
                    id = "audit_init_1",
                    timestamp = "08:05 ص",
                    actorName = "م. فاطمة الزهراء (Super Admin)",
                    actorEmail = UserRole.BOOTSTRAPPED_ADMIN_EMAIL,
                    actorRole = UserRole.SUPER_ADMIN,
                    actionCategory = "RBAC_ADMIN",
                    summaryAr = "تفعيل بوابة الإدارة الشاملة (Admin Portal) وصلاحيات RBAC لجميع المزارع والحسابات",
                    targetEntityId = "farmer_admin_owner"
                ),
                SystemAuditEvent(
                    id = "audit_init_2",
                    timestamp = "07:48 ص",
                    actorName = "المهندس طارق السعيد",
                    actorEmail = "tarek.salhia.agro@gmail.com",
                    actorRole = UserRole.AGRONOMIST_ADMIN,
                    actionCategory = "SATELLITE_SCAN",
                    summaryAr = "فحص طيفي Sentinel-2 لمزرعة النور التصديرية (الصالحية الجديدة) ورصد انسداد المحبس رقم 2",
                    targetEntityId = "field_2"
                ),
                SystemAuditEvent(
                    id = "audit_init_3",
                    timestamp = "07:15 ص",
                    actorName = "الحاج محمود عبد الرحمن",
                    actorEmail = "mahmoud.amalfarm@gmail.com",
                    actorRole = UserRole.FARMER,
                    actionCategory = "WHATSAPP_WA_ME",
                    summaryAr = "إرسال وتوثيق إنذار اللفحة المتأخرة وتعديل ري المحبس رقم 3 عبر رابط wa.me المباشر",
                    targetEntityId = "field_1"
                )
            )
        )
    }

    fun exportSqlSupabaseSchema(): String {
        val sb = StringBuilder()
        sb.appendLine("-- ===========================================================")
        sb.appendLine("-- AgroSight Multi-Tenant SaaS PostgreSQL / Supabase DDL Schema")
        sb.appendLine("-- ===========================================================")
        sb.appendLine(
            """
            CREATE TABLE farmers (
                id TEXT PRIMARY KEY,
                full_name TEXT NOT NULL,
                whatsapp_phone TEXT UNIQUE NOT NULL,
                gmail_address TEXT UNIQUE NOT NULL,
                national_id VARCHAR(14) UNIQUE NOT NULL,
                region TEXT NOT NULL,
                preferred_alert_time TEXT DEFAULT '06:00 AM'
            );

            CREATE TABLE fields (
                id TEXT PRIMARY KEY,
                farmer_id TEXT REFERENCES farmers(id) ON DELETE CASCADE,
                field_name TEXT NOT NULL,
                lat DOUBLE PRECISION NOT NULL,
                lon DOUBLE PRECISION NOT NULL,
                area_feddans REAL NOT NULL,
                crop_type TEXT NOT NULL,
                planting_date DATE NOT NULL,
                irrigation_type TEXT NOT NULL,
                flow_rate_m3_hr REAL NOT NULL
            );

            CREATE TABLE soil_profiles (
                id TEXT PRIMARY KEY,
                field_id TEXT REFERENCES fields(id) ON DELETE CASCADE,
                soil_texture TEXT NOT NULL,
                soil_ph REAL CHECK (soil_ph BETWEEN 4.0 AND 9.5),
                soil_ec REAL NOT NULL,
                water_ec REAL NOT NULL
            );

            CREATE TABLE satellite_spectral_scans (
                id TEXT PRIMARY KEY,
                field_id TEXT REFERENCES fields(id) ON DELETE CASCADE,
                scan_date DATE NOT NULL,
                ndvi_mean REAL NOT NULL,
                ndre_mean REAL NOT NULL,
                psri_mean REAL NOT NULL,
                ndmi_mean REAL NOT NULL,
                anomaly_pattern TEXT CHECK (anomaly_pattern IN ('UNIFORM_VALVE', 'FOCAL_RADIAL_PATCH', 'SOIL_PH_LOCKUP', 'HEALTHY')),
                infected_area_feddans REAL NOT NULL,
                hotspot_coordinates TEXT NOT NULL,
                detected_risk_name TEXT NOT NULL,
                confidence_score INTEGER NOT NULL
            );

            CREATE TABLE irrigation_and_treatment_logs (
                id TEXT PRIMARY KEY,
                field_id TEXT REFERENCES fields(id) ON DELETE CASCADE,
                date DATE NOT NULL,
                et0 REAL NOT NULL,
                kc REAL NOT NULL,
                water_m3_per_feddan REAL NOT NULL,
                valve_run_minutes INTEGER NOT NULL,
                acid_dose_liters REAL NOT NULL,
                preventive_spray_action TEXT NOT NULL,
                status TEXT NOT NULL
            );

            CREATE TABLE whatsapp_messages (
                id TEXT PRIMARY KEY,
                farmer_id TEXT REFERENCES farmers(id) ON DELETE CASCADE,
                field_id TEXT REFERENCES fields(id) ON DELETE CASCADE,
                timestamp TEXT NOT NULL,
                direction TEXT CHECK (direction IN ('OUTBOUND_ALERT', 'INBOUND_FARMER')),
                alert_category TEXT CHECK (alert_category IN ('IRRIGATION_DAILY', 'SATELLITE_DISEASE_WARNING', 'SOIL_PH_ALERT')),
                message_text TEXT NOT NULL,
                status TEXT NOT NULL
            );
            """.trimIndent()
        )
        sb.appendLine(
            """
            -- Zero-Trust Row-Level Security (RLS) Enforcement
            ALTER TABLE farmers ENABLE ROW LEVEL SECURITY;
            ALTER TABLE fields ENABLE ROW LEVEL SECURITY;
            ALTER TABLE soil_profiles ENABLE ROW LEVEL SECURITY;
            ALTER TABLE satellite_spectral_scans ENABLE ROW LEVEL SECURITY;
            ALTER TABLE irrigation_and_treatment_logs ENABLE ROW LEVEL SECURITY;
            ALTER TABLE whatsapp_messages ENABLE ROW LEVEL SECURITY;
            """.trimIndent()
        )
        sb.appendLine("\n-- Live Multi-Tenant Seeded Records (${farmers.size} Farmers, ${fields.size} Fields, ${spectralScans.size} Spectral Scans):")
        farmers.forEach { f ->
            sb.appendLine(
                "INSERT INTO farmers VALUES (" +
                    "'${escapeSqlLiteral(f.id)}', " +
                    "'${escapeSqlLiteral(f.fullName)}', " +
                    "'${escapeSqlLiteral(f.whatsappPhone)}', " +
                    "'${escapeSqlLiteral(f.gmailAddress)}', " +
                    "'${escapeSqlLiteral(f.nationalId)}', " +
                    "'${escapeSqlLiteral(f.region)}', " +
                    "'${escapeSqlLiteral(f.preferredAlertTime)}');"
            )
        }
        return sb.toString()
    }

    private fun escapeSqlLiteral(raw: String): String {
        return raw
            .replace("\u0000", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("'", "''")
            .take(250)
    }

    private fun saveToPersistence() {
        val p = prefs ?: return
        val root = JSONObject()
        root.put("activeFarmerId", activeFarmerId)

        val farmersArr = JSONArray()
        farmers.forEach { f ->
            farmersArr.put(
                JSONObject().apply {
                    put("id", f.id)
                    put("fullName", f.fullName)
                    put("whatsappPhone", f.whatsappPhone)
                    put("gmailAddress", f.gmailAddress)
                    put("nationalId", f.nationalId)
                    put("region", f.region)
                    put("preferredAlertTime", f.preferredAlertTime)
                    put("role", f.role.code)
                    put("accountStatus", f.accountStatus.code)
                    put("lastLoginAt", f.lastLoginAt)
                    put("isDemoAccount", f.isDemoAccount)
                }
            )
        }
        root.put("farmers", farmersArr)

        val fieldsArr = JSONArray()
        fields.forEach { fl ->
            fieldsArr.put(
                JSONObject().apply {
                    put("id", fl.id)
                    put("farmerId", fl.farmerId)
                    put("fieldName", fl.fieldName)
                    put("lat", fl.lat)
                    put("lon", fl.lon)
                    put("areaFeddans", fl.areaFeddans.toDouble())
                    put("cropType", fl.cropType)
                    put("plantingDate", fl.plantingDate)
                    put("irrigationType", fl.irrigationType)
                    put("flowRateM3Hr", fl.flowRateM3Hr.toDouble())
                }
            )
        }
        root.put("fields", fieldsArr)

        val soilsArr = JSONArray()
        soilProfiles.forEach { sp ->
            soilsArr.put(
                JSONObject().apply {
                    put("id", sp.id)
                    put("fieldId", sp.fieldId)
                    put("soilTexture", sp.soilTexture)
                    put("soilPh", sp.soilPh.toDouble())
                    put("soilEc", sp.soilEc.toDouble())
                    put("waterEc", sp.waterEc.toDouble())
                }
            )
        }
        root.put("soilProfiles", soilsArr)

        val scansArr = JSONArray()
        spectralScans.forEach { sc ->
            scansArr.put(
                JSONObject().apply {
                    put("id", sc.id)
                    put("fieldId", sc.fieldId)
                    put("scanDate", sc.scanDate)
                    put("ndviMean", sc.ndviMean.toDouble())
                    put("ndreMean", sc.ndreMean.toDouble())
                    put("psriMean", sc.psriMean.toDouble())
                    put("ndmiMean", sc.ndmiMean.toDouble())
                    put("anomalyPattern", sc.anomalyPattern.code)
                    put("infectedAreaFeddans", sc.infectedAreaFeddans.toDouble())
                    put("hotspotCoordinates", sc.hotspotCoordinates)
                    put("detectedRiskName", sc.detectedRiskName)
                    put("confidenceScore", sc.confidenceScore)
                }
            )
        }
        root.put("satelliteSpectralScans", scansArr)

        val logsArr = JSONArray()
        treatmentLogs.forEach { lg ->
            logsArr.put(
                JSONObject().apply {
                    put("id", lg.id)
                    put("fieldId", lg.fieldId)
                    put("date", lg.date)
                    put("et0", lg.et0.toDouble())
                    put("kc", lg.kc.toDouble())
                    put("waterM3PerFeddan", lg.waterM3PerFeddan.toDouble())
                    put("valveRunMinutes", lg.valveRunMinutes)
                    put("acidDoseLiters", lg.acidDoseLiters.toDouble())
                    put("preventiveSprayAction", lg.preventiveSprayAction)
                    put("status", lg.status)
                }
            )
        }
        root.put("irrigationAndTreatmentLogs", logsArr)

        val msgsArr = JSONArray()
        whatsappMessages.forEach { wm ->
            msgsArr.put(
                JSONObject().apply {
                    put("id", wm.id)
                    put("farmerId", wm.farmerId)
                    put("fieldId", wm.fieldId)
                    put("timestamp", wm.timestamp)
                    put("direction", wm.direction.code)
                    put("alertCategory", wm.alertCategory.code)
                    put("messageText", wm.messageText)
                    put("status", wm.status)
                }
            )
        }
        root.put("whatsappMessages", msgsArr)

        p.edit { putString("db_json_v1", root.toString()) }
    }

    private fun loadFromPersistence(): Boolean {
        val raw = prefs?.getString("db_json_v1", null) ?: return false
        return try {
            val root = JSONObject(raw)
            activeFarmerId = root.optString("activeFarmerId", "farmer_1")

            farmers.clear()
            val fArr = root.optJSONArray("farmers") ?: return false
            for (i in 0 until fArr.length()) {
                val o = fArr.getJSONObject(i)
                val fId = o.getString("id")
                val resolvedGmail = o.optString("gmailAddress", "")
                val defaultRole = when {
                    resolvedGmail.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true) -> UserRole.SUPER_ADMIN
                    fId == "farmer_2" -> UserRole.AGRONOMIST_ADMIN
                    else -> UserRole.FARMER
                }
                val isDemo = o.optBoolean("isDemoAccount", fId in setOf("farmer_1", "farmer_2", "farmer_3"))
                farmers.add(
                    FarmerEntity(
                        id = fId,
                        fullName = o.getString("fullName"),
                        whatsappPhone = o.optString("whatsappPhone", ""),
                        gmailAddress = resolvedGmail,
                        nationalId = o.optString("nationalId", ""),
                        region = o.getString("region"),
                        preferredAlertTime = o.optString("preferredAlertTime", "06:00 ص"),
                        role = UserRole.fromCode(o.optString("role", defaultRole.code)),
                        accountStatus = AccountStatus.fromCode(o.optString("accountStatus", AccountStatus.ACTIVE.code)),
                        lastLoginAt = o.optString("lastLoginAt", "08:00 ص"),
                        isDemoAccount = isDemo
                    )
                )
            }

            fields.clear()
            val flArr = root.optJSONArray("fields")
            if (flArr != null) {
                for (i in 0 until flArr.length()) {
                    val o = flArr.getJSONObject(i)
                    fields.add(
                        FieldEntity(
                            id = o.getString("id"),
                            farmerId = o.getString("farmerId"),
                            fieldName = o.getString("fieldName"),
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon"),
                            areaFeddans = o.getDouble("areaFeddans").toFloat(),
                            cropType = o.getString("cropType"),
                            plantingDate = o.getString("plantingDate"),
                            irrigationType = o.getString("irrigationType"),
                            flowRateM3Hr = o.getDouble("flowRateM3Hr").toFloat()
                        )
                    )
                }
            }

            soilProfiles.clear()
            val spArr = root.optJSONArray("soilProfiles")
            if (spArr != null) {
                for (i in 0 until spArr.length()) {
                    val o = spArr.getJSONObject(i)
                    soilProfiles.add(
                        SoilProfileEntity(
                            id = o.getString("id"),
                            fieldId = o.getString("fieldId"),
                            soilTexture = o.getString("soilTexture"),
                            soilPh = o.getDouble("soilPh").toFloat(),
                            soilEc = o.getDouble("soilEc").toFloat(),
                            waterEc = o.getDouble("waterEc").toFloat()
                        )
                    )
                }
            }

            spectralScans.clear()
            val scArr = root.optJSONArray("satelliteSpectralScans")
            if (scArr != null) {
                for (i in 0 until scArr.length()) {
                    val o = scArr.getJSONObject(i)
                    spectralScans.add(
                        SatelliteSpectralScanEntity(
                            id = o.getString("id"),
                            fieldId = o.getString("fieldId"),
                            scanDate = o.getString("scanDate"),
                            ndviMean = o.getDouble("ndviMean").toFloat(),
                            ndreMean = o.getDouble("ndreMean").toFloat(),
                            psriMean = o.getDouble("psriMean").toFloat(),
                            ndmiMean = o.getDouble("ndmiMean").toFloat(),
                            anomalyPattern = AnomalyPattern.fromCode(o.getString("anomalyPattern")),
                            infectedAreaFeddans = o.getDouble("infectedAreaFeddans").toFloat(),
                            hotspotCoordinates = o.getString("hotspotCoordinates"),
                            detectedRiskName = o.getString("detectedRiskName"),
                            confidenceScore = o.getInt("confidenceScore")
                        )
                    )
                }
            }

            treatmentLogs.clear()
            val lgArr = root.optJSONArray("irrigationAndTreatmentLogs")
            if (lgArr != null) {
                for (i in 0 until lgArr.length()) {
                    val o = lgArr.getJSONObject(i)
                    treatmentLogs.add(
                        IrrigationAndTreatmentLogEntity(
                            id = o.getString("id"),
                            fieldId = o.getString("fieldId"),
                            date = o.getString("date"),
                            et0 = o.getDouble("et0").toFloat(),
                            kc = o.getDouble("kc").toFloat(),
                            waterM3PerFeddan = o.getDouble("waterM3PerFeddan").toFloat(),
                            valveRunMinutes = o.getInt("valveRunMinutes"),
                            acidDoseLiters = o.getDouble("acidDoseLiters").toFloat(),
                            preventiveSprayAction = o.getString("preventiveSprayAction"),
                            status = o.getString("status")
                        )
                    )
                }
            }

            whatsappMessages.clear()
            val wmArr = root.optJSONArray("whatsappMessages")
            if (wmArr != null) {
                for (i in 0 until wmArr.length()) {
                    val o = wmArr.getJSONObject(i)
                    whatsappMessages.add(
                        WhatsAppMessageEntity(
                            id = o.getString("id"),
                            farmerId = o.getString("farmerId"),
                            fieldId = o.getString("fieldId"),
                            timestamp = o.getString("timestamp"),
                            direction = if (o.getString("direction") == "INBOUND_FARMER") WhatsAppDirection.INBOUND_FARMER else WhatsAppDirection.OUTBOUND_ALERT,
                            alertCategory = WhatsAppAlertCategory.entries.find { it.code == o.getString("alertCategory") } ?: WhatsAppAlertCategory.IRRIGATION_DAILY,
                            messageText = o.getString("messageText"),
                            status = o.getString("status")
                        )
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun normalizeEgyptianWhatsApp(raw: String): String {
            if (raw.isBlank()) return ""
            val digits = raw.filter { it.isDigit() }
            return when {
                digits.startsWith("201") && digits.length == 12 -> "+$digits"
                digits.startsWith("01") && digits.length == 11 -> "+2$digits"
                digits.length == 10 && digits.startsWith("1") -> "+20$digits"
                else -> ""
            }
        }

        fun normalizeGmailAddress(raw: String): String {
            val cleaned = raw.trim().lowercase()
            if (cleaned.isBlank()) return ""
            return if (cleaned.contains("@")) cleaned else "$cleaned@gmail.com"
        }

        fun normalizeEgyptianNationalId(raw: String, fallbackSuffix: String = ""): String {
            val digits = raw.filter { it.isDigit() }
            return if (digits.length == 14) {
                digits
            } else {
                ""
            }
        }

        fun validateEgyptianNationalId(raw: String): EgyptianNationalIdValidation {
            val digits = raw.filter { it.isDigit() }
            if (digits.length != 14) {
                return EgyptianNationalIdValidation(
                    isValid = false,
                    normalized14Digits = digits,
                    birthYearSummaryAr = "غير مكتمل",
                    governorateAr = "غير محدد",
                    statusMessageAr = "الرقم القومي المصري يجب أن يتكون من 14 رقماً (${digits.length}/14)"
                )
            }
            val centuryDigit = digits[0]
            if (centuryDigit != '2' && centuryDigit != '3') {
                return EgyptianNationalIdValidation(
                    isValid = false,
                    normalized14Digits = digits,
                    birthYearSummaryAr = "قرن غير صالح",
                    governorateAr = "غير محدد",
                    statusMessageAr = "الرقم القومي المصري يجب أن يبدأ بالرقم 2 (مواليد 1900-1999) أو 3 (مواليد 2000+)"
                )
            }
            val yearPrefix = if (centuryDigit == '2') "19" else "20"
            val yy = digits.substring(1, 3)
            val mm = digits.substring(3, 5).toIntOrNull() ?: 0
            val dd = digits.substring(5, 7).toIntOrNull() ?: 0
            if (mm !in 1..12 || dd !in 1..31) {
                return EgyptianNationalIdValidation(
                    isValid = false,
                    normalized14Digits = digits,
                    birthYearSummaryAr = "$yearPrefix$yy",
                    governorateAr = "غير محدد",
                    statusMessageAr = "تاريخ الميلاد المدمج بالرقم القومي غير صحيح (الشهر/اليوم)"
                )
            }
            val govCode = digits.substring(7, 9)
            val govName = when (govCode) {
                "01" -> "القاهرة"
                "02" -> "الإسكندرية"
                "03" -> "بورسعيد"
                "04" -> "السويس"
                "11" -> "دمياط"
                "12" -> "الدقهلية"
                "13" -> "الشرقية"
                "14" -> "القليوبية"
                "15" -> "كفر الشيخ"
                "16" -> "الغربية"
                "17" -> "المنوفية"
                "18" -> "البحيرة"
                "19" -> "الإسماعيلية"
                "21" -> "الجيزة"
                "22" -> "بني سويف"
                "23" -> "الفيوم"
                "24" -> "المنيا"
                "25" -> "أسيوط"
                "26" -> "سوهاج"
                "27" -> "قنا"
                "28" -> "أسوان"
                "29" -> "الأقصر"
                "31" -> "البحر الأحمر"
                "32" -> "الوادي الجديد"
                "33" -> "مطروح"
                "34" -> "شمال سيناء"
                "35" -> "جنوب سيناء"
                else -> "محافظة مصرية ($govCode)"
            }
            val birthSummary = "مواليد $yearPrefix$yy/$mm/$dd"
            return EgyptianNationalIdValidation(
                isValid = true,
                normalized14Digits = digits,
                birthYearSummaryAr = birthSummary,
                governorateAr = govName,
                statusMessageAr = "رقم قومي مصري صحيح وموثق ✅ ($govName • $birthSummary)"
            )
        }

        fun currentTimeFormatted(): String {
            return try {
                val now = LocalTime.now()
                val fmt = DateTimeFormatter.ofPattern("hh:mm")
                val suffix = if (now.hour < 12) "ص" else "م"
                "${now.format(fmt)} $suffix"
            } catch (_: Exception) {
                "08:15 ص"
            }
        }

        fun buildProactiveDiseaseWhatsAppText(
            farmerName: String,
            fieldName: String,
            scan: SatelliteSpectralScanEntity,
            humidityPct: Int,
            reducedHours: Float,
            preventiveRecommendation: String
        ): String {
            return "🛰️ *إنذار مبكر من القمر الصناعي (AgroSight) - حقل $fieldName*\n" +
                "يا $farmerName، رصد القمر الصناعي Sentinel-2 اليوم تغيراً طيفياً في مؤشر الكلوروفيل (NDRE=${com.example.model.formatDecimal2(scan.ndreMean)}) وتلف الأنسجة (PSRI=${com.example.model.formatDecimal2(scan.psriMean)}) في:\n" +
                "📍 *الموقع:* ${scan.hotspotCoordinates} (بؤرة بمساحة ${com.example.model.formatDecimal2(scan.infectedAreaFeddans)} فدان).\n" +
                "🦠 *التشخيص المرجح (بنسبة ${scan.confidenceScore}%):* ${scan.detectedRiskName} نتيجة وصول الرطوبة الجوية إلى $humidityPct% مع تغدق موضعي بالتربة.\n" +
                "💧 *تعديل الري الفوري:* خفض مقنن الري في المحبس رقم 3 اليوم بنسبة 25% (تشغيل ${com.example.model.formatDecimal1(reducedHours)} ساعة فقط) لمنع انتشار الفطر.\n" +
                "🛡️ *الإجراء الوقائي المستهدف:* $preventiveRecommendation"
        }
    }
}
