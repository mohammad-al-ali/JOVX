-- Partner simplification (user decision 2026-11-19, docs/partner-stores-plan-ar.md):
-- a partner owns one or more STORES instead of establishments with branches.
-- "Start from scratch": establishments, branches and branch assignments are dropped, not converted.
-- The online database had no partners when this was decided; local synthetic partners keep their
-- accounts but have no store until one is added.

-- One administration grant for stores replaces the branch grant (same id, so role grants carry over).
UPDATE permissions
   SET "key" = 'partner.store.manage',
       name_ar = 'إدارة متاجر الشركاء',
       description_ar = 'إضافة متاجر الشركاء وتعديلها وإيقافها وإعادة تفعيلها وتحديد مواعيدها.'
 WHERE "key" = 'partner.branch.manage';

DROP TABLE "partner_employee_branches";
DROP TABLE "partner_branches";
DROP TABLE "partner_establishments";

CREATE TABLE "partner_stores" (
    "id" char(36) NOT NULL,
    "partner_id" char(36) NOT NULL,
    "name" varchar(200) NOT NULL,
    "category_id" char(36) NOT NULL,
    "city_id" char(36) NOT NULL,
    "address_id" char(36) NOT NULL,
    "address_details" varchar(500) DEFAULT NULL,
    "contact_phone" varchar(64) DEFAULT NULL,
    "latitude" numeric(9,6) DEFAULT NULL,
    "longitude" numeric(9,6) DEFAULT NULL,
    "description" varchar(1000) DEFAULT NULL,
    -- Object key in image storage (Cloudflare R2); uploading arrives with the store/products phase.
    "logo_key" varchar(300) DEFAULT NULL,
    "status" varchar(16) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "partner_stores_uq_owner" UNIQUE ("partner_id","id"),
    CONSTRAINT "partner_stores_chk_name" CHECK (char_length(btrim("name")) > 0),
    CONSTRAINT "partner_stores_chk_status" CHECK ("status" IN ('PENDING','ACTIVE','SUSPENDED')),
    CONSTRAINT "partner_stores_chk_point" CHECK (
        ("latitude" IS NULL AND "longitude" IS NULL)
        OR ("latitude" BETWEEN -90 AND 90 AND "longitude" BETWEEN -180 AND 180)),
    CONSTRAINT "partner_stores_fk_partner" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id"),
    CONSTRAINT "partner_stores_fk_category" FOREIGN KEY ("category_id") REFERENCES "categories" ("id"),
    -- The neighborhood must belong to the chosen city.
    CONSTRAINT "partner_stores_fk_location" FOREIGN KEY ("city_id","address_id") REFERENCES "addresses" ("city_id","id")
);
CREATE INDEX "partner_stores_ix_partner" ON "partner_stores" ("partner_id");
CREATE INDEX "partner_stores_ix_city" ON "partner_stores" ("city_id","status");
CREATE INDEX "partner_stores_ix_category" ON "partner_stores" ("category_id");

-- Weekly opening hours, Damascus local time. weekday: 1 = Monday ... 7 = Sunday (ISO).
-- closes_at earlier than opens_at means the store closes after midnight (user decision 2026-11-19).
CREATE TABLE "partner_store_hours" (
    "store_id" char(36) NOT NULL,
    "weekday" smallint NOT NULL,
    "closed" boolean NOT NULL,
    "opens_at" time DEFAULT NULL,
    "closes_at" time DEFAULT NULL,
    PRIMARY KEY ("store_id","weekday"),
    CONSTRAINT "partner_store_hours_chk_day" CHECK ("weekday" BETWEEN 1 AND 7),
    CONSTRAINT "partner_store_hours_chk_times" CHECK (
        ("closed" AND "opens_at" IS NULL AND "closes_at" IS NULL)
        OR (NOT "closed" AND "opens_at" IS NOT NULL AND "closes_at" IS NOT NULL AND "opens_at" <> "closes_at")),
    CONSTRAINT "partner_store_hours_fk_store" FOREIGN KEY ("store_id") REFERENCES "partner_stores" ("id") ON DELETE CASCADE
);

-- A partner employee works in one or more stores of its own partner (checked by the service).
CREATE TABLE "partner_employee_stores" (
    "user_id" char(36) NOT NULL,
    "store_id" char(36) NOT NULL,
    PRIMARY KEY ("user_id","store_id"),
    CONSTRAINT "partner_employee_stores_fk_employee" FOREIGN KEY ("user_id") REFERENCES "partner_employees" ("user_id"),
    CONSTRAINT "partner_employee_stores_fk_store" FOREIGN KEY ("store_id") REFERENCES "partner_stores" ("id")
);
CREATE INDEX "partner_employee_stores_ix_store" ON "partner_employee_stores" ("store_id");
