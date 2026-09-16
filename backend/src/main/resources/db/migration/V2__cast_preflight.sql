-- Pre-flight helper: can this text value be cast to this type?
--
-- Postgres has no "would this cast succeed" predicate, so the honest way to ask is to attempt the
-- cast and catch the failure. This exists so a merge can tell the user "143 rows would fail to
-- convert" BEFORE the migration starts, rather than having them discover it ten minutes into a
-- backfill on row 14,233,901.
--
-- A branch only holds a sampled ~1000 rows, so a cast that succeeds there proves nothing about
-- the other 40 million. This function is how that gap gets closed against the real table.

CREATE OR REPLACE FUNCTION sv.can_cast(value text, target_type text)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL THEN
        RETURN true;   -- NULL casts to anything; NOT NULL is checked separately
    END IF;
    EXECUTE format('SELECT %L::%s', value, target_type);
    RETURN true;
EXCEPTION
    -- Deliberately narrow. A malformed value or a numeric overflow is the answer we are looking
    -- for; anything else (a bad type name, a missing cast) is a bug in the caller and should
    -- surface rather than be silently reported as "row would fail".
    WHEN invalid_text_representation
       OR numeric_value_out_of_range
       OR datetime_field_overflow
       OR invalid_datetime_format
       OR string_data_right_truncation THEN
        RETURN false;
END $$;

COMMENT ON FUNCTION sv.can_cast(text, text) IS
    'Pre-flight check: true if value can be cast to target_type. Used to count rows that would '
    'break a type change before the migration runs.';
