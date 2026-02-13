-- RLS Policies for dedicated users (app_real_user and app_test_user)
-- These users have fixed access based on their role, no session variable needed

-- Policies for app_real_user (can only access production data: is_test = false)
CREATE POLICY real_user_select_policy ON t_users
    FOR SELECT TO app_real_user
    USING (is_test = false);

CREATE POLICY real_user_insert_policy ON t_users
    FOR INSERT TO app_real_user
    WITH CHECK (is_test = false);

CREATE POLICY real_user_update_policy ON t_users
    FOR UPDATE TO app_real_user
    USING (is_test = false)
    WITH CHECK (is_test = false);

-- Policies for app_test_user (can only access test data: is_test = true)
CREATE POLICY test_user_select_policy ON t_users
    FOR SELECT TO app_test_user
    USING (is_test = true);

CREATE POLICY test_user_insert_policy ON t_users
    FOR INSERT TO app_test_user
    WITH CHECK (is_test = true);

CREATE POLICY test_user_update_policy ON t_users
    FOR UPDATE TO app_test_user
    USING (is_test = true)
    WITH CHECK (is_test = true);

-- Force RLS for table owner as well
ALTER TABLE t_users FORCE ROW LEVEL SECURITY;