-- Phase 5 notifications (plan decisions 15, 29-31; user decision 2026-11-21: members and partners).
-- Every notification is an inbox row written in the same transaction as the business change;
-- the push copy waits in an outbox and is sent after commit with retries. Partner notifications go
-- to the owning partner only, never to its employees (decision 31). SUPER_ADMIN switches kinds on/off.

INSERT INTO permissions(id,"key",name_ar,description_ar,level,delegable_to_employee) VALUES
 (9,'notification.manage','رسائل الشركاء','إرسال رسائل الإدارة إلى الشركاء كإشعارات.','ADMIN',FALSE);

-- One row per installed app; a token re-registered by another account moves to it.
CREATE TABLE "device_tokens" (
    "token" varchar(512) NOT NULL,
    "account_id" char(36) NOT NULL,
    "platform" varchar(16) NOT NULL,
    "updated_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("token"),
    CONSTRAINT "device_tokens_chk_platform" CHECK ("platform" IN ('ANDROID','IOS','WEB')),
    CONSTRAINT "device_tokens_fk_account" FOREIGN KEY ("account_id") REFERENCES "users" ("id")
);
CREATE INDEX "device_tokens_ix_account" ON "device_tokens" ("account_id");

CREATE TABLE "notifications" (
    "id" char(36) NOT NULL,
    "account_id" char(36) NOT NULL,
    "kind" varchar(40) NOT NULL,
    "title" varchar(120) NOT NULL,
    "body" varchar(500) NOT NULL,
    -- Small string map for the app (e.g. saleId); never credentials.
    "data" jsonb NOT NULL DEFAULT '{}'::jsonb,
    "created_at" timestamp(6) NOT NULL,
    "read_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "notifications_fk_account" FOREIGN KEY ("account_id") REFERENCES "users" ("id")
);
CREATE INDEX "notifications_ix_account" ON "notifications" ("account_id","created_at" DESC);

-- PENDING until sent; SKIPPED when there is no device or push is not configured; FAILED after retries.
CREATE TABLE "notification_outbox" (
    "notification_id" char(36) NOT NULL,
    "status" varchar(16) NOT NULL,
    "attempts" integer NOT NULL DEFAULT 0,
    "next_attempt_at" timestamp(6) NOT NULL,
    "last_error" varchar(300) DEFAULT NULL,
    "updated_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("notification_id"),
    CONSTRAINT "notification_outbox_chk_status" CHECK ("status" IN ('PENDING','SENT','SKIPPED','FAILED')),
    CONSTRAINT "notification_outbox_fk_notification" FOREIGN KEY ("notification_id") REFERENCES "notifications" ("id")
);
CREATE INDEX "notification_outbox_ix_due" ON "notification_outbox" ("next_attempt_at") WHERE "status" = 'PENDING';

-- Decision 30: SUPER_ADMIN enables or disables each kind.
CREATE TABLE "notification_event_settings" (
    "kind" varchar(40) NOT NULL,
    "audience" varchar(16) NOT NULL,
    "enabled" boolean NOT NULL DEFAULT TRUE,
    "updated_by" char(36) DEFAULT NULL,
    "updated_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("kind"),
    CONSTRAINT "notification_event_settings_chk_audience" CHECK ("audience" IN ('MEMBER','PARTNER')),
    CONSTRAINT "notification_event_settings_fk_user" FOREIGN KEY ("updated_by") REFERENCES "users" ("id")
);
INSERT INTO notification_event_settings(kind,audience) VALUES
 ('SALE_RECEIPT','MEMBER'),
 ('SALE_CANCEL_REQUESTED','MEMBER'),
 ('SALE_CANCEL_RESULT','PARTNER'),
 ('LOW_BALANCE','PARTNER'),
 ('WALLET_REQUEST_RESULT','PARTNER'),
 ('PRODUCT_REVIEWED','PARTNER'),
 ('ADMIN_MESSAGE','PARTNER');

-- Administration messages to one partner or to every active partner.
CREATE TABLE "admin_messages" (
    "id" char(36) NOT NULL,
    "request_id" char(36) NOT NULL,
    "partner_id" char(36) DEFAULT NULL,
    "title" varchar(120) NOT NULL,
    "body" varchar(500) NOT NULL,
    "recipients" integer NOT NULL,
    "sent_by" char(36) NOT NULL,
    "created_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "admin_messages_uq_request" UNIQUE ("request_id"),
    CONSTRAINT "admin_messages_fk_partner" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id"),
    CONSTRAINT "admin_messages_fk_sender" FOREIGN KEY ("sent_by") REFERENCES "users" ("id")
);

-- Owned by wallets: one low-balance alert per partner per Damascus day (decision 23).
CREATE TABLE "wallet_low_balance_alerts" (
    "partner_id" char(36) NOT NULL,
    "alert_date" date NOT NULL,
    "balance" bigint NOT NULL,
    PRIMARY KEY ("partner_id","alert_date"),
    CONSTRAINT "wallet_low_balance_alerts_fk_partner" FOREIGN KEY ("partner_id") REFERENCES "partner_owners" ("id")
);
