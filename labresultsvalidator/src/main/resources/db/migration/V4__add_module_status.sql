-- Add module_status type and status column to modules table
CREATE TYPE module_status AS ENUM ('active', 'archived');

ALTER TABLE modules
    ADD COLUMN status module_status NOT NULL DEFAULT 'active';
