-- Drop native PostgreSQL enum types that are no longer used by any column.
-- All three columns were converted to varchar in V6 and V7; the type
-- definitions were left behind and are cleaned up here.

DROP TYPE IF EXISTS user_role;
DROP TYPE IF EXISTS module_status;
DROP TYPE IF EXISTS upload_status;