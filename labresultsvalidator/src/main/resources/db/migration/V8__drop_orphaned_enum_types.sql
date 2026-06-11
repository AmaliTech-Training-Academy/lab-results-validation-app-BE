-- Drop native PostgreSQL enum types that are no longer used by any column.
-- All three columns were converted to varchar in V6 and V7; the type
-- definitions were left behind and are cleaned up here.

DROP TYPE IF EXISTS user_role CASCADE;
DROP TYPE IF EXISTS module_status CASCADE;
DROP TYPE IF EXISTS upload_status CASCADE;

-- CASCADE removes column defaults that referenced the dropped enum types.
-- Restore plain varchar defaults so inserts without an explicit value still work.
ALTER TABLE modules ALTER COLUMN status SET DEFAULT 'active';
ALTER TABLE csv_uploads ALTER COLUMN status SET DEFAULT 'pending';