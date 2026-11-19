-- Default city and neighborhood preselected in location forms and the public directory filter
-- (user decision 2026-11-19). Exactly one row at most; administration sets and changes it.
-- No seed: reference ids differ between environments, so the value is set through the API.
CREATE TABLE "reference_defaults" (
    "id" smallint NOT NULL DEFAULT 1,
    "city_id" char(36) NOT NULL,
    "address_id" char(36) NOT NULL,
    "updated_by" char(36) NOT NULL,
    "updated_at" timestamp(6) NOT NULL,
    PRIMARY KEY ("id"),
    CONSTRAINT "reference_defaults_chk_single" CHECK ("id" = 1),
    -- The neighborhood must belong to the city (same pair the branches use).
    CONSTRAINT "reference_defaults_fk_location" FOREIGN KEY ("city_id", "address_id") REFERENCES "addresses" ("city_id", "id"),
    CONSTRAINT "reference_defaults_fk_actor" FOREIGN KEY ("updated_by") REFERENCES "users" ("id")
);
