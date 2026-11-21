package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.FarmerEntity
import com.example.model.AccountStatus
import com.example.model.AppScreen
import com.example.model.EGYPTIAN_ZONE_PRESETS
import com.example.model.SUPPORTED_CROPS
import com.example.model.UserRole
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.ui.theme.AmberLight
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.RoseDanger
import com.example.ui.theme.RoseSoft
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.SkySoft
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate500
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.viewmodel.AgroSightUiState

@Composable
private fun AdminBentoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun AdminPortalScreen(
    uiState: AgroSightUiState,
    onUpdateSearchQuery: (String) -> Unit,
    onSelectRoleFilter: (UserRole?) -> Unit,
    onChangeUserRole: (String, UserRole) -> Unit,
    onChangeUserStatus: (String, AccountStatus) -> Unit,
    onDeleteUserAccount: (String) -> Unit,
    onCreateManagedUser: (String, String, String, String, String, String, Float, UserRole) -> Unit,
    onSwitchToUserDashboard: (String, AppScreen) -> Unit,
    onDeleteTreatmentLog: (String) -> Unit,
    onDeleteWhatsAppMessage: (String) -> Unit,
    onDeleteRoomSqlAudit: (String) -> Unit,
    onTriggerDailyCronDispatchAllFields: () -> Unit,
    onOpenSqlSchemaModal: () -> Unit,
    onEscalateCurrentAccountToSuperAdmin: () -> Unit
) {
    var activeAdminTab by remember { mutableIntStateOf(0) } // 0: Executive Analytics, 1: Users & RBAC, 2: All Farms & Records, 3: Security Audit
    val currentUser = uiState.activeFarmer
    val hasAdminAccess = currentUser.role.canAccessAdminPortal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Executive RBAC Header Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Slate900, Color(0xFF1E1B4B), Slate800)
                    )
                )
                .border(1.dp, AmberLight.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(AmberWarning.copy(alpha = 0.25f))
                                .border(1.dp, AmberLight, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                tint = AmberLight,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "بوابة الإدارة والتحكم المركزي الشامل (Admin Portal & RBAC)",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "الحساب النشط: ${currentUser.fullName} (${currentUser.gmailAddress}) • الصلاحية: ${currentUser.role.badgeAr}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberLight
                            )
                        }
                    }

                    Surface(
                        color = if (hasAdminAccess) EmeraldPrimary else RoseDanger,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, if (hasAdminAccess) EmeraldLight else Color.White)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (hasAdminAccess) Icons.Default.VerifiedUser else Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = currentUser.role.code,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Sub-navigation pills inside Admin Portal
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val adminTabs = listOf(
                        Triple(0, "📊 مؤشرات النظام والتحليلات الشاملة", Icons.Default.Analytics),
                        Triple(1, "👥 إدارة المستخدمين والأدوار (RBAC)", Icons.Default.People),
                        Triple(2, "🛰️ إدارة جميع المزارع والسجلات والرسائل", Icons.Default.Storage),
                        Triple(3, "🛡️ سجل المراجعة الأمنية والنشاط (Audit Log)", Icons.Default.History)
                    )
                    adminTabs.forEach { (idx, label, icon) ->
                        val selected = activeAdminTab == idx
                        FilterChip(
                            selected = selected,
                            onClick = { activeAdminTab = idx },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberWarning,
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White,
                                containerColor = Slate800,
                                labelColor = Color.White,
                                iconColor = AmberLight
                            ),
                            modifier = Modifier.testTag("admin_portal_subtab_$idx")
                        )
                    }
                }
            }
        }

        // RBAC Security Guard Notice if a regular Farmer opens the Admin Portal tab
        if (!hasAdminAccess) {
            AdminBentoCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = RoseDanger,
                        modifier = Modifier.size(32.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔒 هذه البوابة محمية بنظام الصلاحيات القائم على الأدوار (Zero-Trust RBAC Guard)",
                            style = MaterialTheme.typography.titleSmall,
                            color = RoseDanger,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "أنت تتصفح حالياً بحساب مزارع (${currentUser.fullName} — صلاحية FARMER). تم حجب بيانات المستخدمين الآخرين وسجلات النظام لحماية الخصوصية. للوصول إلى تحكم المدير العام (Super Admin - ${UserRole.BOOTSTRAPPED_ADMIN_EMAIL})، قم بالتبديل إلى حساب المالك الموثق أدناه:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val ownerAdmin = uiState.dbSnapshot.farmers.find {
                                it.gmailAddress.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
                            } ?: uiState.dbSnapshot.farmers.first()
                            onSwitchToUserDashboard(ownerAdmin.id, AppScreen.ADMIN_PORTAL)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("switch_to_owner_super_admin_button")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("الدخول بحساب المالك Super Admin (${UserRole.BOOTSTRAPPED_ADMIN_EMAIL})")
                    }

                    OutlinedButton(
                        onClick = onEscalateCurrentAccountToSuperAdmin,
                        modifier = Modifier.testTag("escalate_current_to_super_admin_button")
                    ) {
                        Text("تحقق وتفعيل حساب المالك")
                    }
                }
            }
        } else {
            when (activeAdminTab) {
                0 -> AdminExecutiveAnalyticsSection(
                    uiState = uiState,
                    onTriggerDailyCronDispatchAllFields = onTriggerDailyCronDispatchAllFields,
                    onOpenSqlSchemaModal = onOpenSqlSchemaModal,
                    onSwitchToUserDashboard = onSwitchToUserDashboard
                )

                1 -> AdminUserAndRbacManagementSection(
                    uiState = uiState,
                    onUpdateSearchQuery = onUpdateSearchQuery,
                    onSelectRoleFilter = onSelectRoleFilter,
                    onChangeUserRole = onChangeUserRole,
                    onChangeUserStatus = onChangeUserStatus,
                    onDeleteUserAccount = onDeleteUserAccount,
                    onCreateManagedUser = onCreateManagedUser,
                    onSwitchToUserDashboard = onSwitchToUserDashboard
                )

                2 -> AdminAllFarmsAndRecordsExplorerSection(
                    uiState = uiState,
                    onSwitchToUserDashboard = onSwitchToUserDashboard,
                    onDeleteTreatmentLog = onDeleteTreatmentLog,
                    onDeleteWhatsAppMessage = onDeleteWhatsAppMessage,
                    onDeleteRoomSqlAudit = onDeleteRoomSqlAudit
                )

                3 -> AdminSecurityAuditTrailSection(
                    uiState = uiState
                )
            }
        }
    }
}

@Composable
private fun AdminExecutiveAnalyticsSection(
    uiState: AgroSightUiState,
    onTriggerDailyCronDispatchAllFields: () -> Unit,
    onOpenSqlSchemaModal: () -> Unit,
    onSwitchToUserDashboard: (String, AppScreen) -> Unit
) {
    val stats = uiState.systemAnalytics

    AdminBentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "📈 لوحة المراقبة المركزية وإحصائيات المنصة (System-Wide Telemetry & KPIs)",
            style = MaterialTheme.typography.titleSmall,
            color = Slate900,
            fontWeight = FontWeight.Bold
        )

        // Row 1 of KPI tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminMetricTile(
                title = "إجمالي الحسابات",
                value = "${stats.totalUsersCount}",
                subtitle = "${stats.superAdminsCount} مدير • ${stats.agronomistsCount} مشرف • ${stats.farmersCount} مزارع",
                accentColor = EmeraldPrimary,
                bgColor = EmeraldSoft,
                modifier = Modifier.weight(1f)
            )
            AdminMetricTile(
                title = "إجمالي المساحة المزروعة",
                value = "${stats.totalAreaFeddans.toInt()} فدان",
                subtitle = "موزعة على ${stats.totalFieldsCount} مزارع نشطة",
                accentColor = SkyAccent,
                bgColor = SkySoft,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2 of KPI tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminMetricTile(
                title = "متوسط الغطاء النباتي العام",
                value = "NDVI ${formatDecimal2(stats.avgSystemNdvi)}",
                subtitle = "متوسط pH التربة: ${formatDecimal1(stats.avgSystemSoilPh)}",
                accentColor = EmeraldDark,
                bgColor = EmeraldSoft,
                modifier = Modifier.weight(1f)
            )
            AdminMetricTile(
                title = "الإنذارات الطيفية النشطة",
                value = "${stats.activeDiseaseAlertsCount} بؤر",
                subtitle = "${stats.totalWhatsAppLogsCount} رسالة wa.me • ${stats.totalTreatmentLogsCount} عملية",
                accentColor = RoseDanger,
                bgColor = RoseSoft,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onTriggerDailyCronDispatchAllFields,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                modifier = Modifier
                    .weight(1f)
                    .testTag("admin_run_global_cron_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تشغيل المسح الفضائي الشامل لكل الحقول (Cron)")
            }

            OutlinedButton(
                onClick = onOpenSqlSchemaModal,
                modifier = Modifier.testTag("admin_export_sql_schema_button")
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("مخطط SQL")
            }
        }
    }

    // Regional & Tenant Performance Matrix
    AdminBentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "🗺️ مصفوفة المزارع والحسابات المسجلة في النظام (اضغط لفتح لوحة تحكم أي مستخدم)",
            style = MaterialTheme.typography.titleSmall,
            color = Slate900,
            fontWeight = FontWeight.Bold
        )

        uiState.dbSnapshot.farmers.forEach { farmer ->
            val field = uiState.dbSnapshot.fields.find { it.farmerId == farmer.id }
            val soil = uiState.dbSnapshot.soilProfiles.find { it.fieldId == field?.id }
            val scan = uiState.dbSnapshot.satelliteSpectralScans.find { it.fieldId == field?.id }

            Surface(
                color = if (farmer.id == uiState.activeFarmer.id) EmeraldSoft else Slate100,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (farmer.id == uiState.activeFarmer.id) EmeraldPrimary else Slate200
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${farmer.fullName} (${farmer.region})",
                                style = MaterialTheme.typography.labelLarge,
                                color = Slate900,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "📧 ${farmer.gmailAddress} • 📱 ${farmer.whatsappPhone} • 🪪 ${farmer.nationalId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        RoleBadgeChip(role = farmer.role)
                    }

                    if (field != null) {
                        Text(
                            text = "🌾 الحقل: ${field.fieldName} (${field.areaFeddans.toInt()} فدان ${field.cropType}) • NDVI=${formatDecimal2(scan?.ndviMean ?: 0.7f)} • pH=${formatDecimal1(soil?.soilPh ?: 7.6f)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldDark,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "🛰️ آخر تشخيص: ${scan?.detectedRiskName ?: "سليم"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "الحالة: ${farmer.accountStatus.labelAr} • آخر نشاط: ${farmer.lastLoginAt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500
                        )
                        Button(
                            onClick = { onSwitchToUserDashboard(farmer.id, AppScreen.DISEASE_RADAR) },
                            colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("فتح لوحة المستخدم", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminUserAndRbacManagementSection(
    uiState: AgroSightUiState,
    onUpdateSearchQuery: (String) -> Unit,
    onSelectRoleFilter: (UserRole?) -> Unit,
    onChangeUserRole: (String, UserRole) -> Unit,
    onChangeUserStatus: (String, AccountStatus) -> Unit,
    onDeleteUserAccount: (String) -> Unit,
    onCreateManagedUser: (String, String, String, String, String, String, Float, UserRole) -> Unit,
    onSwitchToUserDashboard: (String, AppScreen) -> Unit
) {
    var showCreateUserForm by remember { mutableStateOf(false) }
    var newFullName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("+2010") }
    var newGmail by remember { mutableStateOf("") }
    var newNationalId by remember { mutableStateOf("28905141801234") }
    var newRegion by remember { mutableStateOf(EGYPTIAN_ZONE_PRESETS.first().nameAr) }
    var newCrop by remember { mutableStateOf(SUPPORTED_CROPS.first().nameAr) }
    var newArea by remember { mutableStateOf("50") }
    var newRole by remember { mutableStateOf(UserRole.FARMER) }

    // Search & Role Filter Card
    AdminBentoCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "👥 إدارة المستخدمين والحسابات والصلاحيات (RBAC Directory)",
                style = MaterialTheme.typography.titleSmall,
                color = Slate900,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = { showCreateUserForm = !showCreateUserForm },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                modifier = Modifier.testTag("admin_toggle_create_user_button")
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (showCreateUserForm) "إغلاق النموذج" else "+ إضافة مستخدم جديد")
            }
        }

        if (showCreateUserForm) {
            Surface(
                color = Slate100,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, EmeraldPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "إنشاء حساب مستخدم/مزارع أو مشرف جديد بصلاحية مخصصة (RBAC):",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmeraldDark,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = newFullName,
                        onValueChange = { newFullName = it },
                        label = { Text("الاسم الكامل للمستخدم") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newPhone,
                            onValueChange = { newPhone = it },
                            label = { Text("رقم الموبايل / واتساب") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newGmail,
                            onValueChange = { newGmail = it },
                            label = { Text("حساب Gmail") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newNationalId,
                            onValueChange = { newNationalId = it },
                            label = { Text("الرقم القومي (14 رقم)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newArea,
                            onValueChange = { newArea = it },
                            label = { Text("المساحة (فدان)") },
                            modifier = Modifier.weight(0.6f),
                            singleLine = true
                        )
                    }

                    Text(
                        text = "تحديد الدور والصلاحية (RBAC Role):",
                        style = MaterialTheme.typography.labelMedium,
                        color = Slate700,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UserRole.entries.forEach { roleOpt ->
                            FilterChip(
                                selected = newRole == roleOpt,
                                onClick = { newRole = roleOpt },
                                label = { Text(roleOpt.titleAr) }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (newFullName.isNotBlank()) {
                                onCreateManagedUser(
                                    newFullName,
                                    newPhone,
                                    newGmail,
                                    newNationalId,
                                    newRegion,
                                    newCrop,
                                    newArea.toFloatOrNull() ?: 50f,
                                    newRole
                                )
                                newFullName = ""
                                newGmail = ""
                                showCreateUserForm = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("حفظ وتفعيل الحساب والصلاحية فوراً")
                    }
                }
            }
        }

        OutlinedTextField(
            value = uiState.adminSearchQuery,
            onValueChange = onUpdateSearchQuery,
            label = { Text("بحث بالاسم، البريد Gmail، رقم الموبايل، الرقم القومي، أو المنطقة...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("admin_user_search_input"),
            singleLine = true
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = uiState.adminRoleFilter == null,
                onClick = { onSelectRoleFilter(null) },
                label = { Text("الكل (${uiState.dbSnapshot.farmers.size})") }
            )
            UserRole.entries.forEach { role ->
                val count = uiState.dbSnapshot.farmers.count { it.role == role }
                FilterChip(
                    selected = uiState.adminRoleFilter == role,
                    onClick = { onSelectRoleFilter(role) },
                    label = { Text("${role.code} ($count)") }
                )
            }
        }

        val query = uiState.adminSearchQuery.trim().lowercase()
        val filteredUsers = uiState.dbSnapshot.farmers.filter { f ->
            val roleMatches = uiState.adminRoleFilter == null || f.role == uiState.adminRoleFilter
            val textMatches = query.isBlank() ||
                f.fullName.lowercase().contains(query) ||
                f.gmailAddress.lowercase().contains(query) ||
                f.whatsappPhone.contains(query) ||
                f.nationalId.contains(query) ||
                f.region.lowercase().contains(query)
            roleMatches && textMatches
        }

        filteredUsers.forEach { user ->
            val isOwnerAccount = user.gmailAddress.equals(UserRole.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
            Surface(
                color = Slate100,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (isOwnerAccount) AmberWarning else Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user.fullName + if (isOwnerAccount) " 👑 (حساب المالك)" else "",
                                style = MaterialTheme.typography.titleSmall,
                                color = Slate900,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "📧 ${user.gmailAddress} • 📱 ${user.whatsappPhone}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate700
                            )
                            Text(
                                text = "🪪 الرقم القومي: ${user.nationalId} • المنطقة: ${user.region}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate500
                            )
                        }
                        RoleBadgeChip(role = user.role)
                    }

                    HorizontalDivider(color = Slate200)

                    // Role Assignment Chips
                    Text(
                        text = "تغيير صلاحية الحساب (RBAC Role):",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate700,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UserRole.entries.forEach { r ->
                            FilterChip(
                                selected = user.role == r,
                                onClick = { onChangeUserRole(user.id, r) },
                                label = { Text(r.code, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Status & Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val isSuspended = user.accountStatus == AccountStatus.SUSPENDED
                            OutlinedButton(
                                onClick = {
                                    val nextStatus = if (isSuspended) AccountStatus.ACTIVE else AccountStatus.SUSPENDED
                                    onChangeUserStatus(user.id, nextStatus)
                                },
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSuspended) Icons.Default.CheckCircle else Icons.Default.Block,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isSuspended) "تنشيط الحساب" else "إيقاف مؤقت",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            if (!isOwnerAccount) {
                                IconButton(
                                    onClick = { onDeleteUserAccount(user.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "حذف الحساب",
                                        tint = RoseDanger
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { onSwitchToUserDashboard(user.id, AppScreen.FARM_SOIL_STUDIO) },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("إدارة مزرعة المستخدم", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminAllFarmsAndRecordsExplorerSection(
    uiState: AgroSightUiState,
    onSwitchToUserDashboard: (String, AppScreen) -> Unit,
    onDeleteTreatmentLog: (String) -> Unit,
    onDeleteWhatsAppMessage: (String) -> Unit,
    onDeleteRoomSqlAudit: (String) -> Unit
) {
    // 1. All Fields & Satellite Scans Table
    AdminBentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "🛰️ جميع الحقول والفحوصات الطيفية المسجلة (${uiState.dbSnapshot.fields.size} حقول)",
            style = MaterialTheme.typography.titleSmall,
            color = Slate900,
            fontWeight = FontWeight.Bold
        )
        uiState.dbSnapshot.fields.forEach { field ->
            val owner = uiState.dbSnapshot.farmers.find { it.id == field.farmerId }
            val scan = uiState.dbSnapshot.satelliteSpectralScans.find { it.fieldId == field.id }
            val soil = uiState.dbSnapshot.soilProfiles.find { it.fieldId == field.id }
            Surface(
                color = Slate100,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${field.fieldName} (${field.cropType} • ${field.areaFeddans.toInt()} فدان)",
                            style = MaterialTheme.typography.labelLarge,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "المالك: ${owner?.fullName ?: "-"} • الإحداثيات: (${field.lat}, ${field.lon})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate600
                        )
                        Text(
                            text = "NDVI=${formatDecimal2(scan?.ndviMean ?: 0.7f)} | NDRE=${formatDecimal2(scan?.ndreMean ?: 0.3f)} | PSRI=${formatDecimal2(scan?.psriMean ?: 0.1f)} | pH=${formatDecimal1(soil?.soilPh ?: 7.6f)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (owner != null) {
                                onSwitchToUserDashboard(owner.id, AppScreen.DISEASE_RADAR)
                            }
                        }
                    ) {
                        Text("فحص الرادار", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    // 2. All Irrigation & Acid Treatment Logs across Tenants
    AdminBentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "💧 سجل عمليات الري وحقن الأحماض الشامل (${uiState.dbSnapshot.irrigationAndTreatmentLogs.size} عملية)",
            style = MaterialTheme.typography.titleSmall,
            color = Slate900,
            fontWeight = FontWeight.Bold
        )
        uiState.dbSnapshot.irrigationAndTreatmentLogs.forEach { log ->
            val field = uiState.dbSnapshot.fields.find { it.id == log.fieldId }
            Surface(
                color = Slate100,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${field?.fieldName ?: log.fieldId} • ${log.date} (${log.status})",
                            style = MaterialTheme.typography.labelMedium,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "المقنن: ${formatDecimal1(log.waterM3PerFeddan)} م³/فدان • التشغيل: ${log.valveRunMinutes} دقيقة • الحامض: ${formatDecimal1(log.acidDoseLiters)} لتر",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldDark
                        )
                        Text(
                            text = log.preventiveSprayAction,
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate600
                        )
                    }
                    IconButton(onClick = { onDeleteTreatmentLog(log.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف السجل", tint = RoseDanger)
                    }
                }
            }
        }
    }

    // 3. All Direct WhatsApp (wa.me) Logged Messages across Tenants
    AdminBentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "📲 سجل رسائل واتساب المباشرة (wa.me) لجميع المستخدمين (${uiState.dbSnapshot.whatsappMessages.size} رسالة)",
            style = MaterialTheme.typography.titleSmall,
            color = Slate900,
            fontWeight = FontWeight.Bold
        )
        uiState.dbSnapshot.whatsappMessages.takeLast(12).reversed().forEach { msg ->
            val farmer = uiState.dbSnapshot.farmers.find { it.id == msg.farmerId }
            Surface(
                color = Slate100,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${farmer?.fullName ?: msg.farmerId} (${farmer?.whatsappPhone ?: ""}) • ${msg.timestamp} [${msg.status}]",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldDark,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = msg.messageText.take(180) + if (msg.messageText.length > 180) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700
                        )
                    }
                    IconButton(onClick = { onDeleteWhatsAppMessage(msg.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف الرسالة", tint = RoseDanger)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminSecurityAuditTrailSection(
    uiState: AgroSightUiState
) {
    AdminBentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "🛡️ سجل المراجعة الأمنية والنشاط اللحظي (RBAC & System Audit Trail)",
            style = MaterialTheme.typography.titleSmall,
            color = Slate900,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "يوثق جميع عمليات تسجيل الدخول، تغييرات الصلاحيات (RBAC)، فحوصات الأقمار الصناعية، وعمليات الحذف والإرسال.",
            style = MaterialTheme.typography.bodySmall,
            color = Slate600
        )

        uiState.dbSnapshot.systemAuditEvents.forEach { evt ->
            Surface(
                color = Slate100,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${evt.actorName} (${evt.actorEmail})",
                            style = MaterialTheme.typography.labelMedium,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${evt.actionCategory} • ${evt.timestamp}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SkyAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = evt.summaryAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate700
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminMetricTile(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Slate700,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Slate600
            )
        }
    }
}

@Composable
private fun RoleBadgeChip(role: UserRole) {
    val (bg, border, fg) = when (role) {
        UserRole.SUPER_ADMIN -> Triple(AmberSoft, AmberWarning, AmberWarning)
        UserRole.AGRONOMIST_ADMIN -> Triple(SkySoft, SkyAccent, SkyAccent)
        UserRole.FARMER -> Triple(EmeraldSoft, EmeraldPrimary, EmeraldDark)
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = role.code,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
