-- Add super_admin to the user_role enum.
-- Must be a separate migration from V3 because PostgreSQL does not allow
-- using a newly added enum value within the same transaction.
ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'super_admin';
