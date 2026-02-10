-- Create sequence for User ID generation
CREATE SEQUENCE IF NOT EXISTS t_users_seq START WITH 1 INCREMENT BY 50;

-- Create partitioned users table
-- Partitioning by is_test provides physical separation of test and production data
CREATE TABLE t_users (
    id BIGINT NOT NULL DEFAULT nextval('t_users_seq'),
    username VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    is_test BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (id, is_test)  -- Partition key must be part of primary key
) PARTITION BY LIST (is_test);

-- Production partition (is_test = false)
CREATE TABLE t_users_production PARTITION OF t_users
    FOR VALUES IN (false);

-- Test partition (is_test = true)
CREATE TABLE t_users_test PARTITION OF t_users
    FOR VALUES IN (true);

-- Create index on production partition for username lookups
-- Note: Partial index (WHERE is_test = false) is unnecessary here since
-- all rows in t_users_production already have is_test = false by definition
CREATE INDEX idx_users_production_username ON t_users_production(username);
