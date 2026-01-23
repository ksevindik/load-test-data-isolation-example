-- Enable Row Level Security on t_users table
ALTER TABLE t_users ENABLE ROW LEVEL SECURITY;

-- Policy: Complete data isolation between test and production
-- When app.test_mode = 'true'  -> Only test data visible (is_test = true)
-- When app.test_mode = 'false' or not set -> Only prod data visible (is_test = false)

CREATE POLICY users_test_isolation_policy_for_read ON t_users
    -- FOR ALL
    FOR SELECT TO app_user
    USING (
        CASE
            WHEN current_setting('app.test_mode', true) = 'true' THEN is_test = true
            ELSE is_test = false
        END
    );
CREATE POLICY users_test_isolation_policy_for_insert ON t_users
    FOR INSERT TO app_user
    WITH CHECK (
        CASE
            WHEN current_setting('app.test_mode', true) = 'true' THEN is_test = true
            ELSE is_test = false
        END
    );

CREATE POLICY users_test_isolation_policy_for_update ON t_users
    FOR UPDATE TO app_user
    USING (
        CASE
            WHEN current_setting('app.test_mode', true) = 'true' THEN is_test = true
            ELSE is_test = false
        END
    )
    WITH CHECK (
        CASE
            WHEN current_setting('app.test_mode', true) = 'true' THEN is_test = true
            ELSE is_test = false
        END
    );

-- Force RLS for table owner as well
ALTER TABLE t_users FORCE ROW LEVEL SECURITY;