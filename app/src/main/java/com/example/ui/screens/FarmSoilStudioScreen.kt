package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.model.EGYPTIAN_ZONE_PRESETS
import com.example.model.EgyptianZonePreset
import com.example.model.GROWTH_STAGE_NAMES_AR
import com.example.model.IrrigationSystemType
import com.example.model.NutrientAvailability
import com.example.model.PhClass
import com.example.model.SUPPORTED_CROPS
import com.example.model.SoilTexture
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
import com.example.ui.theme.Slate50
import com.example.ui.theme.Slate500
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.viewmodel.AgroSightUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FarmSoilStudioScreen(
    uiState: AgroSightUiState,
    onUpdateFieldName: (String) -> Unit,
    onUpdateAreaFeddans: (String) -> Unit,
    onSelectZonePreset: (EgyptianZonePreset) -> Unit,
    onUpdateCoordinates: (String, String) -> Unit,
    onSelectCrop: (com.example.model.CropSpec) -> Unit,
    onUpdateDaysAfterSowing: (Int) -> Unit,
    onSelectSoilTexture: (SoilTexture) -> Unit,
    onUpdateSoilPh: (Float) -> Unit,
    onUpdateSoilEce: (Float) -> Unit,
    onUpdateWaterEcw: (Float) -> Unit,
    onSelectIrrigationSystem: (IrrigationSystemType) -> Unit,
    onUpdateValveFlowRate: (Float) -> Unit
) {
    var areaInput by remember(uiState.areaFeddans) {
        mutableStateOf(uiState.areaFeddans.toInt().toString())
    }
    var latInput by remember(uiState.latitude) {
        mutableStateOf(com.example.model.formatCoord4(uiState.latitude))
    }
    var lonInput by remember(uiState.longitude) {
        mutableStateOf(com.example.model.formatCoord4(uiState.longitude))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .testTag("farm_soil_studio_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Field & Egyptian Agricultural Zone Presets
        item {
            BentoCard(
                title = "1. بيانات المزرعة والموقع الجغرافي (GPS)",
                subtitle = "اختر النطاق الزراعي المصري لضبط الإحداثيات وخصائص التربة تلقائياً",
                icon = Icons.Default.LocationOn,
                accentColor = EmeraldPrimary
            ) {
                Text(
                    text = "النطاقات الزراعية المصرية المعتمدة:",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate800
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EGYPTIAN_ZONE_PRESETS.forEach { preset ->
                        val isSelected = uiState.selectedZoneId == preset.id
                        Surface(
                            color = if (isSelected) EmeraldPrimary else Slate100,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) EmeraldDark else Slate200
                            ),
                            modifier = Modifier
                                .clickable { onSelectZonePreset(preset) }
                                .testTag("zone_preset_${preset.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = if (isSelected) Color.White else EmeraldPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = preset.nameAr,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) Color.White else Slate900
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.fieldName,
                        onValueChange = onUpdateFieldName,
                        label = { Text("اسم الحقل / المزرعة") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1.4f)
                            .testTag("field_name_input")
                    )
                    OutlinedTextField(
                        value = areaInput,
                        onValueChange = {
                            areaInput = it
                            onUpdateAreaFeddans(it)
                        },
                        label = { Text("المساحة (فدان)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(0.9f)
                            .testTag("area_feddan_input")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = latInput,
                        onValueChange = {
                            latInput = it
                            onUpdateCoordinates(it, lonInput)
                        },
                        label = { Text("دائرة العرض (Lat °N)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("latitude_input")
                    )
                    OutlinedTextField(
                        value = lonInput,
                        onValueChange = {
                            lonInput = it
                            onUpdateCoordinates(latInput, it)
                        },
                        label = { Text("خط الطول (Lon °E)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("longitude_input")
                    )
                }
            }
        }

        // Section 2: Crop & FAO-56 Phenology Engine
        item {
            BentoCard(
                title = "2. المحصول ومحرك مراحل النمو (FAO-56 Phenology)",
                subtitle = "حساب عمر المحصول باليوم (DAS) ومعامل المحصول القياسي (Kc) لكل مرحلة",
                icon = Icons.Default.Eco,
                accentColor = EmeraldPrimary
            ) {
                Text(
                    text = "اختر المحصول المزروع:",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate800
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SUPPORTED_CROPS.forEach { crop ->
                        val selected = uiState.selectedCrop.id == crop.id
                        Surface(
                            color = if (selected) EmeraldDark else Slate100,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) EmeraldLight else Slate200
                            ),
                            modifier = Modifier
                                .clickable { onSelectCrop(crop) }
                                .testTag("crop_chip_${crop.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = crop.nameAr,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) Color.White else Slate900
                                )
                                Text(
                                    text = "${crop.totalCycleDays} يوم",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) EmeraldSoft else Slate500
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Planting Date & DAS Interactive Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "تاريخ بداية الزراعة: ${uiState.plantingDateIso}",
                            style = MaterialTheme.typography.titleSmall,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "عمر المحصول باليوم (DAS): ${uiState.daysAfterSowing} من ${uiState.selectedCrop.totalCycleDays} يوم",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }

                    Surface(
                        color = EmeraldSoft,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary)
                    ) {
                        Text(
                            text = "Kc = ${"%.2f".format(uiState.growthEvaluation.currentBaseKc)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = EmeraldDark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Slider(
                    value = uiState.daysAfterSowing.toFloat(),
                    onValueChange = { onUpdateDaysAfterSowing(it.toInt()) },
                    valueRange = 1f..uiState.selectedCrop.totalCycleDays.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("das_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 4-Stage FAO-56 Growth Timeline Bar
                FaoGrowthTimelineBar(
                    activeStageIndex = uiState.growthEvaluation.stageIndex,
                    stageDays = uiState.selectedCrop.stageDays,
                    stageKc = uiState.selectedCrop.stageKc,
                    currentKc = uiState.growthEvaluation.currentBaseKc
                )
            }
        }

        // Section 3: Interactive Soil pH & Alkalinity Diagnostic Lab
        item {
            BentoCard(
                title = "3. مختبر حموضة وقلوية وملوحة التربة (Soil Chemistry Lab)",
                subtitle = "تشخيص تيسر العناصر السبعة الكبرى والصغرى واحتياج الغسيل الملحي",
                icon = Icons.Default.Science,
                accentColor = SkyAccent
            ) {
                // Soil Texture Selector
                Text(
                    text = "قوام التربة والسعة المائية المتاحة (AWC):",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate800
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoilTexture.entries.forEach { texture ->
                        val selected = uiState.soilTexture == texture
                        Surface(
                            color = if (selected) SkySoft else Slate100,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) SkyAccent else Slate200
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectSoilTexture(texture) }
                                .testTag("soil_texture_${texture.name.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = texture.nameAr,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) SkyAccent else Slate900,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "${texture.availableWaterCapacityMmPerM} مم/م",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate600
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Soil pH Interactive Slider & Rainbow Spectrum Gauge
                val phBadgeColor = when (uiState.soilDiagnostics.phClass) {
                    PhClass.ACIDIC -> AmberWarning
                    PhClass.OPTIMAL_NEUTRAL -> EmeraldPrimary
                    PhClass.MODERATE_ALKALINE -> AmberWarning
                    PhClass.SEVERE_ALKALINE_SODIC -> RoseDanger
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "مؤشر الحموضة والقلوية (Soil pH): ${"%.1f".format(uiState.soilPh)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "النطاق التفاعلي من 4.0 (حامضي) إلى 9.5 (قلوي صودي)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate500
                        )
                    }
                    Surface(
                        color = phBadgeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, phBadgeColor)
                    ) {
                        Text(
                            text = uiState.soilDiagnostics.phClass.labelAr,
                            style = MaterialTheme.typography.labelLarge,
                            color = phBadgeColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Custom Rainbow/Spectrum pH Gauge with Indicator Needle
                SoilPhSpectrumGauge(phValue = uiState.soilPh)

                Slider(
                    value = uiState.soilPh,
                    onValueChange = onUpdateSoilPh,
                    valueRange = 4.0f..9.5f,
                    steps = 54,
                    colors = SliderDefaults.colors(
                        thumbColor = phBadgeColor,
                        activeTrackColor = phBadgeColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("soil_ph_slider")
                )

                // Quick pH Step Buttons for precision
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(
                        "5.2 حامضية" to 5.2f,
                        "6.6 مثالية" to 6.6f,
                        "7.8 قلوية متوسطة" to 7.8f,
                        "8.5 قلوية شديدة" to 8.5f
                    ).forEach { (label, targetPh) ->
                        Surface(
                            color = Slate100,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                            modifier = Modifier.clickable { onUpdateSoilPh(targetPh) }
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate800,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Dynamic Nutrient Availability Bars (N, P, K, Fe, Zn, Mn, Ca)
                Text(
                    text = "مؤشر تيسر العناصر الغذائية (Nutrient Availability Bars):",
                    style = MaterialTheme.typography.titleSmall,
                    color = Slate900,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiState.soilDiagnostics.lockupExplanationAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.soilPh > 7.4f || uiState.soilPh < 5.5f) RoseDanger else Slate600
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.soilDiagnostics.nutrients.forEach { nutrient ->
                        NutrientAvailabilityBarRow(nutrient = nutrient)
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Salinity Inputs (ECe & ECw) + Leaching Requirement
                Text(
                    text = "ملوحة التربة (ECe) وملوحة مياه الري (ECw):",
                    style = MaterialTheme.typography.titleSmall,
                    color = Slate900,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ملوحة التربة ECe: ${"%.1f".format(uiState.soilEce)} dS/m",
                            style = MaterialTheme.typography.labelLarge,
                            color = Slate800
                        )
                        Slider(
                            value = uiState.soilEce,
                            onValueChange = onUpdateSoilEce,
                            valueRange = 0.5f..10.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = AmberWarning,
                                activeTrackColor = AmberWarning
                            ),
                            modifier = Modifier.testTag("soil_ece_slider")
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ملوحة المياه ECw: ${"%.1f".format(uiState.waterEcw)} dS/m",
                            style = MaterialTheme.typography.labelLarge,
                            color = Slate800
                        )
                        Slider(
                            value = uiState.waterEcw,
                            onValueChange = onUpdateWaterEcw,
                            valueRange = 0.3f..6.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = SkyAccent,
                                activeTrackColor = SkyAccent
                            ),
                            modifier = Modifier.testTag("water_ecw_slider")
                        )
                    }
                }

                // Salinity KPI Summary Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SalinityMiniCard(
                        modifier = Modifier.weight(1f),
                        label = "احتياج الغسيل الملحي (LR)",
                        value = "+${uiState.soilDiagnostics.leachingRequirementPercent}%",
                        subtitle = "فوق صافي الاحتياج المائي",
                        accentColor = SkyAccent
                    )
                    SalinityMiniCard(
                        modifier = Modifier.weight(1f),
                        label = "الإنتاجية المتوقعة للمحصول",
                        value = "${uiState.soilDiagnostics.estimatedYieldPotentialPercent}%",
                        subtitle = "حد التحمل: ${uiState.selectedCrop.salinityThresholdEce} dS/m",
                        accentColor = if (uiState.soilDiagnostics.estimatedYieldPotentialPercent >= 85) EmeraldPrimary else RoseDanger
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Corrective Agronomic Prescriptions Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AmberSoft.copy(alpha = 0.6f))
                        .border(1.dp, AmberLight, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = AmberWarning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "توصيات المعالجة الكيميائية والتسميد الذكي:",
                            style = MaterialTheme.typography.labelLarge,
                            color = Slate900
                        )
                    }
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

        // Section 4: Irrigation System Specs
        item {
            BentoCard(
                title = "4. مواصفات شبكة الري وتصرف المحابس",
                subtitle = "تحديد كفاءة نظام الري ومعدل الضخ لحساب ساعات التشغيل الفعلية",
                icon = Icons.Default.SettingsInputComponent,
                accentColor = SkyAccent
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IrrigationSystemType.entries.forEach { system ->
                        val selected = uiState.irrigationSystem == system
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) EmeraldSoft.copy(alpha = 0.55f) else Slate100)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) EmeraldPrimary else Slate200,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectIrrigationSystem(system) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("irrigation_system_${system.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.WaterDrop,
                                    contentDescription = null,
                                    tint = if (selected) EmeraldPrimary else Slate500
                                )
                                Column {
                                    Text(
                                        text = system.nameAr,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Slate900,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = system.badgeAr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slate600
                                    )
                                }
                            }
                            Surface(
                                color = if (selected) EmeraldPrimary else Slate200,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "${system.efficiencyPercent}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) Color.White else Slate800,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "معدل تصرف المحبس لكل فدان:",
                        style = MaterialTheme.typography.labelLarge,
                        color = Slate900
                    )
                    Text(
                        text = "${"%.1f".format(uiState.valveFlowRateM3PerFeddanHr)} م³/ساعة/فدان",
                        style = MaterialTheme.typography.titleMedium,
                        color = SkyAccent,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = uiState.valveFlowRateM3PerFeddanHr,
                    onValueChange = onUpdateValveFlowRate,
                    valueRange = 6.0f..45.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = SkyAccent,
                        activeTrackColor = SkyAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("valve_flow_slider")
                )
            }
        }
    }
}

@Composable
fun BentoCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        Column {
            // Subtle Top Accent Gradient Strip for High-Craft SaaS Identity
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                accentColor,
                                accentColor.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = 0.18f),
                                        accentColor.copy(alpha = 0.08f)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                color = accentColor.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate500
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
private fun FaoGrowthTimelineBar(
    activeStageIndex: Int,
    stageDays: List<Int>,
    stageKc: List<Float>,
    currentKc: Float
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Slate100)
            .border(1.dp, Slate200, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "مخطط مراحل النمو القياسي FAO-56:",
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate900
                )
            }
            Text(
                text = "المرحلة الحالية: ${GROWTH_STAGE_NAMES_AR[activeStageIndex]}",
                style = MaterialTheme.typography.labelMedium,
                color = EmeraldPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GROWTH_STAGE_NAMES_AR.forEachIndexed { index, stageTitle ->
                val isCurrent = index == activeStageIndex
                val isCompleted = index < activeStageIndex
                val bgColor = when {
                    isCurrent -> EmeraldPrimary
                    isCompleted -> EmeraldLight.copy(alpha = 0.25f)
                    else -> Color.White
                }
                val textColor = when {
                    isCurrent -> Color.White
                    isCompleted -> EmeraldDark
                    else -> Slate600
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .border(
                            1.dp,
                            if (isCurrent) EmeraldDark else Slate200,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stageTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    Text(
                        text = "${stageDays[index]} يوم",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) EmeraldSoft else Slate500
                    )
                    Text(
                        text = "Kc ${"%.2f".format(if (isCurrent) currentKc else stageKc[index])}",
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SoilPhSpectrumGauge(phValue: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            val barHeight = 16.dp.toPx()
            val barTop = 10.dp.toPx()

            // Rainbow spectrum from pH 4.0 (acidic orange-red) -> 6.6 (emerald optimal) -> 8.0 (cyan/blue) -> 9.5 (violet/rose alkaline)
            val spectrumBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFFEF4444), // 4.0 Strong Acidic
                    Color(0xFFF59E0B), // 5.5 Moderate Acidic
                    Color(0xFF10B981), // 6.5 Optimal Neutral
                    Color(0xFF059669), // 7.0 Optimal Neutral
                    Color(0xFF0284C7), // 7.8 Moderate Alkaline
                    Color(0xFF7C3AED), // 8.6 Strong Alkaline
                    Color(0xFFE11D48)  // 9.5 Sodic Alkaline
                )
            )

            drawRoundRect(
                brush = spectrumBrush,
                topLeft = Offset(0f, barTop),
                size = Size(size.width, barHeight),
                cornerRadius = CornerRadius(barHeight / 2f, barHeight / 2f)
            )

            // Normalized position (remember RTL canvas X coordinates: let's map 4.0 on right to 9.5 on left or standard 0..1 with clear labels)
            val fraction = ((phValue - 4.0f) / (9.5f - 4.0f)).coerceIn(0f, 1f)
            val xPos = size.width * (1f - fraction) // RTL: 4.0 on right, 9.5 on left

            // Draw pointer triangle and circle
            val path = Path().apply {
                moveTo(xPos, barTop - 2.dp.toPx())
                lineTo(xPos - 6.dp.toPx(), 0f)
                lineTo(xPos + 6.dp.toPx(), 0f)
                close()
            }
            drawPath(path = path, color = Slate900)

            drawCircle(
                color = Color.White,
                radius = 9.dp.toPx(),
                center = Offset(xPos, barTop + barHeight / 2f)
            )
            drawCircle(
                color = Slate900,
                radius = 6.dp.toPx(),
                center = Offset(xPos, barTop + barHeight / 2f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("4.0 حامضية", style = MaterialTheme.typography.labelSmall, color = AmberWarning)
            Text("6.0 - 7.2 متعادلة مثالية", style = MaterialTheme.typography.labelSmall, color = EmeraldPrimary, fontWeight = FontWeight.Bold)
            Text("7.3 - 8.0 قلوية", style = MaterialTheme.typography.labelSmall, color = SkyAccent)
            Text(">8.0 صودية", style = MaterialTheme.typography.labelSmall, color = RoseDanger)
        }
    }
}

@Composable
private fun NutrientAvailabilityBarRow(nutrient: NutrientAvailability) {
    val animatedFraction by animateFloatAsState(
        targetValue = (nutrient.availabilityPercent / 100f).coerceIn(0.08f, 1f),
        animationSpec = spring(),
        label = "nutrientBar"
    )
    val barColor by animateColorAsState(
        targetValue = when {
            nutrient.availabilityPercent >= 78 -> EmeraldPrimary
            nutrient.availabilityPercent >= 60 -> AmberWarning
            else -> RoseDanger
        },
        label = "nutrientColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Slate100)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
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
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(barColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = nutrient.symbol,
                        style = MaterialTheme.typography.labelLarge,
                        color = barColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = nutrient.nameAr,
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate900
                )
            }
            Text(
                text = nutrient.statusTextAr,
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Slate200)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun SalinityMiniCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subtitle: String,
    accentColor: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate600
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = accentColor,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = Slate500
        )
    }
}
