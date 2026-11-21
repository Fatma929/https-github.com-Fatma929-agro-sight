package com.example.model

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class AppScreen(val titleAr: String, val subtitleAr: String) {
    HOME(
        "الرئيسية - ملخص المزرعة الذكي",
        "نظرة شاملة على الطقس، المساحة، صحة الحقول، والنشاط اليومي"
    ),
    FARM_SOIL_STUDIO(
        "بيانات الأرض والمحصول وكيمياء التربة",
        "إعداد المزرعة ومختبر تشخيص الحموضة والملوحة وتيسر العناصر"
    ),
    METEO_ANALYTICS(
        "تحليل الأرصاد الجوية والبخر-نتح الحي",
        "نموذج FAO-56 Penman-Monteith الحي من محطات Open-Meteo"
    ),
    DISEASE_RADAR(
        "رادار أمراض المحاصيل بالأقمار الصناعية (Sentinel-2)",
        "رصد طيفي 100% بدون كاميرات أو حساسات (NDRE • PSRI • NDMI • NDVI)"
    ),
    SATELLITE_NDVI_MAP(
        "خريطة القطاعات والأقمار الصناعية (NDVI)",
        "تحليل Sentinel-2 للغطاء النباتي وإجهاد الرطوبة وجدولة المحابس"
    ),
    IRRIGATION_SCHEDULE(
        "جدول تشغيل المحابس الأسبوعي ومحاكي السيناريوهات",
        "جدولة تنفيذية لـ 7 أيام مع محاكي (What-If) وتصدير تقرير PDF للطباعة"
    ),
    WHATSAPP_BOT(
        "بوت واتساب الاستباقي والإنذار المبكر",
        "روابط واتساب المباشرة (wa.me) وتوثيق السجل ومحادثة Gemini AI"
    ),
    CHATBOT(
        "شات المستشار الزراعي الذكي (Gemini Agronomy Chatbot)",
        "واجهة محادثة ذكية مربوطة ببيانات حقلك، أقمار Sentinel-2، نماذج FAO-56، وخرائط Google Maps"
    ),
    AI_DIGITAL_TWIN_HUB(
        "المساعد الصوتي والتوأم الرقمي 3D والسحابة",
        "محادثة Gemini الصوتية وبحث Google وخرائط Maps ومشهد Three.js 3D وقاعدة بيانات Firestore & SQL"
    ),
    ADMIN_PORTAL(
        "بوابة الإدارة والتحكم الشامل (Admin Portal & RBAC)",
        "إدارة المستخدمين والصلاحيات (RBAC)، مراقبة جميع المزارع والسجلات، وتحليلات النشاط"
    )
}

enum class SatelliteLayer(val titleAr: String, val badgeAr: String) {
    NDVI("مؤشر الغطاء النباتي (NDVI)", "كثافة الكلوروفيل والنمو الخضري"),
    NDWI("إجهاد الرطوبة المائي (NDWI)", "محتوى الماء في الأوراق والتربة"),
    SALINITY("خريطة ملوحة التربة (ECe)", "توزيع الأملاح بالقطاع (dS/m)"),
    VALVE_HOURS("ساعات تشغيل المحابس", "معدل الضخ اليومي المطلوب")
}

enum class IrrigationDayStatus(val labelAr: String) {
    COMPLETED("تم الري"),
    SCHEDULED("مجدول"),
    POSTPONED_WIND("تأجيل بسبب الرياح")
}

data class WeeklyIrrigationDayItem(
    val dayIndex: Int,
    val dateIso: String,
    val dayLabelAr: String,
    val et0MmDay: Float,
    val etcMmDay: Float,
    val tempMaxC: Float,
    val windSpeedKmh: Float,
    val netM3PerFeddan: Float,
    val grossM3PerFeddan: Float,
    val totalFarmM3: Float,
    val valveRunHours: Float,
    val fertigationNoteAr: String,
    val status: IrrigationDayStatus
)

data class WhatIfSimulatorInput(
    val simEfficiencyPercent: Int = 90,
    val simSoilPh: Float = 6.8f,
    val simTempDeltaC: Float = 0.0f // -3.0°C to +8.0°C (e.g. +5°C heatwave)
)

data class WhatIfSimulationResult(
    val baselineGrossM3PerFeddan: Float,
    val simulatedGrossM3PerFeddan: Float,
    val deltaM3PerFeddan: Float,
    val baselineValveHours: Float,
    val simulatedValveHours: Float,
    val deltaValveMinutes: Int,
    val baselineWeeklyFarmM3: Float,
    val simulatedWeeklyFarmM3: Float,
    val baselineNutrientIndexPct: Int,
    val simulatedNutrientIndexPct: Int,
    val simulatedEt0MmDay: Float,
    val simulatedEtcMmDay: Float,
    val summaryRecommendationAr: String
)

fun evaluateWhatIfSimulation(
    baseTodayEt0: Float,
    baseKc: Float,
    areaFeddans: Float,
    baseEfficiencyPct: Int,
    baseSoilPh: Float,
    soilEce: Float,
    waterEcw: Float,
    crop: CropSpec,
    soilTexture: SoilTexture,
    valveFlowRateM3PerFeddanHr: Float,
    simInput: WhatIfSimulatorInput
): WhatIfSimulationResult {
    val baseDiag = analyzeSoilChemistry(baseSoilPh, soilEce, waterEcw, crop, soilTexture)
    val simDiag = analyzeSoilChemistry(simInput.simSoilPh, soilEce, waterEcw, crop, soilTexture)

    val baseLrRatio = (baseDiag.leachingRequirementPercent / 100f).coerceIn(0f, 0.45f)
    // Optimal pH (6.2..7.0) improves root osmotic uptake & reduces extra flushing overhead slightly
    val phRootBonus = if (simInput.simSoilPh in 6.0f..7.2f && baseSoilPh > 7.4f) 0.95f else 1.0f
    val simLrRatio = ((simDiag.leachingRequirementPercent / 100f) * phRootBonus).coerceIn(0f, 0.45f)

    val baseEtcMm = max(1.0f, baseTodayEt0 * baseKc)
    val baseNetM3 = baseEtcMm * 4.2f
    val baseEff = (baseEfficiencyPct / 100f).coerceIn(0.50f, 0.98f)
    val baseGrossM3 = baseNetM3 / max(0.55f, baseEff * (1f - baseLrRatio))
    val baseHours = baseGrossM3 / max(4f, valveFlowRateM3PerFeddanHr)

    // Temperature wave effect on ET0: each +1°C increases evaporative demand by ~4.8% in arid zones
    val tempMultiplier = (1.0f + simInput.simTempDeltaC * 0.048f).coerceIn(0.75f, 1.50f)
    // High heatwave also raises Kc slightly due to transpiration stress
    val simKc = (baseKc + (simInput.simTempDeltaC * 0.012f)).coerceIn(0.25f, 1.38f)
    val simEt0 = max(1.5f, baseTodayEt0 * tempMultiplier)
    val simEtcMm = max(1.0f, simEt0 * simKc)
    val simNetM3 = simEtcMm * 4.2f

    val simEff = (simInput.simEfficiencyPercent / 100f).coerceIn(0.50f, 0.98f)
    val simGrossM3 = (simNetM3 / max(0.55f, simEff * (1f - simLrRatio))) * phRootBonus
    val simHours = simGrossM3 / max(4f, valveFlowRateM3PerFeddanHr)

    val deltaM3 = simGrossM3 - baseGrossM3
    val deltaMinutes = ((simHours - baseHours) * 60f).roundToInt()

    val baseNutrAvg = baseDiag.nutrients.map { it.availabilityPercent }.average().roundToInt()
    val simNutrAvg = simDiag.nutrients.map { it.availabilityPercent }.average().roundToInt()

    val summary = buildString {
        if (simInput.simTempDeltaC >= 3.0f) {
            append("موجة حارة (+${formatDecimal1(simInput.simTempDeltaC)}°م) ترفع البخر-نتح إلى ${formatDecimal2(simEt0)} مم/يوم؛ ")
        }
        if (simInput.simEfficiencyPercent > baseEfficiencyPct) {
            append("رفع كفاءة الري إلى ${simInput.simEfficiencyPercent}% يوفر المياه؛ ")
        }
        if (simInput.simSoilPh < baseSoilPh && simInput.simSoilPh <= 7.2f) {
            append("خفض pH التربة إلى ${formatDecimal1(simInput.simSoilPh)} يرفع كفاءة امتصاص العناصر من $baseNutrAvg% إلى $simNutrAvg%.")
        } else if (deltaM3 > 0.5f) {
            append("يلزم زيادة الضخ بمقدار +${formatDecimal1(deltaM3)} م³/فدان (${if (deltaMinutes >= 0) "+$deltaMinutes" else "$deltaMinutes"} دقيقة للمحبس) لتفادي الإجهاد المائي.")
        } else if (deltaM3 < -0.5f) {
            append("السيناريو يحقق وفراً مائياً قدره ${formatDecimal1(-deltaM3)} م³/فدان/يوم ويقلل زمن التشغيل ${-deltaMinutes} دقيقة.")
        } else {
            append("حرك مؤشرات المحاكاة لاختبار أثر الموجات الحارة (+5°C) أو تعديل كفاءة الشبكة وحموضة التربة.")
        }
    }

    return WhatIfSimulationResult(
        baselineGrossM3PerFeddan = baseGrossM3,
        simulatedGrossM3PerFeddan = simGrossM3,
        deltaM3PerFeddan = deltaM3,
        baselineValveHours = baseHours,
        simulatedValveHours = simHours,
        deltaValveMinutes = deltaMinutes,
        baselineWeeklyFarmM3 = baseGrossM3 * areaFeddans * 7f,
        simulatedWeeklyFarmM3 = simGrossM3 * areaFeddans * 7f,
        baselineNutrientIndexPct = baseNutrAvg,
        simulatedNutrientIndexPct = simNutrAvg,
        simulatedEt0MmDay = simEt0,
        simulatedEtcMmDay = simEtcMm,
        summaryRecommendationAr = summary
    )
}

fun buildWeeklyIrrigationSchedule(
    forecastDays: List<DailyMeteoRecord>,
    areaFeddans: Float,
    irrigationSystem: IrrigationSystemType,
    leachingRequirementPct: Int,
    soilPh: Float,
    valveFlowRateM3PerFeddanHr: Float,
    statusOverrides: Map<Int, IrrigationDayStatus>
): List<WeeklyIrrigationDayItem> {
    val effRatio = (irrigationSystem.efficiencyPercent / 100f).coerceIn(0.60f, 0.98f)
    val lrRatio = (leachingRequirementPct / 100f).coerceIn(0f, 0.45f)

    val fertigationNotes = listOf(
        if (soilPh > 7.4f) "حقن حامض فوسفوريك + نترات كالسيوم" else "دفعة نيتروجين وفوسفور متوازنة",
        "إضافة بوتاسيوم سائل + أحماض أمينية",
        if (soilPh > 7.4f) "حديد مخلبي EDDHA 6% + زنك ومنجنيز" else "عناصر صغرى مخلبية + ماغنسيوم",
        "رية غسيل أملاح + هيوميك وفولفيك أسيد",
        "سلفات بوتاسيوم + عالي الفوسفور",
        "معالج ملوحة (كالسيوم كربوكسيلي)",
        "ري صافي بدون تسميد (غسيل خطوط)"
    )

    return forecastDays.mapIndexed { idx, rec ->
        val netM3 = rec.waterDeficitM3PerFeddan
        val grossM3 = netM3 / max(0.60f, effRatio * (1f - lrRatio))
        val totalFarmM3 = grossM3 * areaFeddans
        val runHours = grossM3 / max(4f, valveFlowRateM3PerFeddanHr)

        val defaultStatus = when {
            idx == 0 -> IrrigationDayStatus.COMPLETED
            rec.windSpeedKmh >= 25.0f && irrigationSystem != IrrigationSystemType.DRIP -> IrrigationDayStatus.POSTPONED_WIND
            rec.windSpeedKmh >= 26.5f -> IrrigationDayStatus.POSTPONED_WIND
            else -> IrrigationDayStatus.SCHEDULED
        }
        val finalStatus = statusOverrides[idx] ?: defaultStatus

        WeeklyIrrigationDayItem(
            dayIndex = idx,
            dateIso = rec.dateIso,
            dayLabelAr = rec.dayLabelAr,
            et0MmDay = rec.et0MmDay,
            etcMmDay = rec.etcMmDay,
            tempMaxC = rec.tempMaxC,
            windSpeedKmh = rec.windSpeedKmh,
            netM3PerFeddan = netM3,
            grossM3PerFeddan = grossM3,
            totalFarmM3 = totalFarmM3,
            valveRunHours = runHours,
            fertigationNoteAr = fertigationNotes[idx % fertigationNotes.size],
            status = finalStatus
        )
    }
}

fun formatDecimal1(value: Float): String = String.format(Locale.US, "%.1f", value)
fun formatDecimal2(value: Float): String = String.format(Locale.US, "%.2f", value)
fun formatCoord4(value: Double): String = String.format(Locale.US, "%.4f", value)

fun formatHoursMinutesClean(hours: Float): String {
    val totalMinutes = (hours * 60f).roundToInt().coerceAtLeast(10)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "$h ساعة و $m دقيقة"
        h > 0 -> "$h ساعة"
        else -> "$m دقيقة"
    }
}

fun formatHoursMinutesCompact(hours: Float): String {
    val totalMinutes = (hours * 60f).roundToInt().coerceAtLeast(10)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}س ${m}د" else "${m}د"
}

data class EgyptianZonePreset(
    val id: String,
    val nameAr: String,
    val regionSubtitleAr: String,
    val latitude: Double,
    val longitude: Double,
    val defaultSoilTexture: SoilTexture,
    val defaultPh: Float,
    val defaultEce: Float,
    val defaultEcw: Float
)

val EGYPTIAN_ZONE_PRESETS = listOf(
    EgyptianZonePreset(
        id = "wadi_natrun",
        nameAr = "وادي النطرون",
        regionSubtitleAr = "الظهير الصحراوي الغربي - البحيرة",
        latitude = 30.3833,
        longitude = 30.3500,
        defaultSoilTexture = SoilTexture.SANDY,
        defaultPh = 7.8f,
        defaultEce = 2.4f,
        defaultEcw = 1.4f
    ),
    EgyptianZonePreset(
        id = "new_salhia",
        nameAr = "الصالحية الجديدة",
        regionSubtitleAr = "شرق الدلتا - الشرقية والإسماعيلية",
        latitude = 30.6300,
        longitude = 31.9600,
        defaultSoilTexture = SoilTexture.LOAMY,
        defaultPh = 7.4f,
        defaultEce = 1.6f,
        defaultEcw = 1.0f
    ),
    EgyptianZonePreset(
        id = "farafra",
        nameAr = "الفرافرة",
        regionSubtitleAr = "الوادي الجديد - مشروع الـ 1.5 مليون فدان",
        latitude = 27.0567,
        longitude = 27.9703,
        defaultSoilTexture = SoilTexture.SANDY,
        defaultPh = 8.1f,
        defaultEce = 3.1f,
        defaultEcw = 1.7f
    ),
    EgyptianZonePreset(
        id = "delta",
        nameAr = "الدلتا",
        regionSubtitleAr = "وسط الدلتا - كفر الشيخ والغربية",
        latitude = 31.1107,
        longitude = 30.9388,
        defaultSoilTexture = SoilTexture.CLAY,
        defaultPh = 7.2f,
        defaultEce = 1.8f,
        defaultEcw = 0.8f
    ),
    EgyptianZonePreset(
        id = "toshka",
        nameAr = "توشكى",
        regionSubtitleAr = "جنوب الوادي - أسوان",
        latitude = 22.6167,
        longitude = 31.2833,
        defaultSoilTexture = SoilTexture.SANDY,
        defaultPh = 7.9f,
        defaultEce = 2.1f,
        defaultEcw = 0.9f
    ),
    EgyptianZonePreset(
        id = "moghra",
        nameAr = "المغرة",
        regionSubtitleAr = "جنوب العلمين - مطروح",
        latitude = 30.2500,
        longitude = 28.9167,
        defaultSoilTexture = SoilTexture.SANDY,
        defaultPh = 8.3f,
        defaultEce = 4.2f,
        defaultEcw = 2.9f
    )
)

enum class SoilTexture(
    val nameAr: String,
    val descriptionAr: String,
    val availableWaterCapacityMmPerM: Int,
    val infiltrationRateMmPerHr: Int,
    val leachingEfficiencyFactor: Float
) {
    SANDY(
        nameAr = "رملية (Sandy)",
        descriptionAr = "نفاذية عالية، احتفاظ منخفض بالرطوبة، تحتاج ري متقارب",
        availableWaterCapacityMmPerM = 85,
        infiltrationRateMmPerHr = 35,
        leachingEfficiencyFactor = 0.90f
    ),
    LOAMY(
        nameAr = "طميية صفراء (Loamy)",
        descriptionAr = "توازن مثالي بين التهوية والاحتفاظ بالماء والعناصر",
        availableWaterCapacityMmPerM = 160,
        infiltrationRateMmPerHr = 18,
        leachingEfficiencyFactor = 0.80f
    ),
    CLAY(
        nameAr = "طينية (Clay)",
        descriptionAr = "سعة حفظ رطوبة عالية، صرف بطيء، حساسة للتغدق والملوحة",
        availableWaterCapacityMmPerM = 210,
        infiltrationRateMmPerHr = 7,
        leachingEfficiencyFactor = 0.70f
    )
}

enum class IrrigationSystemType(
    val nameAr: String,
    val efficiencyPercent: Int,
    val defaultFlowRateM3PerFeddanHr: Float,
    val badgeAr: String
) {
    DRIP(
        nameAr = "تنقيط (Drip)",
        efficiencyPercent = 90,
        defaultFlowRateM3PerFeddanHr = 16.0f,
        badgeAr = "كفاءة 90% • الأفضل لترشيد المياه"
    ),
    CENTER_PIVOT(
        nameAr = "بيفوت محوري (Center Pivot)",
        efficiencyPercent = 82,
        defaultFlowRateM3PerFeddanHr = 24.0f,
        badgeAr = "كفاءة 82% • المساحات الواسعة"
    ),
    SPRINKLER(
        nameAr = "رشاشات ثابتة (Sprinkler)",
        efficiencyPercent = 75,
        defaultFlowRateM3PerFeddanHr = 20.0f,
        badgeAr = "كفاءة 75% • يتأثر بسرعة الرياح"
    )
}

data class CropSpec(
    val id: String,
    val nameAr: String,
    val categoryAr: String,
    val stageDays: List<Int>,
    val stageKc: List<Float>,
    val rootDepthM: Float,
    val depletionFractionP: Float,
    val salinityThresholdEce: Float,
    val salinitySlopePercentPerDs: Float
) {
    val totalCycleDays: Int get() = stageDays.sum()
}

val SUPPORTED_CROPS = listOf(
    CropSpec(
        id = "tomato",
        nameAr = "طماطم",
        categoryAr = "خضر ثمرية",
        stageDays = listOf(25, 35, 45, 30),
        stageKc = listOf(0.60f, 0.92f, 1.15f, 0.80f),
        rootDepthM = 0.8f,
        depletionFractionP = 0.40f,
        salinityThresholdEce = 2.5f,
        salinitySlopePercentPerDs = 9.9f
    ),
    CropSpec(
        id = "potato",
        nameAr = "بطاطس",
        categoryAr = "محاصيل درنية",
        stageDays = listOf(25, 30, 40, 25),
        stageKc = listOf(0.50f, 0.85f, 1.15f, 0.75f),
        rootDepthM = 0.6f,
        depletionFractionP = 0.35f,
        salinityThresholdEce = 1.7f,
        salinitySlopePercentPerDs = 12.0f
    ),
    CropSpec(
        id = "wheat",
        nameAr = "قمح",
        categoryAr = "محاصيل حبوب استراتيجية",
        stageDays = listOf(25, 35, 55, 35),
        stageKc = listOf(0.40f, 0.80f, 1.15f, 0.35f),
        rootDepthM = 1.1f,
        depletionFractionP = 0.55f,
        salinityThresholdEce = 6.0f,
        salinitySlopePercentPerDs = 7.1f
    ),
    CropSpec(
        id = "corn",
        nameAr = "ذرة",
        categoryAr = "محاصيل حبوب وأعلاف",
        stageDays = listOf(20, 35, 40, 25),
        stageKc = listOf(0.35f, 0.82f, 1.20f, 0.60f),
        rootDepthM = 1.0f,
        depletionFractionP = 0.50f,
        salinityThresholdEce = 1.8f,
        salinitySlopePercentPerDs = 12.0f
    ),
    CropSpec(
        id = "citrus",
        nameAr = "موالح",
        categoryAr = "أشجار فاكهة مستديمة",
        stageDays = listOf(60, 90, 120, 95),
        stageKc = listOf(0.65f, 0.72f, 0.80f, 0.70f),
        rootDepthM = 1.3f,
        depletionFractionP = 0.50f,
        salinityThresholdEce = 1.7f,
        salinitySlopePercentPerDs = 16.0f
    ),
    CropSpec(
        id = "date_palm",
        nameAr = "نخيل",
        categoryAr = "أشجار تمور صحراوية",
        stageDays = listOf(70, 80, 130, 85),
        stageKc = listOf(0.80f, 0.90f, 1.00f, 0.85f),
        rootDepthM = 1.8f,
        depletionFractionP = 0.55f,
        salinityThresholdEce = 4.0f,
        salinitySlopePercentPerDs = 3.6f
    ),
    CropSpec(
        id = "strawberry",
        nameAr = "فراولة",
        categoryAr = "تصدير عالي القيمة",
        stageDays = listOf(20, 30, 50, 25),
        stageKc = listOf(0.40f, 0.68f, 0.85f, 0.75f),
        rootDepthM = 0.35f,
        depletionFractionP = 0.25f,
        salinityThresholdEce = 1.0f,
        salinitySlopePercentPerDs = 33.0f
    ),
    CropSpec(
        id = "sugar_beet",
        nameAr = "بنجر سكر",
        categoryAr = "محاصيل سكرية متحملة",
        stageDays = listOf(30, 45, 65, 40),
        stageKc = listOf(0.35f, 0.80f, 1.20f, 0.70f),
        rootDepthM = 1.1f,
        depletionFractionP = 0.55f,
        salinityThresholdEce = 7.0f,
        salinitySlopePercentPerDs = 5.9f
    )
)

data class GrowthStageEvaluation(
    val stageIndex: Int,
    val stageNameAr: String,
    val stageProgressRatio: Float,
    val overallProgressRatio: Float,
    val currentBaseKc: Float,
    val daysInCurrentStage: Int,
    val totalStageDays: Int
)

val GROWTH_STAGE_NAMES_AR = listOf(
    "المرحلة الابتدائية",
    "النمو الخضري",
    "التزهير والعقد",
    "النضج والحصاد"
)

fun evaluateCropGrowthStage(crop: CropSpec, daysAfterSowing: Int): GrowthStageEvaluation {
    val clampedDas = daysAfterSowing.coerceIn(1, crop.totalCycleDays)
    var accumulated = 0
    for (i in 0 until 4) {
        val stageLen = crop.stageDays[i]
        if (clampedDas <= accumulated + stageLen || i == 3) {
            val daysInStage = (clampedDas - accumulated).coerceIn(1, stageLen)
            val stageProgress = daysInStage.toFloat() / stageLen.toFloat()
            val kc = when (i) {
                0 -> crop.stageKc[0]
                1 -> crop.stageKc[0] + (crop.stageKc[2] - crop.stageKc[0]) * stageProgress
                2 -> crop.stageKc[2]
                else -> crop.stageKc[2] + (crop.stageKc[3] - crop.stageKc[2]) * stageProgress
            }
            return GrowthStageEvaluation(
                stageIndex = i,
                stageNameAr = GROWTH_STAGE_NAMES_AR[i],
                stageProgressRatio = stageProgress,
                overallProgressRatio = clampedDas.toFloat() / crop.totalCycleDays.toFloat(),
                currentBaseKc = kc,
                daysInCurrentStage = daysInStage,
                totalStageDays = stageLen
            )
        }
        accumulated += stageLen
    }
    return GrowthStageEvaluation(
        stageIndex = 2,
        stageNameAr = GROWTH_STAGE_NAMES_AR[2],
        stageProgressRatio = 0.5f,
        overallProgressRatio = 0.5f,
        currentBaseKc = crop.stageKc[2],
        daysInCurrentStage = 20,
        totalStageDays = 40
    )
}

enum class PhClass(val labelAr: String, val summaryAr: String) {
    ACIDIC(
        "حامضية (<6.0)",
        "تثبيط الفوسفور والكالسيوم والماغنسيوم مع زيادة ذوبان الألومنيوم والمنجنيز"
    ),
    OPTIMAL_NEUTRAL(
        "متعادلة مثالية (6.0 - 7.2)",
        "أعلى تيسر حيوي لجميع العناصر الكبرى والصغرى لمنطقة الجذور"
    ),
    MODERATE_ALKALINE(
        "قلوية متوسطة (7.3 - 8.0)",
        "بداية ترسب الفوسفور وتراجع تيسر الحديد والزنك والمنجنيز؛ يُنصح بحقن الأحماض والعناصر المخلبية"
    ),
    SEVERE_ALKALINE_SODIC(
        "قلوية شديدة / صودية (>8.0)",
        "إغلاق حاد للفوسفور والعناصر الصغرى وتدهور بناء التربة؛ يلزم الكبريت الزراعي والجبس وحامض الفوسفوريك"
    )
}

data class NutrientAvailability(
    val symbol: String,
    val nameAr: String,
    val availabilityPercent: Int,
    val statusTextAr: String,
    val isLockedUp: Boolean
)

data class SoilChemistryDiagnostics(
    val phClass: PhClass,
    val nutrients: List<NutrientAvailability>,
    val leachingRequirementPercent: Int,
    val estimatedYieldPotentialPercent: Int,
    val lockupExplanationAr: String,
    val correctivePrescriptionAr: List<String>
)

fun analyzeSoilChemistry(
    ph: Float,
    ece: Float,
    ecw: Float,
    crop: CropSpec,
    soilTexture: SoilTexture
): SoilChemistryDiagnostics {
    val phClass = when {
        ph < 6.0f -> PhClass.ACIDIC
        ph <= 7.2f -> PhClass.OPTIMAL_NEUTRAL
        ph <= 8.0f -> PhClass.MODERATE_ALKALINE
        else -> PhClass.SEVERE_ALKALINE_SODIC
    }

    val nAvail = when {
        ph in 6.0f..7.8f -> 95
        ph < 6.0f -> (95 - (6.0f - ph) * 28f).roundToInt()
        else -> (95 - (ph - 7.8f) * 26f).roundToInt()
    }.coerceIn(22, 98)

    val pAvail = when {
        ph in 6.2f..7.2f -> 92
        ph < 6.2f -> (92 - (6.2f - ph) * 38f).roundToInt()
        else -> (92 - (ph - 7.2f) * 42f).roundToInt()
    }.coerceIn(15, 95)

    val kAvail = when {
        ph in 6.0f..8.2f -> 94
        ph < 6.0f -> (94 - (6.0f - ph) * 32f).roundToInt()
        else -> (94 - (ph - 8.2f) * 25f).roundToInt()
    }.coerceIn(28, 96)

    val feAvail = when {
        ph <= 6.5f -> 96
        else -> (96 - (ph - 6.5f) * 34f).roundToInt()
    }.coerceIn(12, 98)

    val znAvail = when {
        ph in 5.5f..6.8f -> 93
        ph < 5.5f -> (93 - (5.5f - ph) * 18f).roundToInt()
        else -> (93 - (ph - 6.8f) * 33f).roundToInt()
    }.coerceIn(14, 96)

    val mnAvail = when {
        ph in 5.2f..6.8f -> 94
        ph < 5.2f -> 88
        else -> (94 - (ph - 6.8f) * 35f).roundToInt()
    }.coerceIn(14, 96)

    val caAvail = when {
        ph in 6.5f..8.3f -> 94
        ph < 6.5f -> (94 - (6.5f - ph) * 34f).roundToInt()
        else -> (94 - (ph - 8.3f) * 24f).roundToInt()
    }.coerceIn(20, 96)

    fun buildNutrient(symbol: String, nameAr: String, pct: Int, lockReasonAr: String): NutrientAvailability {
        val locked = pct < 62
        val status = when {
            pct >= 80 -> "تيسر ممتاز ($pct%)"
            pct >= 62 -> "تيسر متوسط ($pct%)"
            else -> "مقيد بشدة ($pct%) - $lockReasonAr"
        }
        return NutrientAvailability(
            symbol = symbol,
            nameAr = nameAr,
            availabilityPercent = pct,
            statusTextAr = status,
            isLockedUp = locked
        )
    }

    val nutrients = listOf(
        buildNutrient("N", "النيتروجين", nAvail, if (ph > 7.8f) "تطاير الأمونيا" else "ضعف النشاط البكتيري"),
        buildNutrient("P", "الفوسفور", pAvail, if (ph > 7.2f) "ترسب فوسفات ثلاثي الكالسيوم" else "تثبيت مع الحديد والألومنيوم"),
        buildNutrient("K", "البوتاسيوم", kAvail, if (ph > 8.0f) "تزاحم الصوديوم والكالسيوم" else "غسيل من معقد التبادل"),
        buildNutrient("Fe", "الحديد", feAvail, "تحول إلى هيدروكسيد حديديك غير ذائب"),
        buildNutrient("Zn", "الزنك", znAvail, "تكوين زنكات كالسيوم وكربونات غير ميسرة"),
        buildNutrient("Mn", "المنجنيز", mnAvail, "أكسدة المنجنيز الثنائي إلى صوره غير الممتصة"),
        buildNutrient("Ca", "الكالسيوم", caAvail, if (ph < 6.0f) "استنزاف القواعد الأرضية" else "ترسب كربونات الكالسيوم")
    )

    val denom = max(1.2f, 5f * crop.salinityThresholdEce - ecw)
    val rawLr = (ecw / denom) / soilTexture.leachingEfficiencyFactor
    val lrPercent = (rawLr * 100f).roundToInt().coerceIn(5, 45)

    val excessSalinity = max(0f, ece - crop.salinityThresholdEce)
    val yieldLoss = excessSalinity * crop.salinitySlopePercentPerDs
    val yieldPotential = (100f - yieldLoss).roundToInt().coerceIn(25, 100)

    val explanation = when {
        ph > 7.4f -> "عند ارتفاع pH التربة إلى ${formatDecimal1(ph)} (قلوية)، يتفاعل الفوسفور الذائب مع الكالسيوم الحر مكوناً فوسفات ثلاثي الكالسيوم غير القابل للامتصاص، كما تترسب عناصر الحديد والزنك والمنجنيز في صورة هيدروكسيدات وكربونات مما يسبب اصفرار الأوراق الحديثة."
        ph < 5.5f -> "عند انخفاض pH التربة إلى ${formatDecimal1(ph)} (حامضية)، ينخفض تيسر الكالسيوم والماغنسيوم والنيتروجين، ويتحد الفوسفور مع الحديد والألومنيوم الحر في صورة معقدات غير ذائبة."
        else -> "نطاق pH الحالي (${formatDecimal1(ph)}) مثالي لمنطقة الجذور ويسمح بامتصاص متوازن للنيتروجين والفوسفور والبوتاسيوم والعناصر الصغرى بأعلى كفاءة."
    }

    val prescriptions = buildList {
        if (ph > 7.4f) {
            add("حقن حامض الفوسفوريك (85%) أو حامض الكبريتيك الزراعي مع مياه الري لخفض pH منطقة الجذور إلى 6.5.")
            add("إضافة الحديد المخلبي في صورة EDDHA 6% (ثابت حتى pH 9.0) والزنك والمنجنيز رشاً ورقياً أو مخلبياً.")
        }
        if (ph > 8.0f) {
            add("إضافة الكبريت الزراعي الميكروني بمعدل 150-250 كجم/فدان والجبس الزراعي لإزاحة الصوديوم.")
        }
        if (ph < 5.8f) {
            add("إضافة الحجر الجيري الزراعي (كربونات الكالسيوم) ونترات الكالسيوم لرفع رقم الحموضة.")
        }
        if (ece > crop.salinityThresholdEce) {
            add("تطبيق رية غسيل ملوحة بنسبة إضافية ($lrPercent%) مع حقن معالج ملوحة (كالسيوم كربوكسيلي + أحماض هيوميك وفولفيك).")
        }
        if (isEmpty()) {
            add("الحفاظ على برنامج التسميد المتوازن مع إضافة هيوميك أسيد دورياً لتعزيز السعة التبادلية الكاتيونية.")
        }
    }

    return SoilChemistryDiagnostics(
        phClass = phClass,
        nutrients = nutrients,
        leachingRequirementPercent = lrPercent,
        estimatedYieldPotentialPercent = yieldPotential,
        lockupExplanationAr = explanation,
        correctivePrescriptionAr = prescriptions
    )
}

data class DailyMeteoRecord(
    val dateIso: String,
    val dayLabelAr: String,
    val tempMaxC: Float,
    val tempMinC: Float,
    val relativeHumidityPct: Int,
    val windSpeedKmh: Float,
    val solarRadiationMjM2: Float,
    val precipitationMm: Float,
    val et0MmDay: Float,
    val adjustedKc: Float,
    val etcMmDay: Float,
    val waterDeficitM3PerFeddan: Float,
    val cumulativeDeficitM3PerFeddan: Float
)

data class AgroClimaticAlert(
    val titleAr: String,
    val detailAr: String,
    val severity: AlertSeverity
)

enum class AlertSeverity {
    CRITICAL_HEAT,
    HIGH_WIND,
    HIGH_EVAPORATION,
    OPTIMAL_CLIMATE
}

data class ValveSectorTelemetry(
    val valveNumber: Int,
    val nameAr: String,
    val areaFeddans: Float,
    val ndvi: Float,
    val ndwi: Float,
    val sectorEce: Float,
    val satelliteKc: Float,
    val soilMoistureDepletionPct: Int,
    val netEtcMmDay: Float,
    val grossWaterM3PerFeddan: Float,
    val totalDailyWaterM3: Float,
    val operatingHours: Float,
    val statusBadgeAr: String,
    val statusLevel: SectorStatusLevel,
    val recommendationAr: String
)

enum class SectorStatusLevel {
    OPTIMAL,
    MODERATE_STRESS,
    HIGH_STRESS
}

fun buildSatelliteValveSectors(
    totalAreaFeddans: Float,
    baseKc: Float,
    todayEt0: Float,
    soilEce: Float,
    leachingRequirementPct: Int,
    irrigationSystem: IrrigationSystemType,
    valveFlowRateM3PerFeddanHr: Float
): List<ValveSectorTelemetry> {
    val sectorArea = (totalAreaFeddans / 6f).coerceAtLeast(1f)
    val effRatio = irrigationSystem.efficiencyPercent / 100f
    val lrRatio = (leachingRequirementPct / 100f).coerceIn(0f, 0.45f)

    val sectorModifiers = listOf(
        Triple(1.04f, 0.08f, -0.15f),
        Triple(0.98f, 0.03f, 0.0f),
        Triple(0.82f, -0.11f, 0.45f),
        Triple(1.06f, 0.11f, -0.20f),
        Triple(0.91f, -0.04f, 0.25f),
        Triple(0.96f, 0.02f, 0.10f)
    )

    return sectorModifiers.mapIndexed { idx, (kcMod, ndwiShift, eceShift) ->
        val valveNum = idx + 1
        val satKc = (baseKc * kcMod).coerceIn(0.30f, 1.30f)
        val ndvi = ((satKc - 0.12f) / 1.28f).coerceIn(0.28f, 0.86f)
        val ndwi = (0.24f + ndwiShift - (todayEt0 - 5.0f) * 0.02f).coerceIn(-0.18f, 0.45f)
        val secEce = max(0.6f, soilEce + eceShift)

        val netEtcMm = max(1.2f, todayEt0 * satKc)
        val netM3PerFeddan = netEtcMm * 4.2f
        val stressBoost = if (ndwi < 0.12f) 1.12f else 1.0f
        val grossM3PerFeddan = (netM3PerFeddan / max(0.65f, effRatio * (1f - lrRatio))) * stressBoost
        val totalM3 = grossM3PerFeddan * sectorArea
        val hours = grossM3PerFeddan / max(4f, valveFlowRateM3PerFeddanHr)

        val depletionPct = (42f - ndwi * 55f).roundToInt().coerceIn(18, 72)

        val (statusLevel, statusBadge, rec) = when {
            ndwi < 0.15f || secEce > soilEce + 0.3f -> Triple(
                SectorStatusLevel.HIGH_STRESS,
                "إجهاد مائي وملحي",
                "رصد Sentinel-2 انخفاضاً في مؤشر رطوبة الأوراق (NDWI=${formatDecimal2(ndwi)}) وتركز أملاح بوسط القطاع؛ تم زيادة الحصة المائية 12% لغسيل الجذور."
            )
            ndvi < 0.52f -> Triple(
                SectorStatusLevel.MODERATE_STRESS,
                "تفاوت نمو خضري",
                "كثافة الغطاء النباتي متوسطة؛ يوصى بفحص ضغط النقاطات في نهاية الخطوط ودعم التسميد الآزوتي."
            )
            else -> Triple(
                SectorStatusLevel.OPTIMAL,
                "نمو مثالي متجانس",
                "الغطاء النباتي متجانس (NDVI=${formatDecimal2(ndvi)}) ومستوى الرطوبة الأرضية في النطاق الآمن؛ استمر على جدول التشغيل المحسوب."
            )
        }

        ValveSectorTelemetry(
            valveNumber = valveNum,
            nameAr = "محبس $valveNum",
            areaFeddans = sectorArea,
            ndvi = ndvi,
            ndwi = ndwi,
            sectorEce = secEce,
            satelliteKc = satKc,
            soilMoistureDepletionPct = depletionPct,
            netEtcMmDay = netEtcMm,
            grossWaterM3PerFeddan = grossM3PerFeddan,
            totalDailyWaterM3 = totalM3,
            operatingHours = hours,
            statusBadgeAr = statusBadge,
            statusLevel = statusLevel,
            recommendationAr = rec
        )
    }
}

/**
 * Phase 5: Water Savings Analytics
 * Distinguishes clearly between:
 * - Potential modeled water savings (FAO-56 recommended schedule vs traditional surface/flood baseline)
 * - Measured logged water savings (only when actual applied water m³ is logged in irrigation records)
 */
data class WaterSavingsAnalyticsSummary(
    val traditionalBaselineWeeklyM3: Float,
    val fao56RecommendedWeeklyM3: Float,
    val potentialModeledSavingsWeeklyM3: Float,
    val potentialModeledSavingsPercent: Int,
    val hasMeasuredExecutionLogs: Boolean,
    val measuredLoggedAppliedM3: Float,
    val measuredSavingsM3: Float?,
    val statusExplanationAr: String
)

fun computeWaterSavingsAnalytics(
    weeklySchedule: List<WeeklyIrrigationDayItem>,
    areaFeddans: Float,
    loggedAppliedVolumesM3: List<Float>
): WaterSavingsAnalyticsSummary {
    val recommendedWeeklyM3 = weeklySchedule.sumOf { it.totalFarmM3.toDouble() }.toFloat()
    // Traditional uncalibrated flood/surface baseline (~65% efficiency)
    val traditionalWeeklyM3 = weeklySchedule.sumOf {
        (it.netM3PerFeddan / 0.65f * areaFeddans).toDouble()
    }.toFloat().coerceAtLeast(recommendedWeeklyM3)

    val potentialSavingsM3 = max(0f, traditionalWeeklyM3 - recommendedWeeklyM3)
    val potentialPct = if (traditionalWeeklyM3 > 0f) {
        ((potentialSavingsM3 / traditionalWeeklyM3) * 100f).roundToInt().coerceIn(0, 65)
    } else 0

    val hasLogs = loggedAppliedVolumesM3.isNotEmpty()
    val loggedTotalM3 = loggedAppliedVolumesM3.sum()
    val measuredSavings = if (hasLogs) {
        max(0f, traditionalWeeklyM3 - loggedTotalM3)
    } else null

    val explanation = if (hasLogs) {
        "وفر فعلي موثق من سجلات التشغيل: ${formatDecimal1(measuredSavings ?: 0f)} م³ مقارنة بالري التقليدي، مع وفر نموذجي مستهدف ${potentialPct}% عبر جدولة FAO-56."
    } else {
        "وفر نموذجي محتمل (Potential Modeled Savings): ${formatDecimal1(potentialSavingsM3)} م³/أسبوع (${potentialPct}%) مقارنة بالري التقليدي؛ سجّل كميات الضخ الفعلية لحساب الوفر الفعلي المقاس."
    }

    return WaterSavingsAnalyticsSummary(
        traditionalBaselineWeeklyM3 = traditionalWeeklyM3,
        fao56RecommendedWeeklyM3 = recommendedWeeklyM3,
        potentialModeledSavingsWeeklyM3 = potentialSavingsM3,
        potentialModeledSavingsPercent = potentialPct,
        hasMeasuredExecutionLogs = hasLogs,
        measuredLoggedAppliedM3 = loggedTotalM3,
        measuredSavingsM3 = measuredSavings,
        statusExplanationAr = explanation
    )
}

/**
 * Phase 6: Soil Intelligence & Data Provenance
 * Distinguishes between certified laboratory measurements, farmer field meter estimates,
 * and regional baseline presets.
 */
enum class SoilDataProvenance(val labelAr: String, val confidenceNoteAr: String) {
    CERTIFIED_LAB_ANALYSIS(
        "تحليل معمل معتمد (Certified Lab)",
        "بيانات مقاسة مخبرياً لعينات التربة والمياه؛ دقة عالية لتوصيات التسميد والغسيل"
    ),
    FIELD_PORTABLE_METER(
        "قياس جهاز حقلي محمول (Field Meter)",
        "قراءات حقلية مباشرة (pH / EC)؛ يوصى بمعايرة الجهاز دورياً"
    ),
    REGIONAL_BASELINE_ESTIMATE(
        "تقدير إقليمي افتراضي (Regional Estimate)",
        "قيم استرشادية مبنية على خصائص المنطقة الجغرافية؛ يوصى بإدخال تحليل معمل فعلي"
    )
}

data class SoilLaboratorySample(
    val sampleId: String,
    val sampleDateIso: String,
    val provenance: SoilDataProvenance,
    val depthCm: Int = 30,
    val ph: Float,
    val eceDsM: Float,
    val ecwDsM: Float,
    val organicMatterPercent: Float = 1.2f,
    val availableNitrogenPpm: Float = 45f,
    val availablePhosphorusPpm: Float = 14f,
    val availablePotassiumPpm: Float = 180f,
    val soilTexture: SoilTexture
)

