-- Member foundation (plan decisions 43-57). Amounts: integer in the currency's smallest
-- unit plus ISO code (SYP whole pounds). All timestamps UTC; calendar terms use Asia/Damascus.

INSERT INTO permissions(id,"key",name_ar,description_ar,level,delegable_to_employee) VALUES
 (5,'member.manage','إدارة الأعضاء','بيع العضوية وتجديدها، ودفعات البطاقات، والخطط، وإيقاف البطاقة، وإعادة تعيين PIN العضو.','ADMIN',TRUE),
 (6,'admin.employee.manage','إدارة موظفي الإدارة','إنشاء موظفي الإدارة وتحديد صلاحياتهم.','ADMIN',FALSE);

CREATE TABLE "membership_plans" (
    "id" char(36) NOT NULL,
    "name_ar" varchar(100) NOT NULL,
    "price_amount" bigint NOT NULL,
    "currency" char(3) NOT NULL,
    "duration_months" smallint NOT NULL,
    "family_slots" smallint NOT NULL,
    "active" boolean NOT NULL DEFAULT TRUE,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "membership_plans_chk_name" CHECK (char_length(btrim("name_ar")) > 0),
    CONSTRAINT "membership_plans_chk_price" CHECK ("price_amount" >= 0),
    CONSTRAINT "membership_plans_chk_currency" CHECK ("currency" ~ '^[A-Z]{3}$'),
    CONSTRAINT "membership_plans_chk_duration" CHECK ("duration_months" BETWEEN 1 AND 120),
    CONSTRAINT "membership_plans_chk_family" CHECK ("family_slots" BETWEEN 0 AND 20)
);
CREATE UNIQUE INDEX "membership_plans_uq_name" ON "membership_plans" (lower(btrim("name_ar")));

CREATE SEQUENCE "member_number_seq" START 1;

CREATE TABLE "members" (
    "account_id" char(36) NOT NULL,
    "member_number" varchar(20) NOT NULL,
    "plan_id" char(36) NOT NULL,
    "starts_at" timestamp(6) NOT NULL,
    "expires_at" timestamp(6) NOT NULL,
    "sold_by" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("account_id"),
    CONSTRAINT "members_uq_number" UNIQUE ("member_number"),
    CONSTRAINT "members_chk_term" CHECK ("expires_at" > "starts_at"),
    CONSTRAINT "members_fk_account" FOREIGN KEY ("account_id") REFERENCES "users" ("id"),
    CONSTRAINT "members_fk_plan" FOREIGN KEY ("plan_id") REFERENCES "membership_plans" ("id"),
    CONSTRAINT "members_fk_seller" FOREIGN KEY ("sold_by") REFERENCES "users" ("id")
);

-- Printed by an external system; registered here as stock before sale (decisions 49, 54).
CREATE TABLE "member_cards" (
    "id" char(36) NOT NULL,
    "code" varchar(64) NOT NULL,
    "batch_label" varchar(100) NOT NULL,
    "status" varchar(16) NOT NULL,
    "member_account_id" char(36) DEFAULT NULL,
    "registered_by" char(36) NOT NULL,
    "registered_at" timestamp(6) NOT NULL,
    "assigned_at" timestamp(6) DEFAULT NULL,
    "suspended_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "member_cards_uq_code" UNIQUE ("code"),
    CONSTRAINT "member_cards_chk_code" CHECK ("code" ~ '^[A-Za-z0-9._-]{4,64}$'),
    CONSTRAINT "member_cards_chk_status" CHECK ("status" IN ('IN_STOCK','ASSIGNED','SUSPENDED')),
    CONSTRAINT "member_cards_chk_owner" CHECK (("status" = 'IN_STOCK') = ("member_account_id" IS NULL)),
    CONSTRAINT "member_cards_fk_member" FOREIGN KEY ("member_account_id") REFERENCES "members" ("account_id"),
    CONSTRAINT "member_cards_fk_registrar" FOREIGN KEY ("registered_by") REFERENCES "users" ("id")
);
-- Suspend only, no replacement (decision 50): one card per member, ever.
CREATE UNIQUE INDEX "member_cards_uq_member" ON "member_cards" ("member_account_id") WHERE "member_account_id" IS NOT NULL;
CREATE INDEX "member_cards_ix_status" ON "member_cards" ("status","registered_at");

CREATE TABLE "membership_payments" (
    "id" char(36) NOT NULL,
    "request_id" char(36) NOT NULL,
    "member_account_id" char(36) NOT NULL,
    "plan_id" char(36) NOT NULL,
    "kind" varchar(16) NOT NULL,
    "amount" bigint NOT NULL,
    "currency" char(3) NOT NULL,
    "method" varchar(16) NOT NULL,
    "reference" varchar(200) DEFAULT NULL,
    "previous_expires_at" timestamp(6) DEFAULT NULL,
    "new_expires_at" timestamp(6) NOT NULL,
    "recorded_by" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "membership_payments_uq_request" UNIQUE ("request_id"),
    CONSTRAINT "membership_payments_chk_kind" CHECK ("kind" IN ('SALE','RENEWAL')),
    CONSTRAINT "membership_payments_chk_amount" CHECK ("amount" >= 0),
    CONSTRAINT "membership_payments_chk_currency" CHECK ("currency" ~ '^[A-Z]{3}$'),
    CONSTRAINT "membership_payments_chk_method" CHECK ("method" IN ('CASH','TRANSFER','OTHER')),
    CONSTRAINT "membership_payments_fk_member" FOREIGN KEY ("member_account_id") REFERENCES "members" ("account_id"),
    CONSTRAINT "membership_payments_fk_plan" FOREIGN KEY ("plan_id") REFERENCES "membership_plans" ("id"),
    CONSTRAINT "membership_payments_fk_recorder" FOREIGN KEY ("recorded_by") REFERENCES "users" ("id")
);
CREATE INDEX "membership_payments_ix_member" ON "membership_payments" ("member_account_id","created_at");

CREATE TABLE "membership_events" (
    "id" char(36) NOT NULL,
    "member_account_id" char(36) NOT NULL,
    "actor_id" char(36) NOT NULL,
    "action" varchar(40) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "membership_events_fk_member" FOREIGN KEY ("member_account_id") REFERENCES "members" ("account_id"),
    CONSTRAINT "membership_events_fk_actor" FOREIGN KEY ("actor_id") REFERENCES "users" ("id")
);
CREATE INDEX "membership_events_ix_member" ON "membership_events" ("member_account_id","created_at");

-- Short-lived QR tokens (60 s). Only the digest is stored; consumption arrives with point of sale.
CREATE TABLE "member_scan_tokens" (
    "token_digest" char(64) NOT NULL,
    "member_account_id" char(36) NOT NULL,
    "card_id" char(36) NOT NULL,
    "issued_at" timestamp(6) NOT NULL,
    "expires_at" timestamp(6) NOT NULL,
    "consumed_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("token_digest"),
    CONSTRAINT "member_scan_tokens_fk_member" FOREIGN KEY ("member_account_id") REFERENCES "members" ("account_id"),
    CONSTRAINT "member_scan_tokens_fk_card" FOREIGN KEY ("card_id") REFERENCES "member_cards" ("id")
);
CREATE INDEX "member_scan_tokens_ix_member" ON "member_scan_tokens" ("member_account_id");

-- Points: whole points only. Written by later phases (sales, gifts); read by the member now.
CREATE TABLE "points_accounts" (
    "member_account_id" char(36) NOT NULL,
    "balance" bigint NOT NULL DEFAULT 0,
    "updated_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("member_account_id"),
    CONSTRAINT "points_accounts_chk_balance" CHECK ("balance" >= 0),
    CONSTRAINT "points_accounts_fk_member" FOREIGN KEY ("member_account_id") REFERENCES "users" ("id")
);

CREATE TABLE "points_ledger" (
    "id" char(36) NOT NULL,
    "member_account_id" char(36) NOT NULL,
    "delta" bigint NOT NULL,
    "balance_after" bigint NOT NULL,
    "kind" varchar(32) NOT NULL,
    "reference_id" char(36) DEFAULT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "points_ledger_chk_delta" CHECK ("delta" <> 0),
    CONSTRAINT "points_ledger_chk_balance" CHECK ("balance_after" >= 0),
    CONSTRAINT "points_ledger_fk_account" FOREIGN KEY ("member_account_id") REFERENCES "points_accounts" ("member_account_id")
);
CREATE INDEX "points_ledger_ix_member" ON "points_ledger" ("member_account_id","created_at" DESC,"id" DESC);
