# JOVX Backend

باكند JOVX: Java 21 وSpring Boot وSpring Modulith وSpring Security JWT وPostgreSQL. هذا المستودع يحتوي الباكند واختباراته وترحيلات مخططه فقط.

المستودع: https://github.com/mohammad-al-ali/JOVX

## البنية

- `src/main/java/com/jovx/api/security`: المستخدمون وPIN والدخول والجلسات والأدوار والصلاحيات.
- `src/main/java/com/jovx/api/partners`: الشركاء والمنشآت والفروع وموظفو الشركاء وقوائم التسجيل، بتنظيم controller/service/repository/model/dto/config.
- `src/main/resources/db/postgresql`: ترحيلات Flyway V1 وV2؛ لا تُعدّل ترحيلًا سبق تطبيقه.
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

**متطلب جهاز جديد:** مسارا مفتاحي RSA في `application-local-postgres.properties` يشيران حاليًا إلى ملفات جهاز التطوير الأصلي. جهّز زوج مفاتيح RSA خاصًا ببيئتك واضبط `jovx.security.jwt.public-key` و`jovx.security.jwt.private-key` على ملفيك المحليين. المفاتيح نفسها مستبعدة من المستودع. تغيير مفاتيح بيئة قائمة يجعل توكناتها السابقة غير صالحة.

على قاعدة جديدة مخصصة لهذا الباكند، شغّل ترحيلات Flyway قبل التشغيل. يستخدم Maven plugin متغيرات منفصلة بصلاحيات الترحيل:

```text
JOVX_MIGRATION_URL
JOVX_MIGRATION_USER
JOVX_MIGRATION_PASSWORD
```

ثم نفّذ `./mvnw flyway:migrate`، أو `./mvnw.cmd flyway:migrate` في Windows. لا تُشغّل ذلك على قاعدة الإنتاج الأصلية. حساب Super Admin وبيانات الاختبار والمفاتيح لا تُنشأ تلقائيًا عند التشغيل ولا تُنشر هنا.

المنفذ الافتراضي `18080` على loopback، وفحص التشغيل: `http://127.0.0.1:18080/actuator/health`. استجابة health لا تغني عن اختبار الدخول واتصال قاعدة البيانات.

## البناء والاختبارات

```powershell
./mvnw.cmd verify
```

على Linux/macOS استخدم `./mvnw verify`.

آخر تحقق كامل في مساحة التطوير الأصلية: **89 اختبارًا ناجحًا، دون فشل أو تجاوز** بعد دمج identity في security. الاختبارات التي تحتاج PostgreSQL لا تعمل افتراضيًا: تحتاج `JOVX_POSTGRES_TEST_ADMIN_PASSWORD_FILE` وخادم الاختبار المحلي المعزول على المنفذ 15432؛ تتحقق عمدًا من مسار بياناته الأصلي وتنشئ قواعد اختبار مؤقتة. بدون هذه البيئة يتجاوز Maven اختبارات قاعدة البيانات، ولا يمثل نجاحه تحققًا كاملًا من التخزين.

لا توجد عمليات نشر تلقائي أو حسابات حقيقية أو بيانات إنتاج في هذا المستودع. إعداد جهاز تطوير جديد بالكامل وأتمتة اختبار PostgreSQL خارج مساحة التطوير الحالية يحتاجان إعدادًا مستقلًا.
