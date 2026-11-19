package com.example.data

import com.example.model.formatDecimal1
import com.example.model.formatDecimal2
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SpectralPixelCell(
    val row: Int,
    val col: Int,
    val valveNumber: Int,
    val ndvi: Float,
    val ndre: Float,
    val psri: Float,
    val ndmi: Float,
    val isHotspot: Boolean
)

data class EpidemiologyDiagnosisReport(
    val anomalyPattern: AnomalyPattern,
    val caseTitleAr: String,
    val detectedRiskNameAr: String,
    val confidenceScorePct: Int,
    val hotspotLocationAr: String,
    val affectedValveNumber: Int,
    val infectedAreaFeddans: Float,
    val ndviMean: Float,
    val ndreMean: Float,
    val psriMean: Float,
    val ndmiMean: Float,
    val isBiologicalDisease: Boolean,
    val causalMechanismAr: String,
    val immediateIrrigationActionAr: String,
    val valveAdjustmentPercent: Int, // e.g., -25% for fungal focal rot, +20% for blocked valve
    val targetedPreventiveActionAr: String,
    val costSavingHighlightAr: String,
    val acidDoseLitersPerFeddan: Float,
    val pixelGrid: List<SpectralPixelCell> // 12 cols x 8 rows (96 10m×10m Sentinel-2 pixels)
)

object SatelliteEpidemiologyEngine {

    fun evaluateFieldEpidemiology(
        fieldId: String,
        cropNameAr: String,
        areaFeddans: Float,
        soilPh: Float,
        soilEc: Float,
        tempMaxC: Float,
        relativeHumidityPct: Int,
        windSpeedKmh: Float,
        selectedPattern: AnomalyPattern
    ): EpidemiologyDiagnosisReport {
        val pixels = mutableListOf<SpectralPixelCell>()
        val rows = 8
        val cols = 12

        // Focal hotspot center in Valve 3 (top-left in RTL: row 1..2, col 9..10)
        val focalRow = 1.5f
        val focalCol = 9.5f

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val valveRow = if (r < 4) 0 else 1
                val valveCol = (c / 4).coerceIn(0, 2) // 0->Valve 1/4, 1->Valve 2/5, 2->Valve 3/6
                val valveNum = valveRow * 3 + valveCol + 1

                val distToFocal = sqrt((r - focalRow) * (r - focalRow) + (c - focalCol) * (c - focalCol))
                val radialGaussian = exp(-(distToFocal * distToFocal) / 2.2f)

                var ndvi = 0.72f
                var ndre = 0.52f
                var psri = 0.05f
                var ndmi = 0.34f
                var hotspot = false

                when (selectedPattern) {
                    AnomalyPattern.FOCAL_RADIAL_PATCH -> {
                        // Case A: Radial pathogen patch inside Valve 3 North-East corner
                        if (radialGaussian > 0.25f) {
                            // NDRE drops sharply before NDVI; PSRI spikes due to leaf necrosis/lesions; NDMI high (wet canopy)
                            ndre = (0.52f - 0.33f * radialGaussian).coerceIn(0.14f, 0.55f)
                            psri = (0.05f + 0.34f * radialGaussian).coerceIn(0.04f, 0.42f)
                            ndmi = (0.34f + 0.22f * radialGaussian).coerceIn(0.20f, 0.58f)
                            ndvi = (0.72f - 0.19f * radialGaussian).coerceIn(0.42f, 0.75f)
                            hotspot = radialGaussian > 0.48f
                        }
                    }

                    AnomalyPattern.UNIFORM_VALVE -> {
                        // Case B: Geometric drop across ALL pixels of Valve 2 (valveNum == 2)
                        if (valveNum == 2) {
                            ndmi = 0.09f // Severe water deficit in whole valve block
                            ndre = 0.36f
                            ndvi = 0.49f
                            psri = 0.08f // Low PSRI -> NOT a fungal lesion
                            hotspot = true
                        }
                    }

                    AnomalyPattern.SOIL_PH_LOCKUP -> {
                        // Case C: Field-wide chronic low NDRE across all valves due to high Soil pH > 7.5
                        val spatialWave = ((r + c) % 3) * 0.02f
                        ndre = (0.25f + spatialWave).coerceIn(0.21f, 0.31f)
                        ndvi = (0.56f + spatialWave).coerceIn(0.50f, 0.62f)
                        psri = 0.09f
                        ndmi = 0.31f // Normal moisture
                        hotspot = ndre < 0.27f
                    }

                    AnomalyPattern.HEALTHY -> {
                        val subtle = ((r * 3 + c) % 4) * 0.01f
                        ndvi = 0.75f + subtle
                        ndre = 0.55f + subtle
                        psri = 0.04f
                        ndmi = 0.35f
                        hotspot = false
                    }
                }

                pixels.add(
                    SpectralPixelCell(
                        row = r,
                        col = c,
                        valveNumber = valveNum,
                        ndvi = ndvi,
                        ndre = ndre,
                        psri = psri,
                        ndmi = ndmi,
                        isHotspot = hotspot
                    )
                )
            }
        }

        // Calculate means & specific diagnosis based on Pattern + Crop + Weather + Soil pH
        val acidDose = calculateRequiredAcidLitersPerFeddan(soilPh)

        return when (selectedPattern) {
            AnomalyPattern.FOCAL_RADIAL_PATCH -> {
                val hotspotPixels = pixels.filter { it.isHotspot }.ifEmpty { pixels }
                val ndreAvg = hotspotPixels.map { it.ndre }.average().toFloat()
                val psriAvg = hotspotPixels.map { it.psri }.average().toFloat()
                val ndmiAvg = hotspotPixels.map { it.ndmi }.average().toFloat()
                val ndviAvg = hotspotPixels.map { it.ndvi }.average().toFloat()
                val focalArea = (areaFeddans * 0.0065f).coerceIn(0.25f, 1.20f)

                val pathogenName = selectCropSpecificPathogen(cropNameAr, tempMaxC, relativeHumidityPct)
                val isHighHumidityFungal = relativeHumidityPct >= 55 || tempMaxC < 34f

                EpidemiologyDiagnosisReport(
                    anomalyPattern = AnomalyPattern.FOCAL_RADIAL_PATCH,
                    caseTitleAr = "الحالة (أ): مؤشر استرشادي لبؤرة إجهاد طيفي دائرية (نمذجة طيفية بحاجة لمعاينة مهندس زراعي)",
                    detectedRiskNameAr = "اشتباه طيفي استرشادي: $pathogenName",
                    confidenceScorePct = 89,
                    hotspotLocationAr = "محبس 3 - الركن الشمالي الشرقي - مساحة البؤرة المقدرة ${formatDecimal2(focalArea)} فدان",
                    affectedValveNumber = 3,
                    infectedAreaFeddans = focalArea,
                    ndviMean = ndviAvg,
                    ndreMean = ndreAvg,
                    psriMean = psriAvg,
                    ndmiMean = ndmiAvg,
                    isBiologicalDisease = true,
                    causalMechanismAr = "يُظهر نموذج المحاكاة الطيفية (Sentinel-2 10m×10m) هبوطاً بؤرياً في مؤشر الكلوروفيل المبكر (NDRE=${formatDecimal2(ndreAvg)}) مع ارتفاع مؤشر إجهاد الأنسجة (PSRI=${formatDecimal2(psriAvg)}) ورطوبة مرتفعة (NDMI=${formatDecimal2(ndmiAvg)}) بالتزامن مع رطوبة جوية ($relativeHumidityPct%) وحرارة (${formatDecimal1(tempMaxC)}°C). ملاحظة علمية: هذا المؤشر استرشادي وليس تشخيصاً معملياً مؤكداً.",
                    immediateIrrigationActionAr = if (isHighHumidityFungal) {
                        "مقترح استرشادي: خفض مقنن الري في المحبس رقم 3 بنسبة 25% لتقليل الرطوبة الحرة حول العرش بعد المعاينة الحقلية."
                    } else {
                        "مقترح استرشادي: ضبط انتظام الري في المحبس رقم 3 لمنع الإجهاد المائي الحراري المشجع لتكاثر الآفات."
                    },
                    valveAdjustmentPercent = if (isHighHumidityFungal) -25 else 10,
                    targetedPreventiveActionAr = buildFocalSprayPrescription(cropNameAr, pathogenName, focalArea),
                    costSavingHighlightAr = "المعاينة البؤرية المستهدفة لمساحة ${formatDecimal2(focalArea)} فدان فقط بدلاً من ${areaFeddans.toInt()} فدان تحد من الاستخدام العشوائي للمبيدات.",
                    acidDoseLitersPerFeddan = acidDose,
                    pixelGrid = pixels
                )
            }

            AnomalyPattern.UNIFORM_VALVE -> {
                val valve2Pixels = pixels.filter { it.valveNumber == 2 }
                val sectorArea = (areaFeddans / 6f).coerceAtLeast(1f)
                EpidemiologyDiagnosisReport(
                    anomalyPattern = AnomalyPattern.UNIFORM_VALVE,
                    caseTitleAr = "الحالة (ب): هبوط خطي هندسي مطابق لحدود المحبس رقم 2 بالكامل (مؤشر استرشادي)",
                    detectedRiskNameAr = "اشتباه عطل ميكانيكي أو انسداد في شبكة الري للمحبس رقم 2 (يُستبعد المرض الفطري مبدئياً)",
                    confidenceScorePct = 94,
                    hotspotLocationAr = "محبس 2 بالكامل - حدود هندسية منتظمة (${formatDecimal1(sectorArea)} فدان)",
                    affectedValveNumber = 2,
                    infectedAreaFeddans = sectorArea,
                    ndviMean = valve2Pixels.map { it.ndvi }.average().toFloat(),
                    ndreMean = valve2Pixels.map { it.ndre }.average().toFloat(),
                    psriMean = valve2Pixels.map { it.psri }.average().toFloat(),
                    ndmiMean = valve2Pixels.map { it.ndmi }.average().toFloat(),
                    isBiologicalDisease = false,
                    causalMechanismAr = "الهبوط الطيفي في النموذج المكاني يتخذ شكلاً مستطيلاً يطابق حدود المحبس رقم 2 مع انخفاض مؤشر رطوبة الأوراق (NDMI=0.09) وبقاء مؤشر تلف الأنسجة منخفضاً (PSRI=0.08)، مما يرجح وجود سبب هيدروليكي/ميكانيكي وليس عدوى بيولوجية.",
                    immediateIrrigationActionAr = "فحص ضغط المحبس رقم 2 وفلاتر الفرع الرئيسي وغسيل نهايات الخطوط، وتعويض العجز المائي تدريجياً بعد التأكد الميداني من التسليك.",
                    valveAdjustmentPercent = 20,
                    targetedPreventiveActionAr = "لا يُنصح برش مبيدات فطرية أو حشرية قبل فحص شبكة الري؛ المؤشر يرجح مشكلة ميكانيكية/مائية في المحبس رقم 2.",
                    costSavingHighlightAr = "تجنب رش مبيدات غير ضرورية وحماية المحصول من الإجهاد المائي عبر الفحص الميكانيكي المبكر.",
                    acidDoseLitersPerFeddan = acidDose,
                    pixelGrid = pixels
                )
            }

            AnomalyPattern.SOIL_PH_LOCKUP -> {
                val effPh = max(7.8f, soilPh)
                EpidemiologyDiagnosisReport(
                    anomalyPattern = AnomalyPattern.SOIL_PH_LOCKUP,
                    caseTitleAr = "الحالة (ج): اصفرار عام بالحقل مرتبط بقلوية التربة وتثبيت العناصر (تقدير استرشادي)",
                    detectedRiskNameAr = "اشتباه اصفرار فسيولوجي ناتج عن قلوية التربة المرتفعة (pH ${formatDecimal1(effPh)}) وتثبيت العناصر الصغرى",
                    confidenceScorePct = 92,
                    hotspotLocationAr = "امتداد الحقل بالكامل (${areaFeddans.toInt()} فدان) • غير مرتبط بمحبس واحد",
                    affectedValveNumber = 1,
                    infectedAreaFeddans = areaFeddans,
                    ndviMean = 0.57f,
                    ndreMean = 0.26f,
                    psriMean = 0.09f,
                    ndmiMean = 0.31f,
                    isBiologicalDisease = false,
                    causalMechanismAr = "تراجع مؤشر الكلوروفيل (NDRE=0.26) موزع على كامل الحقل مع رطوبة أرضية معتدلة (NDMI=0.31) وارتفاع حموضة التربة المدخلة إلى pH ${formatDecimal1(effPh)}، مما يرجح تثبيت الحديد والزنك والفوسفور في التربة القلوية.",
                    immediateIrrigationActionAr = "الحفاظ على مقنن الري القياسي مع مراجعة مهندس التسميد لضبط برنامج الحقن الحمضي تدريجياً نحو نطاق pH 6.5.",
                    valveAdjustmentPercent = 0,
                    targetedPreventiveActionAr = "تقدير استرشادي أولي: ${formatDecimal1(max(4.0f, acidDose))} لتر/فدان حامض فوسفوريك (85%) + حديد مخلبي EDDHA 6% بالتقسيم على دفعات؛ يجب معايرة الجرعة معملياً وفق قلوية مياه الري (Bicarbonates) وبإشراف مهندس زراعي مختص.",
                    costSavingHighlightAr = "تجنب رش المبيدات الفطرية غير المبررة وتوجيه المعالجة نحو تيسير العناصر الغذائية في منطقة الجذور.",
                    acidDoseLitersPerFeddan = max(4.0f, acidDose),
                    pixelGrid = pixels
                )
            }

            AnomalyPattern.HEALTHY -> {
                EpidemiologyDiagnosisReport(
                    anomalyPattern = AnomalyPattern.HEALTHY,
                    caseTitleAr = "حالة مرجعية: غطاء نباتي متجانس في النموذج الطيفي دون مؤشرات إجهاد حادة",
                    detectedRiskNameAr = "لا توجد بؤر إجهاد طيفي أو أعطال ري مرصودة حالياً",
                    confidenceScorePct = 97,
                    hotspotLocationAr = "جميع القطاعات (محبس 1 إلى محبس 6) في النطاق الأخضر المستقر",
                    affectedValveNumber = 1,
                    infectedAreaFeddans = 0.0f,
                    ndviMean = 0.76f,
                    ndreMean = 0.56f,
                    psriMean = 0.04f,
                    ndmiMean = 0.35f,
                    isBiologicalDisease = false,
                    causalMechanismAr = "قراءات الكلوروفيل المبكر (NDRE=0.56) ومؤشر الرطوبة (NDMI=0.35) متجانسة في مصفوفة البكسل (10m×10m) دون ارتفاع في مؤشر إجهاد الأنسجة (PSRI=0.04).",
                    immediateIrrigationActionAr = "الاستمرار على جدول الري الاسترشادي المحسوب من معادلة FAO-56 مع المتابعة الميدانية الدورية.",
                    valveAdjustmentPercent = 0,
                    targetedPreventiveActionAr = "لا حاجة لتدخل علاجي؛ يُوصى بالمتابعة الدورية مع المهندس الزراعي.",
                    costSavingHighlightAr = "الحد من الرش الوقائي العشوائي عبر المتابعة الدورية لحالة المحصول.",
                    acidDoseLitersPerFeddan = acidDose,
                    pixelGrid = pixels
                )
            }
        }
    }

    fun buildScanEntityFromReport(
        fieldId: String,
        report: EpidemiologyDiagnosisReport
    ): SatelliteSpectralScanEntity {
        return SatelliteSpectralScanEntity(
            id = "scan_${System.currentTimeMillis()}",
            fieldId = fieldId,
            scanDate = LocalDate.now().toString(),
            ndviMean = report.ndviMean,
            ndreMean = report.ndreMean,
            psriMean = report.psriMean,
            ndmiMean = report.ndmiMean,
            anomalyPattern = report.anomalyPattern,
            infectedAreaFeddans = report.infectedAreaFeddans,
            hotspotCoordinates = report.hotspotLocationAr,
            detectedRiskName = report.detectedRiskNameAr,
            confidenceScore = report.confidenceScorePct
        )
    }

    fun calculateRequiredAcidLitersPerFeddan(soilPh: Float): Float {
        return when {
            soilPh <= 7.0f -> 0.5f
            soilPh <= 7.5f -> 2.0f
            soilPh <= 8.0f -> 3.5f
            soilPh <= 8.4f -> 5.5f
            else -> 7.5f
        }
    }

    private fun selectCropSpecificPathogen(
        cropNameAr: String,
        tempMaxC: Float,
        humidityPct: Int
    ): String {
        val hotAndDry = tempMaxC >= 34.5f && humidityPct < 45
        if (hotAndDry) {
            return "بؤرة نشاط للعنكبوت الأحمر (Tetranychus urticae) مع إجهاد حراري"
        }
        return when {
            cropNameAr.contains("طماطم") || cropNameAr.contains("بطاطس") ->
                "اللفحة المتأخرة (Phytophthora infestans) وبداية أعفان تاجية"
            cropNameAr.contains("قمح") ->
                "الصدأ الأصفر المخطط في القمح (Puccinia striiformis)"
            cropNameAr.contains("ذرة") ->
                "لفحة الأوراق الشمالية في الذرة (Exserohilum turcicum)"
            cropNameAr.contains("فراولة") ->
                "العفن الرمادي (Botrytis cinerea) وأعفان الجذور في الفراولة"
            cropNameAr.contains("موالح") ->
                "تصمغ الموالح وأعفان الجذور الفيتوفثورية (Phytophthora spp.)"
            cropNameAr.contains("نخيل") ->
                "لفحة الأوراق السوداء (اللفحة الدبلودية) في النخيل"
            else ->
                "تبقع الأوراق السركسبوري (Cercospora beticola) في بنجر السكر"
        }
    }

    private fun buildFocalSprayPrescription(
        cropNameAr: String,
        pathogenName: String,
        focalAreaFeddans: Float
    ): String {
        return "توصية استرشادية أولية: فحص بؤرة المحبس 3 ميدانياً (${formatDecimal2(focalAreaFeddans)} فدان فقط - وليس الحقل بالكامل) مع مهندس زراعي مختص للتحقق من ($pathogenName) قبل تطبيق أي مبيد متخصص، مع تجنب الإفراط في التسميد الآزوتي في هذا القطاع لمدة 48 ساعة."
    }
}
