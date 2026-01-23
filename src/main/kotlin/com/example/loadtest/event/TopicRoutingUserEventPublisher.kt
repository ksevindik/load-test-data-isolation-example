package com.example.loadtest.event

import com.example.loadtest.config.TopicRoutingProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * Topic-routing event publisher that sends events to different topics based on traffic type.
 * 
 * - Production traffic (trafficType != "LOAD_TEST") -> user-events.real (using prod_producer credentials)
 * - Test traffic (trafficType == "LOAD_TEST") -> user-events.test (using test_producer credentials)
 * 
 * This ensures complete isolation between production and test event streams,
 * with ACL-enforced access control on each topic.
 */
@Component
@Profile("topic-routing")
class TopicRoutingUserEventPublisher(
    @Qualifier("realKafkaTemplate") private val realKafkaTemplate: KafkaTemplate<String, String>,
    @Qualifier("testKafkaTemplate") private val testKafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val topicRoutingProperties: TopicRoutingProperties
) : UserEventPublisher {
    private val logger = LoggerFactory.getLogger(TopicRoutingUserEventPublisher::class.java)

    companion object {
        const val HEADER_TRAFFIC_TYPE = "X-Traffic-Type"
        const val HEADER_TEST_RUN_ID = "X-Test-Run-Id"
        const val LOAD_TEST_VALUE = "LOAD_TEST"
    }

    override fun publishUserCreated(event: UserCreatedEvent) {
        val trafficType = MDC.get("trafficType")
        val testRunId = MDC.get("testRunId")
        val isTestTraffic = trafficType == LOAD_TEST_VALUE

        val topic = if (isTestTraffic) {
            topicRoutingProperties.topics.test
        } else {
            topicRoutingProperties.topics.real
        }

        val kafkaTemplate = if (isTestTraffic) {
            testKafkaTemplate
        } else {
            realKafkaTemplate
        }

        val payload = objectMapper.writeValueAsString(event)

        val record = ProducerRecord<String, String>(
            topic,
            null,
            event.id.toString(),
            payload
        )

        // Add traffic headers
        if (trafficType != null) {
            record.headers().add(RecordHeader(HEADER_TRAFFIC_TYPE, trafficType.toByteArray()))
        }
        if (testRunId != null && testRunId != "-") {
            record.headers().add(RecordHeader(HEADER_TEST_RUN_ID, testRunId.toByteArray()))
        }

        kafkaTemplate.send(record).whenComplete { result, ex ->
            if (ex != null) {
                logger.error("Failed to publish UserCreatedEvent for user ${event.id} to topic $topic", ex)
            } else {
                logger.info(
                    "Published UserCreatedEvent for user ${event.id} to topic {} partition {} (trafficType: {})",
                    topic,
                    result.recordMetadata.partition(),
                    trafficType ?: "PRODUCTION"
                )
            }
        }
    }
}
