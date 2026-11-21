-- Public product search (2026-11-21): Arabic-insensitive matching shared with
-- CatalogModels.ArabicText.normalize. Lower case, no tashkeel (U+064B..U+065F,
-- U+0670) or tatweel (U+0640), and أ/إ/آ→ا, ى→ي, ة→ه. IMMUTABLE so it can back
-- an expression index later (a pg_trgm index is deferred until volume needs it).
CREATE FUNCTION jovx_ar_norm(value text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    SET search_path = pg_catalog
AS $$
    SELECT regexp_replace(
        translate(regexp_replace(lower(btrim(coalesce(value, ''))), '[\u064B-\u065F\u0670\u0640]', '', 'g'),
                  'أإآىة', 'ااايه'),
        '\s+', ' ', 'g')
$$;
