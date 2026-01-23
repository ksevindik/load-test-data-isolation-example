package com.example.loadtest

import com.example.loadtest.model.User
import com.example.loadtest.service.UserService
import com.example.loadtest.traffic.TrafficContext
import com.example.loadtest.traffic.TrafficContextManager
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Duration

/**
 * Integration tests for Redis cache isolation between test and real traffic.
 * 
 * These tests verify that:
 * - Real traffic cache entries have "real:" prefix
 * - Test traffic cache entries have "test:" prefix
 * - Cache routing works correctly based on traffic type
 * - ACL enforcement: app_real_user cannot access test:* keys and vice versa
 */
@ActiveProfiles(value = ["test"])
class RedisDataIsolationIT : BaseIT() {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var trafficContextManager: TrafficContextManager

    @Autowired
    @Qualifier("realRedisConnectionFactory")
    private lateinit var realRedisConnectionFactory: RedisConnectionFactory

    @Autowired
    @Qualifier("testRedisConnectionFactory")
    private lateinit var testRedisConnectionFactory: RedisConnectionFactory

    private lateinit var realRedisTemplate: StringRedisTemplate
    private lateinit var testRedisTemplate: StringRedisTemplate
    private lateinit var adminRedisTemplate: StringRedisTemplate

    @BeforeEach
    fun setup() {
        // Create StringRedisTemplates for direct key inspection
        // StringRedisTemplate uses StringRedisSerializer for both keys and values,
        // which matches the cache manager's key serialization
        realRedisTemplate = StringRedisTemplate().apply {
            connectionFactory = realRedisConnectionFactory
            afterPropertiesSet()
        }
        testRedisTemplate = StringRedisTemplate().apply {
            connectionFactory = testRedisConnectionFactory
            afterPropertiesSet()
        }
        
        // Create admin template for cleanup (flushAll requires admin privileges)
        adminRedisTemplate = createAdminRedisTemplate()

        // Clear all caches using admin connection (ACL-restricted users can't flushAll)
        adminRedisTemplate.connectionFactory?.connection?.use { conn ->
            conn.serverCommands().flushAll()
        }
    }
    
    private fun createAdminRedisTemplate(): StringRedisTemplate {
        val config = org.springframework.data.redis.connection.RedisStandaloneConfiguration().apply {
            hostName = redis.host
            port = redis.getMappedPort(6379)
            username = "admin"
            setPassword("admin_secret")
        }
        val connectionFactory = org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config)
        connectionFactory.afterPropertiesSet()
        
        return StringRedisTemplate().apply {
            this.connectionFactory = connectionFactory
            afterPropertiesSet()
        }
    }

    @Test
    fun `real traffic cache should use real prefix`() {
        try {
            // Set production traffic context (ensure TEST ThreadLocal is not set)
            trafficContextManager.setTrafficContext(TrafficContext.forProduction())

            // Create a user
            val user = userService.createUser(
                username = "redis_real_user",
                password = "password123",
                email = "redis_real@example.com",
                isTest = false
            )
            flushAndClear()

            // Cache the user (this should use realCacheManager)
            val cachedUser = userService.getUserById(user.id!!)
            assertNotNull(cachedUser)

            // Call again - should hit cache
            val cachedUser2 = userService.getUserById(user.id!!)
            assertEquals(cachedUser?.id, cachedUser2?.id)

            // Verify key exists with real: prefix
            val realKeys = realRedisTemplate.keys("real:*")
            assertTrue(realKeys?.isNotEmpty() == true, "Should have keys with real: prefix")

            // Verify no keys with test: prefix for this user
            val testKeys = testRedisTemplate.keys("test:users::${user.id}")
            assertTrue(testKeys?.isEmpty() == true, "Should NOT have keys with test: prefix")

        } finally {
            trafficContextManager.clearTrafficContext()
        }
    }

    @Test
    fun `test traffic cache should use test prefix`() {
        try {
            // Set test traffic context
            trafficContextManager.setTrafficContext(TrafficContext.forTest("redis-test-123"))

            // Create a test user
            val user = userService.createUser(
                username = "redis_test_user",
                password = "password123",
                email = "redis_test@example.com",
                isTest = true
            )
            flushAndClear()

            // Cache the user (this should use testCacheManager)
            val cachedUser = userService.getUserById(user.id!!)
            assertNotNull(cachedUser)

            // Call again - should hit cache
            val cachedUser2 = userService.getUserById(user.id!!)
            assertEquals(cachedUser?.id, cachedUser2?.id)

            // Verify key exists with test: prefix
            val testKeys = testRedisTemplate.keys("test:*")
            assertTrue(testKeys?.isNotEmpty() == true, "Should have keys with test: prefix")

            // Verify no keys with real: prefix for this user
            val realKeys = realRedisTemplate.keys("real:users::${user.id}")
            assertTrue(realKeys?.isEmpty() == true, "Should NOT have keys with real: prefix")

        } finally {
            trafficContextManager.clearTrafficContext()
        }
    }

    @Test
    fun `cache isolation - test and real traffic use separate cache entries`() {
        var realUser: User? = null
        var testUser: User? = null

        try {
            // First, create and cache a real user
            trafficContextManager.setTrafficContext(TrafficContext.forProduction())

            realUser = userService.createUser(
                username = "isolation_real_user",
                password = "password123",
                email = "isolation_real@example.com",
                isTest = false
            )
            flushAndClear()
            userService.getUserById(realUser.id!!)
            trafficContextManager.clearTrafficContext()

            // Then, create and cache a test user with the same operation
            trafficContextManager.setTrafficContext(TrafficContext.forTest("isolation-test-456"))

            testUser = userService.createUser(
                username = "isolation_test_user",
                password = "password123",
                email = "isolation_test@example.com",
                isTest = true
            )
            flushAndClear()
            userService.getUserById(testUser.id!!)

            // Verify both prefixes have cache entries
            val realKeys = realRedisTemplate.keys("real:*")
            val testKeys = testRedisTemplate.keys("test:*")

            assertTrue(realKeys?.isNotEmpty() == true, "Should have real: prefixed keys")
            assertTrue(testKeys?.isNotEmpty() == true, "Should have test: prefixed keys")

            // Verify the cached users are different
            assertNotEquals(realUser.id, testUser.id)

        } finally {
            trafficContextManager.clearTrafficContext()
        }
    }

    @Test
    fun `cache eviction only affects current traffic type`() {
        try {
            // Create and cache a real user
            trafficContextManager.setTrafficContext(TrafficContext.forProduction())

            val realUser = userService.createUser(
                username = "evict_real_user",
                password = "password123",
                email = "evict_real@example.com",
                isTest = false
            )
            flushAndClear()
            userService.getUserById(realUser.id!!)
            trafficContextManager.clearTrafficContext()

            // Create and cache a test user
            trafficContextManager.setTrafficContext(TrafficContext.forTest("evict-test-789"))

            val testUser = userService.createUser(
                username = "evict_test_user",
                password = "password123",
                email = "evict_test@example.com",
                isTest = true
            )
            flushAndClear()
            userService.getUserById(testUser.id!!)

            // Evict all from test cache
            userService.evictAllUsersCache()

            // Verify test cache is empty (use Awaitility to handle Redis propagation delay)
            await.atMost(Duration.ofSeconds(2)).untilAsserted {
                val testKeys = testRedisTemplate.keys("test:users*")
                assertTrue(testKeys?.isEmpty() == true, "Test cache should be empty after eviction")
            }

            // Verify real cache still has entries
            val realKeys = realRedisTemplate.keys("real:users*")
            assertTrue(realKeys?.isNotEmpty() == true, "Real cache should still have entries")

        } finally {
            trafficContextManager.clearTrafficContext()
        }
    }

    // ==================== ACL Enforcement Tests ====================

    @Test
    fun `ACL - real user should be denied access to test keys`() {
        // app_real_user should NOT be able to write to test:* keys
        val exception = assertThrows<RedisSystemException> {
            realRedisTemplate.opsForValue().set("test:forbidden-key", "should-fail")
        }
        
        // Redis returns NOPERM error for ACL violations
        assertTrue(
            exception.cause?.message?.contains("NOPERM") == true,
            "Should get NOPERM error when real user tries to access test:* key. Got: ${exception.cause?.message}"
        )
    }

    @Test
    fun `ACL - test user should be denied access to real keys`() {
        // app_test_user should NOT be able to write to real:* keys
        val exception = assertThrows<RedisSystemException> {
            testRedisTemplate.opsForValue().set("real:forbidden-key", "should-fail")
        }
        
        // Redis returns NOPERM error for ACL violations
        assertTrue(
            exception.cause?.message?.contains("NOPERM") == true,
            "Should get NOPERM error when test user tries to access real:* key. Got: ${exception.cause?.message}"
        )
    }

    @Test
    fun `ACL - real user can access real keys`() {
        // app_real_user should be able to write/read real:* keys
        realRedisTemplate.opsForValue().set("real:allowed-key", "success")
        val value = realRedisTemplate.opsForValue().get("real:allowed-key")
        assertEquals("success", value, "Real user should be able to access real:* keys")
        
        // Cleanup
        realRedisTemplate.delete("real:allowed-key")
    }

    @Test
    fun `ACL - test user can access test keys`() {
        // app_test_user should be able to write/read test:* keys
        testRedisTemplate.opsForValue().set("test:allowed-key", "success")
        val value = testRedisTemplate.opsForValue().get("test:allowed-key")
        assertEquals("success", value, "Test user should be able to access test:* keys")
        
        // Cleanup
        testRedisTemplate.delete("test:allowed-key")
    }
}
