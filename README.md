# أجرو سايت (AgroSight) — منصة الذكاء الزراعي الفضائي، الري الدقيق (FAO-56)، والرصد الطيفي (Sentinel-2 L2A)

**أجرو سايت (AgroSight)** هو تطبيق أندرويد باللغة العربية (RTL) مبني باستخدام **Kotlin** و **Jetpack Compose (Material 3)** مع خدمة معالجة طيفية خلفية بلغة **Python**، مصمم لإدارة المزارع، جدولة الري لـ 7 أيام وفق معادلة **FAO-56 Penman-Monteith**، الرصد الطيفي لإجهاد المحاصيل عبر نطاقات القمر الصناعي **Sentinel-2 Level-2A** (`NDVI`, `NDRE`, `NDMI`, `PSRI`) مع قناع السحب والظلال (`SCL`)، تحليل كيمياء التربة (`pH` & `ECe`/`ECw`)، وإدارة الصلاحيات متعددة الأدوار (**RBAC Admin Portal**).

---

## 1. المعمارية الهندسية وتقسيم الطبقات (Modular Architecture)

يتبع التطبيق معمارية **MVVM + Domain Use-Case + Repository Interfaces + Geospatial Backend Service**:

- **`com.example.model`**:
  - نماذج المحاصيل المصرية (`CropSpec`، منحنيات `Kc` حسب FAO-56)، قوام التربة (`SoilTexture`)، أنظمة الري (`IrrigationSystemType`)، تحليلات وفر المياه (`WaterSavingsAnalyticsSummary` التي تميز بين الوفر النموذجي المحتمل والوفر الفعلي المقاس من السجلات)، مصدر عينات التربة (`SoilDataProvenance`: معمل معتمد، جهاز حقلي، أو تقدير إقليمي)، ونظام الصلاحيات (`UserRole`: `SUPER_ADMIN`, `AGRONOMIST_ADMIN`, `FARMER` و`AccountStatus`).
- **`com.example.data`**:
  - **`AgroSightServiceContainer.kt`**: حاوية حقن الاعتماديات الخفيفة (`AgroSightDependencies`) وحالة الاستخدام (`RecalculateAgronomicDerivedStateUseCase`) التي تفصل الحسابات الهيدروليكية والزراعية عن `AgroSightViewModel`.
  - **`MeteoForecastDataSource` / `OpenMeteoClient.kt`**: الاتصال بواجهة Open-Meteo FAO-56 Penman-Monteith لجلب توقعات البخر-نتح (`ET₀`) لـ 7 أيام مع قياس زمن الاستجابة الفعلي وكود HTTP الحقيقي، ودعم الذاكرة المؤقتة (`CACHED`) أو وضع النموذج التقديري (`DEMO_ESTIMATED`).
  - **`SatelliteTelemetryDataSource` / `OpenSourceSatelliteClient.kt`**:
    - الاتصال بـ **Element84 Earth Search STAC API** (`sentinel-2-l2a`) للبحث عن المشاهد الفضائية حسب الإحداثيات أو مضلع الحقل (GeoJSON Polygon) واستخراج روابط النطاقات الطيفية الفعلية (`B02`, `B04`, `B05`, `B06`, `B08`, `B8A`, `B11`, `SCL`).
    - التمييز الدقيق لحالة الاستحواذ (`StacAcquisitionStatus`): مشهد مكتمل النطاقات (`VALID_SCENE_WITH_BAND_ASSETS`)، لا توجد مشاهد مطابقة (`NO_MATCHING_SCENES`)، نقص النطاقات الطيفية (`MISSING_OR_INACCESSIBLE_ASSETS`)، خطأ هندسي (`INVALID_GEOMETRY`)، أو فشل الشبكة (`NETWORK_OR_HTTP_FAILURE`).
    - **`Sentinel2BandMathEngine`**: محرك الحسابات الطيفية الفعلية لتحويل القيم الرقمية إلى انعكاسية سطحية (`DN * 0.0001`)، تطبيق قناع السحب والظلال (`SCL in {0, 1, 3, 8, 9, 10, 11}`)، حساب `NDVI` و`NDRE` و`NDMI` و`PSRI` لكل بكسل مع الحماية من القسمة على صفر وبيانات `NoData`، وتوليد مضلعات الشذوذ بصيغة `GeoJSON`، بالإضافة إلى التحليل الزمني متعدد المشاهد (`TemporalChangeAnalysis`) الذي يستبعد المشاهد الغائمة (`>35%`) من إنذارات تدهور المحصول.
  - **`SatelliteEpidemiologyEngine.kt`**:
    - فصل علمي صارم بين 3 مستويات:
      1. **رصد الإجهاد النباتي الطيفي (`vegetationStressDetectionAr`)**
      2. **تقييم المخاطر الزراعية متعدد العوامل (`agriculturalRiskAssessmentAr`)**
      3. **حالة التشخيص المرضي المؤكد (`confirmedDiseaseDiagnosisStatusAr`)** (التي تتطلب فحصاً حقلياً أو معملياً قبل تأكيد الإصابة البيولوجية).
    - يتضمن واجهة `CropDiseaseModelInferencePort` وسجل المطابقة الحقلية `FieldInspectionValidationRecord`.
  - **`CloudWorkspaceRepository` / `AgroSightFirestoreRepository.kt`**:
    - مصادقة Google عبر Jetpack `CredentialManager`.
    - حماية صارمة للبيانات الشخصية (Phase 10): لا يتم رفع الرقم القومي (`nationalId`) أو رقم الهاتف (`whatsappPhone`) أو البريد الإلكتروني (`gmailAddress`) إلى وثائق `/users/{userId}` في Firestore، ولا يتم توليد أرقام قومية وهمية مكملة بالأصفار أو أرقام هواتف افتراضية، مع توفير دالة `deleteCloudWorkspace` لحذف بيانات السحابة بالكامل.
- **`backend/raster_service.py`**:
  - خدمة Python للمعالجة المكانية والطيفية لمشاهد Sentinel-2 L2A (تحويل الانعكاسية، قناع السحب `SCL`، حساب `NDVI`/`NDRE`/`NDMI`/`PSRI`، إحصاءات مضلع الحقل، التحليل الزمني، وتوليد مضلعات `GeoJSON`).

---

## 2. المعادلات العلمية الطيفية والهيدروليكية المطبقة

1. **مؤشرات Sentinel-2 L2A الطيفية**:
   - $$\text{NDVI} = \frac{B08 - B04}{B08 + B04}$$
   - $$\text{NDRE} = \frac{B8A - B05}{B8A + B05}$$
   - $$\text{NDMI} = \frac{B08 - B11}{B08 + B11}$$
   - $$\text{PSRI} = \frac{B04 - B02}{B06}$$
2. **معادلة FAO-56 Penman-Monteith للبخر-نتح المرجعي ($ET_0$)**:
   - $$ET_0 = \frac{0.408 \Delta (R_n - G) + \gamma \frac{900}{T + 273} u_2 (e_s - e_a)}{\Delta + \gamma (1 + 0.34 u_2)}$$
   - تحويل سرعة الرياح من ارتفاع 10م إلى 2م وفق معادلة FAO-56 Eq. 47: $u_2 = u_z \frac{4.87}{\ln(67.8 z - 5.42)}$.
3. **احتياج الغسيل الملحي (Rhoades 1974 / Ayers & Westcot FAO-29)**:
   - $$LR = \frac{EC_w}{5 \cdot EC_{e,\text{threshold}} - EC_w}$$

---

## 3. أوامر البناء والاختبار (Build & Test Commands)

> **تنبيه بيئة البناء**: استخدم أمر `gradle` مباشرةً (وليس `./gradlew`).

- **تشغيل اختبارات الوحدات واختبارات Robolectric لأندرويد:**
  ```bash
  gradle :app:testDebugUnitTest
  ```
- **تشغيل اختبارات خدمة المعالجة الطيفية (Python Backend Tests):**
  ```bash
  python3 -m unittest discover -s backend -p "test_*.py"
  ```
- **فحص ترجمة نسخة الإطلاق وتكامل R8 (Release Compilation):**
  ```bash
  gradle :app:compileReleaseKotlin
  ```
- **تشغيل اختبارات قواعد أمان Firestore (Firebase Emulator):**
  ```bash
  npx firebase emulators:exec --only firestore "node --test firestore.test.js"
  ```

---

## 4. قواعد أمان Firestore ونظام الصلاحيات الأقل امتيازاً (Least-Privilege RBAC)

- **`FARMER`**: يقرأ ويكتب وثيقة مساحة عمله الزراعية (`/users/{userId}`) وسجلات محادثاته (`/users/{userId}/chatLogs/{logId}`) ويستطيع حذف وثيقته السحابية، دون إمكانية تعديل `role` أو `accountStatus` أو رفع حقول PII (`nationalId`, `whatsappPhone`, `gmailAddress`).
- **`AGRONOMIST_ADMIN`**: يقرأ بيانات الحقول الزراعية للمزارعين ويستطيع تحديث المؤشرات التشخيصية والتوصيات الزراعية فقط، ولا يملك صلاحية تغيير أدوار المستخدمين (`role`) أو حالة حساباتهم (`accountStatus`) أو حذف المستخدمين أو الاطلاع على سجلات المحادثات الخاصة (`chatLogs`).
- **`SUPER_ADMIN`**: يمتلك صلاحية إدارة الأدوار وحالة الحسابات والمراجعة الشاملة.
