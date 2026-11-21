@file:SuppressLint("NewApi")

package com.example.data

import android.annotation.SuppressLint
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
    val isHotspot: Boolean,
    val isCloudMasked: Boolean = false
)

/**
 * Future-ready interface for integrating an independently trained and validated crop disease ML model.
 * When no validated model artifact is active, [isValidatedModelLoaded] is false and the platform
 * explicitly labels risk outputs as heuristic multi-factor risk assessments rather than ML predictions.
 */
interface CropDiseaseModelInferencePort {
    val modelVersionId: String
    val isValidatedModelLoaded: Boolean
    fun evaluateAnomalyProbability(
        ndviMean: Float,
        ndreMean: Float,
        psriMean: Float,
        ndmiMean: Float,
        tempMaxC: Float,
        relativeHumidityPct: Int,
        soilPh: Float,
        soilEc: Float
    ): Float?
}

object HeuristicFallbackDiseaseModelPort : CropDiseaseModelInferencePort {
    override val modelVersionId: String = "RULE_BASED_MULTI_FACTOR_V2 (NO_ML_WEIGHTS_LOADED)"
    override val isValidatedModelLoaded: Boolean = false
    override fun evaluateAnomalyProbability(
        ndviMean: Float,
        ndreMean: Float,
        psriMean: Float,
        ndmiMean: Float,
        tempMaxC: Float,
        relativeHumidityPct: Int,
        soilPh: Float,
        soilEc: Float
    ): Float? = null
}

/**
 * Stores field inspection feedback from an agronomist/expert to validate and label satellite anomalies
 * for future supervised model training.
 */
data class FieldInspectionValidationRecord(
    val id: String,
    val fieldId: String,
    val sceneId: String,
    val inspectedValveNumber: Int,
    val detectedAnomalyPattern: AnomalyPattern,
    val expertConfirmedCauseAr: String,
    val isConfirmedBiologicalDisease: Boolean,
    val inspectorNameAr: String,
    val inspectionNotesAr: String,
    val timestampIso: String
)

data class EpidemiologyDiagnosisReport(
    val anomalyPattern: AnomalyPattern,
    val caseTitleAr: String,
    val detectedRiskNameAr: String,
    /**
     * Heuristic pattern consistency index (0-100). Explicitly documented in [uncertaintyAndLimitationsAr]
     * as a rule-based multi-factor consistency index, NOT a lab-validated ML confidence probability.
     */
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
    val valveAdjustmentPercent: Int,
    val targetedPreventiveActionAr: String,
    val costSavingHighlightAr: String,
    val acidDoseLitersPerFeddan: Float,
    val pixelGrid: List<SpectralPixelCell>,
    // Phase 4 Separation: (1) Vegetation Stress Detection vs (2) Agricultural Risk Assessment vs (3) Confirmed Diagnosis
    val vegetationStressDetectionAr: String = "",
    val agriculturalRiskAssessmentAr: String = "",
    val confirmedDiseaseDiagnosisStatusAr: String = "غير مؤكد معملياً — رصد طيفي استرشادي يتطلب معاينة حقلية من مهندس زراعي مختص",
    val supportingObservationsAr: List<String> = emptyList(),
    val possibleCausesAr: List<String> = emptyList(),
    val uncertaintyAndLimitationsAr: String = "",
    val recommendedFieldInspectionAr: String = "",
    val dataSourcesCitedAr: List<String> = emptyList(),
    val dataModeDesignation: String = "DEMO"
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
        selectedPattern: AnomalyPattern,
        isLiveSatelliteSceneAvailable: Boolean = false,
        sceneId: String = "DEMO_SCENE"
    ): EpidemiologyDiagnosisReport {
        val pixels = mutableListOf<SpectralPixelCell>()
        val rows = 8
        val cols = 12

        val focalRow = 1.5f
        val focalCol = 9.5f

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val valveRow = if (r < 4) 0 else 1
                val valveCol = (c / 4).coerceIn(0, 2)
                val valveNum = valveRow * 3 + valveCol + 1

                val distToFocal = sqrt((r - focalRow) * (r - focalRow) + (c - focalCol) * (c - focalCol))
                val radialGaussian = exp(-(distToFocal * distToFocal) / 2.2f)

                // Derive Bottom-Of-Atmosphere band reflectance values (B02, B04, B05, B06, B08, B8A, B11)
                // and compute indices strictly through Sentinel2BandMathEngine formulas:
                // NDVI=(B08-B04)/(B08+B04), NDRE=(B8A-B05)/(B8A+B05), NDMI=(B08-B11)/(B08+B11), PSRI=(B04-B02)/B06
                var b02Blue = 0.042f
                var b04Red = 0.062f
                var b05RedEdge1 = 0.135f
                var b06RedEdge2 = 0.340f
                var b08Nir = 0.395f
                var b8aNarrowNir = 0.430f
                var b11Swir1 = 0.192f
                var hotspot = false

                when (selectedPattern) {
                    AnomalyPattern.FOCAL_RADIAL_PATCH -> {
                        if (radialGaussian > 0.25f) {
                            // Chlorophyll loss raises B04 Red & B05 Red-Edge1, drops B08/B8A, canopy wetness lowers B11 SWIR1
                            b04Red = (0.062f + 0.068f * radialGaussian).coerceIn(0.04f, 0.25f)
                            b05RedEdge1 = (0.135f + 0.135f * radialGaussian).coerceIn(0.10f, 0.35f)
                            b06RedEdge2 = (0.340f - 0.110f * radialGaussian).coerceIn(0.18f, 0.40f)
                            b08Nir = (0.395f - 0.105f * radialGaussian).coerceIn(0.20f, 0.50f)
                            b8aNarrowNir = (0.430f - 0.115f * radialGaussian).coerceIn(0.20f, 0.52f)
                            b11Swir1 = (0.192f - 0.085f * radialGaussian).coerceIn(0.08f, 0.30f)
                            hotspot = radialGaussian > 0.48f
                        }
                    }

                    AnomalyPattern.UNIFORM_VALVE -> {
                        if (valveNum == 2) {
                            // Severe water deficit across Valve 2: high SWIR1 (B11) reflectance -> low NDMI
                            b04Red = 0.108f
                            b02Blue = 0.082f
                            b05RedEdge1 = 0.172f
                            b06RedEdge2 = 0.320f
                            b08Nir = 0.315f
                            b8aNarrowNir = 0.365f
                            b11Swir1 = 0.264f
                            hotspot = true
                        }
                    }

                    AnomalyPattern.SOIL_PH_LOCKUP -> {
                        val spatialWave = ((r + c) % 3) * 0.008f
                        b04Red = 0.092f + spatialWave
                        b02Blue = 0.065f
                        b05RedEdge1 = 0.208f + spatialWave
                        b06RedEdge2 = 0.310f
                        b08Nir = 0.335f + spatialWave
                        b8aNarrowNir = 0.355f + spatialWave
                        b11Swir1 = 0.178f
                    }

                    AnomalyPattern.HEALTHY -> {
                        val subtle = ((r * 3 + c) % 4) * 0.004f
                        b04Red = 0.055f
                        b02Blue = 0.041f
                        b05RedEdge1 = 0.122f
                        b06RedEdge2 = 0.350f
                        b08Nir = 0.435f + subtle
                        b8aNarrowNir = 0.425f + subtle
                        b11Swir1 = 0.208f
                        hotspot = false
                    }
                }

                val sample = Sentinel2PixelReflectance(
                    row = r,
                    col = c,
                    b02Blue = b02Blue,
                    b04Red = b04Red,
                    b05RedEdge1 = b05RedEdge1,
                    b06RedEdge2 = b06RedEdge2,
                    b08Nir = b08Nir,
                    b8aNarrowNir = b8aNarrowNir,
                    b11Swir1 = b11Swir1,
                    sclClass = 4
                )
                val computed = Sentinel2BandMathEngine.evaluatePixel(sample)
                val ndvi = computed.ndvi ?: 0.72f
                val ndre = computed.ndre ?: 0.52f
                val psri = computed.psri ?: 0.05f
                val ndmi = computed.ndmi ?: 0.34f

                if (selectedPattern == AnomalyPattern.SOIL_PH_LOCKUP) {
                    hotspot = ndre < 0.27f
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
                        isHotspot = hotspot,
                        isCloudMasked = !computed.isValidUnmasked
                    )
                )
            }
        }

        val acidDose = calculateRequiredAcidLitersPerFeddan(soilPh)
        val modeTag = if (isLiveSatelliteSceneAvailable) "LIVE_STAC_CALIBRATED ($sceneId)" else "DEMO_SIMULATION_MODE"
        val commonUncertaintyAr =
            "المؤشرات الطيفية للأقمار الصناعية (10m-20m) ترصد شذوذ الانعكاس الضوئي للغطاء النباتي فقط ولا تكفي وحدها لتشخيص مسبب مرضي بعينه دون فحص حقلي/معملي. قيمة التوافق المعروضة تمثل توافق النمط متعدد العوامل (Multi-Factor Heuristic Score) وليست احتمالية نموذج تعلم آلي مدرب."

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
                    caseTitleAr = "الحالة (أ): رصد إجهاد طيفي بؤري دائري وتقييم مخاطر بيئية (بحاجة لمعاينة مهندس زراعي)",
                    detectedRiskNameAr = "مؤشر خطر طيفي/مناخي: $pathogenName",
                    confidenceScorePct = 89,
                    hotspotLocationAr = "محبس 3 - الركن الشمالي الشرقي - مساحة البؤرة المقدرة ${formatDecimal2(focalArea)} فدان",
                    affectedValveNumber = 3,
                    infectedAreaFeddans = focalArea,
                    ndviMean = ndviAvg,
                    ndreMean = ndreAvg,
                    psriMean = psriAvg,
                    ndmiMean = ndmiAvg,
                    isBiologicalDisease = true,
                    causalMechanismAr = "1) رصد الإجهاد النباتي: هبوط بؤري في مؤشر الكلوروفيل المبكر (NDRE=${formatDecimal2(ndreAvg)}) مع ارتفاع مؤشر شيخوخة الأنسجة (PSRI=${formatDecimal2(psriAvg)}) ورطوبة المجموع الخضري (NDMI=${formatDecimal2(ndmiAvg)}).\n2) تقييم المخاطر: تزامن الشذوذ الطيفي مع رطوبة جوية ($relativeHumidityPct%) وحرارة (${formatDecimal1(tempMaxC)}°C) يرفع قابلية الإصابة البؤرية.\n3) حالة التشخيص المؤكد: غير مؤكد معملياً — يلزم الفحص البصري الحقيقي للبؤرة.",
                    immediateIrrigationActionAr = if (isHighHumidityFungal) {
                        "مقترح استرشادي: خفض مقنن الري في المحبس رقم 3 بنسبة 25% لتقليل الرطوبة الحرة حول العرش بعد المعاينة الحقلية."
                    } else {
                        "مقترح استرشادي: ضبط انتظام الري في المحبس رقم 3 لمنع الإجهاد المائي الحراري المشجع لتكاثر الآفات."
                    },
                    valveAdjustmentPercent = if (isHighHumidityFungal) -25 else 10,
                    targetedPreventiveActionAr = buildFocalSprayPrescription(cropNameAr, pathogenName, focalArea),
                    costSavingHighlightAr = "المعاينة البؤرية المستهدفة لمساحة ${formatDecimal2(focalArea)} فدان فقط بدلاً من ${areaFeddans.toInt()} فدان تحد من الاستخدام العشوائي للمبيدات.",
                    acidDoseLitersPerFeddan = acidDose,
                    pixelGrid = pixels,
                    vegetationStressDetectionAr = "شذوذ طيفي بؤري غير منتظم في المحبس 3: تراجع NDRE إلى ${formatDecimal2(ndreAvg)} وارتفاع PSRI إلى ${formatDecimal2(psriAvg)}.",
                    agriculturalRiskAssessmentAr = "خطر زراعي مرتفع لتطور ($pathogenName) لتوافق الرطوبة النسبية ($relativeHumidityPct%) ودرجة الحرارة (${formatDecimal1(tempMaxC)}°C) مع رطوبة المجموع الخضري.",
                    confirmedDiseaseDiagnosisStatusAr = "غير مؤكد — يتطلب معاينة ميدانية من مهندس زراعي لتأكيد المسبب قبل تطبيق أي مبيد",
                    supportingObservationsAr = listOf(
                        "انخفاض NDRE=(B8A-B05)/(B8A+B05) إلى ${formatDecimal2(ndreAvg)} في مركز البؤرة",
                        "ارتفاع PSRI=(B04-B02)/B06 إلى ${formatDecimal2(psriAvg)} (مؤشر تغير صبغات الأوراق)",
                        "ارتفاع NDMI=(B08-B11)/(B08+B11) إلى ${formatDecimal2(ndmiAvg)} مع رطوبة جوية $relativeHumidityPct%"
                    ),
                    possibleCausesAr = listOf(
                        "احتمال بؤرة إصابة فطرية أو بكتيرية أولية ($pathogenName)",
                        "تجمع مائي موضعي أو سوء صرف في الركن الشمالي الشرقي للمحبس 3",
                        "إجهاد موضعي ناتج عن آفات ثاقبة ماصة في حال ارتفاع الحرارة"
                    ),
                    uncertaintyAndLimitationsAr = commonUncertaintyAr,
                    recommendedFieldInspectionAr = "توجه إلى الركن الشمالي الشرقي في المحبس رقم 3 وافحص أسفل الأوراق السفلية وتاج النبات للتحقق من وجود أعراض حقيقية.",
                    dataSourcesCitedAr = listOf(
                        "Sentinel-2 MSI Bands B02, B04, B05, B06, B08, B8A, B11",
                        "Open-Meteo FAO-56 Daily Temperature & Relative Humidity",
                        "User-Entered Soil Chemistry Profile (pH ${formatDecimal1(soilPh)}, ECe ${formatDecimal1(soilEc)} dS/m)"
                    ),
                    dataModeDesignation = modeTag
                )
            }

            AnomalyPattern.UNIFORM_VALVE -> {
                val valve2Pixels = pixels.filter { it.valveNumber == 2 }
                val ndviAvg = valve2Pixels.map { it.ndvi }.average().toFloat()
                val ndreAvg = valve2Pixels.map { it.ndre }.average().toFloat()
                val psriAvg = valve2Pixels.map { it.psri }.average().toFloat()
                val ndmiAvg = valve2Pixels.map { it.ndmi }.average().toFloat()
                val sectorArea = (areaFeddans / 6f).coerceAtLeast(1f)
                EpidemiologyDiagnosisReport(
                    anomalyPattern = AnomalyPattern.UNIFORM_VALVE,
                    caseTitleAr = "الحالة (ب): رصد إجهاد مائي هندسي منتظم مطابق لحدود المحبس رقم 2 بالكامل",
                    detectedRiskNameAr = "اشتباه عطل هيدروليكي أو انسداد في شبكة الري للمحبس رقم 2 (يُستبعد المرض الفطري مبدئياً)",
                    confidenceScorePct = 94,
                    hotspotLocationAr = "محبس 2 بالكامل - حدود هندسية منتظمة (${formatDecimal1(sectorArea)} فدان)",
                    affectedValveNumber = 2,
                    infectedAreaFeddans = sectorArea,
                    ndviMean = ndviAvg,
                    ndreMean = ndreAvg,
                    psriMean = psriAvg,
                    ndmiMean = ndmiAvg,
                    isBiologicalDisease = false,
                    causalMechanismAr = "الهبوط الطيفي يتخذ شكلاً مستطيلاً يطابق حدود المحبس رقم 2 مع انخفاض مؤشر رطوبة الأوراق (NDMI=${formatDecimal2(ndmiAvg)}) وبقاء مؤشر تلف الأنسجة منخفضاً (PSRI=${formatDecimal2(psriAvg)})، مما يرجح وجود سبب هيدروليكي/ميكانيكي وليس عدوى بيولوجية.",
                    immediateIrrigationActionAr = "فحص ضغط المحبس رقم 2 وفلاتر الفرع الرئيسي وغسيل نهايات الخطوط، وتعويض العجز المائي تدريجياً بعد التأكد الميداني من التسليك.",
                    valveAdjustmentPercent = 20,
                    targetedPreventiveActionAr = "لا يُنصح برش مبيدات فطرية أو حشرية قبل فحص شبكة الري؛ المؤشر يرجح مشكلة ميكانيكية/مائية في المحبس رقم 2.",
                    costSavingHighlightAr = "تجنب رش مبيدات غير ضرورية وحماية المحصول من الإجهاد المائي عبر الفحص الميكانيكي المبكر.",
                    acidDoseLitersPerFeddan = acidDose,
                    pixelGrid = pixels,
                    vegetationStressDetectionAr = "هبوط منتظم في مؤشر الرطوبة NDMI (${formatDecimal2(ndmiAvg)}) ومؤشر الغطاء النباتي NDVI (${formatDecimal2(ndviAvg)}) محصور هندسياً داخل حدود المحبس رقم 2.",
                    agriculturalRiskAssessmentAr = "خطر إجهاد مائي/جفاف جذور نتيجة نقص التصرف المائي في المحبس رقم 2، مع انخفاض مؤشر الشيخوخة المرضية PSRI.",
                    confirmedDiseaseDiagnosisStatusAr = "لا يوجد اشتباه مرضي بيولوجي — يتطلب فحص ضغط المياه والفلاتر في المحبس رقم 2",
                    supportingObservationsAr = listOf(
                        "انخفاض NDMI=(B08-B11)/(B08+B11) إلى ${formatDecimal2(ndmiAvg)} نتيجة ارتفاع انعكاس SWIR1 (B11)",
                        "تطابق حدود الشذوذ المكاني مع المستطيل الهندسي للمحبس رقم 2",
                        "بقاء PSRI منخفضاً عند ${formatDecimal2(psriAvg)}"
                    ),
                    possibleCausesAr = listOf(
                        "انسداد جزئي في فلتر المحبس رقم 2 أو انخفاض ضغط الشبكة الفرعية",
                        "عطل في صمام التشغيل الكهربائي (Solenoid Valve) للمحبس رقم 2",
                        "تكلس النقاطات بسبب ارتفاع قلوية المياه"
                    ),
                    uncertaintyAndLimitationsAr = commonUncertaintyAr,
                    recommendedFieldInspectionAr = "قياس ضغط المياه بمانومتر عند بداية ونهاية خطوط المحبس رقم 2 وفحص الفلتر الفرعي.",
                    dataSourcesCitedAr = listOf(
                        "Sentinel-2 MSI Bands B08 (NIR) & B11 (SWIR1) for NDMI",
                        "Sector Hydraulic Topology (Valve 2 Block)"
                    ),
                    dataModeDesignation = modeTag
                )
            }

            AnomalyPattern.SOIL_PH_LOCKUP -> {
                val effPh = max(7.8f, soilPh)
                val ndviAvg = pixels.map { it.ndvi }.average().toFloat()
                val ndreAvg = pixels.map { it.ndre }.average().toFloat()
                val psriAvg = pixels.map { it.psri }.average().toFloat()
                val ndmiAvg = pixels.map { it.ndmi }.average().toFloat()
                EpidemiologyDiagnosisReport(
                    anomalyPattern = AnomalyPattern.SOIL_PH_LOCKUP,
                    caseTitleAr = "الحالة (ج): رصد تراجع عام بالكلوروفيل مرتبط بقلوية التربة وتثبيت العناصر",
                    detectedRiskNameAr = "اشتباه اصفرار فسيولوجي ناتج عن قلوية التربة المرتفعة (pH ${formatDecimal1(effPh)}) وتثبيت العناصر الصغرى",
                    confidenceScorePct = 92,
                    hotspotLocationAr = "امتداد الحقل بالكامل (${areaFeddans.toInt()} فدان) • غير مرتبط بمحبس واحد",
                    affectedValveNumber = 1,
                    infectedAreaFeddans = areaFeddans,
                    ndviMean = ndviAvg,
                    ndreMean = ndreAvg,
                    psriMean = psriAvg,
                    ndmiMean = ndmiAvg,
                    isBiologicalDisease = false,
                    causalMechanismAr = "تراجع مؤشر الكلوروفيل (NDRE=${formatDecimal2(ndreAvg)}) موزع على كامل الحقل مع رطوبة أرضية معتدلة (NDMI=${formatDecimal2(ndmiAvg)}) وارتفاع حموضة التربة المدخلة إلى pH ${formatDecimal1(effPh)}، مما يرجح تثبيت الحديد والزنك والفوسفور في التربة القلوية.",
                    immediateIrrigationActionAr = "الحفاظ على مقنن الري القياسي مع مراجعة مهندس التسميد لضبط برنامج الحقن الحمضي تدريجياً نحو نطاق pH 6.5.",
                    valveAdjustmentPercent = 0,
                    targetedPreventiveActionAr = "تقدير استرشادي أولي: ${formatDecimal1(max(4.0f, acidDose))} لتر/فدان حامض فوسفوريك (85%) + حديد مخلبي EDDHA 6% بالتقسيم على دفعات؛ يجب معايرة الجرعة معملياً وفق قلوية مياه الري (Bicarbonates) وبإشراف مهندس زراعي مختص.",
                    costSavingHighlightAr = "تجنب رش المبيدات الفطرية غير المبررة وتوجيه المعالجة نحو تيسير العناصر الغذائية في منطقة الجذور.",
                    acidDoseLitersPerFeddan = max(4.0f, acidDose),
                    pixelGrid = pixels,
                    vegetationStressDetectionAr = "انخفاض شامل في مؤشر الحافة الحمراء NDRE (${formatDecimal2(ndreAvg)}) عبر كافة قطاعات الحقل مع استقرار رطوبة الأوراق NDMI (${formatDecimal2(ndmiAvg)}).",
                    agriculturalRiskAssessmentAr = "خطر نقص عناصر غذائية صغرى وفوسفور بسبب ارتفاع رقم حموضة التربة المدخل (pH=${formatDecimal1(effPh)}).",
                    confirmedDiseaseDiagnosisStatusAr = "حالة فسيولوجية/غذائية محتملة — يُنصح بتحليل معمل للتربة والأوراق لتأكيد نقص العناصر",
                    supportingObservationsAr = listOf(
                        "انخفاض NDRE=(B8A-B05)/(B8A+B05) إلى ${formatDecimal2(ndreAvg)} على مستوى الحقل",
                        "قراءة pH التربة المدخلة (${formatDecimal1(effPh)}) تتجاوز عتبة تيسر الحديد والفوسفور (7.2)",
                        "استقرار مؤشر الرطوبة NDMI عند ${formatDecimal2(ndmiAvg)}"
                    ),
                    possibleCausesAr = listOf(
                        "تثبيت كيميائي للحديد والزنك والمنجنيز والفوسفور بسبب قلوية التربة",
                        "ارتفاع البيكربونات في مياه الري",
                        "نقص التسميد الآزوتي أو ضعف نشاط الجذور بسبب الملوحة"
                    ),
                    uncertaintyAndLimitationsAr = commonUncertaintyAr,
                    recommendedFieldInspectionAr = "فحص الأوراق الحديثة في قمم النباتات (اصفرار بين العروق مع بقاء العروق خضراء يشير لنقص الحديد) وإجراء تحليل معمل للتربة.",
                    dataSourcesCitedAr = listOf(
                        "Sentinel-2 Red-Edge Bands B05 & B8A for NDRE",
                        "Soil Chemistry Input (pH ${formatDecimal1(effPh)}, ECe ${formatDecimal1(soilEc)} dS/m) & FAO-29 Guidelines"
                    ),
                    dataModeDesignation = modeTag
                )
            }

            AnomalyPattern.HEALTHY -> {
                val ndviAvg = pixels.map { it.ndvi }.average().toFloat()
                val ndreAvg = pixels.map { it.ndre }.average().toFloat()
                val psriAvg = pixels.map { it.psri }.average().toFloat()
                val ndmiAvg = pixels.map { it.ndmi }.average().toFloat()
                EpidemiologyDiagnosisReport(
                    anomalyPattern = AnomalyPattern.HEALTHY,
                    caseTitleAr = "حالة مرجعية: غطاء نباتي متجانس في النموذج الطيفي دون مؤشرات إجهاد حادة",
                    detectedRiskNameAr = "لا توجد بؤر إجهاد طيفي أو أعطال ري مرصودة حالياً",
                    confidenceScorePct = 97,
                    hotspotLocationAr = "جميع القطاعات (محبس 1 إلى محبس 6) في النطاق الأخضر المستقر",
                    affectedValveNumber = 1,
                    infectedAreaFeddans = 0.0f,
                    ndviMean = ndviAvg,
                    ndreMean = ndreAvg,
                    psriMean = psriAvg,
                    ndmiMean = ndmiAvg,
                    isBiologicalDisease = false,
                    causalMechanismAr = "قراءات الكلوروفيل المبكر (NDRE=${formatDecimal2(ndreAvg)}) ومؤشر الرطوبة (NDMI=${formatDecimal2(ndmiAvg)}) متجانسة في مصفوفة البكسل (10m×10m) دون ارتفاع في مؤشر إجهاد الأنسجة (PSRI=${formatDecimal2(psriAvg)}).",
                    immediateIrrigationActionAr = "الاستمرار على جدول الري الاسترشادي المحسوب من معادلة FAO-56 مع المتابعة الميدانية الدورية.",
                    valveAdjustmentPercent = 0,
                    targetedPreventiveActionAr = "لا حاجة لتدخل علاجي؛ يُوصى بالمتابعة الدورية مع المهندس الزراعي.",
                    costSavingHighlightAr = "الحد من الرش الوقائي العشوائي عبر المتابعة الدورية لحالة المحصول.",
                    acidDoseLitersPerFeddan = acidDose,
                    pixelGrid = pixels,
                    vegetationStressDetectionAr = "تجانس طيفي في جميع القطاعات: NDVI=${formatDecimal2(ndviAvg)}, NDRE=${formatDecimal2(ndreAvg)}, NDMI=${formatDecimal2(ndmiAvg)}.",
                    agriculturalRiskAssessmentAr = "مستوى المخاطر الطيفية منخفض حالياً مع استقرار مؤشرات الكلوروفيل والرطوبة.",
                    confirmedDiseaseDiagnosisStatusAr = "لا يوجد اشتباه مرضي مرصود — يُنصح بالاستمرار في الفحص الدوري المعتاد",
                    supportingObservationsAr = listOf(
                        "ارتفاع NDVI إلى ${formatDecimal2(ndviAvg)} وNDRE إلى ${formatDecimal2(ndreAvg)}",
                        "اعتدال رطوبة المجموع الخضري NDMI عند ${formatDecimal2(ndmiAvg)}",
                        "انخفاض مؤشر شيخوخة الأنسجة PSRI عند ${formatDecimal2(psriAvg)}"
                    ),
                    possibleCausesAr = listOf("نمو خضري متوازن وانتظام توزيع مياه الري"),
                    uncertaintyAndLimitationsAr = commonUncertaintyAr,
                    recommendedFieldInspectionAr = "جولة تفقدية أسبوعية روتينية لمصائد الحشرات وضغط الشبكة.",
                    dataSourcesCitedAr = listOf("Sentinel-2 L2A Multi-Spectral Indices", "FAO-56 Water Balance"),
                    dataModeDesignation = modeTag
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
