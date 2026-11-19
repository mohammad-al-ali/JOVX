-- Store catalog (plan decisions 11, 12, 14, 24-28, 62-68). One store owns its products.
-- Prices are whole SYP for now (currency column kept for later currencies).

INSERT INTO permissions(id,"key",name_ar,description_ar,level,delegable_to_employee) VALUES
 (8,'catalog.review','مراجعة منتجات المتاجر','الموافقة على منتجات المتاجر ورفضها بسبب وإخفاؤها، وإدارة تصنيفات المنتجات.','ADMIN',TRUE);

CREATE TABLE "product_categories" (
    "id" char(36) NOT NULL,
    "name_ar" varchar(100) NOT NULL,
    "sort_order" smallint NOT NULL,
    "active" boolean NOT NULL DEFAULT TRUE,
    PRIMARY KEY ("id"),
    CONSTRAINT "product_categories_chk_name" CHECK (char_length(btrim("name_ar")) > 0)
);
CREATE UNIQUE INDEX "product_categories_uq_name" ON "product_categories" (lower(btrim("name_ar")));

-- The 21 categories of the legacy partner app (decision 26).
INSERT INTO product_categories(id,name_ar,sort_order) VALUES
 ('7f0b1c1e-0001-4a00-8000-000000000001','وجبات',1),
 ('7f0b1c1e-0002-4a00-8000-000000000002','مأكولات سريعة',2),
 ('7f0b1c1e-0003-4a00-8000-000000000003','مأكولات ومشروبات',3),
 ('7f0b1c1e-0004-4a00-8000-000000000004','مشروبات',4),
 ('7f0b1c1e-0005-4a00-8000-000000000005','حلويات',5),
 ('7f0b1c1e-0006-4a00-8000-000000000006','مكسرات',6),
 ('7f0b1c1e-0007-4a00-8000-000000000007','ملابس',7),
 ('7f0b1c1e-0008-4a00-8000-000000000008','أحذية وحقائب',8),
 ('7f0b1c1e-0009-4a00-8000-000000000009','هواتف وإلكترونيات',9),
 ('7f0b1c1e-0010-4a00-8000-000000000010','أجهزة منزلية',10),
 ('7f0b1c1e-0011-4a00-8000-000000000011','منزل ومفروشات',11),
 ('7f0b1c1e-0012-4a00-8000-000000000012','زهور وهدايا',12),
 ('7f0b1c1e-0013-4a00-8000-000000000013','تجميل وعناية',13),
 ('7f0b1c1e-0014-4a00-8000-000000000014','رياضة ولياقة',14),
 ('7f0b1c1e-0015-4a00-8000-000000000015','خدمات طبية',15),
 ('7f0b1c1e-0016-4a00-8000-000000000016','خدمات أسنان',16),
 ('7f0b1c1e-0017-4a00-8000-000000000017','عناية بالسيارات',17),
 ('7f0b1c1e-0018-4a00-8000-000000000018','دورات لغات',18),
 ('7f0b1c1e-0019-4a00-8000-000000000019','دورات دراسية',19),
 ('7f0b1c1e-0020-4a00-8000-000000000020','خدمات أخرى',20),
 ('7f0b1c1e-0021-4a00-8000-000000000021','منتجات أخرى',21);

CREATE TABLE "products" (
    "id" char(36) NOT NULL,
    "request_id" char(36) NOT NULL,
    "store_id" char(36) NOT NULL,
    "partner_id" char(36) NOT NULL,
    "category_id" char(36) NOT NULL,
    "name" varchar(200) NOT NULL,
    "description" varchar(2000) DEFAULT NULL,
    "price_amount" bigint NOT NULL,
    "currency" char(3) NOT NULL DEFAULT 'SYP',
    -- NULL = automatic member price from the partner-wide minimum discount (decision 65).
    "member_price_amount" bigint DEFAULT NULL,
    "points" bigint NOT NULL,
    "stock_tracked" boolean NOT NULL DEFAULT FALSE,
    "stock" integer DEFAULT NULL,
    -- PENDING: awaiting review (hidden). APPROVED. REJECTED (with reason). HIDDEN by administration (with reason).
    "review_status" varchar(16) NOT NULL,
    "review_reason" varchar(500) DEFAULT NULL,
    "reviewed_by" char(36) DEFAULT NULL,
    "reviewed_at" timestamp(6) DEFAULT NULL,
    -- The partner's own show/hide switch for an approved product (decision 66).
    "published" boolean NOT NULL DEFAULT TRUE,
    "version" integer NOT NULL DEFAULT 1,
    "created_by" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    "updated_at" timestamp(6) NOT NULL,
    "deleted_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "products_uq_request" UNIQUE ("request_id"),
    CONSTRAINT "products_chk_name" CHECK (char_length(btrim("name")) > 0),
    CONSTRAINT "products_chk_price" CHECK ("price_amount" > 0 AND "price_amount" <= 1000000000000),
    CONSTRAINT "products_chk_currency" CHECK ("currency" ~ '^[A-Z]{3}$'),
    CONSTRAINT "products_chk_member_price" CHECK ("member_price_amount" IS NULL OR ("member_price_amount" >= 0 AND "member_price_amount" <= "price_amount")),
    CONSTRAINT "products_chk_points" CHECK ("points" >= 0 AND "points" <= 1000000000000),
    CONSTRAINT "products_chk_stock" CHECK (("stock_tracked" AND "stock" BETWEEN 0 AND 1000000) OR (NOT "stock_tracked" AND "stock" IS NULL)),
    CONSTRAINT "products_chk_review" CHECK ("review_status" IN ('PENDING','APPROVED','REJECTED','HIDDEN')),
    CONSTRAINT "products_chk_reason" CHECK ("review_status" NOT IN ('REJECTED','HIDDEN') OR char_length(btrim("review_reason")) > 0),
    CONSTRAINT "products_fk_store" FOREIGN KEY ("partner_id","store_id") REFERENCES "partner_stores" ("partner_id","id"),
    CONSTRAINT "products_fk_category" FOREIGN KEY ("category_id") REFERENCES "product_categories" ("id"),
    CONSTRAINT "products_fk_creator" FOREIGN KEY ("created_by") REFERENCES "users" ("id"),
    CONSTRAINT "products_fk_reviewer" FOREIGN KEY ("reviewed_by") REFERENCES "users" ("id")
);
CREATE INDEX "products_ix_store" ON "products" ("store_id","deleted_at");
CREATE INDEX "products_ix_review" ON "products" ("review_status","updated_at") WHERE "deleted_at" IS NULL;

-- Up to 4 images per product; position 0 is the cover (decision 64). Only storage keys are kept.
CREATE TABLE "product_images" (
    "id" char(36) NOT NULL,
    "product_id" char(36) NOT NULL,
    "position" smallint NOT NULL,
    "object_key" varchar(300) NOT NULL,
    "content_type" varchar(32) NOT NULL,
    "size_bytes" integer NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "product_images_uq_position" UNIQUE ("product_id","position"),
    CONSTRAINT "product_images_uq_key" UNIQUE ("object_key"),
    CONSTRAINT "product_images_chk_position" CHECK ("position" BETWEEN 0 AND 3),
    CONSTRAINT "product_images_fk_product" FOREIGN KEY ("product_id") REFERENCES "products" ("id")
);

CREATE TABLE "product_events" (
    "id" char(36) NOT NULL,
    "product_id" char(36) NOT NULL,
    "actor_id" char(36) NOT NULL,
    "action" varchar(40) NOT NULL,
    "reason" varchar(500) DEFAULT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "product_events_fk_product" FOREIGN KEY ("product_id") REFERENCES "products" ("id"),
    CONSTRAINT "product_events_fk_actor" FOREIGN KEY ("actor_id") REFERENCES "users" ("id")
);
CREATE INDEX "product_events_ix_product" ON "product_events" ("product_id","created_at");
