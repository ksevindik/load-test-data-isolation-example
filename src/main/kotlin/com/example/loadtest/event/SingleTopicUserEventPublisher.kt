package com.example.loadtest.event

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * Event publisher that publishes all events to a single topic (user-events).
 * Traffic type is indicated via Kafka headers (X-Traffic-Type, X-Test-Run-Id).
 * Active when topic-routing profile is NOT enabled.
 */
@Component
@Profile("!topic-routing")
class SingleTopicUserEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) : UserEventPublisher {
    private val logger = LoggerFactory.getLogger(SingleTopicUserEventPublisher::class.java)

    companion object {
        const val USER_EVENTS_TOPIC = "user-events"
        const val HEADER_TRAFFIC_TYPE = "X-Traffic-Type"
        const val HEADER_TEST_RUN_ID = "X-Test-Run-Id"
    }

    override fun publishUserCreated(event: UserCreatedEvent) {
        val payload = objectMapper.writeValueAsString(event)
        
        val record = ProducerRecord<String, String>(
            USER_EVENTS_TOPIC,
            null,
            event.id.toString(),
            payload
        )

        // Add traffic headers from MDC
        val trafficType = MDC.get("trafficType")
        val testRunId = MDC.get("testRunId")

        if (trafficType != null) {
            record.headers().add(RecordHeader(HEADER_TRAFFIC_TYPE, trafficType.toByteArray()))
        }
        if (testRunId != null && testRunId != "-") {
            record.headers().add(RecordHeader(HEADER_TEST_RUN_ID, testRunId.toByteArray()))
        }

        kafkaTemplate.send(record).whenComplete { result, ex ->
            if (ex != null) {
                logger.error("Failed to publish UserCreatedEvent for user ${event.id}", ex)
            } else {
                logger.info("Published UserCreatedEvent for user ${event.id} to partition ${result.recordMetadata.partition()}")
            }
        }
    }
}
