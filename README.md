# أجرو سايت (AgroSight) — منصة الري الدقيق، الرصد الطيفي للأمراض، وحسابات المزارعين

**أجرو سايت (AgroSight)** هو تطبيق أندرويد باللغة العربية (RTL) مبني باستخدام **Kotlin** و **Jetpack Compose (Material 3)** لدعم القرار الزراعي وإدارة جدولة الري لـ 7 أيام، تشخيص إجهاد المحاصيل عبر مؤشرات الأقمار الصناعية (Sentinel-2: `NDVI`, `NDRE`, `PSRI`, `NDMI`)، تحليل ملوحة وحموضة التربة (`pH` & `EC`)، وإدارة حسابات المزارعين مع بوابة إدارة مركزية بنظام الصلاحيات (**RBAC Admin Portal**).

---

## 1. المعمارية وتقسيم الطبقات (Modular Architecture)

يتبع التطبيق معمارية **MVVM + Domain Use-Case + Repository Interfaces**:

- **`com.example.model`**:
  - نماذج المحاصيل المصرية (`CropSpec`، منحنيات `Kc` حسب FAO-56)، قوام التربة (`SoilTexture`)، أنظمة الري (`IrrigationSystemType`)، ونظام الصلاحيات (`UserRole`: `SUPER_ADMIN`, `AGRONOMIST_ADMIN`, `FARMER` و`AccountStatus`).
- **`com.example.data`**:
  - **`AgroSightServiceContainer.kt`**: حاوية حقن الاعتماديات الخفيفة (`AgroSightDependencies`) وحالة الاستخدام (`RecalculateAgronomicDerivedStateUseCase`) التي تفصل الحسابات الهيدروليكية والزراعية عن `AgroSightViewModel`.
  - **`MeteoForecastDataSource` / `OpenMeteoClient.kt`**: الاتصال بواجهة Open-Meteo FAO-56 Penman-Monteith لجلب توقعات البخر-نتح (`ET₀`) لـ 7 أيام مع وضع احتياطي واضح (`وضع تقديري احتياطي Offline Baseline`).
  - **`SatelliteTelemetryDataSource` / `OpenSourceSatelliteClient.kt`**: الاتصال بـ Element84 Earth Search STAC API (بيانات مشاهد Sentinel-2 L2A) وNASA POWER Agroclimatology API، مع اشتقاق المعادلات العلمية الموثقة (`computeScientificFormulaAudit`).
  - **`AgroAiCopilotDataSource` / `GeminiAgroService.kt`**: المستشار الذكي متعدد الجولات مع تمرير مفتاح API في ترويسة HTTP (`x-goog-api-key`) وإضافة تنويه السلامة الزراعية الإلزامي.
  - **`CloudWorkspaceRepository` / `AgroSightFirestoreRepository.kt`**: مصادقة Google عبر Jetpack `CredentialManager` ومزامنة مساحة عمل المزارع وسجلات المحادثة مع Firebase Firestore مع تنقيح البيانات الشخصية (PII Redaction) في السجلات.
  - **`DatabaseService.kt` & `AgroSightRoomDatabase.kt`**: إدارة الحسابات متعددة المستأجرين محلياً وسجلات التدقيق في SQLite (Room).
- **`com.example.ui`**:
  - **`components/Recharts7DayIrrigationChartCard.kt`**: مكون **Recharts** التفاعلي لمقارنة جدول الري المقترح للأيام السبعة القادمة (م³/فدان/يوم) مع الاحتياج المائي الصافي للمحصول (`ETc`) ومنحنى البخر-نتح المرجعي (`ET₀`)، بالإضافة إلى لافتة مصدر البيانات وإخلاء المسؤولية الإرشادي (`AgronomicSafetyAndDataProvenanceBanner`).
  - **`screens/`**: شاشات مركزة ومفصولة (`SatelliteDiseaseRadarScreen`, `IrrigationScheduleScreen`, `FieldZonesScreen`, `FarmerWhatsAppCrmScreen`, `AiDigitalTwinHubScreen`, `UserAccountAndAuthScreen`, `AdminRbacPortalScreen`).

---

## 2. المتطلبات الأساسية وإعداد بيئة العمل (Prerequisites & Setup)

- **JDK 17**
- **Android SDK (Compile SDK 36 / Min SDK 26)**
- **Node.js 20+** و **Firebase CLI** (لتشغيل اختبارات قواعد أمان Firestore على المحاكي المحلي)

### إعداد المتغيرات السرية (`.env` / Secrets Panel)
1. انسخ ملف `.env.example` إلى `.env` محلياً (أو أضف المفاتيح في لوحة **Secrets** داخل Google AI Studio):
   ```properties
   GEMINI_API_KEY=
   ```
2. **ملاحظة أمنية حول المفاتيح في تطبيقات العميل (Client-Side Keys)**:
   - أي مفتاح يُحقن عبر `BuildConfig` داخل حزمة APK يمكن استخراجه عبر الهندسة العكسية ولا يُعد سراً محمياً بنسبة 100%.
   - في بيئة الإنتاج النهائية، يُوصى بنقل استدعاءات Gemini إلى **Firebase AI Logic** المحمي بـ **Firebase App Check (Play Integrity)** أو عبر خادم وسيط (Cloud Functions / Cloud Run Proxy).
   - تم إزالة مفاتيح Meta WhatsApp Cloud API بالكامل بناءً على متطلبات الخصوصية واستبدالها بروابط واتساب المباشرة (`https://wa.me/`) التي يرسلها المستخدم بنفسه.

---

## 3. أوامر البناء والاختبار (Build & Test Commands)

> **تنبيه بيئة البناء**: استخدم أمر `gradle` مباشرةً (وليس `./gradlew`).

- **تشغيل اختبارات الوحدات واختبارات Robolectric:**
  ```bash
  gradle :app:testDebugUnitTest
  ```
- **بناء نسخة التطوير (Debug APK):**
  ```bash
  gradle :app:assembleDebug
  ```
- **فحص ترجمة نسخة الإطلاق وتكامل R8 (Release Compilation):**
  ```bash
  gradle :app:compileReleaseKotlin
  ```
- **تشغيل اختبارات قواعد أمان Firestore (Firebase Emulator):**
  ```bash
  FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
  ```

---

## 4. إعداد Firebase وقواعد الأمان (Firestore RBAC & Security Rules)

- **المشروع المربوط**: `gen-lang-client-0569917869`
- **قاعدة بيانات Firestore**: `ai-studio-android-agrosigh-f32d0869-53c9-4336-bd00-112fc0ce2f9f`
- **حساب المدير العام المبدئي (Bootstrapped Super Admin)**: `elzahraafatma288@gmail.com` (يتطلب `email_verified == true` في رمز مصادقة Google ID Token).
- **ضوابط الأمان المطبقة في `firestore.rules`**:
  1. رفض افتراضي (`allow read, write: if false;`) لأي مسار غير مصرح به.
  2. منع التصعيد الذاتي للصلاحيات (Anti-Privilege Escalation): المستخدم العادي لا يمكنه إنشاء وثيقته بدور `SUPER_ADMIN` أو `AGRONOMIST_ADMIN`، ولا يمكنه تعديل حقلي `role` أو `accountStatus` في التحديثات الجزئية.
  3. عزل تام لوثائق `/users/{userId}` ومجموعة `/users/{userId}/chatLogs/{logId}` بحيث لا يقرأها أو يعدلها إلا مالكها أو المشرف الموثق.

---

## 5. النزاهة العلمية والسلامة الزراعية (Scientific Integrity & Limitations)

1. **التمييز بين البيانات الحية والنماذج التقديرية**:
   - يعرض التطبيق شارة حالة صريحة توضح ما إذا كانت بيانات الطقس (`ET₀`) واردة مباشرة من **Open-Meteo FAO-56 API** أو من **النموذج الفيزيائي الاحتياطي (Offline Baseline)**.
   - خريطة البكسل `10m×10m` في رادار الأمراض تعتمد على **نمذجة مكانية طيفية استرشادية (Spatial Spectral Simulation)** مقترنة ببيانات المشهد الفضائي الفعلي من كاتالوج STAC (`Sentinel-2 L2A`) ومرئيات القمر البصرية (`WMTS`)، وليست بديلاً عن التحليل المعملي لعينات النبات والتربة.
2. **إخلاء المسؤولية الزراعية**:
   - جميع توصيات خفض/زيادة الري، غسيل الأملاح، حقن الأحماض (`Phosphoric Acid`)، أو الرش البؤري بالمبيدات هي **مؤشرات استرشادية لدعم القرار**، ويجب مراجعتها ومطابقتها ميدانياً بواسطة **مهندس زراعي مختص** قبل التطبيق الفعلي.

---

## 6. خطوات الإطلاق والإعدادات الخارجية المتبقية (Release & External Steps)

- **تقليص وتشويش الكود (R8 Shrinking & Obfuscation)**: تم تفعيل `isMinifyEnabled = true` و `isShrinkResources = true` في `app/build.gradle.kts` مع قواعد الحفاظ اللازمة في `app/proguard-rules.pro`.
- **توقيع نسخة الإطلاق (Release Signing)**: يعتمد ملف `app/build.gradle.kts` على متغيرات البيئة (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) لتوقيع نسخة الإطلاق في بيئة CI/CD، بينما تظل إعدادات `signingConfigs` الخاصة بـ AI Studio محفوظة دون تغيير.
- **Firebase App Check**: عند النشر على Google Play، يُوصى بتفعيل **Play Integrity** في لوحة تحكم Firebase Console لمنع الاستخدام غير المصرح به لواجهات السحابة.
