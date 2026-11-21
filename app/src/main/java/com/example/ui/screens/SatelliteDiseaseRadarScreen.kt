package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.AnomalyPattern
import com.example.data.EpidemiologyDiagnosisReport
import com.example.data.SpectralPixelCell
import com.example.data.SpectralRadarLayer
import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import com.example.ui.components.AgronomicSafetyAndDataProvenanceBanner
import com.example.ui.components.OpenSourceCommunicationChannelsCard
import com.example.ui.components.Recharts7DayIrrigationComparisonCard
import com.example.ui.components.ScientificFormulaAndCitationCard
import com.example.ui.theme.AmberLight
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.RoseDanger
import com.example.ui.theme.RoseLight
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
import com.example.ui.theme.Slate950
import com.example.viewmodel.AgroSightUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SatelliteDiseaseRadarScreen(
    uiState: AgroSightUiState,
    onSelectSpectralLayer: (SpectralRadarLayer) -> Unit,
    onSelectAnomalyPattern: (AnomalyPattern) -> Unit,
    onSendProactiveWhatsAppAlert: () -> Unit,
    onRunGeminiDiagnosis: () -> Unit,
    onToggleRealOpticalUnderlay: () -> Unit = {},
    onRefreshOpenSourceTelemetry: () -> Unit = {}
) {
    val report = uiState.epidemiologyReport
    val today = uiState.forecastDays.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .testTag("disease_radar_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 100% Sensor-Free & Camera-Free Core Architecture Banner
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Slate900)
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(EmeraldPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.NoPhotography,
                        contentDescription = null,
                        tint = EmeraldLight,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "رصد وبائي طيفي 100% بدون كاميرات أو تصوير أوراق وبدون حساسات",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "يدمج مؤشرات Sentinel-2 المتعددة الأطياف (NDRE, PSRI, NDMI, NDVI) مع أرصاد Open-Meteo الحية وكيمياء التربة (pH & EC) لدعم القرار الزراعي الاستباقي.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
                    )
                }
            }
        }

        // Data Provenance & Agronomic Safety Disclaimer Banner
        item {
            val isLiveGeorefRaster = uiState.rasterBackendResult?.isLiveGeoreferencedRaster == true
            AgronomicSafetyAndDataProvenanceBanner(
                isLiveMeteoConnected = uiState.isLiveMeteoConnected,
                meteoStatusAr = uiState.meteoStatusAr,
                isSimulatedSpectralPattern = !isLiveGeorefRaster
            )
        }

        // Georeferenced Raster Engine Telemetry & 3-Tier Scientific Assessment Card
        item {
            val rasterRes = uiState.rasterBackendResult
            BentoCard(
                title = "محرك المعالجة الطيفية الجغرافية (Sentinel-2 L2A GeoTIFF Raster Engine)",
                subtitle = "حالة مصدر البيانات: ${report.dataModeDesignation}",
                icon = Icons.Default.SatelliteAlt,
                accentColor = if (rasterRes?.isLiveGeoreferencedRaster == true) EmeraldPrimary else AmberWarning
            ) {
                if (rasterRes != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SpectralIndexMiniCard(
                            modifier = Modifier.weight(1f),
                            code = "CRS",
                            labelAr = "نظام الإحداثيات",
                            value = "EPSG:${rasterRes.crsEpsg}",
                            color = EmeraldDark
                        )
                        SpectralIndexMiniCard(
                            modifier = Modifier.weight(1f),
                            code = "RES",
                            labelAr = "الدقة المكانية",
                            value = "${rasterRes.spatialResolutionMeters.toInt()}m",
                            color = SkyAccent
                        )
                        SpectralIndexMiniCard(
                            modifier = Modifier.weight(1f),
                            code = "VALID",
                            labelAr = "البكسلات الصافية",
                            value = "${(rasterRes.validPixelRatio * 100f).toInt()}%",
                            color = EmeraldPrimary
                        )
                        SpectralIndexMiniCard(
                            modifier = Modifier.weight(1f),
                            code = "ANOMALY",
                            labelAr = "مضلعات GeoJSON",
                            value = "${rasterRes.anomalyPixelCount}px",
                            color = if (rasterRes.anomalyPixelCount > 0) RoseDanger else EmeraldPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Surface(
                    color = Slate100,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scientific_three_tier_separation_card")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "1. رصد الإجهاد الطيفي: ${report.vegetationStressDetectionAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "2. تقييم المخاطر الزراعية: ${report.agriculturalRiskAssessmentAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700
                        )
                        Text(
                            text = "3. حالة التشخيص المؤكد: ${report.confirmedDiseaseDiagnosisStatusAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Recharts 7-Day Proposed Irrigation Schedule vs. Daily Crop Water Requirement Chart
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

        // Section 1: Multi-Spectral 10m×10m Pixel Grid & Layer Switcher
        item {
            BentoCard(
                title = "رادار أمراض المحاصيل بالأقمار الصناعية (Sentinel-2 10m×10m Pixel Map)",
                subtitle = "حقل: ${uiState.fieldName} • المحصول: ${uiState.selectedCrop.nameAr} • المزارع: ${uiState.activeFarmer.fullName}",
                icon = Icons.Default.Radar,
                accentColor = RoseDanger
            ) {
                // 4 Multi-Spectral Layer Switcher Buttons (NDRE, PSRI, NDMI, NDVI)
                Text(
                    text = "اختر القناة الطيفية للقمر الصناعي Sentinel-2:",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate800
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpectralRadarLayer.entries.forEach { layer ->
                        val isSel = uiState.activeSpectralRadarLayer == layer
                        val layerAccent = when (layer) {
                            SpectralRadarLayer.NDRE -> EmeraldPrimary
                            SpectralRadarLayer.PSRI -> RoseDanger
                            SpectralRadarLayer.NDMI -> SkyAccent
                            SpectralRadarLayer.NDVI -> EmeraldDark
                        }
                        Surface(
                            color = if (isSel) Slate900 else Slate100,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSel) 2.dp else 1.dp,
                                color = if (isSel) layerAccent else Slate200
                            ),
                            modifier = Modifier
                                .clickable { onSelectSpectralLayer(layer) }
                                .testTag("spectral_layer_${layer.code.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(layerAccent)
                                    )
                                    Text(
                                        text = layer.shortTitleAr,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSel) Color.White else Slate900,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = layer.bandFormula,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSel) CyanLight else Slate500
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Active Layer Description Strip
                Surface(
                    color = Slate100,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = uiState.activeSpectralRadarLayer.fullTitleAr,
                            style = MaterialTheme.typography.labelLarge,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = uiState.activeSpectralRadarLayer.descriptionAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Interactive 10m×10m Sentinel-2 Pixel Grid divided into 6 Valves with Real Optical Underlay
                Sentinel2PixelRadarGrid(
                    report = report,
                    activeLayer = uiState.activeSpectralRadarLayer,
                    realSatelliteBitmap = uiState.satelliteAndNasaTelemetry?.retrievedSatelliteBitmap,
                    showRealOpticalUnderlay = uiState.showRealOpticalSatelliteUnderlay,
                    sceneId = uiState.satelliteAndNasaTelemetry?.sceneId ?: "S2B_MSIL2A_LIVE",
                    onToggleRealOpticalUnderlay = onToggleRealOpticalUnderlay
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 4 Spectral Index Mean KPI Pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpectralIndexMiniCard(
                        modifier = Modifier.weight(1f),
                        code = "NDRE",
                        labelAr = "الكلوروفيل المبكر",
                        value = formatDecimal2(report.ndreMean),
                        color = if (report.ndreMean < 0.35f) AmberWarning else EmeraldPrimary
                    )
                    SpectralIndexMiniCard(
                        modifier = Modifier.weight(1f),
                        code = "PSRI",
                        labelAr = "تلف الأنسجة",
                        value = formatDecimal2(report.psriMean),
                        color = if (report.psriMean > 0.15f) RoseDanger else EmeraldPrimary
                    )
                    SpectralIndexMiniCard(
                        modifier = Modifier.weight(1f),
                        code = "NDMI",
                        labelAr = "الرطوبة والتغدق",
                        value = formatDecimal2(report.ndmiMean),
                        color = if (report.ndmiMean < 0.16f) AmberWarning else SkyAccent
                    )
                    SpectralIndexMiniCard(
                        modifier = Modifier.weight(1f),
                        code = "NDVI",
                        labelAr = "الغطاء العام",
                        value = formatDecimal2(report.ndviMean),
                        color = EmeraldDark
                    )
                }
            }
        }

        // Section 2: Automated Spatial Pattern Classifier (التفريق الذكي بين المرض والعطش والقلوية)
        item {
            BentoCard(
                title = "مصنف الأنماط المكانية الذكي (Spatial Pattern Classifier)",
                subtitle = "التفريق الآلي بين الإصابة المرضية البؤرية (Case A) وعطل المحبس (Case B) وقلوية التربة (Case C)",
                icon = Icons.Default.BugReport,
                accentColor = AmberWarning
            ) {
                Text(
                    text = "اختر نمط الرصد الطيفي لاختبار محرك التشخيص الوبائي:",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate800
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnomalyPattern.entries.forEach { pattern ->
                        val isSel = uiState.activeAnomalyPattern == pattern
                        val patColor = when (pattern) {
                            AnomalyPattern.FOCAL_RADIAL_PATCH -> RoseDanger
                            AnomalyPattern.UNIFORM_VALVE -> SkyAccent
                            AnomalyPattern.SOIL_PH_LOCKUP -> AmberWarning
                            AnomalyPattern.HEALTHY -> EmeraldPrimary
                        }
                        Surface(
                            color = if (isSel) patColor else Slate100,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSel) patColor else Slate200
                            ),
                            modifier = Modifier
                                .clickable { onSelectAnomalyPattern(pattern) }
                                .testTag("pattern_chip_${pattern.code.lowercase()}")
                        ) {
                            Text(
                                text = pattern.caseBadgeAr,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSel) Color.White else Slate900,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Detailed Epidemiological Diagnosis Card
                val diagColor = when (report.anomalyPattern) {
                    AnomalyPattern.FOCAL_RADIAL_PATCH -> RoseDanger
                    AnomalyPattern.UNIFORM_VALVE -> SkyAccent
                    AnomalyPattern.SOIL_PH_LOCKUP -> AmberWarning
                    AnomalyPattern.HEALTHY -> EmeraldPrimary
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(diagColor.copy(alpha = 0.08f))
                        .border(1.5.dp, diagColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = report.caseTitleAr,
                                style = MaterialTheme.typography.labelMedium,
                                color = diagColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = report.detectedRiskNameAr,
                                style = MaterialTheme.typography.titleMedium,
                                color = Slate900,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            color = diagColor,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "توافق النمط ${report.confidenceScorePct}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Location & Weather Context Strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .border(1.dp, Slate200, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "📍 الموقع المرصود: ${report.hotspotLocationAr}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Slate900
                            )
                            Text(
                                text = "☁️ الرطوبة: ${today?.relativeHumidityPct ?: 74}% • الحرارة: ${formatDecimal1(today?.tempMaxC ?: 32f)}°C • pH التربة: ${formatDecimal1(uiState.soilPh)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                    }

                    Text(
                        text = "🔬 التفسير الطيفي والفيزيائي: ${report.causalMechanismAr}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate800
                    )

                    Text(
                        text = "💧 تعديل الري الفوري: ${report.immediateIrrigationActionAr}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkyAccent,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "🛡️ الإجراء الوقائي المستهدف: ${report.targetedPreventiveActionAr}",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldDark,
                        fontWeight = FontWeight.SemiBold
                    )

                    Surface(
                        color = EmeraldSoft,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary)
                    ) {
                        Text(
                            text = "💰 جدوى اقتصادية: ${report.costSavingHighlightAr}",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldDark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons: Send Proactive WhatsApp Early-Warning & Run Gemini AI Analysis
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onSendProactiveWhatsAppAlert,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("send_disease_whatsapp_alert_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "إرسال إنذار مبكر للواتساب",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    Button(
                        onClick = onRunGeminiDiagnosis,
                        colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("run_gemini_epidemiology_button")
                    ) {
                        if (uiState.isGeneratingGeminiDiagnosis) {
                            CircularProgressIndicator(
                                color = EmeraldLight,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = EmeraldLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تحليل Gemini AI",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }

                if (uiState.geminiEpidemiologyAnalysisText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate900)
                            .border(1.dp, EmeraldLight, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = uiState.geminiEpidemiologyAnalysisText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Open-Source Satellite & Meteorological Communication Channels Card
        item {
            OpenSourceCommunicationChannelsCard(
                telemetry = uiState.satelliteAndNasaTelemetry,
                latitude = uiState.latitude,
                longitude = uiState.longitude,
                isSyncing = uiState.isSyncingOpenSourceSatellite,
                onRefreshTelemetry = onRefreshOpenSourceTelemetry
            )
        }

        // Exact Cited Scientific Formulas & Hydrological Derivation Card
        item {
            ScientificFormulaAndCitationCard(
                audit = uiState.formulaAudit,
                irrigationSystemNameAr = uiState.irrigationSystem.nameAr
            )
        }
    }
}

@Composable
private fun Sentinel2PixelRadarGrid(
    report: EpidemiologyDiagnosisReport,
    activeLayer: SpectralRadarLayer,
    realSatelliteBitmap: Bitmap?,
    showRealOpticalUnderlay: Boolean,
    sceneId: String,
    onToggleRealOpticalUnderlay: () -> Unit
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
                    text = "SENTINEL-2 MULTI-SPECTRAL 10m×10m PIXEL MATRIX",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldLight,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Scene: $sceneId • القناة: ${activeLayer.code}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanLight
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
                    .testTag("radar_toggle_real_optical_btn")
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
                        text = if (showRealOpticalUnderlay) "مرئية القمر الحقيقية: مفعّلة" else "إظهار مرئية القمر",
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
                .height(235.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF060E1A))
        ) {
            if (showRealOpticalUnderlay && realSatelliteBitmap != null) {
                Image(
                    bitmap = realSatelliteBitmap.asImageBitmap(),
                    contentDescription = "مرئية القمر الصناعي الحقيقية للحقل",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.55f)
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rows = 8
                val cols = 12
                val cellW = size.width / cols.toFloat()
                val cellH = size.height / rows.toFloat()
                val gap = 2.dp.toPx()

                // Draw each 10m×10m Sentinel-2 pixel cell
                report.pixelGrid.forEach { cell ->
                    // Visual column in RTL: col 0 on right, col 11 on left
                    val visualCol = cols - 1 - cell.col
                    val x = visualCol * cellW + gap / 2f
                    val y = cell.row * cellH + gap / 2f

                    val pixelColor = colorForSpectralPixel(cell, activeLayer)
                    drawRoundRect(
                        color = pixelColor,
                        topLeft = Offset(x, y),
                        size = Size(cellW - gap, cellH - gap),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )

                    if (cell.isHotspot) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.85f),
                            topLeft = Offset(x, y),
                            size = Size(cellW - gap, cellH - gap),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // Draw 6 Valve Sector Boundary Dividers (3 cols x 2 rows)
                val valveW = size.width / 3f
                val valveH = size.height / 2f
                for (vc in 1..2) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.7f),
                        start = Offset(vc * valveW, 0f),
                        end = Offset(vc * valveW, size.height),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                }
                drawLine(
                    color = CyanLight.copy(alpha = 0.85f),
                    start = Offset(0f, valveH),
                    end = Offset(size.width, valveH),
                    strokeWidth = 2.5.dp.toPx()
                )
            }

            // Valve Labels Overlay (محبس 1 .. محبس 6)
            Column(modifier = Modifier.fillMaxSize()) {
                for (r in 0..1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        for (c in 0..2) {
                            val valveNum = r * 3 + c + 1
                            val isAffected = report.affectedValveNumber == valveNum &&
                                report.anomalyPattern != AnomalyPattern.HEALTHY
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .padding(6.dp),
                                contentAlignment = Alignment.TopStart
                            ) {
                                Surface(
                                    color = if (isAffected) RoseDanger.copy(alpha = 0.9f) else Slate950.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isAffected) Color.White else Slate700
                                    )
                                ) {
                                    Text(
                                        text = if (isAffected) "⚠️ محبس $valveNum (بؤرة)" else "محبس $valveNum",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("🟢 سليم عالي الكلوروفيل", style = MaterialTheme.typography.labelSmall, color = EmeraldLight)
            Text("🟡 إجهاد مبكر (NDRE منخفض)", style = MaterialTheme.typography.labelSmall, color = AmberLight)
            Text("🔴 بؤرة تلف أنسجة (PSRI مرتفع)", style = MaterialTheme.typography.labelSmall, color = RoseLight)
        }
    }
}

private fun colorForSpectralPixel(cell: SpectralPixelCell, layer: SpectralRadarLayer): Color {
    return when (layer) {
        SpectralRadarLayer.NDRE -> when {
            cell.ndre >= 0.45f -> EmeraldPrimary.copy(alpha = 0.85f)
            cell.ndre >= 0.32f -> AmberWarning.copy(alpha = 0.85f)
            else -> RoseDanger.copy(alpha = 0.92f)
        }
        SpectralRadarLayer.PSRI -> when {
            cell.psri >= 0.22f -> Color(0xFFE11D48) // High necrosis/lesion hotspot (Magenta/Rose)
            cell.psri >= 0.12f -> AmberWarning.copy(alpha = 0.88f)
            else -> EmeraldDark.copy(alpha = 0.78f)
        }
        SpectralRadarLayer.NDMI -> when {
            cell.ndmi >= 0.44f -> Color(0xFF1D4ED8) // Waterlogged root-rot risk zone (Deep Blue)
            cell.ndmi >= 0.24f -> SkyAccent.copy(alpha = 0.82f)
            else -> AmberWarning.copy(alpha = 0.90f) // Dry/blocked valve zone
        }
        SpectralRadarLayer.NDVI -> when {
            cell.ndvi >= 0.65f -> EmeraldPrimary.copy(alpha = 0.88f)
            cell.ndvi >= 0.52f -> AmberWarning.copy(alpha = 0.85f)
            else -> RoseDanger.copy(alpha = 0.88f)
        }
    }
}

@Composable
private fun SpectralIndexMiniCard(
    modifier: Modifier = Modifier,
    code: String,
    labelAr: String,
    value: String,
    color: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Slate100)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Slate900,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = labelAr,
            style = MaterialTheme.typography.labelSmall,
            color = Slate600,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}
