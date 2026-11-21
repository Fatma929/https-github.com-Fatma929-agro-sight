package com.example.ui.screens

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.model.IrrigationDayStatus
import com.example.model.WeeklyIrrigationDayItem
import com.example.model.formatCoord4
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.model.formatHoursMinutesClean
import com.example.model.formatHoursMinutesCompact
import com.example.ui.components.AgronomicSafetyAndDataProvenanceBanner
import com.example.ui.components.Recharts7DayIrrigationComparisonCard
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
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.viewmodel.AgroSightUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IrrigationScheduleScreen(
    uiState: AgroSightUiState,
    onUpdateDayStatus: (Int, IrrigationDayStatus) -> Unit,
    onUpdateWhatIfEfficiency: (Int) -> Unit,
    onUpdateWhatIfSoilPh: (Float) -> Unit,
    onUpdateWhatIfTempDelta: (Float) -> Unit,
    onApplyWhatIfPreset: (Int, Float, Float) -> Unit,
    onSetPrintModalVisible: (Boolean) -> Unit
) {
    val sim = uiState.whatIfInput
    val res = uiState.whatIfResult

    if (uiState.isPrintReportModalVisible) {
        PrintableIrrigationReportDialog(
            uiState = uiState,
            onDismiss = { onSetPrintModalVisible(false) }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .testTag("irrigation_schedule_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Agronomic Safety & Data Provenance Disclaimer Banner
        item {
            AgronomicSafetyAndDataProvenanceBanner(
                isLiveMeteoConnected = uiState.isLiveMeteoConnected,
                meteoStatusAr = uiState.meteoStatusAr,
                isSimulatedSpectralPattern = true
            )
        }

        // MODULE 0: Recharts 7-Day Proposed Irrigation Schedule vs. Daily Crop Water Requirement Chart
        item {
            Recharts7DayIrrigationComparisonCard(
                forecastDays = uiState.forecastDays,
                weeklySchedule = uiState.weeklySchedule,
                cropNameAr = uiState.selectedCrop.nameAr,
                areaFeddans = uiState.areaFeddans,
                irrigationSystemNameAr = uiState.irrigationSystem.nameAr,
                leachingRequirementPct = uiState.soilDiagnostics.leachingRequirementPercent
            )
        }

        // MODULE 1: Interactive What-If Simulator (محاكي السيناريوهات الذكي)
        item {
            BentoCard(
                title = "محاكي السيناريوهات الذكي (What-If Simulator)",
                subtitle = "اختبر فوراً كيف يؤثر رفع كفاءة الشبكة أو خفض pH التربة أو موجة حارة (+5°C) على المقنن المائي وساعات تشغيل المحبس",
                icon = Icons.Default.Tune,
                accentColor = EmeraldPrimary
            ) {
                // Quick Scenario Presets Row
                Text(
                    text = "سيناريوهات سريعة بضغطة واحدة:",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate800
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScenarioPresetChip(
                        label = "موجة حارة +5°C",
                        accentColor = RoseDanger,
                        testTag = "preset_heatwave_5c",
                        onClick = {
                            onApplyWhatIfPreset(
                                uiState.irrigationSystem.efficiencyPercent,
                                uiState.soilPh,
                                5.0f
                            )
                        }
                    )
                    ScenarioPresetChip(
                        label = "رفع كفاءة الري 95% + خفض pH 6.6",
                        accentColor = EmeraldPrimary,
                        testTag = "preset_optimal_upgrade",
                        onClick = {
                            onApplyWhatIfPreset(95, 6.6f, 0.0f)
                        }
                    )
                    ScenarioPresetChip(
                        label = "موجة حارة +5°C مع تنقيط 92%",
                        accentColor = AmberWarning,
                        testTag = "preset_heatwave_mitigated",
                        onClick = {
                            onApplyWhatIfPreset(92, 6.5f, 5.0f)
                        }
                    )
                    ScenarioPresetChip(
                        label = "إعادة للوضع الحالي",
                        accentColor = SkyAccent,
                        testTag = "preset_reset_baseline",
                        onClick = {
                            onApplyWhatIfPreset(
                                uiState.irrigationSystem.efficiencyPercent,
                                uiState.soilPh,
                                0.0f
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3 Interactive Sliders
                // 1) Irrigation Efficiency Slider
                SimulatorSliderControl(
                    title = "1. كفاءة نظام الري المحاكاة:",
                    currentBadge = "${sim.simEfficiencyPercent}% (الحالي: ${uiState.irrigationSystem.efficiencyPercent}%)",
                    value = sim.simEfficiencyPercent.toFloat(),
                    valueRange = 60f..98f,
                    onValueChange = { onUpdateWhatIfEfficiency(it.roundToInt()) },
                    accentColor = SkyAccent,
                    testTag = "what_if_efficiency_slider"
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 2) Soil pH Slider
                SimulatorSliderControl(
                    title = "2. تعديل حموضة التربة (Soil pH):",
                    currentBadge = "pH ${formatDecimal1(sim.simSoilPh)} (الحالي: ${formatDecimal1(uiState.soilPh)})",
                    value = sim.simSoilPh,
                    valueRange = 4.5f..9.2f,
                    onValueChange = onUpdateWhatIfSoilPh,
                    accentColor = EmeraldPrimary,
                    testTag = "what_if_ph_slider"
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 3) Temperature / Heatwave Delta Slider (-3°C to +8°C)
                val tempSign = if (sim.simTempDeltaC >= 0f) "+" else ""
                SimulatorSliderControl(
                    title = "3. تغير الحرارة / محاكاة موجة حارة (°C):",
                    currentBadge = "${tempSign}${formatDecimal1(sim.simTempDeltaC)} °C",
                    value = sim.simTempDeltaC,
                    valueRange = -3.0f..8.0f,
                    onValueChange = onUpdateWhatIfTempDelta,
                    accentColor = if (sim.simTempDeltaC >= 3.0f) RoseDanger else AmberWarning,
                    testTag = "what_if_temp_slider"
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Live Impact Comparison Grid (الوضع الحالي vs. بعد المحاكاة)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val m3DiffSign = if (res.deltaM3PerFeddan >= 0f) "+" else ""
                    val isWaterSaved = res.deltaM3PerFeddan <= 0f
                    WhatIfImpactCard(
                        modifier = Modifier.weight(1f),
                        title = "المقنن اليومي (م³/فدان)",
                        baselineLabel = "الحالي: ${formatDecimal1(res.baselineGrossM3PerFeddan)} م³/فدان",
                        simulatedValue = "${formatDecimal1(res.simulatedGrossM3PerFeddan)} م³/فدان",
                        deltaBadge = "$m3DiffSign${formatDecimal1(res.deltaM3PerFeddan)} م³/فدان",
                        badgeColor = if (isWaterSaved) EmeraldPrimary else RoseDanger
                    )

                    val minDiffSign = if (res.deltaValveMinutes >= 0) "+" else ""
                    val isTimeSaved = res.deltaValveMinutes <= 0
                    WhatIfImpactCard(
                        modifier = Modifier.weight(1f),
                        title = "زمن تشغيل المحبس",
                        baselineLabel = "الحالي: ${formatHoursMinutesCompact(res.baselineValveHours)}",
                        simulatedValue = formatHoursMinutesClean(res.simulatedValveHours),
                        deltaBadge = "${minDiffSign}${res.deltaValveMinutes} دقيقة/يوم",
                        badgeColor = if (isTimeSaved) SkyAccent else AmberWarning
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val weeklyDiff = (res.simulatedWeeklyFarmM3 - res.baselineWeeklyFarmM3).roundToInt()
                    val weeklySign = if (weeklyDiff >= 0) "+" else ""
                    WhatIfImpactCard(
                        modifier = Modifier.weight(1f),
                        title = "استهلاك المزرعة الأسبوعي",
                        baselineLabel = "الحالي: ${res.baselineWeeklyFarmM3.roundToInt()} م³",
                        simulatedValue = "${res.simulatedWeeklyFarmM3.roundToInt()} م³/أسبوع",
                        deltaBadge = "$weeklySign$weeklyDiff م³ في 7 أيام",
                        badgeColor = if (weeklyDiff <= 0) EmeraldPrimary else AmberWarning
                    )

                    val nutrDiff = res.simulatedNutrientIndexPct - res.baselineNutrientIndexPct
                    val nutrSign = if (nutrDiff >= 0) "+" else ""
                    WhatIfImpactCard(
                        modifier = Modifier.weight(1f),
                        title = "كفاءة تيسر العناصر",
                        baselineLabel = "الحالي: ${res.baselineNutrientIndexPct}%",
                        simulatedValue = "${res.simulatedNutrientIndexPct}% امتصاص",
                        deltaBadge = "${nutrSign}${nutrDiff}% تيسر السماد",
                        badgeColor = if (nutrDiff >= 0) EmeraldPrimary else RoseDanger
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Visual Comparison Bar + Recommendation Banner
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Slate900)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val maxBarM3 = max(res.baselineGrossM3PerFeddan, res.simulatedGrossM3PerFeddan).coerceAtLeast(10f) * 1.15f
                    ComparisonBarLine(
                        label = "الوضع الحالي (${formatDecimal1(res.baselineGrossM3PerFeddan)} م³/فدان)",
                        fraction = (res.baselineGrossM3PerFeddan / maxBarM3).coerceIn(0.1f, 1f),
                        color = CyanLight
                    )
                    ComparisonBarLine(
                        label = "بعد المحاكاة (${formatDecimal1(res.simulatedGrossM3PerFeddan)} م³/فدان • ET0=${formatDecimal2(res.simulatedEt0MmDay)} مم/يوم)",
                        fraction = (res.simulatedGrossM3PerFeddan / maxBarM3).coerceIn(0.1f, 1f),
                        color = if (res.deltaM3PerFeddan > 1.0f) AmberLight else EmeraldLight
                    )
                    Text(
                        text = res.summaryRecommendationAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }

        // MODULE 2: 7-Day Printable Actionable Irrigation Timetable (جدول تشغيل المحابس الأسبوعي)
        item {
            val completedCount = uiState.weeklySchedule.count { it.status == IrrigationDayStatus.COMPLETED }
            val scheduledCount = uiState.weeklySchedule.count { it.status == IrrigationDayStatus.SCHEDULED }
            val postponedCount = uiState.weeklySchedule.count { it.status == IrrigationDayStatus.POSTPONED_WIND }

            BentoCard(
                title = "جدول تشغيل المحابس الأسبوعي (7-Day Actionable Timetable)",
                subtitle = "اضغط على حالة أي يوم للتبديل بين (تم الري / مجدول / تأجيل بسبب الرياح) أو تصدير التقرير للطباعة",
                icon = Icons.Default.CalendarMonth,
                accentColor = SkyAccent
            ) {
                // Top Action & Summary Bar with PDF / Print Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Slate900)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "ملخص التنفيذ الأسبوعي للمزرعة",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "تم الري: $completedCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldLight
                            )
                            Text(
                                text = "مجدول: $scheduledCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanLight
                            )
                            Text(
                                text = "تأجيل رياح: $postponedCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberLight
                            )
                        }
                    }

                    Button(
                        onClick = { onSetPrintModalVisible(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("open_print_pdf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تصدير التقرير PDF / طباعة",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 7-Day Actionable Timetable Rows
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    uiState.weeklySchedule.forEach { item ->
                        WeeklyTimetableDayCard(
                            item = item,
                            onSelectStatus = { newStatus ->
                                onUpdateDayStatus(item.dayIndex, newStatus)
                            }
                        )
                    }
                }
            }
        }

        // Exact Cited Scientific Formulas & Hydrological Derivation Card
        item {
            com.example.ui.components.ScientificFormulaAndCitationCard(
                audit = uiState.formulaAudit,
                irrigationSystemNameAr = uiState.irrigationSystem.nameAr
            )
        }
    }
}

@Composable
private fun ScenarioPresetChip(
    label: String,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = accentColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        modifier = Modifier
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SimulatorSliderControl(
    title: String,
    currentBadge: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    accentColor: Color,
    testTag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Slate100)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Slate900
            )
            Surface(
                color = accentColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
            ) {
                Text(
                    text = currentBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
private fun WhatIfImpactCard(
    modifier: Modifier = Modifier,
    title: String,
    baselineLabel: String,
    simulatedValue: String,
    deltaBadge: String,
    badgeColor: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, Slate200, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Slate500
        )
        Text(
            text = simulatedValue,
            style = MaterialTheme.typography.titleMedium,
            color = Slate900,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = baselineLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Slate500
            )
            Surface(
                color = badgeColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = deltaBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ComparisonBarLine(
    label: String,
    fraction: Float,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate200
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Slate800)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}

@Composable
private fun WeeklyTimetableDayCard(
    item: WeeklyIrrigationDayItem,
    onSelectStatus: (IrrigationDayStatus) -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (item.status) {
            IrrigationDayStatus.COMPLETED -> EmeraldPrimary
            IrrigationDayStatus.SCHEDULED -> SkyAccent
            IrrigationDayStatus.POSTPONED_WIND -> AmberWarning
        },
        label = "statusColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Slate100)
            .border(1.5.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: Day Title + Date + Run Hours & Water Volume
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${item.dayIndex + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column {
                    Text(
                        text = "${item.dayLabelAr} • ${item.dateIso}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Slate900,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = item.fertigationNoteAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }
            }

            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = formatHoursMinutesClean(item.valveRunHours),
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${formatDecimal1(item.grossM3PerFeddan)} م³/فدان (${item.totalFarmM3.roundToInt()} م³)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate600
                )
            }
        }

        // Row 2: Weather Telemetry Chips (ET0, ETc, Temp, Wind)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniMetricBadge("ET0: ${formatDecimal2(item.et0MmDay)} مم/يوم", SkyAccent)
            MiniMetricBadge("ETc: ${formatDecimal2(item.etcMmDay)} مم/يوم", EmeraldPrimary)
            MiniMetricBadge("الحرارة: ${formatDecimal1(item.tempMaxC)} °C", if (item.tempMaxC >= 35f) RoseDanger else Slate600)
            MiniMetricBadge("الرياح: ${formatDecimal1(item.windSpeedKmh)} كم/س", if (item.windSpeedKmh >= 25f) AmberWarning else Slate600)
        }

        // Row 3: Interactive 3-Option Status Toggle ("تم الري", "مجدول", "تأجيل بسبب الرياح")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IrrigationDayStatus.entries.forEach { st ->
                val isSelected = item.status == st
                val chipColor = when (st) {
                    IrrigationDayStatus.COMPLETED -> EmeraldPrimary
                    IrrigationDayStatus.SCHEDULED -> SkyAccent
                    IrrigationDayStatus.POSTPONED_WIND -> AmberWarning
                }
                Surface(
                    color = if (isSelected) chipColor else Color.White,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) chipColor else Slate200
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectStatus(st) }
                        .testTag("day_${item.dayIndex}_status_${st.name.lowercase()}")
                ) {
                    Text(
                        text = st.labelAr,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) Color.White else Slate700,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniMetricBadge(text: String, color: Color) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun PrintableIrrigationReportDialog(
    uiState: AgroSightUiState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val totalWeeklyM3 = uiState.weeklySchedule.sumOf { it.totalFarmM3.toDouble() }.roundToInt()
    val avgDailyPerFeddan = if (uiState.weeklySchedule.isNotEmpty()) {
        uiState.weeklySchedule.map { it.grossM3PerFeddan }.average().toFloat()
    } else 0f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .testTag("printable_report_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = Color.White
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Dialog Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate900)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "معاينة التقرير الرسمي للطباعة وتصدير PDF",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "تقرير جدولة الري الذكي وتحليل التربة • AgroSight SaaS",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldLight
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { triggerAndroidPrintPdf(context, uiState) },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("execute_system_print_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("طباعة / حفظ PDF", style = MaterialTheme.typography.labelMedium)
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Slate800)
                                .testTag("close_print_dialog_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Printable Sheet Body
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Official Report Title Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, Slate900, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "تقرير جدولة الري الذكي والتشخيص الحقلي الأسبوعي",
                                style = MaterialTheme.typography.titleMedium,
                                color = Slate900,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "الحقل: ${uiState.fieldName} • المساحة: ${uiState.areaFeddans.toInt()} فدان • المحصول: ${uiState.selectedCrop.nameAr}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate700
                            )
                            Text(
                                text = "الإحداثيات: ${formatCoord4(uiState.latitude)}°N, ${formatCoord4(uiState.longitude)}°E • عمر المحصول: ${uiState.daysAfterSowing} يوم (Kc=${formatDecimal2(uiState.growthEvaluation.currentBaseKc)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFAF9F5))
                                .border(1.dp, EmeraldPrimary, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_agrosight_logo),
                                contentDescription = "شعار أجرو سايت الرسمي",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                            )
                        }
                    }

                    // Summary Metrics Strip in Printable Sheet
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PrintableSummaryBox(
                            modifier = Modifier.weight(1f),
                            label = "متوسط المقنن اليومي",
                            value = "${formatDecimal1(avgDailyPerFeddan)} م³/فدان"
                        )
                        PrintableSummaryBox(
                            modifier = Modifier.weight(1f),
                            label = "إجمالي الضخ الأسبوعي",
                            value = "$totalWeeklyM3 م³"
                        )
                        PrintableSummaryBox(
                            modifier = Modifier.weight(1f),
                            label = "كيمياء التربة والملوحة",
                            value = "pH ${formatDecimal1(uiState.soilPh)} | ECe ${formatDecimal1(uiState.soilEce)} dS/m"
                        )
                    }

                    // Table 1: 7-Day Actionable Irrigation Timetable
                    Text(
                        text = "أولاً: جدول تشغيل المحابس الأسبوعي (7 أيام)",
                        style = MaterialTheme.typography.titleSmall,
                        color = Slate900,
                        fontWeight = FontWeight.Bold
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Slate400, RoundedCornerShape(10.dp))
                    ) {
                        // Table Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Slate100)
                                .padding(vertical = 8.dp, horizontal = 10.dp)
                        ) {
                            Text("اليوم والتاريخ", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("ETc (مم/يوم)", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("م³/فدان", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("ساعات المحبس", modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("الحالة", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(color = Slate400)

                        uiState.weeklySchedule.forEach { day ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${day.dayLabelAr} (${day.dateIso})", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
                                Text("${formatDecimal2(day.etcMmDay)} مم", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall)
                                Text("${formatDecimal1(day.grossM3PerFeddan)} م³", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(formatHoursMinutesCompact(day.valveRunHours), modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(day.status.labelAr, modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelSmall, color = EmeraldDark, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = Slate200)
                        }
                    }

                    // Table 2: 6 Satellite Sectors Breakdown
                    Text(
                        text = "ثانياً: بيان قطاعات ومحابس المزرعة الستة (Sentinel-2 NDVI)",
                        style = MaterialTheme.typography.titleSmall,
                        color = Slate900,
                        fontWeight = FontWeight.Bold
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Slate400, RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Slate100)
                                .padding(vertical = 8.dp, horizontal = 10.dp)
                        ) {
                            Text("القطاع", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("NDVI / Kc", modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("ملوحة ECe", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("زمن التشغيل", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("الكمية اليومية", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(color = Slate400)

                        uiState.valveSectors.forEach { sec ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 7.dp, horizontal = 10.dp)
                            ) {
                                Text(sec.nameAr, modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("${formatDecimal2(sec.ndvi)} / ${formatDecimal2(sec.satelliteKc)}", modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall)
                                Text("${formatDecimal1(sec.sectorEce)} dS/m", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall)
                                Text(formatHoursMinutesCompact(sec.operatingHours), modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("${sec.totalDailyWaterM3.roundToInt()} م³", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelSmall)
                            }
                            HorizontalDivider(color = Slate200)
                        }
                    }

                    // Soil Chemistry Prescriptions Footer
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Slate400, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "ثالثاً: التوصيات الكيميائية والتسميدية المعتمدة:",
                            style = MaterialTheme.typography.labelLarge,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        uiState.soilDiagnostics.correctivePrescriptionAr.forEach { rec ->
                            Text(
                                text = "• $rec",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate800
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
private fun PrintableSummaryBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Column(
        modifier = modifier
            .border(1.dp, Slate400, RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Slate600)
        Text(text = value, style = MaterialTheme.typography.titleSmall, color = Slate900, fontWeight = FontWeight.Bold)
    }
}

private fun triggerAndroidPrintPdf(context: Context, uiState: AgroSightUiState) {
    try {
        val webView = WebView(context)
        val rowsHtml = uiState.weeklySchedule.joinToString("") { day ->
            """
            <tr>
                <td>${day.dayLabelAr} (${day.dateIso})</td>
                <td>${formatDecimal2(day.et0MmDay)} مم/يوم</td>
                <td>${formatDecimal2(day.etcMmDay)} مم/يوم</td>
                <td>${formatDecimal1(day.grossM3PerFeddan)} م³/فدان</td>
                <td>${day.totalFarmM3.roundToInt()} م³</td>
                <td>${formatHoursMinutesClean(day.valveRunHours)}</td>
                <td>${day.status.labelAr}</td>
            </tr>
            """.trimIndent()
        }

        val html = """
            <!DOCTYPE html>
            <html dir="rtl" lang="ar">
            <head>
                <meta charset="utf-8"/>
                <style>
                    body { font-family: sans-serif; padding: 24px; direction: rtl; color: #0f172a; }
                    h1 { color: #059669; margin-bottom: 4px; }
                    table { width: 100%; border-collapse: collapse; margin-top: 16px; }
                    th, td { border: 1px solid #cbd5e1; padding: 8px; text-align: right; font-size: 13px; }
                    th { background-color: #f1f5f9; }
                </style>
            </head>
            <body>
                <h1>تقرير أجرو سايت (AgroSight) - جدول تشغيل المحابس الأسبوعي</h1>
                <p>الحقل: ${uiState.fieldName} | المساحة: ${uiState.areaFeddans.toInt()} فدان | المحصول: ${uiState.selectedCrop.nameAr} | pH التربة: ${formatDecimal1(uiState.soilPh)} | ECe: ${formatDecimal1(uiState.soilEce)} dS/m</p>
                <table>
                    <thead>
                        <tr>
                            <th>اليوم والتاريخ</th>
                            <th>ET0</th>
                            <th>ETc</th>
                            <th>المقنن (م³/فدان)</th>
                            <th>إجمالي المزرعة</th>
                            <th>زمن تشغيل المحبس</th>
                            <th>الحالة</th>
                        </tr>
                    </thead>
                    <tbody>
                        $rowsHtml
                    </tbody>
                </table>
            </body>
            </html>
        """.trimIndent()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                val adapter = view.createPrintDocumentAdapter("AgroSight_Irrigation_Report")
                printManager?.print(
                    "AgroSight_Weekly_Schedule",
                    adapter,
                    PrintAttributes.Builder().build()
                )
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
    } catch (_: Exception) {
        // Ignore if PrintManager is unavailable in headless test environments
    }
}
