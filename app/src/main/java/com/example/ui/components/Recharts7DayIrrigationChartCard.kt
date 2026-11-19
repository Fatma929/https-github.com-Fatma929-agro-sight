package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.model.DailyMeteoRecord
import com.example.model.WeeklyIrrigationDayItem
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.model.formatHoursMinutesClean
import com.example.ui.theme.AmberLight
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.RoseDanger
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.SkySoft
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate500
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Interactive Recharts-style ComposedChart comparing the 7-Day Proposed Gross Irrigation Schedule
 * (m³/Feddan) against the Daily Net Crop Water Requirement ETc (m³/Feddan), with interactive day
 * selection tooltip, cumulative weekly water balance, and leaching/efficiency breakdown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Recharts7DayIrrigationComparisonCard(
    forecastDays: List<DailyMeteoRecord>,
    weeklySchedule: List<WeeklyIrrigationDayItem>,
    cropNameAr: String,
    areaFeddans: Float,
    irrigationSystemNameAr: String,
    leachingRequirementPct: Int,
    modifier: Modifier = Modifier
) {
    val dayCount = minOf(forecastDays.size, weeklySchedule.size)
    if (dayCount == 0) return

    var selectedDayIdx by remember { mutableIntStateOf(0) }
    val safeSelectedIdx = selectedDayIdx.coerceIn(0, dayCount - 1)
    val selectedMeteo = forecastDays[safeSelectedIdx]
    val selectedSched = weeklySchedule[safeSelectedIdx]

    val totalNetCropNeedM3PerFeddan = forecastDays.take(dayCount).map { it.waterDeficitM3PerFeddan }.sum()
    val totalProposedGrossM3PerFeddan = weeklySchedule.take(dayCount).map { it.grossM3PerFeddan }.sum()
    val totalLeachingAndSystemBufferM3 = (totalProposedGrossM3PerFeddan - totalNetCropNeedM3PerFeddan).coerceAtLeast(0f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("recharts_7day_irrigation_comparison_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
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
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SkySoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "مخطط مقارنة الري والاحتياج المائي",
                            tint = SkyAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "مخطط Recharts التفاعلي: جدول الري المقترح مقابل احتياج المحصول (7 أيام)",
                            style = MaterialTheme.typography.titleSmall,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "مقارنة الاحتياج الصافي للمحصول (ETc) مع المقنن الإجمالي المقترح شامل الغسيل الملحي ($leachingRequirementPct%) وكفاءة ($irrigationSystemNameAr)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                }
            }

            // Legend Row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChartLegendBadge(
                    color = EmeraldPrimary,
                    label = "الاحتياج المائي الصافي للمحصول (ETc م³/فدان)"
                )
                ChartLegendBadge(
                    color = SkyAccent,
                    label = "المقنن المائي المقترح بالجدول (Gross م³/فدان)"
                )
                ChartLegendBadge(
                    color = AmberWarning,
                    label = "منحنى البخر-نتح المرجعي (ET₀ مم/يوم)"
                )
            }

            // Interactive Recharts Dual-Bar + Trend Line Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Slate900)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val maxM3Val = remember(forecastDays, weeklySchedule) {
                        val peakGross = weeklySchedule.take(dayCount).maxOfOrNull { it.grossM3PerFeddan } ?: 35f
                        val peakNet = forecastDays.take(dayCount).maxOfOrNull { it.waterDeficitM3PerFeddan } ?: 25f
                        max(peakGross, peakNet).coerceAtLeast(15f) * 1.18f
                    }

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(175.dp)
                            .testTag("recharts_7day_canvas")
                    ) {
                        val w = size.width
                        val h = size.height
                        val gridSteps = 4

                        // Horizontal dashed Recharts cartesian grid lines
                        for (g in 0..gridSteps) {
                            val y = h * (g.toFloat() / gridSteps.toFloat())
                            drawLine(
                                color = Color.White.copy(alpha = 0.12f),
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                            )
                        }

                        val slotWidth = w / dayCount.toFloat()
                        val barWidth = (slotWidth * 0.28f).coerceAtLeast(6.dp.toPx())
                        val trendPath = Path()

                        for (i in 0 until dayCount) {
                            val netM3 = forecastDays[i].waterDeficitM3PerFeddan
                            val grossM3 = weeklySchedule[i].grossM3PerFeddan
                            val centerX = slotWidth * i + slotWidth / 2f

                            // Highlight selected column background
                            if (i == safeSelectedIdx) {
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.08f),
                                    topLeft = Offset(slotWidth * i + 2.dp.toPx(), 0f),
                                    size = Size(slotWidth - 4.dp.toPx(), h),
                                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                                )
                            }

                            // Bar 1: Net Crop Water Requirement (Emerald)
                            val netRatio = (netM3 / maxM3Val).coerceIn(0.05f, 1f)
                            val netBarH = h * netRatio
                            drawRoundRect(
                                color = EmeraldLight,
                                topLeft = Offset(centerX - barWidth - 2.dp.toPx(), h - netBarH),
                                size = Size(barWidth, netBarH),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )

                            // Bar 2: Proposed Gross Irrigation Schedule (Sky/Cyan)
                            val grossRatio = (grossM3 / maxM3Val).coerceIn(0.05f, 1f)
                            val grossBarH = h * grossRatio
                            drawRoundRect(
                                color = CyanLight,
                                topLeft = Offset(centerX + 2.dp.toPx(), h - grossBarH),
                                size = Size(barWidth, grossBarH),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )

                            // Overlay Line: ET0 trend scaled to canvas
                            val et0Norm = (forecastDays[i].et0MmDay / 9.5f).coerceIn(0.15f, 0.92f)
                            val ptY = h * (1f - et0Norm)
                            if (i == 0) {
                                trendPath.moveTo(centerX, ptY)
                            } else {
                                trendPath.lineTo(centerX, ptY)
                            }
                        }

                        drawPath(
                            path = trendPath,
                            color = AmberLight,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )

                        for (i in 0 until dayCount) {
                            val centerX = slotWidth * i + slotWidth / 2f
                            val et0Norm = (forecastDays[i].et0MmDay / 9.5f).coerceIn(0.15f, 0.92f)
                            val ptY = h * (1f - et0Norm)
                            drawCircle(
                                color = Slate900,
                                radius = 5.dp.toPx(),
                                center = Offset(centerX, ptY)
                            )
                            drawCircle(
                                color = AmberLight,
                                radius = 3.5.dp.toPx(),
                                center = Offset(centerX, ptY)
                            )
                        }
                    }

                    // Interactive X-Axis Day Selector Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (i in 0 until dayCount) {
                            val isSelected = i == safeSelectedIdx
                            val dayItem = weeklySchedule[i]
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) SkyAccent.copy(alpha = 0.25f) else Color.Transparent)
                                    .clickable { selectedDayIdx = i }
                                    .padding(vertical = 6.dp, horizontal = 2.dp)
                                    .testTag("recharts_day_selector_$i"),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = dayItem.dayLabelAr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) CyanLight else Slate400,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "${formatDecimal1(dayItem.grossM3PerFeddan)}م³",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Interactive Recharts Tooltip Card for Selected Day
            Surface(
                color = Slate100,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recharts_selected_day_tooltip")
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
                        Text(
                            text = "تفاصيل قرار الري ليوم (${selectedSched.dayLabelAr} • ${selectedSched.dateIso}) - محصول $cropNameAr",
                            style = MaterialTheme.typography.labelLarge,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = EmeraldSoft,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "زمن المحبس: ${formatHoursMinutesClean(selectedSched.valveRunHours)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldDark,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TooltipKpiBox(
                            modifier = Modifier.weight(1f),
                            title = "احتياج المحصول الصافي (ETc)",
                            value = "${formatDecimal1(selectedMeteo.waterDeficitM3PerFeddan)} م³/فدان",
                            sub = "ET₀=${formatDecimal2(selectedMeteo.et0MmDay)} مم • Kc=${formatDecimal2(selectedMeteo.adjustedKc)}",
                            accent = EmeraldPrimary
                        )
                        TooltipKpiBox(
                            modifier = Modifier.weight(1f),
                            title = "المقنن المقترح بالجدول (Gross)",
                            value = "${formatDecimal1(selectedSched.grossM3PerFeddan)} م³/فدان",
                            sub = "إجمالي المزرعة: ${selectedSched.totalFarmM3.roundToInt()} م³",
                            accent = SkyAccent
                        )
                        val dayBuffer = (selectedSched.grossM3PerFeddan - selectedMeteo.waterDeficitM3PerFeddan).coerceAtLeast(0f)
                        TooltipKpiBox(
                            modifier = Modifier.weight(1f),
                            title = "هامش الغسيل وكفاءة الشبكة",
                            value = "+${formatDecimal1(dayBuffer)} م³/فدان",
                            sub = "غسيل أملاح $leachingRequirementPct% + فواقد الشبكة",
                            accent = AmberWarning
                        )
                    }

                    // 7-Day Cumulative Decision Summary Strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "إجمالي 7 أيام للفدان: احتياج صافي ${totalNetCropNeedM3PerFeddan.roundToInt()} م³  مقابل مقنن مقترح ${totalProposedGrossM3PerFeddan.roundToInt()} م³ (هامش غسيل وكفاءة: +${totalLeachingAndSystemBufferM3.roundToInt()} م³/فدان • إجمالي المزرعة ${(totalProposedGrossM3PerFeddan * areaFeddans).roundToInt()} م³)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartLegendBadge(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
            color = Slate700,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TooltipKpiBox(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    sub: String,
    accent: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = Slate600
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = accent,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = Slate500
        )
    }
}

/**
 * Displays data provenance (Live Open-Meteo / Sentinel-2 STAC vs. Offline Baseline / Spatial Simulation)
 * and a clear Arabic agronomic safety disclaimer recommending verification by a licensed agronomist
 * before applying acids, fertilizers, or pesticides.
 */
@Composable
fun AgronomicSafetyAndDataProvenanceBanner(
    isLiveMeteoConnected: Boolean,
    meteoStatusAr: String,
    isSimulatedSpectralPattern: Boolean = true,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("agronomic_safety_disclaimer_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AmberSoft),
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = "تنويه السلامة الزراعية ومصدر البيانات",
                    tint = AmberWarning,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "شفافية مصدر البيانات وتنويه السلامة الزراعية (Data Provenance & Agronomic Safety)",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate900,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isLiveMeteoConnected) EmeraldSoft else Color.White,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        1.dp,
                        if (isLiveMeteoConnected) EmeraldPrimary.copy(alpha = 0.5f) else AmberWarning.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = if (isLiveMeteoConnected) "الأرصاد: اتصال حي Open-Meteo FAO-56" else "الأرصاد: نموذج تقديري احتياطي (Offline Baseline)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isLiveMeteoConnected) EmeraldDark else Slate800,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (isSimulatedSpectralPattern) {
                    Surface(
                        color = SkySoft,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, SkyAccent.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "البصمة الطيفية: نموذج استرشادي مكاني (10m×10m)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate800,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Text(
                text = "⚠️ تنويه علمي وإرشادي: مخرجات تشخيص الأمراض الفضائية، جرعات الأحماض (pH)، والمقننات المائية (FAO-56 / FAO-29) هي تقديرات استرشادية لدعم القرار ($meteoStatusAr). لا تُغني هذه المؤشرات عن الفحص الحقلي المباشر وتحليل المعمل؛ يُوصى بمراجعة مهندس زراعي معتمد قبل تطبيق الأحماض أو المبيدات أو تعديل برامج التسميد.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate800
            )
        }
    }
}
