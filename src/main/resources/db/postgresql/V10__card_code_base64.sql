-- The external printing system encodes card codes as Base64 in a PDF417 barcode
-- (e.g. "KpdvPmdCaP+QxJrw=="): allow '+', '/' and '=' and up to 128 characters.
ALTER TABLE "member_cards" DROP CONSTRAINT "member_cards_chk_code";
ALTER TABLE "member_cards" ALTER COLUMN "code" TYPE varchar(128);
ALTER TABLE "member_cards" ADD CONSTRAINT "member_cards_chk_code" CHECK ("code" ~ '^[A-Za-z0-9._+/=-]{4,128}$');
