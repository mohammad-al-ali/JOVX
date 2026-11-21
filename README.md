# JOVX Backend

باكند JOVX: Java 21 وSpring Boot وSpring Modulith وSpring Security JWT وPostgreSQL. هذا المستودع يحتوي الباكند واختباراته وترحيلات مخططه فقط.

المستودع: https://github.com/mohammad-al-ali/JOVX

## البنية

- `src/main/java/com/jovx/api/security`: المستخدمون وPIN والدخول والجلسات والأدوار والصلاحيات.
- `src/main/java/com/jovx/api/partners`: الشركاء والمنشآت والفروع وموظفو الشركاء وقوائم التسجيل، بتنظيم controller/service/repository/model/dto/config.
- `src/main/java/com/jovx/api/releases`: إصدارات التطبيقات وسياسة التحديث من داخل التطبيق.
- `src/main/resources/db/postgresql`: ترحيلات Flyway V1 إلى V8؛ لا تُعدّل ترحيلًا سبق تطبيقه. V8 تستبدل المنشآت والفروع بالمتاجر، وتعيد تسمية صلاحية الإدارة إلى `partner.store.manage`.
- `src/test`: اختبارات السلوك والتكامل وحدود الوحدات.

[شرح البنية وتدفق إنشاء الشريك](docs/backend-structure-ar.md).

## التشغيل

افتح `pom.xml` في IntelliJ باستخدام Java 21، ثم شغّل `com.jovx.api.JovxApiApplication`. أضف متغيرات قاعدة التطوير المخصصة للباكند:

```text
DB_URL=jdbc:postgresql://<host>:<port>/<database>
DB_USERNAME=<database-user>
DB_PASSWORD=<database-password>
```

اختياري: `DB_POOL_SIZE` لحجم مجمّع الاتصالات (الافتراضي 4، مناسب لحدود Supabase المجانية).

لا تحفظ القيم السرية في Git. الإعداد الافتراضي `local-postgres` يدعم عنوان PostgreSQL محليًا أو سحابيًا.

**مفاتيح توقيع الدخول (JWT):** الخادم يوقّع توكن الدخول بمفتاح خاص `private.pem` ويتحقق منه بمفتاح عام `public.pem`. الملفان لا يُرفعان إلى Git. افتراضيًا يُقرآن من مجلد `.local/identity-keys` في جهاز التطوير الأصلي؛ في أي جهاز أو خادم آخر أنشئ زوجًا جديدًا وحدّد مكانه بالمتغيرين `JWT_PUBLIC_KEY_LOCATION` و`JWT_PRIVATE_KEY_LOCATION`:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
```

تغيير المفاتيح يُبطل التوكنات الصادرة سابقًا فقط؛ يكفي أن يسجّل المستخدمون الدخول مجددًا.

## النشر على Render (Docker)

1. أنشئ Web Service من هذا المستودع، الفرع `main`، بنوع **Docker** (يُستخدم `Dockerfile` في الجذر؛ البناء بصورة Maven الرسمية دون تنزيل wrapper).
2. في **Secret Files** أضف `public.pem` و`private.pem` (زوج خاص بـRender، لا تعيد استخدام مفاتيح جهازك).
3. في **Environment** أضف:

```text
DB_URL=jdbc:postgresql://<supabase-session-pooler-host>:5432/postgres?sslmode=require
DB_USERNAME=<database-user>
DB_PASSWORD=<database-password>
JWT_PUBLIC_KEY_LOCATION=file:/etc/secrets/public.pem
JWT_PRIVATE_KEY_LOCATION=file:/etc/secrets/private.pem
```

4. اضبط **Health Check Path** على `/actuator/health`.

الحاوية تستمع على `0.0.0.0` وعلى المنفذ الذي يحدده Render عبر `PORT`. الترحيلات لا تعمل تلقائيًا عند التشغيل؛ طبّقها على القاعدة قبل نشر إصدار يحتاجها. بناء الصورة لا يشغّل الاختبارات؛ شغّل `verify` محليًا قبل الدفع. اختياريًا: `JWT_ISSUER` لتغيير جهة الإصدار.

على قاعدة جديدة مخصصة لهذا الباكند، شغّل ترحيلات Flyway قبل التشغيل. يستخدم Maven plugin متغيرات منفصلة بصلاحيات الترحيل:

```text
JOVX_MIGRATION_URL
JOVX_MIGRATION_USER
JOVX_MIGRATION_PASSWORD
```

ثم نفّذ `./mvnw flyway:migrate`، أو `./mvnw.cmd flyway:migrate` في Windows. لا تُشغّل ذلك على قاعدة الإنتاج الأصلية. حساب Super Admin وبيانات الاختبار والمفاتيح لا تُنشأ تلقائيًا عند التشغيل ولا تُنشر هنا.

المنفذ الافتراضي `18080` على loopback، وفحص التشغيل: `http://127.0.0.1:18080/actuator/health`. استجابة health لا تغني عن اختبار الدخول واتصال قاعدة البيانات.

## واجهات الشريك بعد التسجيل

| العملية | المسار | من يملكها |
|---|---|---|
| قائمة الشركاء (صفحات: `limit` افتراضيًا 20 وحدها الأقصى 100، و`offset`، والبحث `q` بالاسم أو الهاتف أو اسم المتجر) وتفاصيل شريك | `GET /api/v1/admin/partners`، `GET /api/v1/admin/partners/{id}` | Super Admin أو Admin بـ`partner.create` أو `partner.branch.manage` |
| الحد الأدنى للخصم | `PUT /api/v1/admin/partners/{id}/discount` | Super Admin أو `partner.create` فقط |
| متاجر الشريك (إدارة): إضافة، تعديل، إيقاف/إعادة تفعيل، مواعيد العمل | `POST .../{id}/stores`، `PUT .../{id}/stores/{sid}`، `PUT .../{id}/stores/{sid}/status`، `PUT .../{id}/stores/{sid}/hours` | Super Admin أو `partner.store.manage` |
| ملف الشريك ومتاجره | `GET /api/v1/partner/profile`، `POST /api/v1/partner/stores`، `PUT /api/v1/partner/stores/{sid}`، `PUT /api/v1/partner/stores/{sid}/status`، `PUT /api/v1/partner/stores/{sid}/hours` | الشريك المالك |
| الموظفون | `GET /api/v1/partner/employees`، `PUT .../employees/{id}/status`، `PUT .../employees/{id}/pin`، `PUT .../employees/{id}/stores` | الشريك المالك (`employee.suspend`/`reactivate`/`pin.change`/`create`) |
| تغيير PIN الشريك | `PUT /api/v1/account/pin` (`currentPin`, `newPin`) | الشريك فقط؛ ينهي كل جلساته |
| الموقع الافتراضي (قراءة) | `GET /api/v1/public/location-defaults` (204 إن لم يُحدد)، ويُضاف أيضًا كحقل `defaults` إلى `GET /api/v1/reference-data` | عام |
| تغيير الموقع الافتراضي | `PUT /api/v1/admin/reference-defaults` (`cityId`, `addressId`؛ الحي يجب أن يتبع المدينة) | Super Admin أو `partner.create` |
| دليل المتاجر العام حسب المدينة | `GET /api/v1/public/partners?cityId=` (اختياري؛ المتاجر النشطة لشركاء نشطين في المدينة، مع الموقع والمواعيد) | عام |

يُسمح بإيقاف أي متجر؛ الموظف المكلف بمتاجر موقوفة فقط لا يستطيع الدخول، والشريك النشط يدخل ولو أُوقفت كل متاجره. المواعيد بتوقيت دمشق، والإغلاق قبل الفتح يعني بعد منتصف الليل. إيقاف الموظف أو تغيير PIN له ينهي جلساته، وإعادة التفعيل تتطلب دخولًا جديدًا. متجر يضاف لشريك معلق يبقى معلقًا ويُفعّل معه. هاتف التواصل مستقل عن هاتف الدخول. إعادة ضبط PIN الشريك من الإدارة مؤجلة.

## إصدارات التطبيقات والتحديث من داخل التطبيق

البناء التلقائي (CI) ينشر لكل تطبيق ملف `version.json` على العنوان `{RELEASES_MANIFEST_BASE_URL}/{admin|member}/android/version.json`، ومعه ملف APK تحت `RELEASES_DOWNLOAD_URL_PREFIX`. الخادم لا يقرأ إلا هذا المصدر المحدد في الإعدادات، ويرفض أي رابط تنزيل خارج البادئة المحددة.

| العملية | المسار | من يملكها |
|---|---|---|
| فحص التحديث (عام) | `GET /api/v1/app-updates?app=member&platform=android&build=123` | أي تطبيق، دون دخول |
| الإصدارات والحد الأدنى | `GET /api/v1/admin/app-releases/{app}` | Super Admin أو `app.release.manage` |
| آخر بناء من version.json | `GET /api/v1/admin/app-releases/{app}/manifest` | نفس الصلاحية |
| استيراده كمسودة | `POST /api/v1/admin/app-releases/{app}/import` | نفس الصلاحية |
| السياسة (اختياري، إجباري، أو اختياري حتى تاريخ) | `PUT /api/v1/admin/app-releases/releases/{id}/policy` | نفس الصلاحية |
| نشر أو إيقاف إصدار | `POST .../releases/{id}/publish`، `POST .../releases/{id}/withdraw` | نفس الصلاحية |
| أقل إصدار مدعوم | `PUT /api/v1/admin/app-releases/{app}/min-supported` | نفس الصلاحية |

الجواب `NONE` أو `OPTIONAL` أو `REQUIRED`، ومعه الرابط وبصمة SHA-256. يصبح التحديث إجباريًا في ثلاث حالات: إذا كان الإصدار أقل من الحد الأدنى المدعوم، أو كان أي إصدار أحدث منشور بوضع إجباري، أو انقضى الموعد النهائي لتحديث اختياري. المسودات والإصدارات الموقوفة لا تُعرض على التطبيقات. وتطبيق V4 لازم قبل نشر هذا الإصدار من الخادم.

## العضوية والنقاط (V5)

| العملية | المسار | من يملكها |
|---|---|---|
| خطط العضوية | `GET/POST /api/v1/admin/membership-plans`، `PUT .../{id}` | Super Admin أو `member.manage` |
| مخزون البطاقات المطبوعة | `GET /api/v1/admin/member-cards`، `POST /api/v1/admin/member-cards/batches` | نفس الصلاحية |
| بيع العضوية (إنشاء العضو وربط البطاقة وتسجيل الدفع) | `POST /api/v1/admin/members` | نفس الصلاحية |
| البحث والتفاصيل | `GET /api/v1/admin/members?q=`، `GET .../{id}` | نفس الصلاحية |
| التجديد | `POST /api/v1/admin/members/{id}/renewals` | نفس الصلاحية |
| إيقاف البطاقة (دون استبدال) | `POST /api/v1/admin/members/{id}/card/suspension` | نفس الصلاحية |
| إعادة تعيين PIN العضو | `PUT /api/v1/admin/members/{id}/pin` | نفس الصلاحية |
| إنشاء الحساب بالبطاقة (أول PIN) | `POST /api/v1/member/claim` | عام، بحدود المحاولات نفسها للدخول |
| بيانات العضو وبطاقته | `GET /api/v1/member/me` | العضو |
| رمز QR لمدة 60 ثانية | `POST /api/v1/member/scan-token` | العضو، بعضوية سارية وبطاقة نشطة |
| النقاط وسجلها | `GET /api/v1/member/points`، `GET .../points/history` | العضو |
| الشركاء والخطط للزائر | `GET /api/v1/public/partners`، `GET /api/v1/public/membership-plans` | عام |

البائع لا يكتب المبلغ: يُسجَّل سعر الخطة وعملتها وقت الدفع. `requestId` يمنع تكرار البيع أو التجديد عند إعادة المحاولة. التجديد يضاف إلى المدة الباقية، أو يبدأ من اليوم إن كانت العضوية منتهية، والأشهر تُحسب بتقويم دمشق. حساب العضو يُنشأ معلقًا دون PIN، ولا يُفعَّل إلا عبر البطاقة والهاتف المطابق. العضو المنتهية عضويته يدخل ويرى رصيده مجمّدًا، دون رمز QR. رمز QR يُخزَّن ملخصه (hash) فقط، وكل رمز جديد يلغي ما قبله.

## نقطة البيع (V12)

| العملية | المسار | من يملكها |
|---|---|---|
| ما يستطيعه البائع ومتاجره | `GET /api/v1/partner/sales/context` | الشريك أو موظفه |
| منتجات الفاتورة (معتمدة وظاهرة، بسعر العضو) | `GET /api/v1/partner/sales/stores/{storeId}/products` | الشريك أو `sale.confirm` |
| مسح العضو (QR أو البطاقة المطبوعة) | `POST /api/v1/partner/sales/scans` | الشريك أو `member.scan` |
| فاتورة منتجات أو بيع حر | `POST /api/v1/partner/sales` (`kind`: `PRODUCTS` أو `FREE`) | الشريك، أو `sale.confirm` للمنتجات و`sale.free` للبيع الحر |
| سجل المبيعات والإيصال | `GET /api/v1/partner/sales`، `GET .../{saleId}` | الشريك كل متاجره؛ `sale.read.branch` متاجره؛ `sale.read.own` مبيعاته |
| لوحة المبيعات (اليوم/7/30 يومًا بتوقيت دمشق) | `GET /api/v1/partner/sales/summary?days=&storeId=` | الشريك، أو `report.read.partner` أو `report.read.branch` |
| مشتريات العضو وتوفيره | `GET /api/v1/member/purchases` | العضو |

البيع معاملة واحدة: خصم النقاط من محفظة الشريك، وإضافتها للعضو، وإنقاص المخزون، والإيصال؛ تنجح معًا أو لا يحدث شيء. رمز QR يُستهلك عند المسح، والمسح الواحد لفاتورة واحدة خلال 5 دقائق وللبائع الذي مسح. رصيد المحفظة الناقص يرفض البيع (`INSUFFICIENT_WALLET_BALANCE`). خصم البيع الحر لا يقل عن الحد الأدنى، ونقاطه لا تقل عن 2% من الإجمالي قبل الخصم (لا حد أعلى سوى رصيد المحفظة). `requestId` يمنع البيع المكرر عند إعادة المحاولة، ونفس `requestId` بمحتوى مختلف يُرفض (`REQUEST_ID_REUSED`). العكس الوحيد هو إلغاء يوافق عليه العضو (V14 أدناه).

## إلغاء الفاتورة بموافقة العضو (V14)

| العملية | المسار | من يملكها |
|---|---|---|
| طلب الإلغاء (خلال 24 ساعة من البيع، سبب اختياري) | `POST /api/v1/partner/sales/{saleId}/cancellation` | الشريك أي فاتورة؛ الموظف ذو `sale.confirm` أو `sale.free` فواتيره فقط (`SALE_NOT_YOURS`) |
| الطلبات المنتظرة | `GET /api/v1/member/cancellations` | العضو |
| القبول أو الرفض (خلال 24 ساعة من الطلب) | `POST /api/v1/member/cancellations/{id}/accept` و`/reject` | العضو صاحب الفاتورة |

القبول معاملة واحدة: تُسحب النقاط من العضو (`INSUFFICIENT_MEMBER_POINTS` إن صرفها، ويبقى الطلب معلّقًا)، وتعود لمحفظة الشريك (`SALE_POINTS_RETURNED`)، ويعود المخزون، وتصير الفاتورة `CANCELLED`. التقارير وأكثر المنتجات وتوفير العضو تستثني الملغاة، والسجل يعرضها. تكرار نفس الجواب آمن. الرموز: `CANCEL_WINDOW_CLOSED`، `CANCEL_ALREADY_PENDING`، `CANCEL_REQUEST_EXPIRED`، `CANCEL_REQUEST_CLOSED`، `SALE_ALREADY_CANCELLED`.

## بحث المنتجات العام (V13)

`GET /api/v1/public/products?q=&categoryId=&cityId=&storeId=&minPrice=&maxPrice=&inStock=&sort=&offset=&limit=` بدون دخول. يعرض المنتجات المعتمدة والمنشورة في المتاجر النشطة لشركاء نشطين فقط. البحث في اسم المنتج ووصفه واسم المتجر، بتطبيع عربي (`jovx_ar_norm`: الهمزات، ى/ي، ة/ه، التشكيل). الترتيب `NEWEST` (افتراضي)، `PRICE_ASC`، `PRICE_DESC` (بسعر العضو)، `SAVING_DESC`، `POINTS_DESC`، `NAME`. الصفحة 20 افتراضيًا و50 حدًا أقصى، مع `total`. القيم غير الصالحة: `INVALID_QUERY`.

## الشريك والموظف والإدارة: إضافات 2026-11-21

| العملية | المسار | من يملكها |
|---|---|---|
| ما يراه الحساب في تطبيق الشريك (المتاجر والصلاحيات) | `GET /api/v1/partner/workspace` | الشريك أو موظفه |
| إعادة تعيين PIN شريك نسيه (تنهي كل جلساته) | `PUT /api/v1/admin/partners/{id}/pin` | SUPER_ADMIN أو `partner.pin.reset` |

## الإشعارات (V15)

| العملية | المسار | من يملكها |
|---|---|---|
| تسجيل الجهاز / إلغاؤه عند الخروج | `PUT /api/v1/devices/token`، `POST /api/v1/devices/token/removal` | العضو أو الشريك |
| صندوق الإشعارات، قراءة واحد، قراءة الكل | `GET /api/v1/notifications`، `POST .../{id}/read`، `POST .../read-all` | العضو أو الشريك |
| تشغيل أنواع الإشعارات وإيقافها | `GET/PUT /api/v1/admin/notification-settings` | SUPER_ADMIN |
| رسالة لشريك أو لكل الشركاء النشطين | `POST /api/v1/admin/partner-messages` | SUPER_ADMIN أو `notification.manage` |

كل إشعار صف في الصندوق يُكتب في معاملة العملية نفسها، ونسخة Push تنتظر في `notification_outbox` وتُرسل بعد الالتزام (كل 10 ثوانٍ، `SKIP LOCKED`، إعادة محاولة حتى 6 مرات). بلا `FIREBASE_SERVICE_ACCOUNT_BASE64` تبقى في الصندوق فقط (`SKIPPED`). موظفو الشريك والإدارة لا يتلقون إشعارات (قرار 31). تنبيه الرصيد المنخفض يوميًا 9:00 بتوقيت دمشق، مرة واحدة لكل شريك في اليوم. متغيرات: `FIREBASE_SERVICE_ACCOUNT_BASE64`، `NOTIFICATIONS_DISPATCH_ENABLED`، `LOW_BALANCE_ALERTS_ENABLED`. الدليل: `docs/firebase-setup-ar.md` في مساحة العمل.

## البناء والاختبارات

```powershell
./mvnw.cmd verify
```

على Linux/macOS استخدم `./mvnw verify`.

آخر تحقق كامل في مساحة التطوير الأصلية (19 نوفمبر 2026): **121 اختبارًا ناجحًا، دون فشل أو تجاوز**، بعد إضافة العضوية والنقاط (V5). الاختبارات التي تحتاج PostgreSQL لا تعمل افتراضيًا: تحتاج `JOVX_POSTGRES_TEST_ADMIN_PASSWORD_FILE` وخادم الاختبار المحلي المعزول على المنفذ 15432؛ تتحقق عمدًا من مسار بياناته الأصلي وتنشئ قواعد اختبار مؤقتة. بدون هذه البيئة يتجاوز Maven اختبارات قاعدة البيانات، ولا يمثل نجاحه تحققًا كاملًا من التخزين.

لا توجد عمليات نشر تلقائي أو حسابات حقيقية أو بيانات إنتاج في هذا المستودع. إعداد جهاز تطوير جديد بالكامل وأتمتة اختبار PostgreSQL خارج مساحة التطوير الحالية يحتاجان إعدادًا مستقلًا.

## قواعد التصميم المفروضة باختبارات

- **أي مسار تحت `/api/v1` يتطلب تسجيل الدخول تلقائيًا.** المسارات العامة محددة صراحة في إعدادات الأمان: الدخول والتجديد والخروج، وفحص التحديثات، ودليل الشركاء والخطط العامة، واستلام البطاقة.
- **كل endpoint يعلن صلاحيته بـ`@PreAuthorize`.** الاختبار يفشل إذا أُضيف endpoint بلا صلاحية ولم يُعتمد صراحة كعام.
- **الخدمات لا تعتمد على طبقة الويب.** ترمي `DomainException` (نوع الخطأ ورمزه)، و`ApiErrors` وحده يحوّل نوع الخطأ إلى رمز HTTP.
- **الطبقات في كل وحدة:** controller لا يصل إلى repository، ولا SQL خارج repository.
