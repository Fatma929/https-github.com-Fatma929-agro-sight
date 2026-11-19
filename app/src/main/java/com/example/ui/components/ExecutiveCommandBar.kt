package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AnomalyPattern
import com.example.model.AppScreen
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.model.formatHoursMinutesCompact
import com.example.ui.theme.AmberLight
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.RoseDanger
import com.example.ui.theme.RoseLight
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.viewmodel.AgroSightUiState
import kotlin.math.roundToInt

/**
 * Flagship Executive Telemetry & Orbital Health Hero Strip:
 * Combines an animated radial Crop Health & Yield Potential Score Gauge, real-time Sentinel-2
 * & Open-Meteo metrics, and one-tap navigation pills to all 6 core modules.
 */
@Composable
fun ExecutiveCommandBar(
    uiState: AgroSightUiState,
    onSelectScreen: (AppScreen) -> Unit,
    onOpenPrintReport: () -> Unit,
    onSendInstantWhatsAppReport: () -> Unit
) {
    val report = uiState.epidemiologyReport
    val audit = uiState.formulaAudit

    // Composite Farm Health Index (0..100) fusing Sentinel-2 NDRE, Soil Yield Potential, and Anomaly Status
    val rawHealthScore = when (report.anomalyPattern) {
        AnomalyPattern.HEALTHY -> 94
        AnomalyPattern.UNIFORM_VALVE -> 78
        AnomalyPattern.SOIL_PH_LOCKUP -> 72
        AnomalyPattern.FOCAL_RADIAL_PATCH -> 66
    }
    val compositeHealthScore = ((rawHealthScore * 0.55f) + (uiState.soilDiagnostics.estimatedYieldPotentialPercent * 0.45f))
        .roundToInt()
        .coerceIn(35, 99)

    val healthColor by animateColorAsState(
        targetValue = when {
            compositeHealthScore >= 82 -> EmeraldLight
            compositeHealthScore >= 68 -> AmberLight
            else -> RoseLight
        },
        animationSpec = tween(500),
        label = "healthColor"
    )

    val animatedProgress by animateFloatAsState(
        targetValue = compositeHealthScore / 100f,
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "healthArc"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Slate900, Slate950)
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("executive_command_bar"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Glassmorphic Telemetry Summary Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Slate800.copy(alpha = 0.95f),
                            Slate900.copy(alpha = 0.98f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            EmeraldLight.copy(alpha = 0.55f),
                            CyanLight.copy(alpha = 0.35f)
                        )
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Animated Radial Crop Vigor & Health Gauge
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(60.dp)) {
                    val strokeW = 6.dp.toPx()
                    drawArc(
                        color = Slate700.copy(alpha = 0.55f),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(healthColor, CyanLight, healthColor)
                        ),
                        startAngle = 135f,
                        sweepAngle = 270f * animatedProgress,
                        useCenter = false,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$compositeHealthScore%",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "صحة الحقل",
                        style = MaterialTheme.typography.labelSmall,
                        color = healthColor
                    )
                }
            }

            // Live 4-Metric Telemetry Grid + Active Scene Badge
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${uiState.fieldName} (${uiState.selectedCrop.nameAr})",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        color = healthColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, healthColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = if (report.anomalyPattern == AnomalyPattern.HEALTHY) "🟢 سليم طيفياً" else "⚠️ ${report.detectedRiskNameAr.take(18)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = healthColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ExecutiveMicroKpi(
                        modifier = Modifier.weight(1f),
                        label = "مقنن اليوم",
                        value = "${formatDecimal1(audit.grossWaterM3PerFeddanDay)} م³",
                        accent = EmeraldLight
                    )
                    ExecutiveMicroKpi(
                        modifier = Modifier.weight(1f),
                        label = "تشغيل المحبس",
                        value = formatHoursMinutesCompact(audit.valveOperatingHoursPerSector),
                        accent = CyanLight
                    )
                    ExecutiveMicroKpi(
                        modifier = Modifier.weight(1f),
                        label = "البخر ET₀",
                        value = "${formatDecimal2(audit.openMeteoEt0MmDay)} مم",
                        accent = AmberLight
                    )
                    ExecutiveMicroKpi(
                        modifier = Modifier.weight(1f),
                        label = "الغطاء NDVI",
                        value = formatDecimal2(audit.meanSentinelNdvi),
                        accent = EmeraldLight
                    )
                }
            }
        }

        // Scrollable 6-Module Ribbon so ALL 6 screens + Instant WhatsApp & PDF are 1 tap away
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val allModules = listOf(
                Triple(AppScreen.DISEASE_RADAR, "🛰️ رادار الأمراض", Icons.Default.Radar),
                Triple(AppScreen.SATELLITE_NDVI_MAP, "🗺️ خريطة القطاعات NDVI", Icons.Default.Public),
                Triple(AppScreen.IRRIGATION_SCHEDULE, "⏱️ جدول المحابس والمحاكي", Icons.Default.CalendarMonth),
                Triple(AppScreen.METEO_ANALYTICS, "☀️ الأرصاد و FAO-56", Icons.Default.WbSunny),
                Triple(AppScreen.FARM_SOIL_STUDIO, "🧪 مختبر التربة و pH", Icons.Default.Science),
                Triple(AppScreen.WHATSAPP_BOT, "💬 بوت واتساب و Worker", Icons.Default.Chat)
            )

            allModules.forEach { (screen, label, _) ->
                val isSelected = uiState.currentScreen == screen
                Surface(
                    color = if (isSelected) EmeraldPrimary else Slate800.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) EmeraldLight else Slate700
                    ),
                    modifier = Modifier
                        .clickable { onSelectScreen(screen) }
                        .testTag("ribbon_screen_${screen.name.lowercase()}")
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) Color.White else Slate400,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            // Quick Action Pill: Instant Morning Broadcast to WhatsApp
            Surface(
                color = Color(0xFF075E54),
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight),
                modifier = Modifier
                    .clickable { onSendInstantWhatsAppReport() }
                    .testTag("ribbon_instant_whatsapp_btn")
            ) {
                Text(
                    text = "📲 إرسال نشرة الواتساب",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            // Quick Action Pill: Open PDF Print Report
            Surface(
                color = SkyAccent.copy(alpha = 0.25f),
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight),
                modifier = Modifier
                    .clickable { onOpenPrintReport() }
                    .testTag("ribbon_instant_pdf_btn")
            ) {
                Text(
                    text = "🖨️ طباعة التقرير PDF",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanLight,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun ExecutiveMicroKpi(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Slate950.copy(alpha = 0.7f))
            .border(1.dp, Slate700.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(vertical = 5.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate400,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
