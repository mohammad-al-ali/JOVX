# JOVX Backend

باكند JOVX: Java 21 وSpring Boot وSpring Modulith وSpring Security JWT وPostgreSQL. هذا المستودع يحتوي الباكند واختباراته وترحيلات مخططه فقط.

المستودع: https://github.com/mohammad-al-ali/JOVX

## البنية

- `src/main/java/com/jovx/api/security`: المستخدمون وPIN والدخول والجلسات والأدوار والصلاحيات.
- `src/main/java/com/jovx/api/partners`: الشركاء والمنشآت والفروع وموظفو الشركاء وقوائم التسجيل، بتنظيم controller/service/repository/model/dto/config.
- `src/main/resources/db/postgresql`: ترحيلات Flyway V1 وV2 وV3؛ لا تُعدّل ترحيلًا سبق تطبيقه. V3 تضيف صلاحية الإدارة `partner.branch.manage`.
- `src/test`: اختبارات السلوك والتكامل وحدود الوحدات.

[شرح البنية وتدفق إنشاء الشريك](docs/backend-structure-ar.md).

## التشغيل

افتح `pom.xml` في IntelliJ باستخدام Java 21، ثم شغّل `com.jovx.api.JovxApiApplication`. أضف متغيرات قاعدة التطوير المخصصة للباكند:

```text
DB_URL=jdbc:postgresql://<host>:<port>/<database>
DB_USERNAME=<database-user>
DB_PASSOWRD=<database-password>
```

الاسم `DB_PASSOWRD` مقصود كما اعتمد في المشروع. لا تحفظ القيم السرية في Git. الإعداد الافتراضي `local-postgres` يدعم عنوان PostgreSQL محليًا أو سحابيًا.

**مفاتيح توقيع الدخول (JWT):** الخادم يوقّع توكن الدخول بمفتاح خاص `private.pem` ويتحقق منه بمفتاح عام `public.pem`. الملفان لا يُرفعان إلى Git. افتراضيًا يُقرآن من مجلد `.local/identity-keys` في جهاز التطوير الأصلي؛ في أي جهاز أو خادم آخر أنشئ زوجًا جديدًا وحدّد مكانه بالمتغيرين `JWT_PUBLIC_KEY_LOCATION` و`JWT_PRIVATE_KEY_LOCATION`:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
```

تغيير المفاتيح يُبطل التوكنات الصادرة سابقًا فقط؛ يكفي أن يسجّل المستخدمون الدخول مجددًا.

## النشر على Render (Docker)

1. أنشئ Web Service من هذا المستودع، الفرع `main`، بنوع **Docker** (يُستخدم `Dockerfile` في الجذر).
2. في **Secret Files** أضف `public.pem` و`private.pem` (زوج خاص بـRender، لا تعيد استخدام مفاتيح جهازك).
3. في **Environment** أضف:

```text
DB_URL=jdbc:postgresql://<supabase-session-pooler-host>:5432/postgres?sslmode=require
DB_USERNAME=<database-user>
DB_PASSOWRD=<database-password>
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
| قائمة الشركاء وتفاصيل شريك | `GET /api/v1/admin/partners`، `GET /api/v1/admin/partners/{id}` | Super Admin أو Admin بـ`partner.create` أو `partner.branch.manage` |
| الحد الأدنى للخصم | `PUT /api/v1/admin/partners/{id}/discount` | Super Admin أو `partner.create` فقط |
| إضافة/تعديل منشأة (إدارة) | `POST .../{id}/establishments`، `PUT .../{id}/establishments/{eid}` | Super Admin أو `partner.create` |
| إضافة/تعديل/إيقاف فرع (إدارة) | `POST .../{id}/establishments/{eid}/branches`، `PUT .../{id}/branches/{bid}`، `PUT .../{id}/branches/{bid}/status` | Super Admin أو `partner.branch.manage` |
| ملف الشريك ومنشآته وفروعه | `GET /api/v1/partner/profile`، `POST /api/v1/partner/establishments`، `PUT /api/v1/partner/establishments/{eid}`، `POST /api/v1/partner/establishments/{eid}/branches`، `PUT /api/v1/partner/branches/{bid}`، `PUT /api/v1/partner/branches/{bid}/status` | الشريك المالك |
| الموظفون | `GET /api/v1/partner/employees`، `PUT .../employees/{id}/status`، `PUT .../employees/{id}/pin`، `PUT .../employees/{id}/branches` | الشريك المالك (`employee.suspend`/`reactivate`/`pin.change`/`create`) |
| تغيير PIN الشريك | `PUT /api/v1/account/pin` (`currentPin`, `newPin`) | الشريك فقط؛ ينهي كل جلساته |

يُسمح بإيقاف أي فرع؛ الموظف المكلف بفروع موقوفة فقط لا يستطيع الدخول. إيقاف الموظف أو تغيير PIN له ينهي جلساته، وإعادة التفعيل تتطلب دخولًا جديدًا. منشأة تضاف لشريك معلق تبقى معلقة وتُفعّل معه. هاتف التواصل مستقل عن هاتف الدخول. إعادة ضبط PIN الشريك من الإدارة مؤجلة.

## البناء والاختبارات

```powershell
./mvnw.cmd verify
```

على Linux/macOS استخدم `./mvnw verify`.

آخر تحقق كامل في مساحة التطوير الأصلية (17 نوفمبر 2026): **99 اختبارًا ناجحًا، دون فشل أو تجاوز**، بعد إضافة إدارة الشركاء والمنشآت والفروع والموظفين وتغيير PIN الشريك. الاختبارات التي تحتاج PostgreSQL لا تعمل افتراضيًا: تحتاج `JOVX_POSTGRES_TEST_ADMIN_PASSWORD_FILE` وخادم الاختبار المحلي المعزول على المنفذ 15432؛ تتحقق عمدًا من مسار بياناته الأصلي وتنشئ قواعد اختبار مؤقتة. بدون هذه البيئة يتجاوز Maven اختبارات قاعدة البيانات، ولا يمثل نجاحه تحققًا كاملًا من التخزين.

لا توجد عمليات نشر تلقائي أو حسابات حقيقية أو بيانات إنتاج في هذا المستودع. إعداد جهاز تطوير جديد بالكامل وأتمتة اختبار PostgreSQL خارج مساحة التطوير الحالية يحتاجان إعدادًا مستقلًا.
