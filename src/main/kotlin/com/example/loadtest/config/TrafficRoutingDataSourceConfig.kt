package com.example.loadtest.config

import com.example.loadtest.datasource.TrafficRoutingDataSource
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

@Configuration
@Profile("datasource-routing")
class TrafficRoutingDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.real")
    fun realDataSource(): DataSource =
        DataSourceBuilder.create()
            .type(HikariDataSource::class.java)
            .build()

    @Bean
    @ConfigurationProperties("spring.datasource.test")
    fun testDataSource(): DataSource =
        DataSourceBuilder.create()
            .type(HikariDataSource::class.java)
            .build()

    @Bean
    @Primary
    fun routingDataSource(
        realDataSource: DataSource,
        testDataSource: DataSource
    ): DataSource {

        val targets = hashMapOf<Any, Any>(
            "REAL" to realDataSource,
            "TEST" to testDataSource
        )

        return TrafficRoutingDataSource().apply {
            setDefaultTargetDataSource(realDataSource)
            setTargetDataSources(targets)
            afterPropertiesSet()
        }
    }

}
