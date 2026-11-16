-- Preserve all existing IDs. Conflicting legacy names must be resolved explicitly;
-- the unique indexes fail atomically instead of deleting or merging records.
-- Ignore case and repeated whitespace, but preserve Arabic letters and diacritics.
CREATE UNIQUE INDEX uq_cities_normalized_name
    ON cities (lower(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g'))));
CREATE UNIQUE INDEX uq_categories_normalized_name
    ON categories (lower(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g'))));
CREATE UNIQUE INDEX uq_addresses_city_normalized_name
    ON addresses (city_id, lower(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g'))));

ALTER TABLE cities ADD CONSTRAINT ck_cities_nonblank_name
    CHECK (length(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g'))) > 0);
ALTER TABLE categories ADD CONSTRAINT ck_categories_nonblank_name
    CHECK (length(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g'))) > 0);
ALTER TABLE addresses ADD CONSTRAINT ck_addresses_nonblank_name
    CHECK (length(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g'))) > 0);
