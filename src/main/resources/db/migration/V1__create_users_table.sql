-- Create sequence for User ID generation
CREATE SEQUENCE IF NOT EXISTS t_users_seq START WITH 1 INCREMENT BY 50;

-- Create composite partitioned users table
-- Level 1: LIST partition by is_test (production vs test data separation)
-- Level 2: RANGE partition by created_date (yearly intervals)
CREATE TABLE t_users (
    id BIGINT NOT NULL DEFAULT nextval('t_users_seq'),
    username VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    is_test BOOLEAN NOT NULL DEFAULT false,
    created_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    PRIMARY KEY (id, is_test, created_date)  -- All partition keys must be part of PK
) PARTITION BY LIST (is_test);

-- Production partition (is_test = false), sub-partitioned by RANGE on created_date
CREATE TABLE t_users_production PARTITION OF t_users
    FOR VALUES IN (false)
    PARTITION BY RANGE (created_date);

-- Test partition (is_test = true), sub-partitioned by RANGE on created_date
CREATE TABLE t_users_test PARTITION OF t_users
    FOR VALUES IN (true)
    PARTITION BY RANGE (created_date);

-- Create yearly sub-partitions for production data
CREATE TABLE t_users_production_2026 PARTITION OF t_users_production
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE t_users_production_2027 PARTITION OF t_users_production
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');

-- Create yearly sub-partitions for test data
CREATE TABLE t_users_test_2026 PARTITION OF t_users_test
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE t_users_test_2027 PARTITION OF t_users_test
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');

-- Create index on production partitions for username lookups
CREATE INDEX idx_users_production_username ON t_users_production(username);
