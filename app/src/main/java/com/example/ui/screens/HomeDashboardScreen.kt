package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AnomalyPattern
import com.example.data.FieldEntity
import com.example.model.AppScreen
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.ui.theme.AmberLight
import com.example.ui.theme.CyanLight
import com.example.ui.theme.ForestBgDark
import com.example.ui.theme.ForestBgDeep
import com.example.ui.theme.ForestBgMid
import com.example.ui.theme.ForestCardBorder
import com.example.ui.theme.ForestCardDark
import com.example.ui.theme.ForestCardElevated
import com.example.ui.theme.ForestCardGlass
import com.example.ui.theme.LimeAccent
import com.example.ui.theme.LimeBright
import com.example.ui.theme.LimeSoftBg
import com.example.ui.theme.MintTextMuted
import com.example.ui.theme.MintTextPrimary
import com.example.ui.theme.MintTextSoft
import com.example.ui.theme.RoseLight
import com.example.viewmodel.AgroSightUiState
import kotlin.math.roundToInt

private enum class HomeSubMode {
    WELCOME_COVER,
    MAIN_DASHBOARD,
    FIELD_DETAIL
}

@Composable
fun HomeDashboardScreen(
    uiState: AgroSightUiState,
    onNavigateToScreen: (AppScreen) -> Unit,
    onSelectField: (String) -> Unit,
    onOpenAuthModal: () -> Unit,
    onSendMorningWhatsAppReport: () -> Unit,
    onRefreshTelemetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var subMode by rememberSaveable { mutableStateOf(HomeSubMode.MAIN_DASHBOARD) }
    var showHeroWelcomeBanner by rememberSaveable { mutableStateOf(true) }

    if (subMode != HomeSubMode.MAIN_DASHBOARD) {
        BackHandler {
            subMode = HomeSubMode.MAIN_DASHBOARD
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        ForestBgDark,
                        ForestBgDeep
                    )
                )
            )
            .testTag("home_dashboard_screen")
    ) {
        AnimatedContent(
            targetState = subMode,
            label = "home_submode_transition"
        ) { targetMode ->
            when (targetMode) {
                HomeSubMode.WELCOME_COVER -> {
                    FullScreenWelcomeHeroCover(
                        uiState = uiState,
                        onGetStarted = { subMode = HomeSubMode.MAIN_DASHBOARD }
                    )
                }

                HomeSubMode.MAIN_DASHBOARD -> {
                    MainHomeOverviewContent(
                        uiState = uiState,
                        showHeroWelcomeBanner = showHeroWelcomeBanner,
                        onToggleWelcomeCover = { subMode = HomeSubMode.WELCOME_COVER },
                        onDismissHeroBanner = { showHeroWelcomeBanner = false },
                        onNavigateToScreen = onNavigateToScreen,
                        onSelectFieldAndOpenDetail = { fieldId ->
                            onSelectField(fieldId)
                            subMode = HomeSubMode.FIELD_DETAIL
                        },
                        onOpenFieldDetail = { subMode = HomeSubMode.FIELD_DETAIL },
                        onOpenAuthModal = onOpenAuthModal,
                        onSendMorningWhatsAppReport = onSendMorningWhatsAppReport
                    )
                }

                HomeSubMode.FIELD_DETAIL -> {
                    FieldDetailStreamlinedView(
                        uiState = uiState,
                        onBackToOverview = { subMode = HomeSubMode.MAIN_DASHBOARD },
                        onNavigateToScreen = onNavigateToScreen,
                        onRefreshTelemetry = onRefreshTelemetry
                    )
                }
            }
        }
    }
}

// ============================================================================
// SCREEN 1 OF MOCKUP: FULL-BLEED DRONE & CROP ROWS HERO COVER
// ============================================================================

@Composable
private fun FullScreenWelcomeHeroCover(
    uiState: AgroSightUiState,
    onGetStarted: () -> Unit
) {
    val today = uiState.forecastDays.firstOrNull()
    val tempInt = (today?.tempMaxC ?: 28f).roundToInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ForestBgDeep)
            .testTag("welcome_hero_cover")
    ) {
        // Custom Canvas: Lush green crop rows converging to horizon + agricultural drone spraying mist
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Sky & deep forest horizon gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF14381E),
                        Color(0xFF1D4D2B),
                        Color(0xFF0A2112),
                        Color(0xFF06150B)
                    )
                )
            )

            // Perspective crop rows
            val horizonY = h * 0.18f
            val vanishX = w * 0.5f
            val rowCount = 16
            for (i in -rowCount..rowCount) {
                val bottomX = vanishX + (i * (w * 0.14f))
                val path = Path().apply {
                    moveTo(vanishX + i * 8f, horizonY)
                    lineTo(bottomX - w * 0.04f, h)
                    lineTo(bottomX + w * 0.04f, h)
                    close()
                }
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF4C9E32).copy(alpha = 0.35f),
                            Color(0xFF6DBE45).copy(alpha = 0.25f),
                            Color(0xFF0A2413).copy(alpha = 0.85f)
                        ),
                        startY = horizonY,
                        endY = h
                    )
                )
            }

            // Golden precision spray cones beneath the drone
            val droneCenterY = h * 0.31f
            val sprayNozzles = listOf(
                w * 0.22f,
                w * 0.36f,
                w * 0.64f,
                w * 0.78f
            )
            sprayNozzles.forEach { nozzleX ->
                val conePath = Path().apply {
                    moveTo(nozzleX, droneCenterY + 18f)
                    lineTo(nozzleX - w * 0.11f, droneCenterY + h * 0.22f)
                    lineTo(nozzleX + w * 0.11f, droneCenterY + h * 0.22f)
                    close()
                }
                drawPath(
                    path = conePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF59E0B).copy(alpha = 0.55f),
                            Color(0xFFD97706).copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        startY = droneCenterY + 18f,
                        endY = droneCenterY + h * 0.22f
                    )
                )
            }

            // Agricultural Quad-Drone silhouette in center-top
            drawLine(
                color = Color(0xFFCBD5E1),
                start = Offset(w * 0.18f, droneCenterY),
                end = Offset(w * 0.82f, droneCenterY),
                strokeWidth = 7f,
                cap = StrokeCap.Round
            )
            // Drone body pod
            drawRoundRect(
                color = Color(0xFFF8FAFC),
                topLeft = Offset(w * 0.42f, droneCenterY - 22f),
                size = Size(w * 0.16f, 44f),
                cornerRadius = CornerRadius(18f, 18f)
            )
            // Camera gimbal lens
            drawCircle(
                color = Color(0xFF0F172A),
                radius = 12f,
                center = Offset(w * 0.5f, droneCenterY + 8f)
            )
            drawCircle(
                color = CyanLight,
                radius = 5f,
                center = Offset(w * 0.5f, droneCenterY + 8f)
            )
            // Rotors
            sprayNozzles.forEach { rx ->
                drawCircle(
                    color = Color(0xFF1E293B),
                    radius = 10f,
                    center = Offset(rx, droneCenterY)
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.65f),
                    start = Offset(rx - 28f, droneCenterY - 10f),
                    end = Offset(rx + 28f, droneCenterY - 10f),
                    strokeWidth = 3.5f,
                    cap = StrokeCap.Round
                )
            }

            // Bottom dark vignette for crisp headline contrast
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        ForestBgDeep.copy(alpha = 0.78f),
                        ForestBgDeep
                    ),
                    startY = h * 0.44f,
                    endY = h
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Floating Weather Pill (matches 28° Raining • Location in Screen 1)
            Surface(
                color = ForestCardDark.copy(alpha = 0.88f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, ForestCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = null,
                        tint = AmberLight,
                        modifier = Modifier.size(30.dp)
                    )
                    Column {
                        Text(
                            text = "$tempInt°",
                            color = MintTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "أجواء زراعية مستقرة",
                            color = MintTextSoft,
                            fontSize = 11.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = LimeBright,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = uiState.activeFarmer.region,
                                color = MintTextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // Bottom Headline + Subtitle + Lime Pill CTA Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "مزرعتك ترتقي إلى\nالمستوى التالي من الذكاء!",
                    color = MintTextPrimary,
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "حلول زراعية ذكية تجمع الأقمار الصناعية Sentinel-2\nوجدولة الري الدقيق لمن يسعون للتميز والإنتاجية.",
                    color = MintTextSoft,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = onGetStarted,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LimeAccent,
                        contentColor = ForestBgDeep
                    ),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("welcome_get_started_button")
                ) {
                    Text(
                        text = "ابدأ الآن",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = ">>>",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

// ============================================================================
// SCREEN 2 OF MOCKUP: MAIN HOME OVERVIEW (GREETING, WEATHER, 2x2 GRID, FIELDS)
// ============================================================================

@Composable
private fun MainHomeOverviewContent(
    uiState: AgroSightUiState,
    showHeroWelcomeBanner: Boolean,
    onToggleWelcomeCover: () -> Unit,
    onDismissHeroBanner: () -> Unit,
    onNavigateToScreen: (AppScreen) -> Unit,
    onSelectFieldAndOpenDetail: (String) -> Unit,
    onOpenFieldDetail: () -> Unit,
    onOpenAuthModal: () -> Unit,
    onSendMorningWhatsAppReport: () -> Unit
) {
    val today = uiState.forecastDays.firstOrNull()
    val tempInt = (today?.tempMaxC ?: 32f).roundToInt()
    val humidityPct = today?.relativeHumidityPct ?: 65
    val windKmh = today?.windSpeedKmh ?: 12.0f
    val et0Mm = today?.et0MmDay ?: 5.4f

    val fieldHealthPct = ((uiState.epidemiologyReport.ndviMean * 100f).roundToInt() + 18)
        .coerceIn(68, 96)
    val estimatedYieldTonsPerFeddan = (uiState.soilDiagnostics.estimatedYieldPotentialPercent / 100f) * 9.8f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Clean Top Greeting Bar ("Hi, Sarah 👋 / أهلاً، م. أحمد 👋")
        item {
            HomeGreetingHeader(
                uiState = uiState,
                onOpenAuthModal = onOpenAuthModal,
                onOpenNotifications = { onNavigateToScreen(AppScreen.WHATSAPP_BOT) },
                onOpenAdminPortal = { onNavigateToScreen(AppScreen.ADMIN_PORTAL) }
            )
        }

        // 2. Compact Interactive Drone Hero Banner (Links to Screen 1 cover or Quick Tour)
        item {
            AnimatedVisibility(
                visible = showHeroWelcomeBanner,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                CompactDroneHeroCard(
                    onOpenFullCover = onToggleWelcomeCover,
                    onStartFieldInspection = onOpenFieldDetail,
                    onDismiss = onDismissHeroBanner
                )
            }
        }

        // 3. Weather Glass Card (Exact 2-column layout from Screen 2 of Mockup)
        item {
            HomeWeatherGlassCard(
                tempInt = tempInt,
                regionName = uiState.activeFarmer.region,
                humidityPct = humidityPct,
                windKmh = windKmh,
                et0Mm = et0Mm,
                onClick = { onNavigateToScreen(AppScreen.METEO_ANALYTICS) }
            )
        }

        // 4. Farm Overview (نظرة عامة على المزرعة + View All) -> 2x2 Bento Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeaderRow(
                    titleAr = "نظرة عامة على المزرعة",
                    actionLabelAr = "عرض التفاصيل",
                    onActionClick = onOpenFieldDetail
                )

                val totalAreaFeddans = uiState.dbSnapshot.fields
                    .filter { it.farmerId == uiState.activeFarmer.id }
                    .sumOf { it.areaFeddans.toDouble() }
                    .toFloat()
                    .takeIf { it > 0f } ?: uiState.areaFeddans

                val activeCropsCount = uiState.dbSnapshot.fields.map { it.cropType }.distinct().size.coerceAtLeast(1)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FarmOverviewBentoCard(
                        labelAr = "إجمالي المساحة",
                        mainValue = "${totalAreaFeddans.roundToInt()}",
                        unitAr = "فدان",
                        subtitleAr = "+5 فدان هذا الموسم",
                        subtitleColor = LimeBright,
                        icon = Icons.Default.Eco,
                        onClick = { onNavigateToScreen(AppScreen.FARM_SOIL_STUDIO) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bento_total_area")
                    )

                    FarmOverviewBentoCard(
                        labelAr = "المحاصيل النشطة",
                        mainValue = "$activeCropsCount",
                        unitAr = "محاصيل",
                        subtitleAr = "الحالي: ${uiState.selectedCrop.nameAr}",
                        subtitleColor = MintTextSoft,
                        icon = Icons.Default.Grass,
                        onClick = { onNavigateToScreen(AppScreen.FARM_SOIL_STUDIO) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bento_active_crops")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FarmOverviewBentoCard(
                        labelAr = "صحة الحقل",
                        mainValue = "$fieldHealthPct%",
                        unitAr = "",
                        subtitleAr = if (fieldHealthPct >= 80) "حالة ممتازة ومستقرة" else "يحتاج متابعة طيفية",
                        subtitleColor = if (fieldHealthPct >= 80) LimeBright else AmberLight,
                        icon = Icons.Default.Favorite,
                        onClick = onOpenFieldDetail,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bento_field_health")
                    )

                    FarmOverviewBentoCard(
                        labelAr = "الإنتاجية المتوقعة",
                        mainValue = formatDecimal1(estimatedYieldTonsPerFeddan),
                        unitAr = "طن/فدان",
                        subtitleAr = "+12% عن الموسم السابق",
                        subtitleColor = LimeBright,
                        icon = Icons.Default.BarChart,
                        onClick = { onNavigateToScreen(AppScreen.IRRIGATION_SCHEDULE) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bento_estimated_yield")
                    )
                }
            }
        }

        // 5. Active Fields (الحقول النشطة + View All) matching Screen 2 cards
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeaderRow(
                    titleAr = "الحقول النشطة",
                    actionLabelAr = "خريطة القطاعات",
                    onActionClick = { onNavigateToScreen(AppScreen.SATELLITE_NDVI_MAP) }
                )

                val fieldsList = uiState.dbSnapshot.fields
                fieldsList.forEachIndexed { index, field ->
                    val isSelected = field.id == uiState.fieldId
                    val scan = uiState.dbSnapshot.satelliteSpectralScans.find { it.fieldId == field.id }
                    val healthScore = ((scan?.ndviMean ?: 0.68f) * 100f).roundToInt().coerceIn(65, 95)
                    val isHealthy = scan?.anomalyPattern == AnomalyPattern.HEALTHY || healthScore >= 78

                    ActiveFieldCardItem(
                        field = field,
                        index = index,
                        isSelected = isSelected,
                        healthScorePct = healthScore,
                        healthLabelAr = if (isHealthy) "جيدة ($healthScore%)" else "متوسطة ($healthScore%)",
                        statusLabelAr = if (isHealthy) "في المسار" else "يحتاج انتباه",
                        isOnTrack = isHealthy,
                        onClick = { onSelectFieldAndOpenDetail(field.id) }
                    )
                }
            }
        }

        // 6. Quick Access Strip for Advanced Scientific & AI Modules
        item {
            StreamlinedModulesQuickRow(
                uiState = uiState,
                onNavigateToScreen = onNavigateToScreen,
                onSendMorningWhatsAppReport = onSendMorningWhatsAppReport
            )
        }
    }
}

@Composable
private fun HomeGreetingHeader(
    uiState: AgroSightUiState,
    onOpenAuthModal: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAdminPortal: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenAuthModal)
                .padding(vertical = 4.dp)
                .testTag("home_profile_header")
        ) {
            // Circular Farmer Avatar with Lime Border
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(LimeAccent, ForestCardElevated)
                        )
                    )
                    .border(2.dp, LimeBright, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "حساب المزارع",
                    tint = ForestBgDeep,
                    modifier = Modifier.size(34.dp)
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "أهلاً، ${uiState.activeFarmer.fullName} 👋",
                        color = MintTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "إليك ما يحدث في مزرعتك اليوم • ${uiState.activeFarmer.region}",
                    color = MintTextSoft,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.activeFarmer.role.canAccessAdminPortal) {
                IconButton(
                    onClick = onOpenAdminPortal,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ForestCardDark)
                        .border(1.dp, ForestCardBorder, CircleShape)
                        .testTag("home_admin_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AdminPanelSettings,
                        contentDescription = "بوابة الإدارة",
                        tint = LimeBright,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            IconButton(
                onClick = onOpenNotifications,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ForestCardDark)
                    .border(1.dp, ForestCardBorder, CircleShape)
                    .testTag("home_notifications_button")
            ) {
                Box {
                    Icon(
                        imageVector = Icons.Default.NotificationsNone,
                        contentDescription = "التنبيهات",
                        tint = MintTextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(LimeBright)
                            .align(Alignment.TopEnd)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactDroneHeroCard(
    onOpenFullCover: () -> Unit,
    onStartFieldInspection: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ForestCardDark),
        border = BorderStroke(1.dp, ForestCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenFullCover)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF1A4728),
                            Color(0xFF10301B)
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = LimeAccent.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, LimeAccent.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "🛰️ الجيل الجديد من الزراعة الذكية",
                            color = LimeBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "مزرعتك في مستوى جديد من الدقة!",
                        color = MintTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "افحص خريطة الحقل الحرارية، جدول الري، وصحة النبات بضغطة واحدة.",
                        color = MintTextSoft,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStartFieldInspection,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LimeAccent,
                                contentColor = ForestBgDeep
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "فحص الحقل الآن >>>",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Surface(
                            color = Color.Transparent,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, ForestCardBorder),
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onOpenFullCover)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = "عرض الغلاف",
                                    color = MintTextSoft,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeWeatherGlassCard(
    tempInt: Int,
    regionName: String,
    humidityPct: Int,
    windKmh: Float,
    et0Mm: Float,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ForestCardDark),
        border = BorderStroke(1.dp, ForestCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("home_weather_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Column: Sun/Cloud icon + Temperature + Location
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1.05f)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(LimeSoftBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = null,
                        tint = AmberLight,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "$tempInt°",
                            color = MintTextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "مشمس",
                            color = MintTextSoft,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MintTextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = regionName,
                            color = MintTextSoft,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Right Column: Humidity, Wind, ET0/Rain rows with dividers (exact match to mockup)
            Column(
                modifier = Modifier.weight(1.1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WeatherMetricMiniRow(
                    icon = Icons.Default.WaterDrop,
                    labelAr = "الرطوبة",
                    valueAr = "$humidityPct%"
                )
                HorizontalDivider(color = ForestCardBorder.copy(alpha = 0.5f), thickness = 0.7.dp)
                WeatherMetricMiniRow(
                    icon = Icons.Default.Air,
                    labelAr = "الرياح",
                    valueAr = "${formatDecimal1(windKmh)} كم/س"
                )
                HorizontalDivider(color = ForestCardBorder.copy(alpha = 0.5f), thickness = 0.7.dp)
                WeatherMetricMiniRow(
                    icon = Icons.Default.Cloud,
                    labelAr = "البخر-نتح",
                    valueAr = "${formatDecimal1(et0Mm)} مم"
                )
            }
        }
    }
}

@Composable
private fun WeatherMetricMiniRow(
    icon: ImageVector,
    labelAr: String,
    valueAr: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MintTextSoft,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = labelAr,
                color = MintTextSoft,
                fontSize = 11.sp
            )
        }
        Text(
            text = valueAr,
            color = MintTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SectionHeaderRow(
    titleAr: String,
    actionLabelAr: String,
    onActionClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = titleAr,
            color = MintTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = actionLabelAr,
            color = LimeBright,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onActionClick)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun FarmOverviewBentoCard(
    labelAr: String,
    mainValue: String,
    unitAr: String,
    subtitleAr: String,
    subtitleColor: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ForestCardDark),
        border = BorderStroke(1.dp, ForestCardBorder),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = labelAr,
                        color = MintTextSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = mainValue,
                            color = MintTextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (unitAr.isNotEmpty()) {
                            Text(
                                text = unitAr,
                                color = MintTextSoft,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }
                }

                // Circular Lime-Tinted Icon Badge (Matches Screen 2 Bento cards)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(LimeSoftBg)
                        .border(1.dp, ForestCardBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = LimeBright,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = subtitleAr,
                color = subtitleColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActiveFieldCardItem(
    field: FieldEntity,
    index: Int,
    isSelected: Boolean,
    healthScorePct: Int,
    healthLabelAr: String,
    statusLabelAr: String,
    isOnTrack: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) ForestCardElevated else ForestCardDark
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) LimeAccent else ForestCardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("active_field_card_${field.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Left Aerial Crop Rows Thumbnail (Custom Canvas matching the field photo in Screen 2)
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, ForestCardBorder, RoundedCornerShape(14.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = if (index % 2 == 0) {
                                listOf(Color(0xFF285E2B), Color(0xFF56A836), Color(0xFF173A1E))
                            } else {
                                listOf(Color(0xFF1F4E29), Color(0xFF428E36), Color(0xFF112C17))
                            }
                        )
                    )
                    // Curved crop rows
                    for (r in 0..7) {
                        val startX = r * (w / 6f)
                        val p = Path().apply {
                            moveTo(startX, 0f)
                            quadraticTo(w * 0.45f, h * 0.5f, startX - w * 0.2f, h)
                        }
                        drawPath(
                            path = p,
                            color = LimeBright.copy(alpha = 0.35f),
                            style = Stroke(width = 4f)
                        )
                    }
                }

                if (isSelected) {
                    Surface(
                        color = LimeAccent,
                        shape = RoundedCornerShape(bottomEnd = 10.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            text = "نشط",
                            color = ForestBgDeep,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Right Field Details: Title, Crop tag, and 3 columns (Area | Health | Status)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = field.fieldName,
                        color = MintTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MintTextSoft,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Eco,
                        contentDescription = null,
                        tint = LimeBright,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "${field.cropType} • ${field.irrigationType}",
                        color = MintTextSoft,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 3-Column Metrics Row: Area | Health | Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FieldMiniColumn(
                        labelAr = "المساحة",
                        valueAr = "${field.areaFeddans.roundToInt()} فدان",
                        valueColor = MintTextPrimary
                    )

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(ForestCardBorder)
                    )

                    FieldMiniColumn(
                        labelAr = "الصحة",
                        valueAr = healthLabelAr,
                        valueColor = if (isOnTrack) LimeBright else AmberLight
                    )

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(ForestCardBorder)
                    )

                    FieldMiniColumn(
                        labelAr = "الحالة",
                        valueAr = statusLabelAr,
                        valueColor = if (isOnTrack) MintTextPrimary else AmberLight
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldMiniColumn(
    labelAr: String,
    valueAr: String,
    valueColor: Color
) {
    Column {
        Text(
            text = labelAr,
            color = MintTextMuted,
            fontSize = 10.sp
        )
        Text(
            text = valueAr,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StreamlinedModulesQuickRow(
    uiState: AgroSightUiState,
    onNavigateToScreen: (AppScreen) -> Unit,
    onSendMorningWhatsAppReport: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "الأدوات الذكية السريعة",
            color = MintTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickShortcutPill(
                icon = Icons.Default.AutoAwesome,
                titleAr = "المساعد الصوتي 3D",
                subtitleAr = "Gemini + خرائط",
                onClick = { onNavigateToScreen(AppScreen.AI_DIGITAL_TWIN_HUB) },
                modifier = Modifier.weight(1f)
            )
            QuickShortcutPill(
                icon = Icons.Default.Science,
                titleAr = "مختبر التربة",
                subtitleAr = "pH ${formatDecimal1(uiState.soilPh)} • EC ${formatDecimal1(uiState.soilEce)}",
                onClick = { onNavigateToScreen(AppScreen.FARM_SOIL_STUDIO) },
                modifier = Modifier.weight(1f)
            )
            QuickShortcutPill(
                icon = Icons.Default.CheckCircle,
                titleAr = "تقرير واتساب",
                subtitleAr = "إرسال فوري",
                onClick = onSendMorningWhatsAppReport,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun QuickShortcutPill(
    icon: ImageVector,
    titleAr: String,
    subtitleAr: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ForestCardDark),
        border = BorderStroke(1.dp, ForestCardBorder),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LimeBright,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = titleAr,
                color = MintTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitleAr,
                color = MintTextSoft,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================================
// SCREEN 3 OF MOCKUP: FIELD DETAIL VIEW (3D THERMAL MAP, 4 ACTION PILLS,
// FIELD HEALTH INDEX WAVE CHART, RECENT ACTIVITY LIST)
// ============================================================================

@Composable
private fun FieldDetailStreamlinedView(
    uiState: AgroSightUiState,
    onBackToOverview: () -> Unit,
    onNavigateToScreen: (AppScreen) -> Unit,
    onRefreshTelemetry: () -> Unit
) {
    val fieldHealthPct = ((uiState.epidemiologyReport.ndviMean * 100f).roundToInt() + 18)
        .coerceIn(68, 96)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("field_detail_streamlined_view"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Top Header Bar (< Back | Field Title & Crop • Area | 3-Dots Menu)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBackToOverview,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ForestCardDark)
                        .border(1.dp, ForestCardBorder, CircleShape)
                        .testTag("field_detail_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع",
                        tint = MintTextPrimary
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = uiState.fieldName,
                        color = MintTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${uiState.selectedCrop.nameAr} • ${uiState.areaFeddans.roundToInt()} فدان",
                        color = MintTextSoft,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = onRefreshTelemetry,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ForestCardDark)
                        .border(1.dp, ForestCardBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "تحديث البيانات",
                        tint = MintTextPrimary
                    )
                }
            }
        }

        // 2. Interactive 3D-Perspective Thermal/NDVI Field Map Card (Matches Screen 3 top image)
        item {
            PerspectiveThermalFieldMapCard(
                uiState = uiState,
                onExpandMap = { onNavigateToScreen(AppScreen.SATELLITE_NDVI_MAP) },
                onOpenDiseaseRadar = { onNavigateToScreen(AppScreen.DISEASE_RADAR) }
            )
        }

        // 3. 4 Quick Action Icon Pills Bar (Overview | Irrigation | Scouting | Task)
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ForestCardDark),
                border = BorderStroke(1.dp, ForestCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FieldActionTabItem(
                        icon = Icons.Default.GridView,
                        labelAr = "نظرة عامة",
                        isActive = true,
                        onClick = {}
                    )
                    FieldActionTabItem(
                        icon = Icons.Default.WaterDrop,
                        labelAr = "الري",
                        isActive = false,
                        onClick = { onNavigateToScreen(AppScreen.IRRIGATION_SCHEDULE) }
                    )
                    FieldActionTabItem(
                        icon = Icons.Default.Radar,
                        labelAr = "الرصد الطيفي",
                        isActive = false,
                        onClick = { onNavigateToScreen(AppScreen.DISEASE_RADAR) }
                    )
                    FieldActionTabItem(
                        icon = Icons.Default.AssignmentTurnedIn,
                        labelAr = "المهام",
                        isActive = false,
                        onClick = { onNavigateToScreen(AppScreen.WHATSAPP_BOT) }
                    )
                }
            }
        }

        // 4. Field Health Index Card with Smooth Green Wave Area Chart (Matches Screen 3 center card)
        item {
            FieldHealthIndexWaveCard(
                healthPct = fieldHealthPct,
                ndviMean = uiState.epidemiologyReport.ndviMean,
                onClick = { onNavigateToScreen(AppScreen.DISEASE_RADAR) }
            )
        }

        // 5. Recent Activity List (النشاط الأخير + View All) with "مكتمل / Completed" pills
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeaderRow(
                    titleAr = "النشاط الأخير",
                    actionLabelAr = "عرض السجل",
                    onActionClick = { onNavigateToScreen(AppScreen.IRRIGATION_SCHEDULE) }
                )

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestCardDark),
                    border = BorderStroke(1.dp, ForestCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        val todaySchedule = uiState.weeklySchedule.firstOrNull()
                        val waterM3Text = formatDecimal1(todaySchedule?.grossM3PerFeddan ?: 26.4f)

                        RecentActivityRowItem(
                            icon = Icons.Default.WaterDrop,
                            iconBgColor = Color(0xFF1D4ED8),
                            iconTint = Color(0xFF93C5FD),
                            titleAr = "تم إكمال الري ($waterM3Text م³/فدان)",
                            subtitleAr = "${uiState.fieldName} • اليوم، 8:30 ص",
                            statusAr = "مكتمل",
                            onClick = { onNavigateToScreen(AppScreen.IRRIGATION_SCHEDULE) }
                        )

                        HorizontalDivider(
                            color = ForestCardBorder.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        RecentActivityRowItem(
                            icon = Icons.Default.Eco,
                            iconBgColor = LimeSoftBg,
                            iconTint = LimeBright,
                            titleAr = "تطبيق التسميد ومعادلة الحموضة",
                            subtitleAr = "${uiState.fieldName} • حقن ${formatDecimal1(uiState.epidemiologyReport.acidDoseLitersPerFeddan)} لتر حامض/فدان",
                            statusAr = "مكتمل",
                            onClick = { onNavigateToScreen(AppScreen.FARM_SOIL_STUDIO) }
                        )

                        HorizontalDivider(
                            color = ForestCardBorder.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        RecentActivityRowItem(
                            icon = Icons.Default.Radar,
                            iconBgColor = Color(0xFF422006),
                            iconTint = AmberLight,
                            titleAr = "مسح القمر الصناعي Sentinel-2",
                            subtitleAr = "${uiState.fieldName} • ${uiState.epidemiologyReport.detectedRiskNameAr}",
                            statusAr = "مكتمل",
                            onClick = { onNavigateToScreen(AppScreen.DISEASE_RADAR) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerspectiveThermalFieldMapCard(
    uiState: AgroSightUiState,
    onExpandMap: () -> Unit,
    onOpenDiseaseRadar: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ForestCardDark),
        border = BorderStroke(1.dp, ForestCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .height(215.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Custom 3D-tilted satellite thermal/NDVI field canvas matching Screen 3 mockup
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Deep dark green outer terrain
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF102815),
                            Color(0xFF193C20),
                            Color(0xFF0C2011)
                        )
                    )
                )

                // Subtle background field rows
                for (i in 0..14) {
                    val y = i * (h / 14f)
                    drawLine(
                        color = Color(0xFF234D2A).copy(alpha = 0.45f),
                        start = Offset(0f, y),
                        end = Offset(w, y + 25f),
                        strokeWidth = 3f
                    )
                }

                // 3D-Perspective tilted polygon boundary of the monitored field
                val pTopLeft = Offset(w * 0.34f, h * 0.12f)
                val pTopRight = Offset(w * 0.90f, h * 0.36f)
                val pBottomRight = Offset(w * 0.62f, h * 0.88f)
                val pBottomLeft = Offset(w * 0.07f, h * 0.56f)

                val fieldBoundary = Path().apply {
                    moveTo(pTopLeft.x, pTopLeft.y)
                    lineTo(pTopRight.x, pTopRight.y)
                    lineTo(pBottomRight.x, pBottomRight.y)
                    lineTo(pBottomLeft.x, pBottomLeft.y)
                    close()
                }

                // Healthy chlorophyll base inside polygon
                drawPath(
                    path = fieldBoundary,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF45A834),
                            Color(0xFF76C945),
                            Color(0xFFEAB308),
                            Color(0xFFEF4444)
                        ),
                        start = pBottomLeft,
                        end = pTopRight
                    )
                )

                // Internal sub-plot grid lines inside the 3D field
                val gridSteps = 6
                for (g in 1 until gridSteps) {
                    val t = g / gridSteps.toFloat()
                    val startH = Offset(
                        pTopLeft.x + (pBottomLeft.x - pTopLeft.x) * t,
                        pTopLeft.y + (pBottomLeft.y - pTopLeft.y) * t
                    )
                    val endH = Offset(
                        pTopRight.x + (pBottomRight.x - pTopRight.x) * t,
                        pTopRight.y + (pBottomRight.y - pTopRight.y) * t
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.25f),
                        start = startH,
                        end = endH,
                        strokeWidth = 1.5f
                    )

                    val startV = Offset(
                        pTopLeft.x + (pTopRight.x - pTopLeft.x) * t,
                        pTopLeft.y + (pTopRight.y - pTopLeft.y) * t
                    )
                    val endV = Offset(
                        pBottomLeft.x + (pBottomRight.x - pBottomLeft.x) * t,
                        pBottomLeft.y + (pBottomRight.y - pBottomLeft.y) * t
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.25f),
                        start = startV,
                        end = endV,
                        strokeWidth = 1.5f
                    )
                }

                // Thermal stress hotspot glow on right sector (matching red/orange zone in Screen 3)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFEF4444).copy(alpha = 0.78f),
                            Color(0xFFF59E0B).copy(alpha = 0.45f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.68f, h * 0.52f),
                        radius = w * 0.22f
                    ),
                    radius = w * 0.22f,
                    center = Offset(w * 0.68f, h * 0.52f)
                )

                // Crisp white perimeter outline around the 3D field
                drawPath(
                    path = fieldBoundary,
                    color = Color.White.copy(alpha = 0.9f),
                    style = Stroke(width = 3.5f)
                )
            }

            // Top-Start Satellite Live Badge
            Surface(
                color = ForestBgDeep.copy(alpha = 0.78f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, ForestCardBorder),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(LimeBright)
                    )
                    Text(
                        text = "Sentinel-2 NDVI: ${formatDecimal2(uiState.epidemiologyReport.ndviMean)}",
                        color = MintTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Bottom-Left Expand Button (Matches Screen 3 mockup)
            IconButton(
                onClick = onExpandMap,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ForestBgDeep.copy(alpha = 0.82f))
                    .border(1.dp, ForestCardBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "تكبير الخريطة",
                    tint = MintTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Bottom-Right Drone/Radar Button (Matches Screen 3 mockup)
            IconButton(
                onClick = onOpenDiseaseRadar,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ForestBgDeep.copy(alpha = 0.82f))
                    .border(1.dp, ForestCardBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = "رادار الأمراض الطيفي",
                    tint = LimeBright,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FieldActionTabItem(
    icon: ImageVector,
    labelAr: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isActive) LimeSoftBg else ForestCardElevated)
                .border(
                    width = 1.dp,
                    color = if (isActive) LimeAccent else ForestCardBorder,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = labelAr,
                tint = if (isActive) LimeBright else MintTextSoft,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = labelAr,
            color = if (isActive) LimeBright else MintTextSoft,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium
        )
    }
}

@Composable
private fun FieldHealthIndexWaveCard(
    healthPct: Int,
    ndviMean: Float,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ForestCardDark),
        border = BorderStroke(1.dp, ForestCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "مؤشر صحة الحقل (Field Health Index)",
                    color = MintTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left side: 85% Good (+10% vs last week)
                Column(modifier = Modifier.weight(0.9f)) {
                    Text(
                        text = "$healthPct%",
                        color = MintTextPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (healthPct >= 80) "حالة جيدة جداً" else "حالة متوسطة",
                        color = LimeBright,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "+10% مقارنة بالأسبوع الماضي",
                        color = MintTextSoft,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "NDVI = ${formatDecimal2(ndviMean)}",
                        color = MintTextMuted,
                        fontSize = 10.sp
                    )
                }

                // Right side: Smooth Green Bezier Wave Chart (1..8..15..22..29)
                Column(
                    modifier = Modifier.weight(1.3f),
                    horizontalAlignment = Alignment.End
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(95.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            // Horizontal guide lines (100%, 50%, 0%)
                            listOf(0.1f, 0.5f, 0.9f).forEach { ratio ->
                                drawLine(
                                    color = ForestCardBorder.copy(alpha = 0.45f),
                                    start = Offset(0f, h * ratio),
                                    end = Offset(w, h * ratio),
                                    strokeWidth = 1f
                                )
                            }

                            val points = listOf(
                                Offset(w * 0.04f, h * 0.78f),
                                Offset(w * 0.26f, h * 0.36f),
                                Offset(w * 0.50f, h * 0.45f),
                                Offset(w * 0.74f, h * 0.32f),
                                Offset(w * 0.96f, h * 0.16f)
                            )

                            val linePath = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                for (i in 0 until points.size - 1) {
                                    val p0 = points[i]
                                    val p1 = points[i + 1]
                                    val midX = (p0.x + p1.x) / 2f
                                    cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                                }
                            }

                            val fillPath = Path().apply {
                                addPath(linePath)
                                lineTo(points.last().x, h)
                                lineTo(points.first().x, h)
                                close()
                            }

                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        LimeAccent.copy(alpha = 0.48f),
                                        LimeAccent.copy(alpha = 0.05f),
                                        Color.Transparent
                                    )
                                )
                            )

                            drawPath(
                                path = linePath,
                                color = LimeBright,
                                style = Stroke(width = 4.5f, cap = StrokeCap.Round)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("1", "8", "15", "22", "29").forEach { dayNum ->
                            Text(
                                text = dayNum,
                                color = MintTextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentActivityRowItem(
    icon: ImageVector,
    iconBgColor: Color,
    iconTint: Color,
    titleAr: String,
    subtitleAr: String,
    statusAr: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleAr,
                    color = MintTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitleAr,
                    color = MintTextSoft,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Green "Completed / مكتمل" Pill Badge
        Surface(
            color = LimeSoftBg,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, ForestCardBorder)
        ) {
            Text(
                text = statusAr,
                color = LimeBright,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}
