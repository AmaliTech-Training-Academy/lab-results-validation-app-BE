-- Drop the dependent view first so PostgreSQL allows the column type change.
DROP VIEW IF EXISTS reporting.vw_upload_audit;

-- Change the role column from the native user_role enum type to varchar.
-- This allows the UserRoleConverter to bridge the lowercase DB values
-- ('admin', 'instructor', 'super_admin') and the uppercase Java enum constants
-- (ADMIN, INSTRUCTOR, SUPER_ADMIN) without requiring a rename of enum values.
ALTER TABLE users ALTER COLUMN role TYPE varchar USING role::text;

-- Recreate the view with the same definition.
-- uploaded_by_role is now varchar instead of user_role enum,
-- which is consistent with the column change above.
CREATE OR REPLACE VIEW reporting.vw_upload_audit AS
SELECT
    cu.id            AS upload_id,
    cu.filename,
    cu.file_sha256,
    cu.uploaded_at,
    cu.total_rows,
    cu.accepted_rows,
    cu.rejected_rows,
    cu.status,
    u.email          AS uploaded_by_email,
    u.role           AS uploaded_by_role
FROM csv_uploads cu
         JOIN users u ON u.id = cu.uploaded_by_user_id;