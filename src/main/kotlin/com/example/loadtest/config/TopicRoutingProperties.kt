package com.example.loadtest.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile

@Profile("topic-routing")
@ConfigurationProperties(prefix = "app.kafka")
data class TopicRoutingProperties(
    val topics: Topics = Topics(),
    val producer: ProducerCredentials = ProducerCredentials(),
    val consumer: ConsumerCredentials = ConsumerCredentials()
) {
    data class Topics(
        val real: String = "user-events.real",
        val test: String = "user-events.test"
    )

    data class ProducerCredentials(
        val real: Credentials = Credentials(),
        val test: Credentials = Credentials()
    )

    data class ConsumerCredentials(
        val real: ConsumerCreds = ConsumerCreds(),
        val test: ConsumerCreds = ConsumerCreds()
    )

    data class Credentials(
        val username: String = "",
        val password: String = ""
    )

    data class ConsumerCreds(
        val username: String = "",
        val password: String = "",
        val groupId: String = ""
    )
}
