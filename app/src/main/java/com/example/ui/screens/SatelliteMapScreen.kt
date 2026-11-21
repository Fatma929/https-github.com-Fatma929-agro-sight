package com.example.ui.screens

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.SatelliteLayer
import com.example.model.SectorStatusLevel
import com.example.model.ValveSectorTelemetry
import com.example.model.formatCoord4
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.model.formatHoursMinutesClean
import com.example.model.formatHoursMinutesCompact
import com.example.ui.components.OpenSourceCommunicationChannelsCard
import com.example.ui.components.ScientificFormulaAndCitationCard
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.RoseDanger
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
import com.example.ui.theme.Slate950
import com.example.viewmodel.AgroSightUiState

@Composable
fun SatelliteMapScreen(
    uiState: AgroSightUiState,
    onSelectLayer: (SatelliteLayer) -> Unit,
    onSelectValve: (Int) -> Unit,
    onOpenScheduleAndSimulator: () -> Unit = {},
    onOpenPrintReport: () -> Unit = {},
    onToggleRealOpticalUnderlay: () -> Unit = {},
    onRefreshOpenSourceTelemetry: () -> Unit = {},
    onAskGeminiChatbot: (String) -> Unit = {},
    onOpenWhatsAppChatbot: (String) -> Unit = {}
) {
    val sectors = uiState.valveSectors
    val activeSector = sectors.find { it.valveNumber == uiState.selectedValveNumber } ?: sectors.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .testTag("satellite_map_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Real Open-Source Satellite & Agrometeorological Communication Channels
        item {
            OpenSourceCommunicationChannelsCard(
                telemetry = uiState.satelliteAndNasaTelemetry,
                latitude = uiState.latitude,
                longitude = uiState.longitude,
                isSyncing = uiState.isSyncingOpenSourceSatellite,
                onRefreshTelemetry = onRefreshOpenSourceTelemetry
            )
        }

        // Interactive Google Maps Satellite Hybrid + AI Chatbots (Gemini & WhatsApp) Connector Card
        item {
            InteractiveGoogleMapsAndChatbotCard(
                uiState = uiState,
                activeSector = activeSector,
                onSelectValve = onSelectValve,
                onAskGeminiChatbot = onAskGeminiChatbot,
                onOpenWhatsAppChatbot = onOpenWhatsAppChatbot
            )
        }

        // Layer Switcher Buttons + Interactive Manna-Style Satellite Valve Map with Real Optical Underlay
        item {
            BentoCard(
                title = "خريطة القطاعات والمرئيات الفضائية الحقيقية (Sentinel-2 & WMTS Imagery)",
                subtitle = "اضغط على أي قطاع (محبس 1 إلى محبس 6) لفحص مؤشر الغطاء النباتي وجدولة الري",
                icon = Icons.Default.SatelliteAlt,
                accentColor = EmeraldPrimary
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SatelliteLayer.entries.forEach { layer ->
                        val isSelected = uiState.activeSatelliteLayer == layer
                        Surface(
                            color = if (isSelected) Slate900 else Slate100,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) EmeraldLight else Slate200
                            ),
                            modifier = Modifier
                                .clickable { onSelectLayer(layer) }
                                .testTag("layer_btn_${layer.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = if (isSelected) EmeraldLight else Slate600,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = layer.titleAr,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) Color.White else Slate900
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (sectors.isNotEmpty()) {
                    MannaSatellitePolygonMap(
                        sectors = sectors,
                        activeLayer = uiState.activeSatelliteLayer,
                        selectedValveNumber = uiState.selectedValveNumber,
                        realSatelliteBitmap = uiState.satelliteAndNasaTelemetry?.retrievedSatelliteBitmap,
                        showRealOpticalUnderlay = uiState.showRealOpticalSatelliteUnderlay,
                        sceneId = uiState.satelliteAndNasaTelemetry?.sceneId ?: "S2B_MSIL2A_LIVE",
                        latitude = uiState.latitude,
                        longitude = uiState.longitude,
                        onToggleRealOpticalUnderlay = onToggleRealOpticalUnderlay,
                        onSelectValve = onSelectValve
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sectors.forEach { sector ->
                        val isSelected = sector.valveNumber == uiState.selectedValveNumber
                        val pillColor = sectorHeatmapColor(sector, uiState.activeSatelliteLayer)
                        Surface(
                            color = if (isSelected) Slate900 else Slate100,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) pillColor else Slate200
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectValve(sector.valveNumber) }
                                .testTag("valve_selector_${sector.valveNumber}")
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(pillColor)
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = sector.nameAr,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) Color.White else Slate900,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = formatLayerMetricShort(sector, uiState.activeSatelliteLayer),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) EmeraldLight else Slate600
                                )
                            }
                        }
                    }
                }
            }
        }

        // Selected Sector Telemetry & Valve Decision Card
        if (activeSector != null) {
            item {
                SelectedValveTelemetryCard(
                    sector = activeSector,
                    irrigationSystemName = uiState.irrigationSystem.nameAr,
                    leachingPct = uiState.soilDiagnostics.leachingRequirementPercent
                )
            }
        }

        // Full 6-Valve Smart Irrigation Schedule + Quick Action Buttons to What-If Simulator & PDF Print
        if (sectors.isNotEmpty()) {
            item {
                BentoCard(
                    title = "ملخص تشغيل القطاعات الستة وخيارات المحاكاة والطباعة",
                    subtitle = "حساب تلقائي لساعات الضخ والكميات بالمتر المكعب لكل قطاع بناءً على القمر الصناعي",
                    icon = Icons.Default.Schedule,
                    accentColor = SkyAccent
                ) {
                    val totalFarmDailyM3 = sectors.sumOf { it.totalDailyWaterM3.toDouble() }.toInt()
                    val avgHours = sectors.map { it.operatingHours }.average()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate900)
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "إجمالي الضخ اليومي للمزرعة (${uiState.areaFeddans.toInt()} فدان)",
                                style = MaterialTheme.typography.labelMedium,
                                color = Slate400
                            )
                            Text(
                                text = "$totalFarmDailyM3 م³ / يوم",
                                style = MaterialTheme.typography.headlineMedium,
                                color = EmeraldLight,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "متوسط تشغيل المحبس",
                                style = MaterialTheme.typography.labelMedium,
                                color = Slate400
                            )
                            Text(
                                text = formatHoursMinutesClean(avgHours.toFloat()),
                                style = MaterialTheme.typography.titleMedium,
                                color = CyanLight,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Action Buttons for What-If Simulator & PDF Print
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onOpenScheduleAndSimulator,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("jump_to_what_if_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("محاكي السيناريوهات والجدول", style = MaterialTheme.typography.labelLarge)
                        }

                        Button(
                            onClick = onOpenPrintReport,
                            colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("satellite_open_pdf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تصدير التقرير PDF / طباعة", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectors.forEach { sector ->
                            ValveScheduleRowCard(
                                sector = sector,
                                isSelected = sector.valveNumber == uiState.selectedValveNumber,
                                onClick = { onSelectValve(sector.valveNumber) }
                            )
                        }
                    }
                }
            }
        }

        // Cited Scientific Formulas & Hydrological Derivation Card
        item {
            ScientificFormulaAndCitationCard(
                audit = uiState.formulaAudit,
                irrigationSystemNameAr = uiState.irrigationSystem.nameAr
            )
        }
    }
}

@Composable
private fun MannaSatellitePolygonMap(
    sectors: List<ValveSectorTelemetry>,
    activeLayer: SatelliteLayer,
    selectedValveNumber: Int,
    realSatelliteBitmap: Bitmap?,
    showRealOpticalUnderlay: Boolean,
    sceneId: String,
    latitude: Double,
    longitude: Double,
    onToggleRealOpticalUnderlay: () -> Unit,
    onSelectValve: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Slate950)
            .border(1.dp, Slate700, RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SENTINEL-2 L2A + WMTS OPTICAL • (${formatCoord4(latitude)}°N, ${formatCoord4(longitude)}°E)",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldLight,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Scene: $sceneId",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate400,
                    maxLines = 1
                )
            }

            Surface(
                color = if (showRealOpticalUnderlay) EmeraldPrimary.copy(alpha = 0.25f) else Slate800,
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (showRealOpticalUnderlay) EmeraldLight else Slate600
                ),
                modifier = Modifier
                    .clickable { onToggleRealOpticalUnderlay() }
                    .testTag("toggle_real_optical_satellite_btn")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = EmeraldLight,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (showRealOpticalUnderlay) "مرئية القمر الصناعي الحقيقية: مفعّلة" else "إظهار مرئية القمر الصناعي",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF07111E))
        ) {
            // Render real downloaded optical satellite tile bitmap if available and enabled
            if (showRealOpticalUnderlay && realSatelliteBitmap != null) {
                Image(
                    bitmap = realSatelliteBitmap.asImageBitmap(),
                    contentDescription = "مرئية القمر الصناعي الحقيقية للحقل",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.62f)
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(sectors) {
                        detectTapGestures { offset ->
                            val colWidth = size.width / 3f
                            val rowHeight = size.height / 2f
                            val col = (offset.x / colWidth).toInt().coerceIn(0, 2)
                            val row = (offset.y / rowHeight).toInt().coerceIn(0, 1)
                            val rtlCol = 2 - col
                            val clickedValve = row * 3 + rtlCol + 1
                            onSelectValve(clickedValve.coerceIn(1, 6))
                        }
                    }
            ) {
                val stepX = size.width / 12f
                val stepY = size.height / 8f
                for (i in 1..11) {
                    drawLine(
                        color = Slate800.copy(alpha = 0.55f),
                        start = Offset(i * stepX, 0f),
                        end = Offset(i * stepX, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                for (j in 1..7) {
                    drawLine(
                        color = Slate800.copy(alpha = 0.55f),
                        start = Offset(0f, j * stepY),
                        end = Offset(size.width, j * stepY),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val midY = size.height / 2f
                drawLine(
                    color = CyanLight.copy(alpha = 0.8f),
                    start = Offset(10.dp.toPx(), midY),
                    end = Offset(size.width - 10.dp.toPx(), midY),
                    strokeWidth = 3.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
                )

                val colW = size.width / 3f
                val rowH = size.height / 2f
                val pad = 8.dp.toPx()

                sectors.forEach { sector ->
                    val idx = sector.valveNumber - 1
                    val row = idx / 3
                    val rtlCol = idx % 3
                    val visualCol = 2 - rtlCol

                    val left = visualCol * colW + pad
                    val top = row * rowH + pad
                    val polyW = colW - pad * 2f
                    val polyH = rowH - pad * 2f

                    val slant = if (row == 0) 6.dp.toPx() else -6.dp.toPx()
                    val polyPath = Path().apply {
                        moveTo(left + slant, top)
                        lineTo(left + polyW, top + 3.dp.toPx())
                        lineTo(left + polyW - slant, top + polyH)
                        lineTo(left, top + polyH - 3.dp.toPx())
                        close()
                    }

                    val baseColor = sectorHeatmapColor(sector, activeLayer)
                    val isSelected = sector.valveNumber == selectedValveNumber

                    drawPath(
                        path = polyPath,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                baseColor.copy(alpha = if (isSelected) 0.88f else 0.65f),
                                baseColor.copy(alpha = if (isSelected) 0.68f else 0.42f)
                            ),
                            start = Offset(left, top),
                            end = Offset(left + polyW, top + polyH)
                        )
                    )

                    for (r in 1..4) {
                        val ry = top + (polyH * r / 5f)
                        drawLine(
                            color = Color.White.copy(alpha = 0.14f),
                            start = Offset(left + 8.dp.toPx(), ry),
                            end = Offset(left + polyW - 8.dp.toPx(), ry),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    drawPath(
                        path = polyPath,
                        color = if (isSelected) Color.White else baseColor,
                        style = Stroke(width = if (isSelected) 3.dp.toPx() else 1.5.dp.toPx())
                    )

                    val centerX = left + polyW / 2f
                    val valveNodeY = if (row == 0) top + polyH else top
                    drawCircle(
                        color = if (isSelected) CyanLight else Color.White,
                        radius = 5.dp.toPx(),
                        center = Offset(centerX, valveNodeY)
                    )
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                for (row in 0..1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        for (col in 0..2) {
                            val valveNum = row * 3 + col + 1
                            val sector = sectors.find { it.valveNumber == valveNum }
                            if (sector != null) {
                                val isSel = sector.valveNumber == selectedValveNumber
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Slate950.copy(alpha = if (isSel) 0.84f else 0.65f))
                                            .border(
                                                1.dp,
                                                if (isSel) Color.White else Color.Transparent,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = sector.nameAr,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = formatLayerMetricFull(sector, activeLayer),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EmeraldLight,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = formatHoursMinutesCompact(sector.operatingHours),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyanLight
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendChip(color = EmeraldPrimary, text = "نمو كثيف / رطوبة مثالية")
            LegendChip(color = AmberWarning, text = "تفاوت متوسط / تنبيه")
            LegendChip(color = RoseDanger, text = "إجهاد مائي أو ملحي مرتفع")
        }
    }
}

@Composable
private fun LegendChip(color: Color, text: String) {
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
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Slate400
        )
    }
}

private fun sectorHeatmapColor(sector: ValveSectorTelemetry, layer: SatelliteLayer): Color {
    return when (layer) {
        SatelliteLayer.NDVI -> when {
            sector.ndvi >= 0.62f -> EmeraldPrimary
            sector.ndvi >= 0.50f -> AmberWarning
            else -> RoseDanger
        }
        SatelliteLayer.NDWI -> when {
            sector.ndwi >= 0.22f -> SkyAccent
            sector.ndwi >= 0.14f -> AmberWarning
            else -> RoseDanger
        }
        SatelliteLayer.SALINITY -> when {
            sector.sectorEce <= 2.3f -> EmeraldPrimary
            sector.sectorEce <= 2.7f -> AmberWarning
            else -> RoseDanger
        }
        SatelliteLayer.VALVE_HOURS -> when {
            sector.operatingHours <= 2.2f -> SkyAccent
            sector.operatingHours <= 2.7f -> EmeraldPrimary
            else -> AmberWarning
        }
    }
}

private fun formatLayerMetricShort(sector: ValveSectorTelemetry, layer: SatelliteLayer): String {
    return when (layer) {
        SatelliteLayer.NDVI -> "NDVI ${formatDecimal2(sector.ndvi)}"
        SatelliteLayer.NDWI -> "NDWI ${formatDecimal2(sector.ndwi)}"
        SatelliteLayer.SALINITY -> "${formatDecimal1(sector.sectorEce)} dS/m"
        SatelliteLayer.VALVE_HOURS -> formatHoursMinutesCompact(sector.operatingHours)
    }
}

private fun formatLayerMetricFull(sector: ValveSectorTelemetry, layer: SatelliteLayer): String {
    return when (layer) {
        SatelliteLayer.NDVI -> "NDVI: ${formatDecimal2(sector.ndvi)}"
        SatelliteLayer.NDWI -> "NDWI: ${formatDecimal2(sector.ndwi)}"
        SatelliteLayer.SALINITY -> "${formatDecimal1(sector.sectorEce)} dS/m"
        SatelliteLayer.VALVE_HOURS -> "Kc: ${formatDecimal2(sector.satelliteKc)}"
    }
}

@Composable
private fun SelectedValveTelemetryCard(
    sector: ValveSectorTelemetry,
    irrigationSystemName: String,
    leachingPct: Int
) {
    val statusColor = when (sector.statusLevel) {
        SectorStatusLevel.OPTIMAL -> EmeraldPrimary
        SectorStatusLevel.MODERATE_STRESS -> AmberWarning
        SectorStatusLevel.HIGH_STRESS -> RoseDanger
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("selected_valve_telemetry_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, statusColor.copy(alpha = 0.55f))
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Slate900),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "#${sector.valveNumber}",
                            style = MaterialTheme.typography.titleLarge,
                            color = EmeraldLight,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(
                            text = "تشخيص القمر الصناعي: ${sector.nameAr} (${sector.areaFeddans.toInt()} فدان)",
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "نظام $irrigationSystemName • غسيل ملوحة مضاف +$leachingPct%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate500
                        )
                    }
                }

                Surface(
                    color = statusColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)
                ) {
                    Text(
                        text = sector.statusBadgeAr,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "مؤشر الغطاء NDVI",
                    value = formatDecimal2(sector.ndvi),
                    sub = "Kc = ${formatDecimal2(sector.satelliteKc)}",
                    color = EmeraldPrimary
                )
                TelemetryMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "إجهاد الرطوبة NDWI",
                    value = formatDecimal2(sector.ndwi),
                    sub = "استنزاف ${sector.soilMoistureDepletionPct}%",
                    color = SkyAccent
                )
                TelemetryMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "الكمية المطلوبة",
                    value = "${sector.totalDailyWaterM3.toInt()} م³",
                    sub = "${formatDecimal1(sector.grossWaterM3PerFeddan)} م³/فدان",
                    color = EmeraldDark
                )
                TelemetryMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "زمن التشغيل",
                    value = formatHoursMinutesCompact(sector.operatingHours),
                    sub = "${formatDecimal1(sector.sectorEce)} dS/m",
                    color = AmberWarning
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusColor.copy(alpha = 0.08f))
                    .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (sector.statusLevel == SectorStatusLevel.OPTIMAL) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = sector.recommendationAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate800
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    sub: String,
    color: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Slate100)
            .border(1.dp, Slate200, RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate500,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = Slate600,
            maxLines = 1
        )
    }
}

@Composable
private fun ValveScheduleRowCard(
    sector: ValveSectorTelemetry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val statusColor = when (sector.statusLevel) {
        SectorStatusLevel.OPTIMAL -> EmeraldPrimary
        SectorStatusLevel.MODERATE_STRESS -> AmberWarning
        SectorStatusLevel.HIGH_STRESS -> RoseDanger
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) SkySoft.copy(alpha = 0.5f) else Slate100)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) SkyAccent else Slate200,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(statusColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${sector.valveNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Column {
                Text(
                    text = "${sector.nameAr} • ${sector.statusBadgeAr}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate900,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "NDVI: ${formatDecimal2(sector.ndvi)} • Kc: ${formatDecimal2(sector.satelliteKc)} • الصافي: ${formatDecimal1(sector.netEtcMmDay)} مم/يوم",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate600
                )
            }
        }

        Column(horizontalAlignment = Alignment.Start) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = null,
                    tint = SkyAccent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = formatHoursMinutesClean(sector.operatingHours),
                    style = MaterialTheme.typography.titleSmall,
                    color = SkyAccent,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "${sector.totalDailyWaterM3.toInt()} م³ (${formatDecimal1(sector.grossWaterM3PerFeddan)} م³/فدان)",
                style = MaterialTheme.typography.labelSmall,
                color = Slate600
            )
        }
    }
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InteractiveGoogleMapsAndChatbotCard(
    uiState: AgroSightUiState,
    activeSector: ValveSectorTelemetry?,
    onSelectValve: (Int) -> Unit,
    onAskGeminiChatbot: (String) -> Unit,
    onOpenWhatsAppChatbot: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val googleMapsHtml = androidx.compose.runtime.remember(
        uiState.latitude,
        uiState.longitude,
        uiState.fieldName,
        uiState.selectedValveNumber,
        uiState.epidemiologyReport.ndviMean
    ) {
        buildInteractiveGoogleMapsLeafletHtml(
            latitude = uiState.latitude,
            longitude = uiState.longitude,
            fieldName = uiState.fieldName,
            cropNameAr = uiState.selectedCrop.nameAr,
            selectedValve = uiState.selectedValveNumber,
            ndviMean = uiState.epidemiologyReport.ndviMean,
            sectors = uiState.valveSectors
        )
    }

    BentoCard(
        title = "خريطة Google Maps التفاعلية والربط المباشر مع الشات بوت (Gemini & WhatsApp Chatbots)",
        subtitle = "عرض القمر الصناعي الحي من Google Maps مع مضلعات المحابس الـ 6 واستشارة الشات بوت الذكي بضغطة واحدة",
        icon = Icons.Default.SatelliteAlt,
        accentColor = SkyAccent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(235.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, SkyAccent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = android.webkit.WebViewClient()
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(
                        "https://maps.google.com",
                        googleMapsHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("satellite_screen_google_maps_webview")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            uiState.valveSectors.forEach { sec ->
                val isSelected = sec.valveNumber == uiState.selectedValveNumber
                Surface(
                    color = if (isSelected) Slate900 else Slate100,
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) EmeraldLight else Slate200
                    ),
                    modifier = Modifier.clickable { onSelectValve(sec.valveNumber) }
                ) {
                    Text(
                        text = "📍 ${sec.nameAr} (NDVI ${formatDecimal2(sec.ndvi)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.White else Slate900,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val sectorPrompt = if (activeSector != null) {
            "حلل لي على خريطة Google Maps (${uiState.latitude}, ${uiState.longitude}) قطاع ${activeSector.nameAr} في ${uiState.fieldName} حيث NDVI=${formatDecimal2(activeSector.ndvi)} وNDWI=${formatDecimal2(activeSector.ndwi)} وزمن الري=${formatDecimal1(activeSector.operatingHours)} ساعة، وما التوصية الفورية؟"
        } else {
            "حلل لي خريطة Google Maps (${uiState.latitude}, ${uiState.longitude}) لحقل ${uiState.fieldName} وما توصية الري والتسميد اليوم؟"
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onAskGeminiChatbot(sectorPrompt) },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("satellite_ask_gemini_chatbot_btn")
            ) {
                Text(
                    text = "🤖 اسأل شات Gemini الذكي",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = { onOpenWhatsAppChatbot(sectorPrompt) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF075E54)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("satellite_ask_whatsapp_chatbot_btn")
            ) {
                Text(
                    text = "💬 شات بوت واتساب",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Button(
            onClick = {
                try {
                    val uri = "https://www.google.com/maps/search/?api=1&query=${uiState.latitude},${uiState.longitude}".toUri()
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("satellite_open_google_maps_app_btn")
        ) {
            Text(
                text = "🗺️ فتح الموقع (${formatCoord4(uiState.latitude)}°N, ${formatCoord4(uiState.longitude)}°E) في تطبيق Google Maps",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

