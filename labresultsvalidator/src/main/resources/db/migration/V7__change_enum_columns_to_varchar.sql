-- Convert remaining native PostgreSQL enum columns to varchar so the
-- JPA AttributeConverters can bind values without a type-mismatch error.

-- Drop dependent view first
DROP VIEW IF EXISTS reporting.vw_upload_audit;

-- Convert modules status column
ALTER TABLE modules
ALTER COLUMN status TYPE varchar USING status::text;

-- Convert csv_uploads status column
ALTER TABLE csv_uploads
ALTER COLUMN status TYPE varchar USING status::text;

-- Recreate the view with the same definition
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