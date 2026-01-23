-- Create sequence for User ID generation
CREATE SEQUENCE IF NOT EXISTS t_users_seq START WITH 1 INCREMENT BY 50;

-- Create users table
CREATE TABLE t_users (
    id BIGINT NOT NULL DEFAULT nextval('t_users_seq'),
    username VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    is_test BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (id)
);

-- Create partial index on real data
CREATE INDEX idx_users_real ON t_users(username) where is_test = false;
