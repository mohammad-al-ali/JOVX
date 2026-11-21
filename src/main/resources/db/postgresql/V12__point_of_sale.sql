-- Phase 4 point of sale (plan decisions 6-17, 39-41, 70-73).
-- One scan = one invoice; every point the member receives is debited from the partner wallet;
-- no reversal. Amounts are whole units of "currency" (SYP for now).

INSERT INTO permissions(id,"key",name_ar,description_ar,level,delegable_to_employee) VALUES
 (22,'sale.free','البيع الحر','بيع بإجمالي يكتبه البائع، بخصم لا يقل عن الحد الأدنى للشريك.','PARTNER',TRUE);
INSERT INTO role_permissions(role_id,permission_id,level) VALUES (3,22,'PARTNER'),(6,22,'PARTNER');

-- Sale points leave the wallet with a reference to the sale.
ALTER TABLE "wallet_ledger" DROP CONSTRAINT "wallet_ledger_chk_kind";
ALTER TABLE "wallet_ledger" ADD CONSTRAINT "wallet_ledger_chk_kind"
  CHECK ("kind" IN ('TOPUP_APPROVED','DIRECT_TOPUP','REFUND_HELD','REFUND_RELEASED','SALE_POINTS'));
ALTER TABLE "wallet_ledger" ADD COLUMN "reference_id" char(36) DEFAULT NULL;

-- A seller scanned a member (QR or physical card): 5 minutes to finish ONE invoice (decisions 16, 73).
CREATE TABLE "sale_scans" (
    "id" char(36) NOT NULL,
    "partner_id" char(36) NOT NULL,
    "store_id" char(36) NOT NULL,
    "member_account_id" char(36) NOT NULL,
    "card_id" char(36) NOT NULL,
    "method" varchar(8) NOT NULL,
    "scanned_by" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    "expires_at" timestamp(6) NOT NULL,
    "consumed_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "sale_scans_chk_method" CHECK ("method" IN ('QR','CARD')),
    CONSTRAINT "sale_scans_fk_partner" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id"),
    CONSTRAINT "sale_scans_fk_store" FOREIGN KEY ("store_id") REFERENCES "partner_stores" ("id"),
    CONSTRAINT "sale_scans_fk_member" FOREIGN KEY ("member_account_id") REFERENCES "users" ("id"),
    CONSTRAINT "sale_scans_fk_card" FOREIGN KEY ("card_id") REFERENCES "member_cards" ("id"),
    CONSTRAINT "sale_scans_fk_seller" FOREIGN KEY ("scanned_by") REFERENCES "users" ("id")
);
CREATE INDEX "sale_scans_ix_seller" ON "sale_scans" ("scanned_by","created_at" DESC);

-- gross = before discount; net = what the member pays; discount = gross - net (the member's saving).
CREATE TABLE "sales" (
    "id" char(36) NOT NULL,
    "request_id" char(36) NOT NULL,
    "request_digest" char(64) NOT NULL,
    "partner_id" char(36) NOT NULL,
    "store_id" char(36) NOT NULL,
    "member_account_id" char(36) NOT NULL,
    "card_id" char(36) NOT NULL,
    "scan_id" char(36) NOT NULL,
    "kind" varchar(16) NOT NULL,
    "currency" char(3) NOT NULL DEFAULT 'SYP',
    "gross_amount" bigint NOT NULL,
    "discount_amount" bigint NOT NULL,
    "net_amount" bigint NOT NULL,
    "discount_percent" numeric(5,2) DEFAULT NULL,
    "points" bigint NOT NULL,
    "seller_id" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "sales_uq_request" UNIQUE ("request_id"),
    CONSTRAINT "sales_uq_scan" UNIQUE ("scan_id"),
    CONSTRAINT "sales_chk_kind" CHECK ("kind" IN ('PRODUCTS','FREE')),
    CONSTRAINT "sales_chk_currency" CHECK ("currency" ~ '^[A-Z]{3}$'),
    CONSTRAINT "sales_chk_amounts" CHECK ("gross_amount" > 0 AND "discount_amount" >= 0 AND "net_amount" >= 0
        AND "gross_amount" = "discount_amount" + "net_amount"),
    CONSTRAINT "sales_chk_points" CHECK ("points" >= 0),
    CONSTRAINT "sales_chk_percent" CHECK (("kind" = 'FREE') = ("discount_percent" IS NOT NULL)),
    CONSTRAINT "sales_fk_partner" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id"),
    CONSTRAINT "sales_fk_store" FOREIGN KEY ("store_id") REFERENCES "partner_stores" ("id"),
    CONSTRAINT "sales_fk_member" FOREIGN KEY ("member_account_id") REFERENCES "users" ("id"),
    CONSTRAINT "sales_fk_card" FOREIGN KEY ("card_id") REFERENCES "member_cards" ("id"),
    CONSTRAINT "sales_fk_scan" FOREIGN KEY ("scan_id") REFERENCES "sale_scans" ("id"),
    CONSTRAINT "sales_fk_seller" FOREIGN KEY ("seller_id") REFERENCES "users" ("id")
);
CREATE INDEX "sales_ix_partner" ON "sales" ("partner_id","created_at" DESC);
CREATE INDEX "sales_ix_store" ON "sales" ("store_id","created_at" DESC);
CREATE INDEX "sales_ix_member" ON "sales" ("member_account_id","created_at" DESC);
CREATE INDEX "sales_ix_seller" ON "sales" ("seller_id","created_at" DESC);

-- Product invoice lines keep the name and prices at the moment of sale.
CREATE TABLE "sale_lines" (
    "sale_id" char(36) NOT NULL,
    "line_no" smallint NOT NULL,
    "product_id" char(36) NOT NULL,
    "name" varchar(200) NOT NULL,
    "unit_price" bigint NOT NULL,
    "unit_member_price" bigint NOT NULL,
    "unit_points" bigint NOT NULL,
    "quantity" integer NOT NULL,
    PRIMARY KEY ("sale_id","line_no"),
    CONSTRAINT "sale_lines_chk_values" CHECK ("unit_price" > 0 AND "unit_member_price" BETWEEN 0 AND "unit_price"
        AND "unit_points" >= 0 AND "quantity" BETWEEN 1 AND 1000),
    CONSTRAINT "sale_lines_fk_sale" FOREIGN KEY ("sale_id") REFERENCES "sales" ("id"),
    CONSTRAINT "sale_lines_fk_product" FOREIGN KEY ("product_id") REFERENCES "products" ("id")
);
CREATE INDEX "sale_lines_ix_product" ON "sale_lines" ("product_id");
