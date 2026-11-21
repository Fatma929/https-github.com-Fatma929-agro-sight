package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.BuildConfig
import com.example.model.SatelliteLayer
import com.example.model.ValveSectorTelemetry
import com.example.model.formatCoord4
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.model.formatHoursMinutesCompact
import com.example.ui.theme.AmberLight
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
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
import com.example.ui.theme.RoseDanger
import com.example.ui.theme.SkyAccent
import com.example.viewmodel.AgroSightUiState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.roundToInt

enum class DashboardMapEngineMode {
    GOOGLE_MAPS_SDK,
    MAPBOX_SATELLITE_GL
}

private fun isConfiguredGoogleMapsKey(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("AIza") &&
        trimmed.length >= 30 &&
        !trimmed.all { it.isDigit() }
}

private fun isConfiguredMapboxToken(value: String): Boolean {
    val trimmed = value.trim()
    return (trimmed.startsWith("pk.") || trimmed.startsWith("sk.")) &&
        trimmed.length >= 25 &&
        !trimmed.all { it.isDigit() }
}

private fun hasManifestGoogleMapsApiKey(context: android.content.Context): Boolean {
    return runCatching {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_META_DATA
        )
        val key = appInfo.metaData?.getString("com.google.android.geo.API_KEY")?.trim().orEmpty()
        key.isNotEmpty() && !key.all { it.isDigit() }
    }.getOrDefault(false)
}

@Composable
fun DashboardSatelliteMapCard(
    uiState: AgroSightUiState,
    onSelectLayer: (SatelliteLayer) -> Unit,
    onSelectValve: (Int) -> Unit,
    onSelectField: (String) -> Unit,
    onExpandFullSatelliteScreen: () -> Unit,
    onAskGeminiAboutSector: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasCustomGoogleMapsKey = remember {
        isConfiguredGoogleMapsKey(BuildConfig.GOOGLE_MAPS_API_KEY)
    }
    val hasValidManifestMapsKey = remember(context) {
        hasManifestGoogleMapsApiKey(context)
    }
    val hasCustomMapboxToken = remember {
        isConfiguredMapboxToken(BuildConfig.MAPBOX_ACCESS_TOKEN)
    }

    var mapEngineMode by rememberSaveable {
        mutableStateOf(
            if (hasCustomGoogleMapsKey && hasValidManifestMapsKey) {
                DashboardMapEngineMode.GOOGLE_MAPS_SDK
            } else {
                DashboardMapEngineMode.MAPBOX_SATELLITE_GL
            }
        )
    }
    var showFieldBoundaries by rememberSaveable { mutableStateOf(true) }
    var showSatelliteRasterOverlay by rememberSaveable { mutableStateOf(true) }
    var overlayOpacity by rememberSaveable { mutableFloatStateOf(0.68f) }

    val sectors = uiState.valveSectors
    val activeSector = sectors.find { it.valveNumber == uiState.selectedValveNumber }
        ?: sectors.firstOrNull()

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ForestCardDark),
        border = BorderStroke(1.dp, ForestCardBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("dashboard_satellite_map_sdk_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Header: Title + Engine Mode Switcher (Google Maps SDK vs Mapbox / Satellite GL)
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
                            .background(LimeSoftBg)
                            .border(1.dp, LimeAccent.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SatelliteAlt,
                            contentDescription = null,
                            tint = LimeBright,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "خريطة حدود الحقول وطبقات الأقمار الصناعية",
                            color = MintTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${uiState.fieldName} • (${formatCoord4(uiState.latitude)}°N, ${formatCoord4(uiState.longitude)}°E)",
                            color = MintTextSoft,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onExpandFullSatelliteScreen,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(ForestCardElevated)
                        .border(1.dp, ForestCardBorder, CircleShape)
                        .testTag("dashboard_map_expand_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "فتح استوديو الأقمار الصناعية الكامل",
                        tint = LimeBright,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 2. SDK Engine Switcher Row (Google Maps SDK Compose vs Mapbox GL / Hybrid Satellite)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EngineModePill(
                    title = "🗺️ Google Maps SDK",
                    subtitle = if (hasCustomGoogleMapsKey) "Native Maps Active" else "Compose SDK",
                    isSelected = mapEngineMode == DashboardMapEngineMode.GOOGLE_MAPS_SDK,
                    onClick = { mapEngineMode = DashboardMapEngineMode.GOOGLE_MAPS_SDK },
                    testTag = "dashboard_engine_google_maps_sdk"
                )
                EngineModePill(
                    title = "🛰️ Mapbox / Satellite GL",
                    subtitle = if (hasCustomMapboxToken) "Mapbox Token Active" else "Hybrid Raster + GeoJSON",
                    isSelected = mapEngineMode == DashboardMapEngineMode.MAPBOX_SATELLITE_GL,
                    onClick = { mapEngineMode = DashboardMapEngineMode.MAPBOX_SATELLITE_GL },
                    testTag = "dashboard_engine_mapbox_gl"
                )

                // Field Boundary Polygon Toggle
                Surface(
                    color = if (showFieldBoundaries) LimeSoftBg else ForestCardElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (showFieldBoundaries) LimeAccent else ForestCardBorder
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showFieldBoundaries = !showFieldBoundaries }
                        .testTag("dashboard_toggle_field_boundaries")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = if (showFieldBoundaries) LimeBright else MintTextSoft,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (showFieldBoundaries) "حدود الحقل: مفعّلة" else "إظهار الحدود",
                            color = if (showFieldBoundaries) LimeBright else MintTextSoft,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Satellite Raster Overlay Toggle
                Surface(
                    color = if (showSatelliteRasterOverlay) LimeSoftBg else ForestCardElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (showSatelliteRasterOverlay) CyanLight else ForestCardBorder
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showSatelliteRasterOverlay = !showSatelliteRasterOverlay }
                        .testTag("dashboard_toggle_satellite_overlay")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = if (showSatelliteRasterOverlay) CyanLight else MintTextSoft,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (showSatelliteRasterOverlay) "الطبقة الطيفية: ${(overlayOpacity * 100).roundToInt()}%" else "إظهار الطبقة الطيفية",
                            color = if (showSatelliteRasterOverlay) CyanLight else MintTextSoft,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. Satellite Spectral Layer Switcher + Opacity Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SatelliteLayer.entries.forEach { layer ->
                    val isSelected = uiState.activeSatelliteLayer == layer
                    Surface(
                        color = if (isSelected) LimeAccent else ForestCardElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) LimeBright else ForestCardBorder
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onSelectLayer(layer) }
                            .testTag("dashboard_layer_chip_${layer.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = if (isSelected) ForestBgDeep else MintTextSoft,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = layer.titleAr,
                                color = if (isSelected) ForestBgDeep else MintTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }

                listOf(0.45f to "45%", 0.68f to "68%", 0.90f to "90%").forEach { (alphaVal, label) ->
                    val isCurrentAlpha = kotlin.math.abs(overlayOpacity - alphaVal) < 0.05f
                    Surface(
                        color = if (isCurrentAlpha) ForestCardElevated else ForestBgDeep,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (isCurrentAlpha) CyanLight else ForestCardBorder
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { overlayOpacity = alphaVal }
                    ) {
                        Text(
                            text = "شفافية $label",
                            color = if (isCurrentAlpha) CyanLight else MintTextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // 4. Multi-Field Boundary Quick Switcher (Jump camera between active farm fields)
            if (uiState.dbSnapshot.fields.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    uiState.dbSnapshot.fields.forEach { field ->
                        val isCurrentField = field.id == uiState.fieldId
                        Surface(
                            color = if (isCurrentField) ForestCardElevated else ForestBgDeep,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isCurrentField) LimeBright else ForestCardBorder
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectField(field.id) }
                                .testTag("dashboard_map_field_chip_${field.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = if (isCurrentField) LimeBright else MintTextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "${field.fieldName} (${field.areaFeddans.roundToInt()} فدان)",
                                    color = if (isCurrentField) MintTextPrimary else MintTextSoft,
                                    fontSize = 11.sp,
                                    fontWeight = if (isCurrentField) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // 5. Interactive Map Viewport (Native Google Maps SDK Compose OR Mapbox GL / Hybrid Satellite)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(285.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.5.dp, ForestCardBorder, RoundedCornerShape(18.dp))
                    .background(ForestBgDeep)
            ) {
                if (mapEngineMode == DashboardMapEngineMode.GOOGLE_MAPS_SDK &&
                    hasCustomGoogleMapsKey &&
                    hasValidManifestMapsKey
                ) {
                    NativeGoogleMapsSdkViewport(
                        uiState = uiState,
                        sectors = sectors,
                        showFieldBoundaries = showFieldBoundaries,
                        showSatelliteRasterOverlay = showSatelliteRasterOverlay,
                        overlayOpacity = overlayOpacity,
                        realSatelliteBitmap = uiState.satelliteAndNasaTelemetry?.retrievedSatelliteBitmap,
                        onSelectValve = onSelectValve
                    )
                } else {
                    MapboxAndGoogleHybridWebGlViewport(
                        uiState = uiState,
                        sectors = sectors,
                        engineMode = mapEngineMode,
                        showFieldBoundaries = showFieldBoundaries,
                        showSatelliteRasterOverlay = showSatelliteRasterOverlay,
                        overlayOpacity = overlayOpacity,
                        mapboxAccessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
                    )
                }

                // Top-Start Live Telemetry Overlay Badge
                Surface(
                    color = ForestBgDeep.copy(alpha = 0.86f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, ForestCardBorder),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
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
                            text = "Sentinel-2 ${uiState.activeSatelliteLayer.name}: NDVI ${formatDecimal2(uiState.epidemiologyReport.ndviMean)} • ${uiState.areaFeddans.roundToInt()} فدان",
                            color = MintTextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 6. Interactive 6-Valve Sector Selector Strip
            if (sectors.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sectors.forEach { sector ->
                        val isSelected = sector.valveNumber == uiState.selectedValveNumber
                        val sectorColor = dashboardSectorOverlayColor(sector, uiState.activeSatelliteLayer)
                        Surface(
                            color = if (isSelected) ForestCardElevated else ForestBgDeep,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) sectorColor else ForestCardBorder
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectValve(sector.valveNumber) }
                                .testTag("dashboard_valve_chip_${sector.valveNumber}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(sectorColor)
                                )
                                Column {
                                    Text(
                                        text = sector.nameAr,
                                        color = MintTextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = dashboardFormatSectorMetric(sector, uiState.activeSatelliteLayer),
                                        color = if (isSelected) LimeBright else MintTextSoft,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. Selected Sector Telemetry Summary & One-Tap Actions
            if (activeSector != null) {
                HorizontalDivider(color = ForestCardBorder.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "القطاع المحدد: ${activeSector.nameAr} (${activeSector.statusBadgeAr})",
                            color = LimeBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "NDVI ${formatDecimal2(activeSector.ndvi)} • NDWI ${formatDecimal2(activeSector.ndwi)} • المقنن ${formatDecimal1(activeSector.grossWaterM3PerFeddan)} م³/فدان (${formatHoursMinutesCompact(activeSector.operatingHours)})",
                            color = MintTextSoft,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val prompt = "حلل لي حدود وقطاع ${activeSector.nameAr} في ${uiState.fieldName} عند الإحداثيات (${uiState.latitude}, ${uiState.longitude}) حيث NDVI=${formatDecimal2(activeSector.ndvi)} وNDWI=${formatDecimal2(activeSector.ndwi)} وملوحة التربة=${formatDecimal1(activeSector.sectorEce)} dS/m."
                            onAskGeminiAboutSector(prompt)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LimeAccent,
                            contentColor = ForestBgDeep
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1.15f)
                            .testTag("dashboard_map_ask_gemini_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "استشارة Gemini عن القطاع",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Button(
                        onClick = {
                            try {
                                val uri = "https://www.google.com/maps/search/?api=1&query=${uiState.latitude},${uiState.longitude}".toUri()
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (_: Exception) {
                                onExpandFullSatelliteScreen()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForestCardElevated,
                            contentColor = MintTextPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(0.85f)
                            .testTag("dashboard_map_open_gmaps_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = CyanLight,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تطبيق Google Maps",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineModePill(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        color = if (isSelected) ForestCardElevated else ForestBgDeep,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) LimeAccent else ForestCardBorder
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = title,
                color = if (isSelected) LimeBright else MintTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = subtitle,
                color = MintTextMuted,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun NativeGoogleMapsSdkViewport(
    uiState: AgroSightUiState,
    sectors: List<ValveSectorTelemetry>,
    showFieldBoundaries: Boolean,
    showSatelliteRasterOverlay: Boolean,
    overlayOpacity: Float,
    realSatelliteBitmap: Bitmap?,
    onSelectValve: (Int) -> Unit
) {
    val fieldCenter = remember(uiState.latitude, uiState.longitude) {
        LatLng(uiState.latitude, uiState.longitude)
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(fieldCenter, 16.0f)
    }

    LaunchedEffect(fieldCenter) {
        runCatching {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(fieldCenter, 16.0f),
                durationMs = 550
            )
        }
    }

    val dLat = 0.0014
    val dLon = 0.0016
    val farmBounds = remember(fieldCenter) {
        LatLngBounds(
            LatLng(fieldCenter.latitude - dLat * 1.05, fieldCenter.longitude - dLon * 1.55),
            LatLng(fieldCenter.latitude + dLat * 1.05, fieldCenter.longitude + dLon * 1.55)
        )
    }
    val outerPerimeterPoints = remember(fieldCenter) {
        listOf(
            LatLng(fieldCenter.latitude + dLat * 1.02, fieldCenter.longitude - dLon * 1.52),
            LatLng(fieldCenter.latitude + dLat * 1.02, fieldCenter.longitude + dLon * 1.52),
            LatLng(fieldCenter.latitude - dLat * 1.02, fieldCenter.longitude + dLon * 1.52),
            LatLng(fieldCenter.latitude - dLat * 1.02, fieldCenter.longitude - dLon * 1.52)
        )
    }

    GoogleMap(
        modifier = Modifier
            .fillMaxSize()
            .testTag("native_google_maps_compose_view"),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            mapType = MapType.HYBRID,
            isBuildingEnabled = false
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = true,
            compassEnabled = true,
            mapToolbarEnabled = true
        )
    ) {
        // 1. Optional Sentinel-2 / NASA GIBS Satellite Raster GroundOverlay
        if (showSatelliteRasterOverlay && realSatelliteBitmap != null) {
            val descriptor = remember(realSatelliteBitmap) {
                runCatching { BitmapDescriptorFactory.fromBitmap(realSatelliteBitmap) }.getOrNull()
            }
            if (descriptor != null) {
                GroundOverlay(
                    position = GroundOverlayPosition.create(farmBounds),
                    image = descriptor,
                    transparency = (1f - overlayOpacity).coerceIn(0.05f, 0.85f),
                    zIndex = 1f
                )
            }
        }

        // 2. Outer Farm Boundary GeoJSON Polygon
        if (showFieldBoundaries) {
            Polygon(
                points = outerPerimeterPoints,
                strokeColor = LimeBright,
                strokeWidth = 7f,
                fillColor = Color.Transparent,
                zIndex = 2f
            )
        }

        // 3. 6 Irrigation Valve Sector Polygons + Spectral Heatmap Overlays
        sectors.forEachIndexed { idx, sector ->
            val row = idx / 3
            val col = idx % 3
            val centerLat = fieldCenter.latitude + (0.5 - row) * dLat
            val centerLon = fieldCenter.longitude + (col - 1) * dLon
            val halfLat = dLat * 0.45
            val halfLon = dLon * 0.45

            val sectorPolygon = listOf(
                LatLng(centerLat + halfLat, centerLon - halfLon),
                LatLng(centerLat + halfLat, centerLon + halfLon),
                LatLng(centerLat - halfLat, centerLon + halfLon),
                LatLng(centerLat - halfLat, centerLon - halfLon)
            )
            val isSelected = sector.valveNumber == uiState.selectedValveNumber
            val baseOverlayColor = dashboardSectorOverlayColor(sector, uiState.activeSatelliteLayer)

            if (showFieldBoundaries || showSatelliteRasterOverlay) {
                Polygon(
                    points = sectorPolygon,
                    clickable = true,
                    fillColor = if (showSatelliteRasterOverlay) {
                        baseOverlayColor.copy(alpha = if (isSelected) overlayOpacity else (overlayOpacity * 0.65f))
                    } else {
                        Color.Transparent
                    },
                    strokeColor = if (showFieldBoundaries) {
                        if (isSelected) Color.White else baseOverlayColor
                    } else {
                        Color.Transparent
                    },
                    strokeWidth = if (isSelected) 7f else 3.5f,
                    zIndex = if (isSelected) 4f else 3f,
                    onClick = { onSelectValve(sector.valveNumber) }
                )
            }

            if (sector.valveNumber == uiState.epidemiologyReport.affectedValveNumber && showSatelliteRasterOverlay) {
                Circle(
                    center = LatLng(centerLat, centerLon),
                    radius = 52.0,
                    fillColor = RoseDanger.copy(alpha = 0.35f),
                    strokeColor = RoseDanger,
                    strokeWidth = 4f,
                    zIndex = 5f
                )
            }

            if (isSelected) {
                val markerState = remember(sector.valveNumber, centerLat, centerLon) {
                    MarkerState(position = LatLng(centerLat, centerLon))
                }
                Marker(
                    state = markerState,
                    title = "${sector.nameAr} • ${uiState.fieldName}",
                    snippet = "NDVI ${formatDecimal2(sector.ndvi)} | NDWI ${formatDecimal2(sector.ndwi)} | الري ${formatHoursMinutesCompact(sector.operatingHours)}"
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapboxAndGoogleHybridWebGlViewport(
    uiState: AgroSightUiState,
    sectors: List<ValveSectorTelemetry>,
    engineMode: DashboardMapEngineMode,
    showFieldBoundaries: Boolean,
    showSatelliteRasterOverlay: Boolean,
    overlayOpacity: Float,
    mapboxAccessToken: String
) {
    val htmlContent = remember(
        uiState.latitude,
        uiState.longitude,
        uiState.fieldName,
        uiState.selectedCrop.nameAr,
        uiState.selectedValveNumber,
        uiState.activeSatelliteLayer,
        uiState.epidemiologyReport.ndviMean,
        uiState.epidemiologyReport.affectedValveNumber,
        engineMode,
        showFieldBoundaries,
        showSatelliteRasterOverlay,
        overlayOpacity,
        sectors
    ) {
        buildDashboardMapboxAndGoogleSatelliteHtml(
            latitude = uiState.latitude,
            longitude = uiState.longitude,
            fieldName = uiState.fieldName,
            cropNameAr = uiState.selectedCrop.nameAr,
            selectedValve = uiState.selectedValveNumber,
            affectedValve = uiState.epidemiologyReport.affectedValveNumber,
            activeLayer = uiState.activeSatelliteLayer,
            engineMode = engineMode,
            showBoundaries = showFieldBoundaries,
            showRasterOverlay = showSatelliteRasterOverlay,
            overlayOpacity = overlayOpacity,
            sectors = sectors,
            mapboxToken = mapboxAccessToken
        )
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "https://maps.google.com",
                htmlContent,
                "text/html",
                "UTF-8",
                null
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_mapbox_hybrid_webview")
    )
}

private fun dashboardSectorOverlayColor(sector: ValveSectorTelemetry, layer: SatelliteLayer): Color {
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
            else -> AmberLight
        }
    }
}

private fun dashboardFormatSectorMetric(sector: ValveSectorTelemetry, layer: SatelliteLayer): String {
    return when (layer) {
        SatelliteLayer.NDVI -> "NDVI ${formatDecimal2(sector.ndvi)}"
        SatelliteLayer.NDWI -> "NDWI ${formatDecimal2(sector.ndwi)}"
        SatelliteLayer.SALINITY -> "${formatDecimal1(sector.sectorEce)} dS/m"
        SatelliteLayer.VALVE_HOURS -> formatHoursMinutesCompact(sector.operatingHours)
    }
}

private fun buildDashboardMapboxAndGoogleSatelliteHtml(
    latitude: Double,
    longitude: Double,
    fieldName: String,
    cropNameAr: String,
    selectedValve: Int,
    affectedValve: Int,
    activeLayer: SatelliteLayer,
    engineMode: DashboardMapEngineMode,
    showBoundaries: Boolean,
    showRasterOverlay: Boolean,
    overlayOpacity: Float,
    sectors: List<ValveSectorTelemetry>,
    mapboxToken: String
): String {
    val validMapboxToken = if (isConfiguredMapboxToken(mapboxToken)) mapboxToken.trim() else ""
    val useGoogleHybrid = engineMode == DashboardMapEngineMode.GOOGLE_MAPS_SDK || validMapboxToken.isEmpty()
    val engineBadgeLabel = when {
        engineMode == DashboardMapEngineMode.GOOGLE_MAPS_SDK -> "Google Maps Hybrid Satellite SDK"
        validMapboxToken.isNotEmpty() -> "Mapbox Satellite Streets v12"
        else -> "Sentinel-2 & Google Hybrid Satellite GL"
    }
    val sectorJs = if (sectors.isNotEmpty()) {
        sectors.joinToString(",") { sec ->
            val hexColor = when (activeLayer) {
                SatelliteLayer.NDVI -> if (sec.ndvi >= 0.62f) "#10b981" else if (sec.ndvi >= 0.50f) "#f59e0b" else "#ef4444"
                SatelliteLayer.NDWI -> if (sec.ndwi >= 0.22f) "#0ea5e9" else if (sec.ndwi >= 0.14f) "#f59e0b" else "#ef4444"
                SatelliteLayer.SALINITY -> if (sec.sectorEce <= 2.3f) "#10b981" else if (sec.sectorEce <= 2.7f) "#f59e0b" else "#ef4444"
                SatelliteLayer.VALVE_HOURS -> if (sec.operatingHours <= 2.2f) "#0ea5e9" else if (sec.operatingHours <= 2.7f) "#10b981" else "#f59e0b"
            }
            "{v:${sec.valveNumber},name:'${sec.nameAr}',ndvi:${formatDecimal2(sec.ndvi)},ndwi:${formatDecimal2(sec.ndwi)},ece:${formatDecimal1(sec.sectorEce)},hrs:${formatDecimal1(sec.operatingHours)},color:'$hexColor'}"
        }
    } else {
        (1..6).joinToString(",") { v ->
            "{v:$v,name:'محبس $v',ndvi:0.68,ndwi:0.24,ece:2.1,hrs:2.3,color:'#10b981'}"
        }
    }

    return """
<!DOCTYPE html>
<html dir="rtl">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <style>
    body, html { margin: 0; padding: 0; width: 100%; height: 100%; background: #07140b; font-family: sans-serif; overflow: hidden; direction: rtl; text-align: right; }
    #map { width: 100%; height: 100%; }
    .bottom-legend {
      position: absolute; bottom: 8px; right: 8px; left: 8px; z-index: 999;
      background: rgba(6, 21, 11, 0.88); color: #e2f8e8; border: 1px solid rgba(132, 204, 22, 0.45);
      border-radius: 10px; padding: 5px 10px; font-size: 10px;
      display: flex; justify-content: space-between; align-items: center;
      pointer-events: none; direction: rtl;
    }
    .sector-label {
      background: rgba(6, 21, 11, 0.82); color: #ffffff; border: 1px solid #84cc16;
      border-radius: 6px; padding: 2px 5px; font-size: 10px; font-weight: bold;
      white-space: nowrap;
    }
  </style>
</head>
<body>
  <div id="map"></div>
  <div class="bottom-legend">
    <span>🗺️ $engineBadgeLabel</span>
    <span>الطبقة: ${activeLayer.titleAr} • محبس #$selectedValve</span>
  </div>
  <script>
    (function() {
      const lat = $latitude;
      const lon = $longitude;
      const selValve = $selectedValve;
      const affValve = $affectedValve;
      const showBounds = $showBoundaries;
      const showOverlay = $showRasterOverlay;
      const alpha = $overlayOpacity;
      const mbToken = '$validMapboxToken';
      const useGoogle = $useGoogleHybrid;
      const sectors = [$sectorJs];

      if (typeof L !== 'undefined') {
        const map = L.map('map', { zoomControl: true, attributionControl: false }).setView([lat, lon], 16);
        const tileUrl = (!useGoogle && mbToken.length > 5)
          ? 'https://api.mapbox.com/styles/v1/mapbox/satellite-streets-v12/tiles/{z}/{x}/{y}?access_token=' + mbToken
          : 'https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}';

        L.tileLayer(tileUrl, { maxZoom: 20, tileSize: (!useGoogle && mbToken.length > 5) ? 512 : 256, zoomOffset: (!useGoogle && mbToken.length > 5) ? -1 : 0 }).addTo(map);

        const dLat = 0.0014;
        const dLon = 0.0016;

        // Outer Farm GeoJSON Boundary Perimeter
        if (showBounds) {
          const outerBounds = [
            [lat + dLat * 1.02, lon - dLon * 1.52],
            [lat + dLat * 1.02, lon + dLon * 1.52],
            [lat - dLat * 1.02, lon + dLon * 1.52],
            [lat - dLat * 1.02, lon - dLon * 1.52]
          ];
          L.polygon(outerBounds, {
            color: '#a3e635',
            weight: 3,
            dashArray: '8, 5',
            fill: false
          }).addTo(map);
        }

        // 6 Valve Sectors + Spectral Raster Overlay
        sectors.forEach(function(sec, idx) {
          const row = Math.floor(idx / 3);
          const col = idx % 3;
          const sLat = lat + (0.5 - row) * dLat;
          const sLon = lon + (col - 1) * dLon;
          const isSelected = (sec.v === selValve);
          const bounds = [
            [sLat - dLat * 0.45, sLon - dLon * 0.45],
            [sLat + dLat * 0.45, sLon + dLon * 0.45]
          ];

          if (showBounds || showOverlay) {
            const rect = L.rectangle(bounds, {
              color: showBounds ? (isSelected ? '#ffffff' : sec.color) : 'transparent',
              weight: isSelected ? 3.2 : 1.6,
              fillColor: sec.color,
              fillOpacity: showOverlay ? (isSelected ? alpha : alpha * 0.68) : 0.0
            }).addTo(map);

            rect.bindPopup(
              '<b>' + sec.name + ' ($fieldName - $cropNameAr)</b><br/>' +
              'NDVI: ' + sec.ndvi + ' | NDWI: ' + sec.ndwi + '<br/>' +
              'ECe: ' + sec.ece + ' dS/m | زمن الري: ' + sec.hrs + ' ساعة'
            );
            if (isSelected) {
              rect.openPopup();
            }
          }

          if (sec.v === affValve && showOverlay) {
            L.circle([sLat, sLon], {
              radius: 48,
              color: '#f43f5e',
              weight: 2,
              fillColor: '#f43f5e',
              fillOpacity: 0.35
            }).addTo(map);
          }
        });
      }
    })();
  </script>
</body>
</html>
    """.trimIndent()
}
