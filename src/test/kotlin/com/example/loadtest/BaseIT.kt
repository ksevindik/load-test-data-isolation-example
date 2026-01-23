package com.example.loadtest

import com.zaxxer.hikari.pool.HikariProxyConnection
import jakarta.persistence.EntityManager
import org.h2.tools.Server
import org.postgresql.jdbc.PgConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import javax.sql.DataSource

@SpringBootTest
@Transactional
@ActiveProfiles(value=["test"])
abstract class BaseIT {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("loadtest")
            .withUsername("db_admin_user")
            .withPassword("secret")
            .withReuse(false)
            .withCopyFileToContainer(
                MountableFile.forHostPath("docker/postgres/01-init-app-users.sql"),
                "/docker-entrypoint-initdb.d/01-init-app-users.sql"
            )

        @JvmStatic
        val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
            .withReuse(false)

        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(false)
            // Configure ACL via command line arguments (more reliable than file in testcontainers)
            .withCommand(
                "redis-server",
                "--bind", "0.0.0.0",
                "--protected-mode", "no",
                // Define ACL users inline
                "--user", "default", "off", "nopass", "~*", "-@all",
                "--user", "admin", "on", ">admin_secret", "~*", "+@all",
                "--user", "app_real_user", "on", ">real_pwd", "resetkeys", "~real:*", "+@all",
                "--user", "app_test_user", "on", ">test_pwd", "resetkeys", "~test:*", "+@all"
            )

        init {
            postgres.start()
            kafka.start()
            redis.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            // PostgreSQL - Datasource uses app_user for RLS
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { "app_user" }
            registry.add("spring.datasource.password") { "secret" }

            registry.add("spring.datasource.real.jdbc-url") { postgres.jdbcUrl }
            registry.add("spring.datasource.test.jdbc-url") { postgres.jdbcUrl }

            // Flyway uses db_admin_user (superuser) for migrations
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { "db_admin_user" }
            registry.add("spring.flyway.password") { "secret" }

            // Kafka
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
            
            // Kafka topic routing (for topic-routing profile - no SASL/ACL in test)
            registry.add("spring.kafka.properties.security.protocol") { "PLAINTEXT" }
            registry.add("app.kafka.topics.real") { "user-events.real" }
            registry.add("app.kafka.topics.test") { "user-events.test" }
            registry.add("app.kafka.producer.real.username") { "test" }
            registry.add("app.kafka.producer.real.password") { "test" }
            registry.add("app.kafka.producer.test.username") { "test" }
            registry.add("app.kafka.producer.test.password") { "test" }
            registry.add("app.kafka.consumer.real.username") { "test" }
            registry.add("app.kafka.consumer.real.password") { "test" }
            registry.add("app.kafka.consumer.real.group-id") { "prod-consumer-group" }
            registry.add("app.kafka.consumer.test.username") { "test" }
            registry.add("app.kafka.consumer.test.password") { "test" }
            registry.add("app.kafka.consumer.test.group-id") { "test-consumer-group" }

            // Redis (default - not used when routing is enabled)
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }

            // Redis routing with ACL enabled
            registry.add("app.redis.host") { redis.host }
            registry.add("app.redis.port") { redis.getMappedPort(6379) }
            registry.add("app.redis.real.username") { "app_real_user" }
            registry.add("app.redis.real.password") { "real_pwd" }
            registry.add("app.redis.real.key-prefix") { "real:" }
            registry.add("app.redis.real.ttl-seconds") { 3600 }
            registry.add("app.redis.test.username") { "app_test_user" }
            registry.add("app.redis.test.password") { "test_pwd" }
            registry.add("app.redis.test.key-prefix") { "test:" }
            registry.add("app.redis.test.ttl-seconds") { 600 }
        }
    }

    @Autowired
    protected lateinit var dataSource: DataSource

    @Autowired
    protected lateinit var entityManager: EntityManager

    fun openDBConsole() {
        Server.startWebServer(
            (DataSourceUtils.getConnection(dataSource) as HikariProxyConnection)
                .unwrap(PgConnection::class.java))
    }

    fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

}
