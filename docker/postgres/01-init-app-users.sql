-- Create application user (non-superuser) for RLS to work
CREATE USER app_user WITH PASSWORD 'secret';
CREATE USER app_real_user WITH PASSWORD 'secret';
CREATE USER app_test_user WITH PASSWORD 'secret';

-- Grant connect permission
GRANT CONNECT ON DATABASE loadtest TO app_user;
GRANT CONNECT ON DATABASE loadtest TO app_real_user;
GRANT CONNECT ON DATABASE loadtest TO app_test_user;

GRANT USAGE ON SCHEMA public TO app_user;
GRANT USAGE ON SCHEMA public TO app_real_user;
GRANT USAGE ON SCHEMA public TO app_test_user;


-- Grant permissions on future tables (Flyway will create them)
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_real_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_test_user;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO app_real_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO app_test_user;
