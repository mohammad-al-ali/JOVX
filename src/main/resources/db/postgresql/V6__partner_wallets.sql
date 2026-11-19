-- Partner points wallet (plan decisions 18-23, 36, 58-61). One wallet point = 1 SYP, fixed.
-- balance: spendable points. held: points reserved by pending refund requests.

INSERT INTO permissions(id,"key",name_ar,description_ar,level,delegable_to_employee) VALUES
 (7,'wallet.manage','إدارة محافظ الشركاء','الموافقة على طلبات الشحن والإرجاع ورفضها، والشحن المباشر، وإعدادات المحافظ.','ADMIN',TRUE);

CREATE TABLE "partner_wallets" (
    "partner_id" char(36) NOT NULL,
    "balance" bigint NOT NULL DEFAULT 0,
    "held" bigint NOT NULL DEFAULT 0,
    "updated_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("partner_id"),
    CONSTRAINT "partner_wallets_chk_balance" CHECK ("balance" >= 0),
    CONSTRAINT "partner_wallets_chk_held" CHECK ("held" >= 0),
    CONSTRAINT "partner_wallets_fk_partner" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id")
);

CREATE TABLE "wallet_requests" (
    "id" char(36) NOT NULL,
    "request_id" char(36) NOT NULL,
    "partner_id" char(36) NOT NULL,
    "kind" varchar(16) NOT NULL,
    "amount" bigint NOT NULL,
    "method" varchar(16) NOT NULL,
    "reference" varchar(200) DEFAULT NULL,
    "note" varchar(500) DEFAULT NULL,
    "status" varchar(16) NOT NULL,
    "requested_by" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    "decided_by" char(36) DEFAULT NULL,
    "decided_at" timestamp(6) DEFAULT NULL,
    "decision_reason" varchar(500) DEFAULT NULL,
    "payout_method" varchar(16) DEFAULT NULL,
    "payout_reference" varchar(200) DEFAULT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "wallet_requests_uq_request" UNIQUE ("request_id"),
    CONSTRAINT "wallet_requests_chk_kind" CHECK ("kind" IN ('TOPUP','REFUND')),
    CONSTRAINT "wallet_requests_chk_amount" CHECK ("amount" > 0),
    CONSTRAINT "wallet_requests_chk_method" CHECK ("method" IN ('CASH','TRANSFER','SHAM_CASH','OTHER')),
    CONSTRAINT "wallet_requests_chk_payout" CHECK ("payout_method" IS NULL OR "payout_method" IN ('CASH','TRANSFER','SHAM_CASH','OTHER')),
    CONSTRAINT "wallet_requests_chk_status" CHECK ("status" IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT "wallet_requests_chk_decision" CHECK (("status" = 'PENDING') = ("decided_at" IS NULL)),
    CONSTRAINT "wallet_requests_chk_reason" CHECK ("status" <> 'REJECTED' OR char_length(btrim("decision_reason")) > 0),
    CONSTRAINT "wallet_requests_fk_partner" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id"),
    CONSTRAINT "wallet_requests_fk_requester" FOREIGN KEY ("requested_by") REFERENCES "users" ("id"),
    CONSTRAINT "wallet_requests_fk_decider" FOREIGN KEY ("decided_by") REFERENCES "users" ("id")
);
CREATE INDEX "wallet_requests_ix_status" ON "wallet_requests" ("status","created_at");
CREATE INDEX "wallet_requests_ix_partner" ON "wallet_requests" ("partner_id","created_at" DESC);

-- Append-only movements of the spendable balance; refunds move points into "held" first.
CREATE TABLE "wallet_ledger" (
    "id" char(36) NOT NULL,
    "partner_id" char(36) NOT NULL,
    "delta" bigint NOT NULL,
    "balance_after" bigint NOT NULL,
    "kind" varchar(32) NOT NULL,
    "wallet_request_id" char(36) DEFAULT NULL,
    "actor_id" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "wallet_ledger_chk_delta" CHECK ("delta" <> 0),
    CONSTRAINT "wallet_ledger_chk_balance" CHECK ("balance_after" >= 0),
    CONSTRAINT "wallet_ledger_chk_kind" CHECK ("kind" IN ('TOPUP_APPROVED','DIRECT_TOPUP','REFUND_HELD','REFUND_RELEASED')),
    CONSTRAINT "wallet_ledger_fk_wallet" FOREIGN KEY ("partner_id") REFERENCES "partner_wallets" ("partner_id"),
    CONSTRAINT "wallet_ledger_fk_request" FOREIGN KEY ("wallet_request_id") REFERENCES "wallet_requests" ("id"),
    CONSTRAINT "wallet_ledger_fk_actor" FOREIGN KEY ("actor_id") REFERENCES "users" ("id")
);
CREATE INDEX "wallet_ledger_ix_partner" ON "wallet_ledger" ("partner_id","created_at" DESC,"id" DESC);

-- Exactly one settings row; limits apply to partner requests and direct top-ups (decision 58).
CREATE TABLE "wallet_settings" (
    "id" smallint NOT NULL,
    "min_topup" bigint NOT NULL,
    "max_topup" bigint NOT NULL,
    "low_balance_threshold" bigint NOT NULL,
    "updated_by" char(36) DEFAULT NULL,
    "updated_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "wallet_settings_chk_single" CHECK ("id" = 1),
    CONSTRAINT "wallet_settings_chk_limits" CHECK ("min_topup" >= 1 AND "max_topup" >= "min_topup"),
    CONSTRAINT "wallet_settings_chk_threshold" CHECK ("low_balance_threshold" >= 0),
    CONSTRAINT "wallet_settings_fk_user" FOREIGN KEY ("updated_by") REFERENCES "users" ("id")
);
-- Starting values only: min 1, a high max, alert under 200 (decision 23). Administration adjusts them.
INSERT INTO wallet_settings(id,min_topup,max_topup,low_balance_threshold) VALUES (1,1,1000000000,200);
