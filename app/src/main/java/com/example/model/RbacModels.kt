package com.example.model

enum class UserRole(
    val code: String,
    val titleAr: String,
    val badgeAr: String,
    val canAccessAdminPortal: Boolean,
    val canManageUsersAndRoles: Boolean,
    val canEditAllFieldsAndRecords: Boolean
) {
    SUPER_ADMIN(
        code = "SUPER_ADMIN",
        titleAr = "مدير النظام العام (Super Admin)",
        badgeAr = "SUPER ADMIN • صلاحيات كاملة",
        canAccessAdminPortal = true,
        canManageUsersAndRoles = true,
        canEditAllFieldsAndRecords = true
    ),
    AGRONOMIST_ADMIN(
        code = "AGRONOMIST_ADMIN",
        titleAr = "مهندس استشاري ومشرف (Agronomist Admin)",
        badgeAr = "AGRONOMIST • إشراف فني",
        canAccessAdminPortal = true,
        canManageUsersAndRoles = false,
        canEditAllFieldsAndRecords = true
    ),
    FARMER(
        code = "FARMER",
        titleAr = "مزارع / مالك مزرعة (Farmer Account)",
        badgeAr = "FARMER • لوحة المزرعة",
        canAccessAdminPortal = false,
        canManageUsersAndRoles = false,
        canEditAllFieldsAndRecords = false
    );

    companion object {
        const val BOOTSTRAPPED_ADMIN_EMAIL = "elzahraafatma288@gmail.com"

        fun fromCode(code: String): UserRole =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: FARMER
    }
}

enum class AccountStatus(val code: String, val labelAr: String) {
    ACTIVE("ACTIVE", "نشط وموثق ✅"),
    SUSPENDED("SUSPENDED", "موقوف مؤقتاً ⏸️"),
    PENDING_VERIFICATION("PENDING_VERIFICATION", "قيد المراجعة ⏳");

    companion object {
        fun fromCode(code: String): AccountStatus =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: ACTIVE
    }
}

data class SystemAuditEvent(
    val id: String,
    val timestamp: String,
    val actorName: String,
    val actorEmail: String,
    val actorRole: UserRole,
    val actionCategory: String, // "AUTH_LOGIN" | "RBAC_ADMIN" | "SATELLITE_SCAN" | "IRRIGATION_DISPATCH" | "WHATSAPP_WA_ME" | "SQL_RECORD"
    val summaryAr: String,
    val targetEntityId: String
)

data class SystemWideAnalyticsSummary(
    val totalUsersCount: Int,
    val activeUsersCount: Int,
    val suspendedUsersCount: Int,
    val superAdminsCount: Int,
    val agronomistsCount: Int,
    val farmersCount: Int,
    val totalFieldsCount: Int,
    val totalAreaFeddans: Float,
    val totalDailyWaterM3: Float,
    val avgSystemNdvi: Float,
    val avgSystemSoilPh: Float,
    val activeDiseaseAlertsCount: Int,
    val totalWhatsAppLogsCount: Int,
    val totalTreatmentLogsCount: Int,
    val totalAuditEventsCount: Int
)
