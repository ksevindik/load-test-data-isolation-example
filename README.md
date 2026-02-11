# Load Test Data Isolation Example

A Spring Boot application demonstrating **multi-layer data isolation** patterns for load testing. This project showcases strategies to isolate test data from production data across PostgreSQL, Redis, and Kafka.

## The Problem

When running load tests against a production-like environment, you need to:
- Prevent test data from polluting production data
- Prevent production data from being visible during load tests
- Easily identify and clean up test data after tests complete
- Ensure complete isolation across all layers (database, cache, messaging)

## Solution Overview

This project demonstrates data isolation at three layers:

| Layer | Strategy | Mechanism | Profile |
|-------|----------|-----------|---------|
| **PostgreSQL** | Row Level Security (RLS) | Session variable or dedicated users | Default / `datasource-routing` |
| **Redis** | Key Prefix Routing | `real:*` vs `test:*` with ACLs | Always enabled |
| **Kafka** | Topic-Based Routing | Separate topics with SASL/ACL | `topic-routing` |

## Architecture

### Traffic Context Management

All isolation strategies are driven by the `X-Traffic-Type` header:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              HTTP Request                                    │
│  Headers: X-Traffic-Type: LOAD_TEST, X-Test-Run-Id: uuid                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           TrafficTypeFilter                                  │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ TrafficContext.of(request) → TrafficContextManager.setTrafficContext() │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
          │                    │                    │
          ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│    PostgreSQL   │  │      Redis      │  │      Kafka      │
│  RLS / Routing  │  │  Key Prefixes   │  │  Topic Routing  │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

### Key Components

| Component | Package | Description |
|-----------|---------|-------------|
| `TrafficContext` | `traffic` | Data class holding `trafficType` and `testRunId` |
| `TrafficContextManager` | `traffic` | Manages ThreadLocal state and MDC for logging |
| `TrafficTypeFilter` | `traffic` | HTTP filter that extracts headers and sets context |
| `SessionBasedTestMode` | `traffic` | Sets PostgreSQL session variable for RLS |

## PostgreSQL Data Isolation

The `t_users` table uses **composite partitioning** with two levels:
- **Level 1 (LIST):** Partition by `is_test` to separate production and test data
- **Level 2 (RANGE):** Sub-partition by `created_date` with yearly intervals

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              PostgreSQL                                      │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │              t_users (Partitioned by LIST on is_test)                  │ │
│  │              + RLS Policies                                            │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                    │                              │                         │
│                    ▼                              ▼                         │
│  ┌─────────────────────────────────┐  ┌─────────────────────────────────┐ │
│  │ t_users_production (is_test=F)  │  │   t_users_test (is_test=T)      │ │
│  │ PARTITION BY RANGE (created_date)│  │ PARTITION BY RANGE (created_date)│ │
│  │                                 │  │                                 │ │
│  │  ┌─────────────────────────┐   │  │  ┌─────────────────────────┐   │ │
│  │  │ t_users_production_2026 │   │  │  │ t_users_test_2026       │   │ │
│  │  │ t_users_production_2027 │   │  │  │ t_users_test_2027       │   │ │
│  │  └─────────────────────────┘   │  │  └─────────────────────────┘   │ │
│  └─────────────────────────────────┘  └─────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Benefits of Composite Partitioning

| Benefit | Description |
|---------|-------------|
| **Physical Isolation** | Test and production data stored in separate physical segments |
| **Time-Based Management** | Yearly partitions enable efficient data lifecycle management |
| **Easy Cleanup** | Drop old yearly partitions: `DROP TABLE t_users_test_2026` |
| **Query Performance** | PostgreSQL prunes partitions based on both `is_test` AND `created_date` |
| **Independent Maintenance** | Can vacuum/analyze individual yearly partitions |
| **Archival** | Detach and archive old yearly partitions independently |

### Strategy 1: Session-Based Test Mode (Default)

Uses a PostgreSQL session variable (`app.test_mode`) to dynamically switch between test and production data. Combined with partitioning, this provides both logical and physical separation.

```
┌─────────────────────────────────────────────────────────────────┐
│                      Application                                 │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │   Filter    │───▶│  Service    │───▶│ Repository  │         │
│  │ (set mode)  │    │             │    │             │         │
│  └─────────────┘    └─────────────┘    └─────────────┘         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PostgreSQL (app_user)                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ SET app.test_mode = 'true' / 'false'                    │   │
│  │                                                          │   │
│  │ RLS Policy checks: current_setting('app.test_mode')     │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌──────────────────────┐    ┌──────────────────────┐         │
│  │ t_users_production   │    │   t_users_test       │         │
│  │  (is_test=false)     │    │  (is_test=true)      │         │
│  └──────────────────────┘    └──────────────────────┘         │
└─────────────────────────────────────────────────────────────────┘
```

**How it works:**
1. `TrafficTypeFilter` intercepts incoming requests
2. Checks for `X-Traffic-Type: LOAD_TEST` header
3. `SessionBasedTestMode` sets PostgreSQL session variable: `SET app.test_mode = 'true'`
4. RLS policy filters data based on the session variable
5. PostgreSQL routes queries to the appropriate partition

**Pros:** Single database connection/user, simple configuration, dynamic switching per request

**Cons:** Requires careful session management, session variable must be set on every request

### Strategy 2: Dedicated User with Routing (`datasource-routing` profile)

Uses separate database users (`app_real_user`, `app_test_user`) with fixed RLS policies.

```
┌─────────────────────────────────────────────────────────────────┐
│                      Application                                 │
│  ┌─────────────┐    ┌───────────────────────┐    ┌───────────┐ │
│  │   Filter    │───▶│ TrafficRoutingDataSource │──▶│ Repository │ │
│  │ (set ctx)   │    │                       │    │           │ │
│  └─────────────┘    └───────────────────────┘    └───────────┘ │
└─────────────────────────────────────────────────────────────────┘
          │                   │
          │         ┌─────────┴─────────┐
          │         ▼                   ▼
          │  ┌─────────────┐    ┌─────────────┐
          │  │app_real_user│    │app_test_user│
          │  └─────────────┘    └─────────────┘
          │         │                   │
          ▼         ▼                   ▼
┌─────────────────────────────────────────────────────────────────┐
│                       PostgreSQL                                 │
│                                                                 │
│  RLS Policy for app_real_user: is_test = false                 │
│  RLS Policy for app_test_user: is_test = true                  │
│                                                                 │
│  ┌──────────────────────┐    ┌──────────────────────┐         │
│  │ t_users_production   │    │   t_users_test       │         │
│  │  (is_test=false)     │    │  (is_test=true)      │         │
│  └──────────────────────┘    └──────────────────────┘         │
└─────────────────────────────────────────────────────────────────┘
```

**How it works:**
1. `TrafficTypeFilter` intercepts incoming requests and sets `TrafficContextManager`
2. `TrafficRoutingDataSource` selects appropriate connection pool based on context
3. RLS policy enforces data isolation based on the connected user
4. PostgreSQL routes queries to the appropriate partition

**Pros:** Complete isolation at connection level, no session variable management, more secure

**Cons:** Requires multiple connection pools, more complex configuration

### Test Data Cleanup

With composite partitioning, you have flexible cleanup options:

```sql
-- Option 1: Drop a specific yearly test partition
DROP TABLE t_users_test_2026;

-- Option 2: Truncate all test data for a specific year
TRUNCATE TABLE t_users_test_2026;

-- Option 3: Detach and archive old test partitions
ALTER TABLE t_users_test DETACH PARTITION t_users_test_2026;
-- (move to archive storage, then drop)
DROP TABLE t_users_test_2026;

-- Option 4: Create new yearly partition for upcoming year
CREATE TABLE t_users_test_2028 PARTITION OF t_users_test
    FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');
```

### Partition Maintenance

New yearly partitions must be created before data arrives. Options:
1. **Manual:** Create partitions ahead of time (e.g., create next year's partitions in December)
2. **Automated:** Use `pg_partman` extension or a scheduled job to auto-create partitions

## Redis Cache Isolation

Routes cache operations to use different key prefixes based on traffic type, with Redis ACL-enforced access control. This is enabled by default for all configurations.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Application                                     │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    RoutingCacheManager                               │   │
│  │   if trafficType == "LOAD_TEST" → testCacheManager                  │   │
│  │   else                          → realCacheManager                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                    │                              │                         │
│                    ▼                              ▼                         │
│  ┌──────────────────────────┐    ┌──────────────────────────────────┐     │
│  │    realCacheManager       │    │       testCacheManager           │     │
│  │  - keyPrefix: "real:"     │    │  - keyPrefix: "test:"            │     │
│  │  - TTL: 1 hour            │    │  - TTL: 10 minutes               │     │
│  │  - user: app_real_user    │    │  - user: app_test_user           │     │
│  └──────────────────────────┘    └──────────────────────────────────┘     │
└───────────────────┬──────────────────────────────┬──────────────────────────┘
                    │                              │
                    ▼                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Redis 7+                                        │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ user app_real_user on >real_pwd ~real:* +@all                        │  │
│  │ user app_test_user on >test_pwd ~test:* +@all                        │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌─────────────────────────┐    ┌─────────────────────────────────────┐   │
│  │   real:users::123       │    │   test:users::456                    │   │
│  │   (TTL: 1 hour)         │    │   (TTL: 10 minutes)                  │   │
│  └─────────────────────────┘    └─────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Redis Users and ACLs

| User | Key Pattern | TTL | Description |
|------|-------------|-----|-------------|
| `app_real_user` | `real:*` | 1 hour | Production cache entries |
| `app_test_user` | `test:*` | 10 min | Test cache entries (auto-cleanup) |

### Cache Key Examples

| Traffic Type | Cache Operation | Redis Key |
|--------------|----------------|-----------|
| Production | `@Cacheable(key = "#id")` | `real:users::123` |
| Load Test | `@Cacheable(key = "#id")` | `test:users::456` |

## Kafka Event Isolation

### Strategy 1: Single Topic with Header-Based Filtering (Default)

All events are published to a single topic (`user-events`) with traffic type indicated via Kafka headers. Consumers filter events based on the `X-Traffic-Type` header.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Application                                    │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              SingleTopicUserEventPublisher                        │   │
│  │   - Publishes ALL events to "user-events" topic                  │   │
│  │   - Adds X-Traffic-Type header (LOAD_TEST or PRODUCTION)         │   │
│  │   - Adds X-Test-Run-Id header for test traffic                   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────┬───────────────────────────────────┘
                                      │
                                      ▼
                        ┌─────────────────────────┐
                        │      user-events        │
                        │  (mixed test + prod)    │
                        │                         │
                        │  Headers:               │
                        │  - X-Traffic-Type       │
                        │  - X-Test-Run-Id        │
                        └─────────────────────────┘
                                      │
                                      ▼
                        ┌─────────────────────────┐
                        │ UserCreatedEventConsumer │
                        │                         │
                        │ if X-Traffic-Type ==    │
                        │    "LOAD_TEST":         │
                        │    → IGNORE (log only)  │
                        │ else:                   │
                        │    → PROCESS (business) │
                        └─────────────────────────┘
```

**How it works:**
1. `SingleTopicUserEventPublisher` publishes events to `user-events` topic
2. Adds `X-Traffic-Type` and `X-Test-Run-Id` headers from MDC
3. `UserCreatedEventConsumer` checks headers and filters test events
4. Production events trigger business logic; test events are logged and ignored

**Pros:** Simple setup, single topic, no ACL configuration needed

**Cons:** Test and production events share the same topic, relies on consumer filtering

### Strategy 2: Topic-Based Routing with ACLs (`topic-routing` profile)

Events are routed to separate topics based on traffic type, with SASL/SCRAM authentication and ACL-enforced access control.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Application                                    │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              TopicRoutingUserEventPublisher                       │   │
│  │   if trafficType == "LOAD_TEST" → user-events.test               │   │
│  │   else                          → user-events.real               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└───────────────────────────┬─────────────────────────┬───────────────────┘
                            │                         │
                            ▼                         ▼
              ┌─────────────────────┐   ┌─────────────────────┐
              │  user-events.real   │   │  user-events.test   │
              │  (prod_producer)    │   │  (test_producer)    │
              └─────────────────────┘   └─────────────────────┘
                            │                         │
                            ▼                         ▼
              ┌─────────────────────┐   ┌─────────────────────┐
              │ RealUserCreated     │   │ TestUserCreated     │
              │ EventConsumer       │   │ EventConsumer       │
              │ (prod_consumer)     │   │ (test_consumer)     │
              └─────────────────────┘   └─────────────────────┘
                            │                         │
                            ▼                         ▼
              ┌─────────────────────┐   ┌─────────────────────┐
              │ Execute business    │   │ Track/verify only   │
              │ logic (email, CRM)  │   │ (no side effects)   │
              └─────────────────────┘   └─────────────────────┘
```

**How it works:**
1. `TopicRoutingUserEventPublisher` checks `TrafficContextManager` for traffic type
2. Production events → `user-events.real` (using `prod_producer` credentials)
3. Test events → `user-events.test` (using `test_producer` credentials)
4. Separate consumers for each topic with dedicated credentials
5. ACLs enforce that producers/consumers can only access their designated topics

**Pros:** Complete isolation, ACL enforcement, independent scaling, easy cleanup (delete test topic)

**Cons:** Requires SASL/SCRAM setup, more complex configuration, multiple connection factories

### Event Publisher Implementations

| Implementation | Profile | Behavior |
|----------------|---------|----------|
| `SingleTopicUserEventPublisher` | Default (no `topic-routing`) | Publishes to `user-events` topic with `X-Traffic-Type` header |
| `TopicRoutingUserEventPublisher` | `topic-routing` | Routes to `user-events.real` or `user-events.test` |

### Kafka Users and ACLs (for `topic-routing` profile)

| User | Operations | Topic |
|------|-----------|-------|
| `prod_producer` | Write | `user-events.real` |
| `prod_consumer` | Read | `user-events.real` |
| `test_producer` | Write | `user-events.test` |
| `test_consumer` | Read | `user-events.test` |

## Database Schema

### User Table (Composite Partitioned)

```sql
-- Composite partitioned table: LIST (is_test) + RANGE (created_date)
CREATE TABLE t_users (
    id BIGINT NOT NULL DEFAULT nextval('t_users_seq'),
    username VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    is_test BOOLEAN NOT NULL DEFAULT false,
    created_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    PRIMARY KEY (id, is_test, created_date)  -- All partition keys must be in PK
) PARTITION BY LIST (is_test);

-- Production partition (sub-partitioned by RANGE)
CREATE TABLE t_users_production PARTITION OF t_users
    FOR VALUES IN (false)
    PARTITION BY RANGE (created_date);

-- Test partition (sub-partitioned by RANGE)
CREATE TABLE t_users_test PARTITION OF t_users
    FOR VALUES IN (true)
    PARTITION BY RANGE (created_date);

-- Yearly sub-partitions (example for 2026)
CREATE TABLE t_users_production_2026 PARTITION OF t_users_production
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE t_users_test_2026 PARTITION OF t_users_test
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
```

### RLS Policies

**Session-Based (for `app_user`):**
```sql
CREATE POLICY users_test_isolation_policy ON t_users
    FOR SELECT TO app_user
    USING (
        CASE 
            WHEN current_setting('app.test_mode', true) = 'true' THEN is_test = true
            ELSE is_test = false
        END
    );
```

**Dedicated Users:**
```sql
-- app_real_user can only see production data
CREATE POLICY real_user_select_policy ON t_users
    FOR SELECT TO app_real_user
    USING (is_test = false);

-- app_test_user can only see test data
CREATE POLICY test_user_select_policy ON t_users
    FOR SELECT TO app_test_user
    USING (is_test = true);
```

## Request Headers

| Header | Value | Description |
|--------|-------|-------------|
| `X-Traffic-Type` | `LOAD_TEST` | Switches to test mode/user |
| `X-Test-Run-Id` | UUID | Unique identifier for the test run (for logging) |

## Prerequisites

- Java 21
- Docker & Docker Compose
- Gradle

## Running with Docker Compose

### 1. Start infrastructure services

```bash
docker compose up -d
```

This starts:
- PostgreSQL (port 5432) with RLS and ACLs
- Redis (port 6379) with ACLs
- Kafka (port 9092) with SASL/SCRAM and ACLs
- Zookeeper (port 2181)
- Kafka UI (port 9080)
- RedisInsight (port 5540)

### 2. Build and run the application

```bash
./gradlew bootJar
docker compose --profile app up -d
```

### 3. Run with specific profiles

```bash
# Full isolation: Database routing + Kafka topic routing (Redis key-prefix is always enabled)
SPRING_PROFILES_ACTIVE=datasource-routing,topic-routing docker compose --profile app up -d
```

### 4. Stop all services

```bash
docker compose down
```

## Spring Profiles

| Profile | Description |
|---------|-------------|
| (default) | Session-based test mode with `app_user`, single Kafka topic, Redis cache with key-prefix isolation (`real:*` / `test:*`) |
| `datasource-routing` | Dedicated user routing with `app_real_user` / `app_test_user` for PostgreSQL |
| `topic-routing` | Topic-based Kafka isolation with SASL/ACL (`user-events.real` / `user-events.test`) |

**Note:** Redis cache isolation with key prefixes is always enabled by default.

### Profile Combinations

```bash
# Database: session-based, Kafka: single topic, Redis: key-prefix routing (default)
./gradlew bootRun

# Database: dedicated user routing, Kafka: single topic, Redis: key-prefix routing
SPRING_PROFILES_ACTIVE=datasource-routing ./gradlew bootRun

# Database: session-based, Kafka: topic routing with ACL, Redis: key-prefix routing
SPRING_PROFILES_ACTIVE=topic-routing ./gradlew bootRun

# Full isolation: Database routing + Kafka topic routing + Redis key-prefix routing
SPRING_PROFILES_ACTIVE=datasource-routing,topic-routing ./gradlew bootRun
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/users` | Get all users (filtered by RLS) |
| GET | `/users/{id}` | Get user by ID |
| POST | `/users` | Create new user |
| PUT | `/users/{id}` | Update user |

## Usage Examples

### Access Production Data (Default)

```bash
curl http://localhost:8080/users
```

### Access Test Data

```bash
curl -H "X-Traffic-Type: LOAD_TEST" http://localhost:8080/users
```

### Create Production User

```bash
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"username":"prod_user","password":"secret","email":"prod@example.com"}'
```

### Create Test User

```bash
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -H "X-Traffic-Type: LOAD_TEST" \
  -H "X-Test-Run-Id: my-test-run-123" \
  -d '{"username":"test_user","password":"secret","email":"test@loadtest.com"}'
```

## Swagger UI

Access the API documentation at: http://localhost:8080/swagger-ui.html

The `X-Traffic-Type` and `X-Test-Run-Id` headers are available as parameters for all endpoints.

## Logging

MDC (Mapped Diagnostic Context) includes traffic type and test run ID in all log entries:

```
2024-01-21 14:30:45.123 INFO [trafficType=LOAD_TEST] [testRunId=550e8400-...] c.e.l.controller.UserController : Getting users...
```

## Web UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| Swagger UI | http://localhost:8080/swagger-ui.html | - |
| Kafka UI | http://localhost:9080 | Uses kafka/kafka_secret for Kafka SASL |
| RedisInsight | http://localhost:5540 | Add connection: host=redis, port=6379, user=admin, password=admin_secret |

## Configuration Files

| File | Description |
|------|-------------|
| `application.yml` | Base configuration (includes Redis routing config) |
| `application-datasource-routing.yml` | Database routing DataSource configuration |
| `application-topic-routing.yml` | Kafka topic routing configuration |
| `RedisRoutingConfig.kt` | Redis cache routing configuration (always active) |
| `RoutingCacheManager.kt` | Routes cache operations based on traffic type |
| `docker/postgres/01-init-app-users.sql` | PostgreSQL user creation |
| `docker/redis/users.acl` | Redis ACL definitions |
| `docker/redis/redis.conf` | Redis configuration |
| `docker/kafka/create-users-and-acls.sh` | Kafka users, topics, and ACLs |
| `docker/kafka/kafka_server_jaas.conf` | Kafka SASL configuration |

## Integration Tests

The project includes comprehensive integration tests using Testcontainers that verify all isolation mechanisms work correctly.

### Test Infrastructure

| Container | Configuration |
|-----------|---------------|
| PostgreSQL | RLS enabled, multiple users (`app_user`, `app_real_user`, `app_test_user`) |
| Redis | ACL enabled via command-line arguments |
| Kafka | Plain (no SASL in tests for simplicity) |

### Redis ACL in Tests

Integration tests verify Redis ACL enforcement using inline user definitions:

```kotlin
.withCommand(
    "redis-server",
    "--user", "default", "off", "nopass", "~*", "-@all",
    "--user", "admin", "on", ">admin_secret", "~*", "+@all",
    "--user", "app_real_user", "on", ">real_pwd", "resetkeys", "~real:*", "+@all",
    "--user", "app_test_user", "on", ">test_pwd", "resetkeys", "~test:*", "+@all"
)
```

### ACL Enforcement Tests

`RedisDataIsolationIT` includes tests that verify:
- ✅ Real user can access `real:*` keys
- ✅ Test user can access `test:*` keys
- ❌ Real user gets `NOPERM` error when accessing `test:*` keys
- ❌ Test user gets `NOPERM` error when accessing `real:*` keys

### Running Tests

```bash
# Run all tests
./gradlew test

# Run Redis isolation tests
./gradlew test --tests "com.example.loadtest.RedisDataIsolationIT"

# Run Kafka topic routing tests
./gradlew test --tests "com.example.loadtest.TopicBasedUserEventPublishingIT"
```

## Benefits Summary

| Feature | Benefit |
|---------|---------|
| **RLS** | Data isolation at database level, impossible to access wrong data |
| **Key Prefix** | Visual and logical separation in Redis |
| **Topic Routing** | Complete separation of event streams |
| **ACLs** | Security enforcement at infrastructure level |
| **Short TTL** | Automatic cleanup of test cache data |
| **MDC Logging** | Easy filtering and debugging of test traffic |
| **ACL Tests** | Integration tests verify permission enforcement |