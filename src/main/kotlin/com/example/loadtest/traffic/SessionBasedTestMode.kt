package com.example.loadtest.traffic

import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class SessionBasedTestMode(private val jdbcTemplate: JdbcTemplate, private val environment: Environment) {
    fun attemptToMarkAsTest() {
        if (!environment.activeProfiles.contains("datasource-routing")) {
            jdbcTemplate.execute("SET app.test_mode = 'true'")
        }
    }

    fun attemptToClear() {
        if (!environment.activeProfiles.contains("datasource-routing")) {
            jdbcTemplate.execute("SET app.test_mode = 'false'")
        }
    }

    fun isTestMode(): Boolean {
        val result = jdbcTemplate.queryForObject(
            "SELECT current_setting('app.test_mode', true)",
            String::class.java
        )
        return result == "true"
    }

    fun getCurrentUser() : String {
        return jdbcTemplate.queryForObject(
            "SELECT current_user",
            String::class.java
        )!!
    }
}