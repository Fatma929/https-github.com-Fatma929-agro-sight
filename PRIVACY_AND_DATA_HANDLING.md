# سياسة الخصوصية وحماية البيانات (Privacy & Data Handling Note)

يوثق هذا الملف فئات البيانات التي يعالجها تطبيق **أجرو سايت (AgroSight)**، أماكن تخزينها، الضوابط الأمنية المطبقة لحماية خصوصية المزارعين، وحدود الاحتفاظ بالبيانات وحذفها، بما يتطابق 100% مع الكود المصدري وقواعد أمان `firestore.rules`.

---

## 1. فئات البيانات التي يتم جمعها ومعالجتها (Categories of Data Collected)

| فئة البيانات | أمثلة الحقول | الغرض من المعالجة | مكان التخزين الفعلي |
| :--- | :--- | :--- | :--- |
| **معرف المصادقة والاسم الزراعي** | معرف Firebase (`userId` / `uid`)، اسم المزارع (`farmerName`)، الدور (`role`)، حالة الحساب (`accountStatus`) | مصادقة المستخدم وعزل مساحة العمل الخاصة به وإدارة الصلاحيات (RBAC) | محلياً (`SharedPreferences`) وفي سحابة Firebase Firestore (`/users/{uid}`) عند تسجيل الدخول بحساب Google |
| **بيانات التواصل والرقم القومي (بيانات شخصية مفصولة)** | رقم الهاتف/واتساب (`whatsappPhone`)، البريد الإلكتروني (`gmailAddress`)، الرقم القومي المصري ذو الـ 14 رقماً (`nationalId`) | التحقق المحلي من الهوية وتجهيز روابط المراسلة المباشرة (`https://wa.me/`) على جهاز المستخدم | **محلياً على الجهاز فقط** (`agrosight_multi_tenant_db`). **لا يتم أبداً رفع الرقم القومي (`nationalId`) أو رقم الهاتف (`whatsappPhone`) أو البريد (`gmailAddress`) إلى وثيقة `/users/{uid}` في سحابة Firestore**، وتمنع قواعد `firestore.rules` أي محاولة لإرسال هذه الحقول |
| **البيانات الزراعية والجغرافية للمزرعة** | اسم الحقل (`fieldName`)، المنطقة (`region`)، إحداثيات الحقل (`latitude`, `longitude`)، المساحة بالفدان (`areaFeddans`)، المحصول (`cropType`)، حموضة وملوحة التربة والمياه (`soilPh`, `soilEce`, `waterEcw`)، مؤشرات القمر الصناعي (`ndviMean`, `ndreMean`, `psriMean`, `ndmiMean`, `detectedRiskName`) | حساب البخر-نتح المرجعي (`ET₀`) من Open-Meteo، جلب مشاهد القمر الصناعي Sentinel-2، وحساب جدول الري لـ 7 أيام | محلياً، وفي وثيقة `/users/{uid}` في Firestore للمستخدم المسجل، وفي جدول التدقيق المحلي Room SQLite (`agrosight_sql_audit.db`) |
| **سجلات الأسئلة الزراعية الذكية** | الرسالة النصية والرد الإرشادي والمصادر (`messageText`, `groundingSources`, `channel`, `role`) | عرض سجل الاستشارات الزراعية الخاصة بالمستخدم فقط | محلياً، وفي المجموعة الفرعية `/users/{uid}/chat_logs/{logId}` المعزولة للمالك فقط (`isOwner(userId)`) أو مدير النظام العام (`isSuperAdmin()`) |
| **المدخلات الصوتية (`RECORD_AUDIO`)** | صوت المستخدم أثناء الضغط على زر الميكروفون | تحويل الكلام العربي إلى نص فوري عبر `SpeechRecognizer` للنظام | **عابرة في الذاكرة فقط (Transient)**؛ لا يسجل التطبيق أو يخزن أي ملفات صوتية خام |

---

## 2. الضوابط الأمنية وحماية الخصوصية المطبقة (Security & Privacy Controls)

### أ. فصل بيانات الهوية الحساسة ومنع توليد بيانات وهمية (Data Minimization & Zero Fabrication)
- يلتزم `AgroSightFirestoreRepository.kt` بعدم رفع الرقم القومي (`nationalId`) أو رقم الهاتف (`whatsappPhone`) أو البريد الإلكتروني (`gmailAddress`) إلى وثائق `/users/{userId}` في Firestore.
- لا يقوم التطبيق أبداً بتوليد أرقام قومية وهمية مكوّنة من أصفار (`00000000000000`) أو إدراج أرقام هواتف أو عناوين بريد افتراضية عند غيابها.
- تتحقق قواعد `firestore.rules` عبر `incoming().keys().hasOnly([...])` من رفض أي وثيقة تحتوي على `nationalId` أو حقول شخصية غير مصرح بها.

### ب. صلاحيات مبنية على مبدأ أقل امتياز (Least-Privilege RBAC)
- **`FARMER`**: يقرأ ويعدل مساحة عمله الزراعية فقط (`/users/{userId}`) وسجلات محادثاته (`/users/{userId}/chat_logs/{logId}`)، ولا يمكنه قراءة بيانات أي مزارع آخر أو ترقية صلاحيته.
- **`AGRONOMIST_ADMIN`**: يمكنه قراءة البيانات الزراعية والطيفيه للحقول وتحديث المؤشرات الإرشادية (`soilPh`, `soilEce`, `waterEcw`, `ndviMean`, `ndreMean`, `psriMean`, `ndmiMean`, `detectedRiskName`)، ولكن **تمنعه قواعد `firestore.rules`** من تغيير أدوار المستخدمين (`role`)، أو حالة الحساب (`accountStatus`)، أو حذف حسابات المزارعين، أو الاطلاع على سجلات المحادثات الخاصة (`chat_logs`)، أو تعديل سجل المشرفين (`/admins`).
- **`SUPER_ADMIN`**: يقتصر على المالك الموثق (`email_verified == true`) أو المشرفين المعينين في `/admins/{uid}` بصلاحية `SUPER_ADMIN` لإدارة الأدوار وحالات الحسابات.

### ج. تنقيح السجلات ومنع النسخ الاحتياطي غير المشفر (PII Log Redaction & Backup Hardening)
- تستخدم `handleFirestoreError` دوال `redactUidForLog` و `redactPathForLog` لمنع طباعة أي بيانات شخصية أو معرفات كاملة في `Logcat`.
- تم ضبط `android:allowBackup="false"` في `AndroidManifest.xml` واستبعاد ملفات قواعد البيانات المحلية في `backup_rules.xml` و `data_extraction_rules.xml`.

---

## 3. الاحتفاظ بالبيانات وحذف الحساب (Data Retention & Deletion Workflows)

- **حذف البيانات السحابية (`deleteCloudWorkspace`)**: يوفر `AgroSightFirestoreRepository.deleteCloudWorkspace(userId)` إمكانية حذف سجلات `chat_logs` ووثيقة `/users/{userId}` بالكامل من سحابة Firestore للمستخدم المالك (`allow delete: if isOwner(userId) || isSuperAdmin()`).
- **حذف البيانات المحلية**: يمكن للمستخدم حذف سجلات التدقيق المحلية في Room SQLite (`deleteRoomSqlAudit`)، ويمكن لمدير النظام حذف الحسابات المحلية من بوابة الإدارة (`adminDeleteUserAccount`).
