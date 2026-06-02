-- Promote the seed super-admin user to the new super_admin role.
-- Runs after V2 has committed the new enum value.
UPDATE users
SET    role = 'super_admin'
WHERE  email = 'superadmin@amalitech.com';
