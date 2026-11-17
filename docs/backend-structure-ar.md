# بنية الباكند الحالية

آخر تحقق: 16 نوفمبر 2026. التطبيق Spring Boot واحد، وفيه وحدتان باستخدام Spring Modulith: `security` و`partners`. جُمعت الهوية والحسابات وإعدادات الحماية تحت اسم `security`؛ لم تعد هناك حزمة Java باسم `identity`.

## ترتيب الملفات

```text
src/main/
  java/com/jovx/api/
    JovxApiApplication.java
    security/
      api/
      internal/
        config/
        accounts/
        sessions/
        roles/
        web/
    partners/
      controller/
      service/
      repository/
      model/
      dto/
      config/
  resources/
    application.properties
    application-local-postgres.properties
    db/postgresql/
```

`JovxApiApplication` نقطة تشغيل التطبيق، وتضبط الوقت على UTC. كل وحدة تحتوي `package-info.java` لتعريف حدودها واعتمادياتها.

## وحدة security

| المجلد | المسؤولية الحالية |
|---|---|
| `api` | عقود Java التي تستطيع الوحدات الأخرى استخدامها، مثل إنشاء حساب الشريك وفحص صلاحية المستخدم. ليست روابط HTTP. |
| `internal/config` | إعداد Spring Security، فحص JWT والفلتر الذي يراجع الطلبات. انتقلت إليه إعدادات الحماية التي كانت داخل `identity/internal/security`. |
| `internal/accounts` | المستخدمون، توحيد الهاتف الدولي، تشفير PIN، تحميل UserDetails وإنشاء حسابات الشركاء والموظفين. |
| `internal/sessions` | الدخول والخروج، إصدار JWT وتجديده، إلغاء الجلسة السابقة، تدوير Refresh Token وحدود محاولات الدخول. |
| `internal/roles` | الأدوار والصلاحيات وإسنادها وحساب الصلاحيات الفعلية. |
| `internal/web` | HTTP Controllers للدخول والجلسات والأدوار، وتحويل الأخطاء إلى استجابات API. |

بيانات المستخدم وPIN hash في جدول `users`. جداول الجلسات ما زالت مطلوبة لتطبيق قرار الجهاز الواحد والإلغاء الفوري، حتى لو كان Access Token بصيغة JWT.

## وحدة partners وMVC

| المجلد | المسؤولية |
|---|---|
| `controller` | استقبال طلبات HTTP والتحقق من صيغة الحقول وإرجاع الاستجابة. |
| `service` | قواعد العمل: صلاحية الإنشاء، الحد الأدنى للخصم، علاقة المدينة والحي، ملكية الشريك والموظفين. |
| `repository` | قراءة وكتابة PostgreSQL باستخدام JDBC وSQL. |
| `model` | نماذج المجال وقيمه، مجمعة في `PartnerModels`. |
| `dto` | حقول الطلبات والاستجابات، مجمعة في `PartnerDtos`. |
| `config` | ربط المكونات وإعدادها. |

Flutter هو واجهة العرض؛ الباكند يرجع JSON. وحدة الشركاء مفصولة بهذه الطبقات بالفعل. وحدة `security` ما زالت منظمة داخليًا حسب الوظيفة، وبعض خدماتها تتعامل مع JDBC؛ تغيير الاسم لا يعني اكتمال إعادة تنظيمها إلى طبقات MVC.

## مثال: إنشاء شريك

1. يرسل Flutter البيانات والتوكن إلى `PartnerController`.
2. تتحقق طبقة الحماية من JWT والجلسة، ثم يفحص `PartnerService` صلاحية الإدارة وقواعد التسجيل.
3. يستخدم Service العقد `security.api.PartnerAccountProvisioning` لإنشاء المستخدم وتشفير PIN.
4. يحفظ `PartnerRepository` الشريك والمنشأة والفرع وسجل العملية.
5. تتم الكتابة في معاملة واحدة؛ فشل حفظ الفرع يؤدي إلى التراجع عن إنشاء الحساب أيضًا.
6. تعرض الواجهة بيانات الشريك المعلق للمراجعة، ويظل التفعيل طلبًا منفصلًا.

لا تستورد `partners` تفاصيل `security/internal`. وعندما يحتاج الدخول إلى فحص حالة الشريك، تستخدم security عقد `LoginEligibility` الذي تنفذه `PartnerAccessPolicy`؛ فلا تقرأ جداول أعمال الشركاء مباشرة.

## الإعدادات والتحقق

`application.properties` يحتوي إعدادات التطبيق وسياسة الجلسات. اتصال PostgreSQL يأتي من `DB_URL` و`DB_USERNAME` و`DB_PASSWORD`. اسم profile هو `local-postgres` حاليًا، لكن عنوان الاتصال قد يشير إلى قاعدة التطوير السحابية الجديدة.

ملفات Flyway في `resources/db/postgresql` تصف مخطط قاعدة البيانات. لم يتطلب دمج الحزم أي migration، وبقيت أسماء الجداول، بما فيها `identity_sessions`، وإعداد issuer وروابط APIs كما هي. لم تتغير Flutter أو قاعدة الإنتاج.

نجح مشغّل التحقق المحلي `run-postgres.ps1 -Action Verify` في مساحة الترحيل الأصلية: **89 اختبار Java، صفر فشل وصفر تجاوز**، ويشمل ذلك سلوك الدخول والصلاحيات والتراجع والتزامن وحدود الوحدات. يتحقق اختبار المعمارية من وجود `security` وعدم وجود وحدة `identity` ومنع اختراق حدود الوحدات.

بعد تحميل التغيير، أعد تشغيل الباكند في IntelliJ باستخدام Rerun ليشغّل أصناف الحزمة الجديدة؛ لا يلزم تغيير متغيرات الاتصال.
