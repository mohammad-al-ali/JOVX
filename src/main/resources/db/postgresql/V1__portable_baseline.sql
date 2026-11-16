-- PostgreSQL baseline equivalent to the existing MySQL V4 model.
-- Independent Flyway history; no production data or credentials.
-- IDs remain text UUIDs for portable JDBC contracts; timestamps are UTC.

CREATE TABLE "addresses" (
    "id" char(36) NOT NULL,
    "city_id" char(36) NOT NULL,
    "name" varchar(200) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "addresses_uq_address_city" UNIQUE ("city_id","id")
);

CREATE TABLE "categories" (
    "id" char(36) NOT NULL,
    "name" varchar(200) NOT NULL,
    PRIMARY KEY ("id")
);

CREATE TABLE "cities" (
    "id" char(36) NOT NULL,
    "name" varchar(200) NOT NULL,
    PRIMARY KEY ("id")
);

CREATE TABLE "identity_auth_events" (
    "id" char(36) NOT NULL,
    "account_id" char(36) DEFAULT NULL,
    "action" varchar(40) NOT NULL,
    "session_id" char(36) DEFAULT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id")
);

CREATE TABLE "identity_auth_rate_limits" (
    "bucket_key" char(64) NOT NULL,
    "expires_at" timestamp(6) NOT NULL,
    "attempts" int NOT NULL,
    PRIMARY KEY ("bucket_key")
);

CREATE TABLE "identity_refresh_tokens" (
    "token_digest" char(64) NOT NULL,
    "session_id" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    "expires_at" timestamp(6) NOT NULL,
    "consumed_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("token_digest")
);

CREATE TABLE "identity_sessions" (
    "id" char(36) NOT NULL,
    "account_id" char(36) NOT NULL,
    "credential_tag" char(64) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    "expires_at" timestamp(6) NOT NULL,
    "revoked_at" timestamp(6) DEFAULT NULL,
    "active_account_id" char(36) GENERATED ALWAYS AS (CASE WHEN "revoked_at" IS NULL THEN "account_id" ELSE NULL END) STORED,
    PRIMARY KEY ("id"),
    CONSTRAINT "identity_sessions_uq_identity_active_session" UNIQUE ("active_account_id")
);

CREATE TABLE "partner_branches" (
    "id" char(36) NOT NULL,
    "establishment_id" char(36) NOT NULL,
    "name" varchar(200) NOT NULL,
    "city_id" char(36) NOT NULL,
    "address_id" char(36) NOT NULL,
    "address_details" varchar(500) DEFAULT NULL,
    "status" varchar(16) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "partner_branches_chk_1" CHECK (("status" in ('ACTIVE','SUSPENDED')))
);

CREATE TABLE "partner_employee_branches" (
    "user_id" char(36) NOT NULL,
    "branch_id" char(36) NOT NULL,
    PRIMARY KEY ("user_id","branch_id")
);

CREATE TABLE "partner_employees" (
    "user_id" char(36) NOT NULL,
    "related_entity" varchar(16) NOT NULL DEFAULT 'PARTNER',
    "related_id" char(36) NOT NULL,
    PRIMARY KEY ("user_id"),
    CONSTRAINT "partner_employees_chk_1" CHECK (("related_entity" = 'PARTNER'))
);

CREATE TABLE "partner_establishments" (
    "id" char(36) NOT NULL,
    "partner_id" char(36) NOT NULL,
    "category_id" char(36) NOT NULL,
    "name" varchar(200) NOT NULL,
    "contact_phone" varchar(64) DEFAULT NULL,
    "status" varchar(16) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "partner_establishments_uq_establishment_owner" UNIQUE ("partner_id","id"),
    CONSTRAINT "partner_establishments_chk_1" CHECK (("status" in ('PENDING','ACTIVE','SUSPENDED')))
);

CREATE TABLE "partner_events" (
    "id" char(36) NOT NULL,
    "partner_id" char(36) NOT NULL,
    "actor_id" char(36) NOT NULL,
    "action" varchar(40) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id")
);

CREATE TABLE "partner_owners" (
    "id" char(36) NOT NULL,
    "account_id" char(36) NOT NULL,
    "minimum_discount_percent" decimal(5,2) NOT NULL,
    "status" varchar(16) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "partner_owners_account_id" UNIQUE ("account_id"),
    CONSTRAINT "partner_owners_chk_1" CHECK (("minimum_discount_percent" between 0 and 100)),
    CONSTRAINT "partner_owners_chk_2" CHECK (("status" in ('PENDING','ACTIVE','SUSPENDED')))
);

CREATE TABLE "permissions" (
    "id" smallint NOT NULL,
    "key" varchar(64) NOT NULL,
    "name_ar" varchar(150) NOT NULL,
    "description_ar" varchar(500) NOT NULL,
    "level" varchar(16) NOT NULL,
    "delegable_to_employee" boolean NOT NULL DEFAULT FALSE,
    PRIMARY KEY ("id"),
    CONSTRAINT "permissions_key" UNIQUE ("key"),
    CONSTRAINT "permissions_uq_permission_level" UNIQUE ("id","level"),
    CONSTRAINT "permissions_chk_1" CHECK (("level" in ('ADMIN','PARTNER','MEMBER')))
);

CREATE TABLE "role_events" (
    "id" char(36) NOT NULL,
    "actor_id" char(36) NOT NULL,
    "target_user_id" char(36) NOT NULL,
    "action" varchar(40) NOT NULL,
    "role_key" varchar(32) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id")
);

CREATE TABLE "role_permissions" (
    "role_id" smallint NOT NULL,
    "permission_id" smallint NOT NULL,
    "level" varchar(16) NOT NULL,
    PRIMARY KEY ("role_id","permission_id")
);

CREATE TABLE "roles" (
    "id" smallint NOT NULL,
    "key" varchar(32) NOT NULL,
    "name_ar" varchar(100) NOT NULL,
    "level" varchar(16) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "roles_key" UNIQUE ("key"),
    CONSTRAINT "roles_uq_role_level" UNIQUE ("id","level"),
    CONSTRAINT "roles_chk_1" CHECK (("level" in ('ADMIN','PARTNER','MEMBER')))
);

CREATE TABLE "user_permissions" (
    "user_id" char(36) NOT NULL,
    "permission_id" smallint NOT NULL,
    "granted_by" char(36) NOT NULL,
    PRIMARY KEY ("user_id","permission_id")
);

CREATE TABLE "users" (
    "id" char(36) NOT NULL,
    "phone_e164" varchar(16) NOT NULL,
    "status" varchar(16) NOT NULL,
    "created_at" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    "failed_pin_attempts" int NOT NULL DEFAULT '0',
    "locked_until" timestamp(6) DEFAULT NULL,
    "name" varchar(200) NOT NULL,
    "pin_hash" varchar(255) NOT NULL,
    "pin_changed_at" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    "role_id" smallint NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "users_uq_identity_accounts_phone" UNIQUE ("phone_e164"),
    CONSTRAINT "ck_identity_accounts_phone" CHECK ("phone_e164" ~ '^[+][1-9][0-9]{1,14}$'),
    CONSTRAINT "ck_identity_accounts_status" CHECK (("status" in ('PENDING','ACTIVE','SUSPENDED'))),
    CONSTRAINT "ck_users_name" CHECK ((char_length(trim("name")) > 0)),
    CONSTRAINT "ck_users_pin_hash" CHECK ((char_length("pin_hash") > 6))
);

ALTER TABLE "addresses" ADD CONSTRAINT "addresses_ibfk_1" FOREIGN KEY ("city_id") REFERENCES "cities" ("id");

ALTER TABLE "identity_auth_events" ADD CONSTRAINT "identity_auth_events_ibfk_1" FOREIGN KEY ("account_id") REFERENCES "users" ("id");

ALTER TABLE "identity_refresh_tokens" ADD CONSTRAINT "identity_refresh_tokens_ibfk_1" FOREIGN KEY ("session_id") REFERENCES "identity_sessions" ("id");

ALTER TABLE "identity_sessions" ADD CONSTRAINT "identity_sessions_ibfk_1" FOREIGN KEY ("account_id") REFERENCES "users" ("id");

ALTER TABLE "partner_branches" ADD CONSTRAINT "partner_branches_ibfk_1" FOREIGN KEY ("establishment_id") REFERENCES "partner_establishments" ("id");

ALTER TABLE "partner_branches" ADD CONSTRAINT "partner_branches_ibfk_2" FOREIGN KEY ("city_id", "address_id") REFERENCES "addresses" ("city_id", "id");

ALTER TABLE "partner_employee_branches" ADD CONSTRAINT "partner_employee_branches_ibfk_1" FOREIGN KEY ("user_id") REFERENCES "partner_employees" ("user_id");

ALTER TABLE "partner_employee_branches" ADD CONSTRAINT "partner_employee_branches_ibfk_2" FOREIGN KEY ("branch_id") REFERENCES "partner_branches" ("id");

ALTER TABLE "partner_employees" ADD CONSTRAINT "partner_employees_ibfk_1" FOREIGN KEY ("user_id") REFERENCES "users" ("id");

ALTER TABLE "partner_employees" ADD CONSTRAINT "partner_employees_ibfk_2" FOREIGN KEY ("related_id") REFERENCES "partner_owners" ("id");

ALTER TABLE "partner_establishments" ADD CONSTRAINT "partner_establishments_ibfk_1" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id");

ALTER TABLE "partner_establishments" ADD CONSTRAINT "partner_establishments_ibfk_2" FOREIGN KEY ("category_id") REFERENCES "categories" ("id");

ALTER TABLE "partner_events" ADD CONSTRAINT "partner_events_ibfk_1" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id");

ALTER TABLE "partner_events" ADD CONSTRAINT "partner_events_ibfk_2" FOREIGN KEY ("actor_id") REFERENCES "users" ("id");

ALTER TABLE "partner_owners" ADD CONSTRAINT "partner_owners_ibfk_1" FOREIGN KEY ("account_id") REFERENCES "users" ("id");

ALTER TABLE "role_events" ADD CONSTRAINT "role_events_ibfk_1" FOREIGN KEY ("actor_id") REFERENCES "users" ("id");

ALTER TABLE "role_events" ADD CONSTRAINT "role_events_ibfk_2" FOREIGN KEY ("target_user_id") REFERENCES "users" ("id");

ALTER TABLE "role_permissions" ADD CONSTRAINT "role_permissions_ibfk_1" FOREIGN KEY ("role_id", "level") REFERENCES "roles" ("id", "level");

ALTER TABLE "role_permissions" ADD CONSTRAINT "role_permissions_ibfk_2" FOREIGN KEY ("permission_id", "level") REFERENCES "permissions" ("id", "level");

ALTER TABLE "user_permissions" ADD CONSTRAINT "user_permissions_ibfk_1" FOREIGN KEY ("user_id") REFERENCES "users" ("id");

ALTER TABLE "user_permissions" ADD CONSTRAINT "user_permissions_ibfk_2" FOREIGN KEY ("permission_id") REFERENCES "permissions" ("id");

ALTER TABLE "user_permissions" ADD CONSTRAINT "user_permissions_ibfk_3" FOREIGN KEY ("granted_by") REFERENCES "users" ("id");

ALTER TABLE "users" ADD CONSTRAINT "fk_users_role" FOREIGN KEY ("role_id") REFERENCES "roles" ("id");

CREATE INDEX "identity_auth_events_account_id" ON "identity_auth_events" ("account_id");

CREATE INDEX "identity_auth_rate_limits_ix_auth_rate_expiry" ON "identity_auth_rate_limits" ("expires_at");

CREATE INDEX "identity_refresh_tokens_session_id" ON "identity_refresh_tokens" ("session_id");

CREATE INDEX "identity_sessions_ix_identity_sessions_account" ON "identity_sessions" ("account_id");

CREATE INDEX "partner_branches_establishment_id" ON "partner_branches" ("establishment_id");

CREATE INDEX "partner_branches_city_id" ON "partner_branches" ("city_id","address_id");

CREATE INDEX "partner_employee_branches_branch_id" ON "partner_employee_branches" ("branch_id");

CREATE INDEX "partner_employees_related_id" ON "partner_employees" ("related_id");

CREATE INDEX "partner_establishments_category_id" ON "partner_establishments" ("category_id");

CREATE INDEX "partner_events_partner_id" ON "partner_events" ("partner_id");

CREATE INDEX "partner_events_actor_id" ON "partner_events" ("actor_id");

CREATE INDEX "role_events_actor_id" ON "role_events" ("actor_id");

CREATE INDEX "role_events_target_user_id" ON "role_events" ("target_user_id");

CREATE INDEX "role_permissions_role_id" ON "role_permissions" ("role_id","level");

CREATE INDEX "role_permissions_permission_id" ON "role_permissions" ("permission_id","level");

CREATE INDEX "user_permissions_permission_id" ON "user_permissions" ("permission_id");

CREATE INDEX "user_permissions_granted_by" ON "user_permissions" ("granted_by");

CREATE INDEX "users_fk_users_role" ON "users" ("role_id");

INSERT INTO roles(id,"key",name_ar,level) VALUES
 (1,'SUPER_ADMIN','المشرف الأعلى','ADMIN'),(2,'ADMIN','إداري','ADMIN'),
 (3,'PARTNER','شريك','PARTNER'),(4,'MEMBER','عضو','MEMBER'),
 (5,'ADMIN_EMPLOYEE','موظف إدارة','ADMIN'),(6,'PARTNER_EMPLOYEE','موظف شريك','PARTNER');

INSERT INTO permissions(id,"key",name_ar,description_ar,level,delegable_to_employee) VALUES
 (1,'partner.create','إنشاء وتفعيل الشركاء','إنشاء الشريك ومنشأته الأولى وتفعيل التسجيل.','ADMIN',FALSE),
 (2,'partner.pin.reset','إعادة ضبط PIN الشريك','إعادة ضبط PIN الشريك عند نسيانه وفق إجراء الاستعادة المعتمد.','ADMIN',FALSE),
 (10,'member.scan','مسح بطاقة العضو','مسح بطاقة العضو ضمن فروع العمل المسموحة.','PARTNER',TRUE),
 (11,'sale.confirm','تأكيد البيع','تأكيد عملية البيع ضمن الفروع المسموحة.','PARTNER',TRUE),
 (12,'sale.read.own','عرض المبيعات الشخصية','عرض المبيعات التي نفذها الموظف نفسه.','PARTNER',TRUE),
 (13,'sale.read.branch','عرض مبيعات الفرع','عرض سجل المبيعات للفروع المكلف بها الموظف.','PARTNER',TRUE),
 (14,'report.read.branch','عرض تقارير الفرع','عرض تقارير الفروع المسموحة.','PARTNER',TRUE),
 (15,'report.read.partner','عرض تقارير الشريك','عرض التقارير المجمعة للشريك التابع له الموظف.','PARTNER',TRUE),
 (16,'catalog.read','عرض المنتجات','عرض كتالوج المنتجات ضمن نطاق الشريك.','PARTNER',TRUE),
 (17,'catalog.submit','تقديم تعديلات المنتجات','تقديم بيانات المنتجات وتعديلاتها وفق دورة الاعتماد.','PARTNER',TRUE),
 (18,'partner.profile.submit','تقديم تعديلات ملف الشريك','تقديم تعديلات بيانات الشريك وفق دورة الاعتماد.','PARTNER',TRUE),
 (19,'wallet.read','عرض المحفظة','الاطلاع على محفظة الشريك.','PARTNER',TRUE),
 (20,'wallet.topup.request','طلب شحن المحفظة','تقديم طلب شحن دون صلاحية اعتماده.','PARTNER',TRUE),
 (21,'gift.read','عرض الهدايا','عرض الهدايا المتاحة ضمن نطاق الشريك.','PARTNER',TRUE),
 (30,'employee.create','إنشاء الموظفين','إنشاء موظف تابع للشريك دون صلاحيات تلقائية.','PARTNER',FALSE),
 (31,'employee.suspend','إيقاف الموظفين','إيقاف موظف تابع للشريك.','PARTNER',FALSE),
 (32,'employee.reactivate','إعادة تفعيل الموظفين','إعادة تفعيل موظف تابع للشريك.','PARTNER',FALSE),
 (33,'employee.permissions.manage','تحديد صلاحيات الموظفين','تحديد صلاحيات موظفي الشريك دون تعديل الأدوار العامة.','PARTNER',FALSE),
 (34,'employee.pin.change','تغيير PIN الموظف','تغيير PIN الموظف من الشريك المالك فقط.','PARTNER',FALSE);

INSERT INTO role_permissions SELECT 3,id,level FROM permissions WHERE level='PARTNER';

INSERT INTO role_permissions SELECT 6,id,level FROM permissions WHERE level='PARTNER' AND delegable_to_employee=TRUE;
