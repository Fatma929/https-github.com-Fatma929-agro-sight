package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout. statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.FarmerEntity
import com.example.model.AppScreen
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.model.formatHoursMinutesCompact
import com.example.ui.theme.AmberLight
import com.example.ui.theme.CyanLight
import com.example.ui.theme.ForestBgDark
import com.example.ui.theme.ForestBgDeep
import com.example.ui.theme.ForestCardBorder
import com.example.ui.theme.ForestCardDark
import com.example.ui.theme.ForestCardElevated
import com.example.ui.theme.LimeAccent
import com.example.ui.theme.LimeBright
import com.example.ui.theme.LimeSoftBg
import com.example.ui.theme.MintTextMuted
import com.example.ui.theme.MintTextPrimary
import com.example.ui.theme.MintTextSoft
import com.example.ui.theme.RoseLight
import com.example.viewmodel.AgroSightUiState

private fun screenIcon(screen: AppScreen): ImageVector = when (screen) {
    AppScreen.HOME -> Icons.Default.Home
    AppScreen.DISEASE_RADAR -> Icons.Default.Radar
    AppScreen.IRRIGATION_SCHEDULE -> Icons.Default.CalendarMonth
    AppScreen.SATELLITE_NDVI_MAP -> Icons.Default.SatelliteAlt
    AppScreen.WHATSAPP_BOT -> Icons.AutoMirrored.Filled.Chat
    AppScreen.AI_DIGITAL_TWIN_HUB -> Icons.Default.AutoAwesome
    AppScreen.FARM_SOIL_STUDIO -> Icons.Default.Landscape
    AppScreen.METEO_ANALYTICS -> Icons.Default.WbSunny
    AppScreen.ADMIN_PORTAL -> Icons.Default.AdminPanelSettings
}

private fun screenShortLabel(screen: AppScreen): String = when (screen) {
    AppScreen.HOME -> "الرئيسية"
    AppScreen.DISEASE_RADAR -> "الحقول"
    AppScreen.IRRIGATION_SCHEDULE -> "الري 7 أيام"
    AppScreen.SATELLITE_NDVI_MAP -> "خريطة NDVI"
    AppScreen.WHATSAPP_BOT -> "التنبيهات"
    AppScreen.AI_DIGITAL_TWIN_HUB -> "مساعد 3D"
    AppScreen.FARM_SOIL_STUDIO -> "الأرض والتربة"
    AppScreen.METEO_ANALYTICS -> "الأرصاد"
    AppScreen.ADMIN_PORTAL -> "الإدارة"
}

/**
 * Streamlined Forest-Green Top Bar.
 * On [AppScreen.HOME], the Home dashboard displays its own clean greeting header
 * ("أهلاً، ... 👋" + Notification bell) to match the reference design without header clutter.
 * On secondary screens, a clean single-row bar with Back-to-Home button and quick utilities is shown.
 */
@Composable
fun AgroSightHeader(
    currentScreen: AppScreen,
    farmers: List<FarmerEntity>,
    activeFarmer: FarmerEntity,
    fieldName: String,
    areaFeddans: Float,
    cropNameAr: String,
    kcValue: Float,
    isLoadingMeteo: Boolean,
    onSwitchFarmer: (String) -> Unit,
    onOpenAuthModal: () -> Unit,
    onOpenDbSchemaModal: () -> Unit,
    onOpenAdminPortal: () -> Unit = {},
    onOpenDrawer: () -> Unit,
    onRefreshMeteo: () -> Unit,
    onOpenPrintReport: () -> Unit,
    onNavigateHome: () -> Unit = {}
) {
    if (currentScreen == AppScreen.HOME) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ForestBgDark,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            ForestBgDeep,
                            ForestBgDark,
                            ForestCardDark
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onNavigateHome,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ForestCardDark)
                        .border(1.dp, ForestCardBorder, CircleShape)
                        .testTag("top_bar_back_home_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "العودة للرئيسية",
                        tint = MintTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentScreen.titleAr,
                        color = MintTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$fieldName • $cropNameAr (${areaFeddans.toInt()} فدان)",
                        color = MintTextSoft,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = ForestCardDark,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, ForestCardBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onOpenAuthModal)
                        .testTag("header_auth_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "حساب المزارع",
                            tint = LimeBright,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = activeFarmer.fullName.substringBefore(" "),
                            color = MintTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                IconButton(
                    onClick = onOpenPrintReport,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(ForestCardDark)
                        .border(1.dp, ForestCardBorder, CircleShape)
                        .testTag("header_print_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = "تقرير PDF",
                        tint = LimeBright,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onRefreshMeteo,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(ForestCardDark)
                        .border(1.dp, ForestCardBorder, CircleShape)
                        .testTag("header_refresh_button")
                ) {
                    if (isLoadingMeteo) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LimeBright
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث",
                            tint = MintTextSoft,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(ForestCardDark)
                        .border(1.dp, ForestCardBorder, CircleShape)
                        .testTag("header_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "القائمة",
                        tint = MintTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Floating Forest-Green Pill Bottom Navigation Bar with Central Circular Lime-Green `+` Button,
 * matching the reference mockup design (Home | Fields | [ + ] | Alerts | More).
 */
@Composable
fun AgroSightBottomBar(
    currentScreen: AppScreen,
    onSelectScreen: (AppScreen) -> Unit,
    canAccessAdminPortal: Boolean = true,
    onOpenAuthModal: () -> Unit = {},
    onOpenPrintModal: () -> Unit = {}
) {
    var showQuickModulesSheet by rememberSaveable { mutableStateOf(false) }

    if (showQuickModulesSheet) {
        QuickActionsAndModulesDialog(
            currentScreen = currentScreen,
            canAccessAdminPortal = canAccessAdminPortal,
            onSelectScreen = { target ->
                showQuickModulesSheet = false
                onSelectScreen(target)
            },
            onOpenAuthModal = {
                showQuickModulesSheet = false
                onOpenAuthModal()
            },
            onOpenPrintModal = {
                showQuickModulesSheet = false
                onOpenPrintModal()
            },
            onDismiss = { showQuickModulesSheet = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForestBgDeep)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = ForestCardDark,
            border = BorderStroke(1.2.dp, ForestCardBorder),
            shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Slot 1: Home (الرئيسية)
                BottomBarNavTab(
                    icon = Icons.Default.Home,
                    labelAr = "الرئيسية",
                    selected = currentScreen == AppScreen.HOME,
                    onClick = { onSelectScreen(AppScreen.HOME) },
                    testTag = "nav_tab_home"
                )

                // Slot 2: Fields / Radar (الحقول)
                BottomBarNavTab(
                    icon = Icons.Default.Radar,
                    labelAr = "الحقول",
                    selected = currentScreen == AppScreen.DISEASE_RADAR ||
                        currentScreen == AppScreen.SATELLITE_NDVI_MAP,
                    onClick = { onSelectScreen(AppScreen.DISEASE_RADAR) },
                    testTag = "nav_tab_disease_radar"
                )

                // Slot 3: Central Circular Lime-Green '+' FAB (Matches Reference Mockup)
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(LimeBright, LimeAccent)
                            )
                        )
                        .border(2.dp, ForestBgDeep, CircleShape)
                        .clickable { showQuickModulesSheet = true }
                        .testTag("bottom_bar_plus_fab"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "إجراء سريع وجميع الأقسام",
                        tint = ForestBgDeep,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Slot 4: Alerts & Schedule (التنبيهات / الري)
                BottomBarNavTab(
                    icon = Icons.Default.NotificationsNone,
                    labelAr = "التنبيهات",
                    selected = currentScreen == AppScreen.WHATSAPP_BOT ||
                        currentScreen == AppScreen.IRRIGATION_SCHEDULE,
                    onClick = { onSelectScreen(AppScreen.IRRIGATION_SCHEDULE) },
                    testTag = "nav_tab_irrigation_schedule"
                )

                // Slot 5: More (المزيد - opens clean module grid for all screens)
                BottomBarNavTab(
                    icon = Icons.Default.GridView,
                    labelAr = "المزيد",
                    selected = currentScreen in listOf(
                        AppScreen.FARM_SOIL_STUDIO,
                        AppScreen.METEO_ANALYTICS,
                        AppScreen.AI_DIGITAL_TWIN_HUB,
                        AppScreen.ADMIN_PORTAL
                    ),
                    onClick = { showQuickModulesSheet = true },
                    testTag = "nav_tab_more"
                )
            }
        }
    }
}

@Composable
private fun BottomBarNavTab(
    icon: ImageVector,
    labelAr: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = labelAr,
            tint = if (selected) LimeBright else MintTextSoft,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = labelAr,
            color = if (selected) LimeBright else MintTextSoft,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun QuickActionsAndModulesDialog(
    currentScreen: AppScreen,
    canAccessAdminPortal: Boolean,
    onSelectScreen: (AppScreen) -> Unit,
    onOpenAuthModal: () -> Unit,
    onOpenPrintModal: () -> Unit,
    onDismiss: () -> Unit
) {
    val allModules = buildList {
        add(AppScreen.HOME)
        add(AppScreen.DISEASE_RADAR)
        add(AppScreen.IRRIGATION_SCHEDULE)
        add(AppScreen.SATELLITE_NDVI_MAP)
        add(AppScreen.FARM_SOIL_STUDIO)
        add(AppScreen.METEO_ANALYTICS)
        add(AppScreen.WHATSAPP_BOT)
        add(AppScreen.AI_DIGITAL_TWIN_HUB)
        if (canAccessAdminPortal) add(AppScreen.ADMIN_PORTAL)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = ForestBgDark),
            border = BorderStroke(1.5.dp, ForestCardBorder),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "التنقل السريع وأقسام المزرعة",
                            color = MintTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "اختر القسم المطلوب للانتقال الفوري",
                            color = MintTextSoft,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ForestCardDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = MintTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(color = ForestCardBorder)

                allModules.chunked(2).forEach { rowScreens ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowScreens.forEach { screen ->
                            val isSelected = screen == currentScreen
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) ForestCardElevated else ForestCardDark
                                ),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) LimeAccent else ForestCardBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSelectScreen(screen) }
                                    .testTag("quick_menu_${screen.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) LimeAccent else LimeSoftBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = screenIcon(screen),
                                            contentDescription = null,
                                            tint = if (isSelected) ForestBgDeep else LimeBright,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = screenShortLabel(screen),
                                            color = MintTextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = screen.titleAr,
                                            color = MintTextSoft,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        if (rowScreens.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                HorizontalDivider(color = ForestCardBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = ForestCardDark,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ForestCardBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenAuthModal)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = LimeBright,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "حساب المزارع / دخول",
                                color = MintTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        color = ForestCardDark,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ForestCardBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenPrintModal)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = null,
                                tint = LimeBright,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "طباعة تقرير PDF",
                                color = MintTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgroSightDrawerContent(
    currentScreen: AppScreen,
    activeFarmer: FarmerEntity,
    fieldName: String,
    areaFeddans: Float,
    cropNameAr: String,
    soilPh: Float,
    todayEt0: Float,
    onSelectScreen: (AppScreen) -> Unit,
    onOpenAuthModal: () -> Unit,
    onOpenDbSchemaModal: () -> Unit,
    onOpenPrintReport: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(ForestBgDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ForestCardDark),
            border = BorderStroke(1.dp, ForestCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = activeFarmer.fullName,
                    color = MintTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "$fieldName • $cropNameAr (${areaFeddans.toInt()} فدان)",
                    color = MintTextSoft,
                    fontSize = 12.sp
                )
                Text(
                    text = "pH ${formatDecimal1(soilPh)} • ET₀ ${formatDecimal2(todayEt0)} مم/يوم",
                    color = LimeBright,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(color = ForestCardBorder)

        val drawerScreens = buildList {
            add(AppScreen.HOME)
            add(AppScreen.DISEASE_RADAR)
            add(AppScreen.IRRIGATION_SCHEDULE)
            add(AppScreen.SATELLITE_NDVI_MAP)
            add(AppScreen.FARM_SOIL_STUDIO)
            add(AppScreen.METEO_ANALYTICS)
            add(AppScreen.WHATSAPP_BOT)
            add(AppScreen.AI_DIGITAL_TWIN_HUB)
            if (activeFarmer.role.canAccessAdminPortal) add(AppScreen.ADMIN_PORTAL)
        }

        drawerScreens.forEach { screen ->
            val selected = screen == currentScreen
            NavigationDrawerItem(
                label = {
                    Column {
                        Text(
                            text = screenShortLabel(screen),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = screen.titleAr,
                            fontSize = 10.sp,
                            color = MintTextSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                icon = {
                    Icon(
                        imageVector = screenIcon(screen),
                        contentDescription = screen.titleAr
                    )
                },
                selected = selected,
                onClick = {
                    onSelectScreen(screen)
                    onCloseDrawer()
                },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = ForestCardElevated,
                    unselectedContainerColor = Color.Transparent,
                    selectedIconColor = LimeBright,
                    unselectedIconColor = MintTextSoft,
                    selectedTextColor = MintTextPrimary,
                    unselectedTextColor = MintTextSoft
                )
            )
        }

        HorizontalDivider(color = ForestCardBorder)

        NavigationDrawerItem(
            label = { Text("توثيق الحساب / تبديل المزارع", fontSize = 13.sp) },
            icon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
            selected = false,
            onClick = {
                onCloseDrawer()
                onOpenAuthModal()
            },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedIconColor = LimeBright,
                unselectedTextColor = MintTextPrimary
            )
        )

        NavigationDrawerItem(
            label = { Text("طباعة تقرير الري الأسبوعي (PDF)", fontSize = 13.sp) },
            icon = { Icon(Icons.Default.Print, contentDescription = null) },
            selected = false,
            onClick = {
                onCloseDrawer()
                onOpenPrintReport()
            },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedIconColor = LimeBright,
                unselectedTextColor = MintTextPrimary
            )
        )

        NavigationDrawerItem(
            label = { Text("مخطط قاعدة البيانات (SQL Schema)", fontSize = 13.sp) },
            icon = { Icon(Icons.Default.Storage, contentDescription = null) },
            selected = false,
            onClick = {
                onCloseDrawer()
                onOpenDbSchemaModal()
            },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedIconColor = MintTextSoft,
                unselectedTextColor = MintTextSoft
            )
        )
    }
}
