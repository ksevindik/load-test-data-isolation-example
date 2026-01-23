package com.example.loadtest.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.redis")
data class RedisRoutingProperties(
    val host: String = "localhost",
    val port: Int = 6379,
    val real: RedisUserConfig = RedisUserConfig(),
    val test: RedisUserConfig = RedisUserConfig()
) {
    data class RedisUserConfig(
        val username: String = "",
        val password: String = "",
        val keyPrefix: String = "",
        val ttlSeconds: Long = 3600
    )
}
