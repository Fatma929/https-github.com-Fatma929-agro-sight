package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.model.AgroClimaticAlert
import com.example.model.AlertSeverity
import com.example.model.DailyMeteoRecord
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
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate50
import com.example.ui.theme.Slate500
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.viewmodel.AgroSightUiState
import kotlin.math.max

@Composable
fun MeteoAnalyticsScreen(
    uiState: AgroSightUiState,
    onSelectDayIndex: (Int) -> Unit,
    onRefreshMeteo: () -> Unit
) {
    val days = uiState.forecastDays
    val selectedIdx = uiState.selectedForecastDayIndex.coerceIn(0, (days.size - 1).coerceAtLeast(0))
    val activeDay = days.getOrNull(selectedIdx)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .testTag("meteo_analytics_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Live Open-Meteo Connection Status Strip
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Slate900)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = CyanLight
                    )
                    Column {
                        Text(
                            text = uiState.meteoStatusAr,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        Text(
                            text = "الإحداثيات: ${com.example.model.formatCoord4(uiState.latitude)}°N, ${com.example.model.formatCoord4(uiState.longitude)}°E • المحصول: ${uiState.selectedCrop.nameAr}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate400
                        )
                    }
                }
                Button(
                    onClick = onRefreshMeteo,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("meteo_sync_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("مزامنة حية", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Smart Agro-Climatic Alerts Banner
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.climaticAlerts.forEach { alert ->
                    AgroAlertBanner(alert = alert)
                }
            }
        }

        // Top 5 Bento KPI Cards
        if (activeDay != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MeteoBentoKpiCard(
                            modifier = Modifier.weight(1f),
                            title = "البخر-نتح المرجعي (ET0)",
                            value = "${com.example.model.formatDecimal2(activeDay.et0MmDay)} مم/يوم",
                            subtitle = "معادلة FAO-56 Penman",
                            icon = Icons.Default.WbSunny,
                            accentColor = SkyAccent
                        )
                        MeteoBentoKpiCard(
                            modifier = Modifier.weight(1f),
                            title = "معامل المحصول المعدل (Kc)",
                            value = com.example.model.formatDecimal2(activeDay.adjustedKc),
                            subtitle = "${uiState.growthEvaluation.stageNameAr} (معدل مناخياً)",
                            icon = Icons.Default.Eco,
                            accentColor = EmeraldPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MeteoBentoKpiCard(
                            modifier = Modifier.weight(1f),
                            title = "الاستهلاك المائي الفعلي (ETc)",
                            value = "${com.example.model.formatDecimal2(activeDay.etcMmDay)} مم/يوم",
                            subtitle = "${com.example.model.formatDecimal1(activeDay.waterDeficitM3PerFeddan)} م³/فدان/يوم",
                            icon = Icons.Default.WaterDrop,
                            accentColor = EmeraldDark
                        )
                        MeteoBentoKpiCard(
                            modifier = Modifier.weight(1f),
                            title = "الحرارة العظمى / الصغرى والرطوبة",
                            value = "${com.example.model.formatDecimal1(activeDay.tempMaxC)}° / ${com.example.model.formatDecimal1(activeDay.tempMinC)}°C",
                            subtitle = "الرطوبة النسبية: ${activeDay.relativeHumidityPct}%",
                            icon = Icons.Default.Thermostat,
                            accentColor = if (activeDay.tempMaxC >= 35f) RoseDanger else AmberWarning
                        )
                    }

                    MeteoBentoKpiCard(
                        modifier = Modifier.fillMaxWidth(),
                        title = "سرعة الرياح والإشعاع الشمسي الكلي",
                        value = "${com.example.model.formatDecimal1(activeDay.windSpeedKmh)} كم/س • ${com.example.model.formatDecimal1(activeDay.solarRadiationMjM2)} MJ/m²",
                        subtitle = if (activeDay.windSpeedKmh >= 25f) "تنبيه: رياح نشطة تحد من كفاءة الرشاشات" else "سرعة رياح مثالية لجميع أنظمة الري",
                        icon = Icons.Default.Air,
                        accentColor = if (activeDay.windSpeedKmh >= 25f) AmberWarning else SkyAccent
                    )
                }
            }
        }

        // Chart 1: 7-Day Composed Chart (ET0 Bars + ETc Emerald Area + Max Temp Orange Line)
        if (days.isNotEmpty()) {
            item {
                BentoCard(
                    title = "مخطط البخر-نتح المرجعي (ET0) والفعلي (ETc) والحرارة (7 أيام)",
                    subtitle = "اضغط على أي يوم في الرسم البياني لعرض التفاصيل الدقيقة في التلميحات العربية",
                    icon = Icons.Default.Analytics,
                    accentColor = EmeraldPrimary
                ) {
                    // Legend Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChartLegendDot(color = SkyAccent, label = "ET0 المرجعي (أعمدة)")
                        ChartLegendDot(color = EmeraldPrimary, label = "ETc الفعلي (مساحة خضراء)")
                        ChartLegendDot(color = AmberWarning, label = "الحرارة العظمى °C (خط)")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ComposedEt0EtcTempChart(
                        days = days,
                        selectedIndex = selectedIdx,
                        onSelectIndex = onSelectDayIndex
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Day Selector Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        days.forEachIndexed { idx, day ->
                            val isSel = idx == selectedIdx
                            Surface(
                                color = if (isSel) Slate900 else Slate100,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSelectDayIndex(idx) }
                                    .testTag("forecast_day_pill_$idx")
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = day.dayLabelAr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSel) Color.White else Slate800,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${"%.1f".format(day.etcMmDay)} مم",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSel) EmeraldLight else Slate500
                                    )
                                }
                            }
                        }
                    }

                    // Custom Arabic Tooltip Card for Selected Day
                    if (activeDay != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        ArabicMeteoTooltipCard(day = activeDay)
                    }
                }
            }

            // Chart 2: 7-Day Cumulative Water Deficit Forecast Chart (m³/feddan)
            item {
                BentoCard(
                    title = "توقعات العجز المائي التراكمي خلال 7 أيام (م³/فدان)",
                    subtitle = "إجمالي الفقد المائي المتوقع لتعويضه عبر جدولة المحابس",
                    icon = Icons.Default.WaterDrop,
                    accentColor = SkyAccent
                ) {
                    CumulativeWaterDeficitChart(
                        days = days,
                        selectedIndex = selectedIdx,
                        totalAreaFeddans = uiState.areaFeddans,
                        onSelectIndex = onSelectDayIndex
                    )
                }
            }
        }

        // Cited Scientific Formulas (FAO-56 Penman-Monteith, Sentinel-2 Kc, FAO-29 Leaching, Gross Water)
        item {
            com.example.ui.components.ScientificFormulaAndCitationCard(
                audit = uiState.formulaAudit,
                irrigationSystemNameAr = uiState.irrigationSystem.nameAr
            )
        }

        // Open-Source Satellite & Meteorological Communication Channels Card
        item {
            com.example.ui.components.OpenSourceCommunicationChannelsCard(
                telemetry = uiState.satelliteAndNasaTelemetry,
                latitude = uiState.latitude,
                longitude = uiState.longitude,
                isSyncing = uiState.isSyncingOpenSourceSatellite,
                onRefreshTelemetry = onRefreshMeteo
            )
        }
    }
}

@Composable
private fun AgroAlertBanner(alert: AgroClimaticAlert) {
    val (bgColor, borderColor, iconColor, icon) = when (alert.severity) {
        AlertSeverity.CRITICAL_HEAT -> Quadruple(RoseSoft, RoseDanger, RoseDanger, Icons.Default.WarningAmber)
        AlertSeverity.HIGH_WIND -> Quadruple(AmberSoft, AmberWarning, AmberWarning, Icons.Default.Air)
        AlertSeverity.HIGH_EVAPORATION -> Quadruple(SkySoft, SkyAccent, SkyAccent, Icons.Default.WbSunny)
        AlertSeverity.OPTIMAL_CLIMATE -> Quadruple(EmeraldSoft, EmeraldPrimary, EmeraldPrimary, Icons.Default.CheckCircle)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = alert.titleAr,
                style = MaterialTheme.typography.titleSmall,
                color = Slate900,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = alert.detailAr,
                style = MaterialTheme.typography.bodySmall,
                color = Slate800
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun MeteoBentoKpiCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate600,
                    maxLines = 1
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = Slate900,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ChartLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate600
        )
    }
}

@Composable
private fun ComposedEt0EtcTempChart(
    days: List<DailyMeteoRecord>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit
) {
    val maxEt = max(8.5f, days.maxOfOrNull { max(it.et0MmDay, it.etcMmDay) } ?: 8.5f) * 1.15f
    val maxTemp = max(42f, days.maxOfOrNull { it.tempMaxC } ?: 40f)
    val minTemp = 10f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Slate100)
            .pointerInput(days.size) {
                detectTapGestures { offset ->
                    val slotWidth = size.width / days.size.toFloat()
                    // Because LayoutDirection is Rtl, let's map slot according to visual position
                    val rawIndex = (offset.x / slotWidth).toInt().coerceIn(0, days.size - 1)
                    // In RTL, day 0 is drawn on the right side so it aligns with the RTL day selector row below
                    val rtlIndex = (days.size - 1 - rawIndex).coerceIn(0, days.size - 1)
                    onSelectIndex(rtlIndex)
                }
            }
            .padding(12.dp)
    ) {
        val count = days.size
        if (count == 0) return@Canvas

        val chartHeight = size.height - 16.dp.toPx()
        val slotWidth = size.width / count.toFloat()

        // Horizontal subtle grid lines
        for (g in 1..4) {
            val y = chartHeight * (g / 4f)
            drawLine(
                color = Slate200,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Helper for X coordinate in RTL (index 0 on the right, index count-1 on the left)
        fun xForIndex(i: Int): Float {
            val visualSlot = count - 1 - i
            return visualSlot * slotWidth + slotWidth / 2f
        }

        // 1. Draw ETc Emerald Area under curve
        val areaPath = Path()
        days.forEachIndexed { idx, rec ->
            val x = xForIndex(idx)
            val y = chartHeight * (1f - (rec.etcMmDay / maxEt).coerceIn(0f, 1f))
            if (idx == 0) {
                areaPath.moveTo(x, chartHeight)
                areaPath.lineTo(x, y)
            } else {
                areaPath.lineTo(x, y)
            }
            if (idx == count - 1) {
                areaPath.lineTo(x, chartHeight)
                areaPath.close()
            }
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(EmeraldLight.copy(alpha = 0.42f), EmeraldSoft.copy(alpha = 0.08f))
            )
        )

        // 2. Draw ET0 Sky Bars
        days.forEachIndexed { idx, rec ->
            val xCenter = xForIndex(idx)
            val barW = (slotWidth * 0.34f).coerceAtMost(24.dp.toPx())
            val barH = chartHeight * (rec.et0MmDay / maxEt).coerceIn(0.05f, 1f)
            val isSel = idx == selectedIndex
            drawRoundRect(
                color = if (isSel) SkyAccent else SkyAccent.copy(alpha = 0.65f),
                topLeft = Offset(xCenter - barW / 2f, chartHeight - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
        }

        // 3. Draw ETc Emerald Curve Line
        val etcLinePath = Path()
        days.forEachIndexed { idx, rec ->
            val x = xForIndex(idx)
            val y = chartHeight * (1f - (rec.etcMmDay / maxEt).coerceIn(0f, 1f))
            if (idx == 0) etcLinePath.moveTo(x, y) else etcLinePath.lineTo(x, y)
        }
        drawPath(
            path = etcLinePath,
            color = EmeraldPrimary,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // 4. Draw Max Temperature Orange Line & Points
        val tempPath = Path()
        days.forEachIndexed { idx, rec ->
            val x = xForIndex(idx)
            val normT = ((rec.tempMaxC - minTemp) / (maxTemp - minTemp)).coerceIn(0.1f, 0.95f)
            val y = chartHeight * (1f - normT)
            if (idx == 0) tempPath.moveTo(x, y) else tempPath.lineTo(x, y)
        }
        drawPath(
            path = tempPath,
            color = AmberWarning,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Highlight selected day vertical guide + points
        val selX = xForIndex(selectedIndex)
        drawLine(
            color = Slate800.copy(alpha = 0.35f),
            start = Offset(selX, 0f),
            end = Offset(selX, chartHeight),
            strokeWidth = 1.5.dp.toPx()
        )

        days.forEachIndexed { idx, rec ->
            val x = xForIndex(idx)
            val etcY = chartHeight * (1f - (rec.etcMmDay / maxEt).coerceIn(0f, 1f))
            val normT = ((rec.tempMaxC - minTemp) / (maxTemp - minTemp)).coerceIn(0.1f, 0.95f)
            val tempY = chartHeight * (1f - normT)

            drawCircle(
                color = EmeraldPrimary,
                radius = if (idx == selectedIndex) 6.dp.toPx() else 4.dp.toPx(),
                center = Offset(x, etcY)
            )
            drawCircle(
                color = AmberWarning,
                radius = if (idx == selectedIndex) 5.5.dp.toPx() else 3.5.dp.toPx(),
                center = Offset(x, tempY)
            )
        }
    }
}

@Composable
private fun ArabicMeteoTooltipCard(day: DailyMeteoRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Slate900)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "تفاصيل يوم: ${day.dayLabelAr} (${day.dateIso})",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Kc = ${"%.2f".format(day.adjustedKc)}",
                style = MaterialTheme.typography.labelMedium,
                color = EmeraldLight
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "ET0: ${"%.2f".format(day.et0MmDay)} مم",
                style = MaterialTheme.typography.bodySmall,
                color = CyanLight
            )
            Text(
                text = "ETc: ${"%.2f".format(day.etcMmDay)} مم",
                style = MaterialTheme.typography.bodySmall,
                color = EmeraldLight,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "الحرارة: ${"%.1f".format(day.tempMaxC)}°C",
                style = MaterialTheme.typography.bodySmall,
                color = AmberLight
            )
            Text(
                text = "الفقد: ${"%.1f".format(day.waterDeficitM3PerFeddan)} م³/فدان",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun CumulativeWaterDeficitChart(
    days: List<DailyMeteoRecord>,
    selectedIndex: Int,
    totalAreaFeddans: Float,
    onSelectIndex: (Int) -> Unit
) {
    val maxCum = max(50f, days.maxOfOrNull { it.cumulativeDeficitM3PerFeddan } ?: 140f) * 1.1f
    val total7DayPerFeddan = days.lastOrNull()?.cumulativeDeficitM3PerFeddan ?: 0f
    val total7DayFarmM3 = total7DayPerFeddan * totalAreaFeddans

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "إجمالي العجز المائي التراكمي (7 أيام):",
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate600
                )
                Text(
                    text = "${"%.1f".format(total7DayPerFeddan)} م³/فدان",
                    style = MaterialTheme.typography.titleLarge,
                    color = SkyAccent,
                    fontWeight = FontWeight.Bold
                )
            }
            Surface(
                color = SkySoft,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SkyAccent)
            ) {
                Text(
                    text = "إجمالي المزرعة: ${total7DayFarmM3.toInt()} م³",
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyAccent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Slate100)
                .pointerInput(days.size) {
                    detectTapGestures { offset ->
                        val slotWidth = size.width / days.size.toFloat()
                        val rawIndex = (offset.x / slotWidth).toInt().coerceIn(0, days.size - 1)
                        val rtlIndex = (days.size - 1 - rawIndex).coerceIn(0, days.size - 1)
                        onSelectIndex(rtlIndex)
                    }
                }
                .padding(12.dp)
        ) {
            val count = days.size
            if (count == 0) return@Canvas
            val chartHeight = size.height - 10.dp.toPx()
            val slotWidth = size.width / count.toFloat()

            fun xForIndex(i: Int): Float {
                val visualSlot = count - 1 - i
                return visualSlot * slotWidth + slotWidth / 2f
            }

            val areaPath = Path()
            val linePath = Path()

            days.forEachIndexed { idx, rec ->
                val x = xForIndex(idx)
                val y = chartHeight * (1f - (rec.cumulativeDeficitM3PerFeddan / maxCum).coerceIn(0f, 1f))
                if (idx == 0) {
                    areaPath.moveTo(x, chartHeight)
                    areaPath.lineTo(x, y)
                    linePath.moveTo(x, y)
                } else {
                    areaPath.lineTo(x, y)
                    linePath.lineTo(x, y)
                }
                if (idx == count - 1) {
                    areaPath.lineTo(x, chartHeight)
                    areaPath.close()
                }
            }

            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(SkyAccent.copy(alpha = 0.38f), SkySoft.copy(alpha = 0.08f))
                )
            )
            drawPath(
                path = linePath,
                color = SkyAccent,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            days.forEachIndexed { idx, rec ->
                val x = xForIndex(idx)
                val y = chartHeight * (1f - (rec.cumulativeDeficitM3PerFeddan / maxCum).coerceIn(0f, 1f))
                drawCircle(
                    color = if (idx == selectedIndex) Slate900 else SkyAccent,
                    radius = if (idx == selectedIndex) 6.dp.toPx() else 4.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            days.forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = day.dayLabelAr,
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate600
                    )
                    Text(
                        text = "${day.cumulativeDeficitM3PerFeddan.toInt()}م³",
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
