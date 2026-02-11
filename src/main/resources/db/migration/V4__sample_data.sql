-- Seed sample users for demonstration
-- 5 production users (is_test = false)
-- 5 test users (is_test = true)

-- Production users
INSERT INTO t_users (id, username, password, email, is_test, created_date) VALUES
    (nextval('t_users_seq'), 'john.doe', 'password123', 'john.doe@example.com', false, '2026-02-01 10:00:00'),
    (nextval('t_users_seq'), 'jane.smith', 'password123', 'jane.smith@example.com', false, '2026-02-01 10:00:00'),
    (nextval('t_users_seq'), 'bob.wilson', 'password123', 'bob.wilson@example.com', false, '2026-02-01 10:00:00'),
    (nextval('t_users_seq'), 'alice.brown', 'password123', 'alice.brown@example.com', false, '2026-02-01 10:00:00'),
    (nextval('t_users_seq'), 'charlie.davis', 'password123', 'charlie.davis@example.com', false, '2026-02-01 10:00:00')
ON CONFLICT DO NOTHING;

-- Test users (for load testing)
INSERT INTO t_users (id, username, password, email, is_test, created_date) VALUES
    (nextval('t_users_seq'), 'test.user1', 'testpass123', 'test.user1@loadtest.com', true, '2026-02-01 10:00:00'),
    (nextval('t_users_seq'), 'test.user2', 'testpass123', 'test.user2@loadtest.com', true, '2026-02-01 10:00:00'),
    (nextval('t_users_seq'), 'test.user3', 'testpass123', 'test.user3@loadtest.com', true, '2026-02-01 10:00:00'),
    (nextval('t_users_seq'), 'test.user4', 'testpass123', 'test.user4@loadtest.com', true, '2026-02-01 10:00:00'),
    (nextval('t_users_seq'), 'test.user5', 'testpass123', 'test.user5@loadtest.com', true, '2026-02-01 10:00:00')
ON CONFLICT DO NOTHING;
