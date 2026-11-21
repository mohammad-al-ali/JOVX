-- Invoice cancellation with the member's consent (user decision 2026-11-21, supersedes decision 7).
-- The seller (or the partner) asks within 24 hours of the sale; the member accepts or rejects
-- within 24 hours of the request. Acceptance takes the points back from the member, returns them
-- to the partner wallet and restores product stock, all in one transaction. A member whose
-- balance is below the invoice points cannot accept (the request stays pending until it expires).

ALTER TABLE "sales" ADD COLUMN "status" varchar(16) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE "sales" ADD COLUMN "cancelled_at" timestamp(6) DEFAULT NULL;
ALTER TABLE "sales" ADD CONSTRAINT "sales_chk_status"
  CHECK (("status" = 'COMPLETED' AND "cancelled_at" IS NULL) OR ("status" = 'CANCELLED' AND "cancelled_at" IS NOT NULL));

CREATE TABLE "sale_cancellations" (
    "id" char(36) NOT NULL,
    "sale_id" char(36) NOT NULL,
    "requested_by" char(36) NOT NULL,
    "requested_at" timestamp(6) NOT NULL,
    "expires_at" timestamp(6) NOT NULL,
    -- PENDING until the member decides; EXPIRED is written when a pending request is seen past expires_at.
    "status" varchar(16) NOT NULL,
    "reason" varchar(300) DEFAULT NULL,
    "decided_at" timestamp(6) DEFAULT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "sale_cancellations_chk_status" CHECK ("status" IN ('PENDING','ACCEPTED','REJECTED','EXPIRED')),
    CONSTRAINT "sale_cancellations_chk_decided" CHECK (("status" = 'PENDING') = ("decided_at" IS NULL)),
    CONSTRAINT "sale_cancellations_chk_window" CHECK ("expires_at" > "requested_at"),
    CONSTRAINT "sale_cancellations_fk_sale" FOREIGN KEY ("sale_id") REFERENCES "sales" ("id"),
    CONSTRAINT "sale_cancellations_fk_requester" FOREIGN KEY ("requested_by") REFERENCES "users" ("id")
);
-- At most one open request and at most one accepted request per sale.
CREATE UNIQUE INDEX "sale_cancellations_uq_pending" ON "sale_cancellations" ("sale_id") WHERE "status" = 'PENDING';
CREATE UNIQUE INDEX "sale_cancellations_uq_accepted" ON "sale_cancellations" ("sale_id") WHERE "status" = 'ACCEPTED';
CREATE INDEX "sale_cancellations_ix_sale" ON "sale_cancellations" ("sale_id","requested_at" DESC);

-- Returned sale points re-enter the partner wallet, referencing the sale.
ALTER TABLE "wallet_ledger" DROP CONSTRAINT "wallet_ledger_chk_kind";
ALTER TABLE "wallet_ledger" ADD CONSTRAINT "wallet_ledger_chk_kind"
  CHECK ("kind" IN ('TOPUP_APPROVED','DIRECT_TOPUP','REFUND_HELD','REFUND_RELEASED','SALE_POINTS','SALE_POINTS_RETURNED'));
