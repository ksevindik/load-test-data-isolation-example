package com.example.loadtest.config

import com.example.loadtest.cache.RoutingCacheManager
import com.example.loadtest.traffic.TrafficContextManager
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

/**
 * Redis configuration for routing-based cache isolation.
 * 
 * Creates separate connection factories and cache managers for real and test traffic:
 * - realCacheManager: Uses app_real_user, "real:" key prefix, 1 hour TTL
 * - testCacheManager: Uses app_test_user, "test:" key prefix, 10 minute TTL
 * 
 * Combined with Redis ACLs, this ensures complete isolation:
 * - app_real_user can only access real:* keys
 * - app_test_user can only access test:* keys
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(RedisRoutingProperties::class)
class RedisRoutingConfig(
    private val properties: RedisRoutingProperties
) {

    // ==================== Connection Factories ====================

    @Bean
    fun realRedisConnectionFactory(): RedisConnectionFactory {
        val config = RedisStandaloneConfiguration().apply {
            hostName = properties.host
            port = properties.port
            username = properties.real.username
            setPassword(properties.real.password)
        }
        return LettuceConnectionFactory(config)
    }

    @Bean
    fun testRedisConnectionFactory(): RedisConnectionFactory {
        val config = RedisStandaloneConfiguration().apply {
            hostName = properties.host
            port = properties.port
            username = properties.test.username
            setPassword(properties.test.password)
        }
        return LettuceConnectionFactory(config)
    }

    // ==================== Cache Managers ====================

    @Bean
    fun realCacheManager(realRedisConnectionFactory: RedisConnectionFactory): CacheManager {
        val cacheConfig = createCacheConfiguration(
            keyPrefix = properties.real.keyPrefix,
            ttlSeconds = properties.real.ttlSeconds
        )
        return RedisCacheManager.builder(realRedisConnectionFactory)
            .cacheDefaults(cacheConfig)
            .build()
    }

    @Bean
    fun testCacheManager(testRedisConnectionFactory: RedisConnectionFactory): CacheManager {
        val cacheConfig = createCacheConfiguration(
            keyPrefix = properties.test.keyPrefix,
            ttlSeconds = properties.test.ttlSeconds
        )
        return RedisCacheManager.builder(testRedisConnectionFactory)
            .cacheDefaults(cacheConfig)
            .build()
    }

    @Bean
    @Primary
    fun routingCacheManager(
        realCacheManager: CacheManager,
        testCacheManager: CacheManager,
        trafficContextManager: TrafficContextManager
    ): CacheManager {
        return RoutingCacheManager(realCacheManager, testCacheManager, trafficContextManager)
    }

    // ==================== Utilities ====================

    private fun createCacheConfiguration(keyPrefix: String, ttlSeconds: Long): RedisCacheConfiguration {
        return RedisCacheConfiguration.defaultCacheConfig()
            .prefixCacheNameWith(keyPrefix)
            .entryTtl(Duration.ofSeconds(ttlSeconds))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(GenericJackson2JsonRedisSerializer())
            )
            .disableCachingNullValues()
    }
}
