package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.OpenSourceChannelStatus
import com.example.data.RealSatelliteAndNasaTelemetry
import com.example.data.ScientificCitationItem
import com.example.data.ScientificFormulaCalculationAudit
import com.example.model.formatCoord4
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.model.formatHoursMinutesClean
import com.example.ui.theme.AmberLight
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.EmeraldSoft
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
import com.example.ui.theme.Slate950

/**
 * Displays live Open-Source Communication Channels (ESA Copernicus Sentinel-2 STAC, NASA POWER,
 * Open-Meteo FAO-56, WMTS Optical Tiles) with endpoint status, latency, and scene metadata.
 */
@Composable
fun OpenSourceCommunicationChannelsCard(
    telemetry: RealSatelliteAndNasaTelemetry?,
    latitude: Double,
    longitude: Double,
    isSyncing: Boolean,
    onRefreshTelemetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("open_source_channels_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                            .background(Slate900),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = EmeraldLight,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "قنوات الاتصال المفتوحة ومصادر الأقمار الصناعية والأرصاد الحية",
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Copernicus Sentinel-2 L2A (STAC) • NASA POWER • Open-Meteo FAO-56 • WMTS Imagery",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = onRefreshTelemetry,
                    enabled = !isSyncing,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("sync_open_source_channels_btn")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تحديث القنوات", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Sentinel-2 STAC Scene & NASA POWER Live Metadata Banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Slate950)
                    .border(1.dp, Slate800, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "معرّف المشهد الفضائي (Sentinel-2 Scene ID):",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate400
                    )
                    Surface(
                        color = EmeraldPrimary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight)
                    ) {
                        Text(
                            text = "MGRS Tile: ${telemetry?.mgrsTileId ?: "35RQQ"} • سحب ${formatDecimal1(telemetry?.cloudCoverPct ?: 1.1f)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldLight,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = telemetry?.sceneId ?: "S2B_MSIL2A_20261116T083609_N0510_R064_T35RQQ",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = CyanLight,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DarkTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "إشعاع NASA POWER",
                        value = "${formatDecimal1(telemetry?.nasaSolarRadiationMjM2Day ?: 24.2f)} MJ/m²/d"
                    )
                    DarkTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "رطوبة جذور NASA",
                        value = "${formatDecimal1(telemetry?.nasaRootZoneSoilMoisturePct ?: 44.5f)}%"
                    )
                    DarkTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "عجز ضغط البخار VPD",
                        value = "${formatDecimal2(telemetry?.nasaVaporPressureDeficitKpa ?: 2.35f)} kPa"
                    )
                }

                Text(
                    text = "الإحداثيات الجغرافية المرصودة: ${formatCoord4(latitude)}°N, ${formatCoord4(longitude)}°E • تاريخ الالتقاط: ${telemetry?.acquisitionDateIso ?: "2026-11-16"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate400
                )
            }

            // Individual Open-Source Channel Rows with Citations
            val channels = telemetry?.channels.orEmpty()
            if (channels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    channels.forEach { ch ->
                        OpenSourceChannelRow(channel = ch)
                    }
                }
            }
        }
    }
}

@Composable
private fun DarkTelemetryPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Slate900)
            .border(1.dp, Slate800, RoundedCornerShape(10.dp))
            .padding(8.dp),
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
            color = EmeraldLight,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun OpenSourceChannelRow(channel: OpenSourceChannelStatus) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Slate100)
            .border(1.dp, Slate200, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (channel.isConnected) EmeraldPrimary else AmberWarning)
                )
                Text(
                    text = channel.nameAr,
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate900,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                color = if (channel.isConnected) EmeraldSoft else SkySoft,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "HTTP ${channel.httpStatusCode} • ${channel.latencyMs}ms",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (channel.isConnected) EmeraldDark else SkyAccent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Text(
            text = "${channel.platformNameEn} (${channel.protocolAr})",
            style = MaterialTheme.typography.labelSmall,
            color = Slate700,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "Endpoint: ${channel.endpointUrl}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = SkyAccent,
            maxLines = 1
        )

        Text(
            text = "📚 المصدر والترخيص العلمي: ${channel.licenseAndCitationAr}",
            style = MaterialTheme.typography.labelSmall,
            color = Slate600
        )
    }
}

/**
 * Displays the complete step-by-step mathematical formulas used to calculate meteorological conditions
 * (FAO-56 Penman-Monteith ET0) and exact crop irrigation requirements (Kc, ETc, Leaching Fraction,
 * Gross Irrigation m³/feddan, and Valve Operating Hours) with authoritative scientific citations.
 */
@Composable
fun ScientificFormulaAndCitationCard(
    audit: ScientificFormulaCalculationAudit,
    irrigationSystemNameAr: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scientific_formula_citations_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(EmeraldPrimary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Calculate,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = "المعادلات الهيدرولوجية الدقيقة وتوثيق المصادر العلمية (Cited Formulas)",
                        style = MaterialTheme.typography.titleMedium,
                        color = Slate900,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "اشتقاق خطوة بخطوة للمقنن المائي لمحصول (${audit.cropNameAr}) بنظام ($irrigationSystemNameAr)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }
            }

            // Summary Formula Chain Pipeline Banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Slate900)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "ملخص سلسلة الحساب الفيزيائي للمحصول اليوم (${audit.cropNameAr}):",
                    style = MaterialTheme.typography.labelLarge,
                    color = EmeraldLight,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FormulaStepBadge(
                        step = "1. ET₀ المرجعي",
                        value = "${formatDecimal2(audit.openMeteoEt0MmDay)} مم",
                        sub = "FAO-56 Penman"
                    )
                    FormulaStepBadge(
                        step = "2. معامل Kc",
                        value = formatDecimal2(audit.fusedAuthoritativeKc),
                        sub = "NDVI=${formatDecimal2(audit.meanSentinelNdvi)}"
                    )
                    FormulaStepBadge(
                        step = "3. صافي ETc",
                        value = "${formatDecimal2(audit.netEtcMmDay)} مم",
                        sub = "${formatDecimal1(audit.netWaterM3PerFeddanDay)} م³/فدان"
                    )
                    FormulaStepBadge(
                        step = "4. الإجمالي GIR",
                        value = "${formatDecimal1(audit.grossWaterM3PerFeddanDay)} م³",
                        sub = formatHoursMinutesClean(audit.valveOperatingHoursPerSector)
                    )
                }
            }

            // Detailed Formula Cards with Exact Substituted Numbers & Academic Citations
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                audit.citations.forEach { item ->
                    CitationFormulaBlock(item = item)
                }
            }
        }
    }
}

@Composable
private fun FormulaStepBadge(
    step: String,
    value: String,
    sub: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = step,
            style = MaterialTheme.typography.labelSmall,
            color = Slate400
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = CyanLight
        )
    }
}

@Composable
private fun CitationFormulaBlock(item: ScientificCitationItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Slate100)
            .border(1.dp, Slate200, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.titleAr,
                style = MaterialTheme.typography.labelLarge,
                color = Slate900,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = Slate900,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = item.code,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = EmeraldLight,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        // Symbolic Mathematical Formula Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Slate950)
                .padding(10.dp)
        ) {
            Text(
                text = item.formulaLatexLike,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = CyanLight,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Live Numerical Substitution for Active Crop & Field
        Text(
            text = "🔢 التطبيق الرقمي الحي: ${item.substitutedValuesAr}",
            style = MaterialTheme.typography.bodySmall,
            color = EmeraldDark,
            fontWeight = FontWeight.Bold
        )

        HorizontalDivider(color = Slate200)

        // Authoritative Reference & Open-Source Platform Citation
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = Slate600,
                modifier = Modifier.size(15.dp)
            )
            Column {
                Text(
                    text = "المرجع العلمي المعتمد: ${item.referenceSourceEn}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate700
                )
                Text(
                    text = "المصدر المفتوح: ${item.openDataUrl}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = SkyAccent
                )
            }
        }
    }
}
