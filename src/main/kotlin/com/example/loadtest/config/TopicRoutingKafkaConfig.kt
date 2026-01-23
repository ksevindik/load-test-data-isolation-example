package com.example.loadtest.config

import com.example.loadtest.event.UserCreatedEvent
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JsonDeserializer

@Configuration
@Profile("topic-routing")
@EnableConfigurationProperties(TopicRoutingProperties::class)
class TopicRoutingKafkaConfig(
    private val properties: TopicRoutingProperties
) {

    @Value("\${spring.kafka.bootstrap-servers:kafka:29092}")
    private lateinit var bootstrapServers: String

    @Value("\${spring.kafka.properties.security.protocol:SASL_PLAINTEXT}")
    private lateinit var securityProtocol: String

    // ==================== Producer Factories ====================

    @Bean
    fun realProducerFactory(): ProducerFactory<String, String> {
        val props = mutableMapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java
        )
        addSecurityConfig(props, properties.producer.real.username, properties.producer.real.password)
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun testProducerFactory(): ProducerFactory<String, String> {
        val props = mutableMapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java
        )
        addSecurityConfig(props, properties.producer.test.username, properties.producer.test.password)
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun realKafkaTemplate(realProducerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> {
        return KafkaTemplate(realProducerFactory)
    }

    @Bean
    fun testKafkaTemplate(testProducerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> {
        return KafkaTemplate(testProducerFactory)
    }

    // ==================== Consumer Factories ====================

    @Bean
    fun realConsumerFactory(): ConsumerFactory<String, UserCreatedEvent> {
        val props = mutableMapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to JsonDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            JsonDeserializer.TRUSTED_PACKAGES to "com.example.loadtest.event",
            JsonDeserializer.VALUE_DEFAULT_TYPE to UserCreatedEvent::class.java.name
        )
        addSecurityConfig(props, properties.consumer.real.username, properties.consumer.real.password)
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean
    fun testConsumerFactory(): ConsumerFactory<String, UserCreatedEvent> {
        val props = mutableMapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to JsonDeserializer::class.java,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            JsonDeserializer.TRUSTED_PACKAGES to "com.example.loadtest.event",
            JsonDeserializer.VALUE_DEFAULT_TYPE to UserCreatedEvent::class.java.name
        )
        addSecurityConfig(props, properties.consumer.test.username, properties.consumer.test.password)
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean
    fun realKafkaListenerContainerFactory(
        realConsumerFactory: ConsumerFactory<String, UserCreatedEvent>
    ): ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent>()
        factory.setConsumerFactory(realConsumerFactory)
        return factory
    }

    @Bean
    fun testKafkaListenerContainerFactory(
        testConsumerFactory: ConsumerFactory<String, UserCreatedEvent>
    ): ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent>()
        factory.setConsumerFactory(testConsumerFactory)
        return factory
    }

    // ==================== Utilities ====================

    private fun addSecurityConfig(props: MutableMap<String, Any>, username: String, password: String) {
        // Only add SASL config if security protocol is SASL-based
        if (securityProtocol.contains("SASL", ignoreCase = true)) {
            props["security.protocol"] = securityProtocol
            props["sasl.mechanism"] = "SCRAM-SHA-256"
            props["sasl.jaas.config"] = buildJaasConfig(username, password)
        }
        // For PLAINTEXT (testing), no security config needed
    }

    private fun buildJaasConfig(username: String, password: String): String {
        return "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                "username=\"$username\" password=\"$password\";"
    }
}
