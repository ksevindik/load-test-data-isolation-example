package com.example.loadtest

import com.example.loadtest.event.SingleTopicUserEventPublisher
import com.example.loadtest.event.UserCreatedEvent
import com.example.loadtest.event.UserCreatedEventConsumer
import com.example.loadtest.service.UserService
import com.example.loadtest.traffic.TrafficContext
import com.example.loadtest.traffic.TrafficContextManager
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.RecordsToDelete
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UserEventPublishingIT : BaseIT() {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var trafficContextManager: TrafficContextManager

    @Autowired
    private lateinit var userCreatedEventConsumer: UserCreatedEventConsumer

    @BeforeEach
    fun cleanupKafkaTopic() {
        // Clear consumer tracking lists
        userCreatedEventConsumer.clearEvents()
        
        val adminProps = mapOf(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers
        )
        AdminClient.create(adminProps).use { adminClient ->
            val topicName = SingleTopicUserEventPublisher.USER_EVENTS_TOPIC
            val existingTopics = adminClient.listTopics().names().get()
            
            if (existingTopics.contains(topicName)) {
                // Get topic partitions and their end offsets
                val topicPartition = TopicPartition(topicName, 0)
                val endOffsets = adminClient.listOffsets(
                    mapOf(topicPartition to org.apache.kafka.clients.admin.OffsetSpec.latest())
                ).all().get()
                
                val endOffset = endOffsets[topicPartition]?.offset() ?: 0L
                
                if (endOffset > 0) {
                    // Delete all records up to the end offset
                    val recordsToDelete = mapOf(topicPartition to RecordsToDelete.beforeOffset(endOffset))
                    adminClient.deleteRecords(recordsToDelete).all().get()
                }
            }
        }
        
        // Small delay to ensure cleanup is complete
        Thread.sleep(500)
    }

    @Test
    fun `should publish test user created event when a test user is created`() {
        // Setup test consumer
        val records: BlockingQueue<ConsumerRecord<String, String>> = LinkedBlockingQueue()
        val container = createTestConsumer(records)
        container.start()

        try {
            // Wait for consumer to be ready
            Thread.sleep(2000)

            // Set test traffic context
            trafficContextManager.setTrafficContext(TrafficContext.forTest("test-run-123"))

            // Create a test user
            val user = userService.createUser(
                username = "kafka_test_user",
                password = "password123",
                email = "kafka_test@example.com",
                isTest = true
            )
            flushAndClear()

            // Wait for the message
            val record = records.poll(10, TimeUnit.SECONDS)

            // Assertions
            assertNotNull(record, "Should receive a Kafka message")
            
            // Verify message key
            assertEquals(user.id.toString(), record!!.key())

            // Verify message payload
            val event = objectMapper.readValue(record.value(), UserCreatedEvent::class.java)
            assertEquals(user.id, event.id)
            assertEquals("kafka_test_user", event.username)
            assertEquals("kafka_test@example.com", event.email)
            assertTrue(event.isTest)

            // Verify headers
            val trafficTypeHeader = record.headers().lastHeader(SingleTopicUserEventPublisher.HEADER_TRAFFIC_TYPE)
            assertNotNull(trafficTypeHeader, "Should have X-Traffic-Type header")
            assertEquals("LOAD_TEST", String(trafficTypeHeader!!.value()))

            val testRunIdHeader = record.headers().lastHeader(SingleTopicUserEventPublisher.HEADER_TEST_RUN_ID)
            assertNotNull(testRunIdHeader, "Should have X-Test-Run-Id header")
            assertEquals("test-run-123", String(testRunIdHeader!!.value()))

        } finally {
            trafficContextManager.clearTrafficContext()
            container.stop()
        }
    }

    @Test
    fun `should publish real user created event when a real user is created`() {
        // Setup test consumer
        val records: BlockingQueue<ConsumerRecord<String, String>> = LinkedBlockingQueue()
        val container = createTestConsumer(records)
        container.start()

        try {
            // Wait for consumer to be ready
            Thread.sleep(2000)

            // Set production traffic context
            trafficContextManager.setTrafficContext(TrafficContext.forProduction())

            // Create a production user
            val user = userService.createUser(
                username = "prod_kafka_user",
                password = "password123",
                email = "prod_kafka@example.com",
                isTest = false
            )
            flushAndClear()

            // Wait for the message
            val record = records.poll(10, TimeUnit.SECONDS)

            // Assertions
            assertNotNull(record, "Should receive a Kafka message")

            // Verify message payload
            val event = objectMapper.readValue(record!!.value(), UserCreatedEvent::class.java)
            assertEquals(user.id, event.id)
            assertEquals("prod_kafka_user", event.username)
            assertFalse(event.isTest)

            // Verify traffic type header
            val trafficTypeHeader = record.headers().lastHeader(SingleTopicUserEventPublisher.HEADER_TRAFFIC_TYPE)
            assertNotNull(trafficTypeHeader)
            assertEquals("PRODUCTION", String(trafficTypeHeader!!.value()))

            // Test run ID should not be present (was "-")
            val testRunIdHeader = record.headers().lastHeader(SingleTopicUserEventPublisher.HEADER_TEST_RUN_ID)
            assertNull(testRunIdHeader, "Should not have X-Test-Run-Id header for production traffic")

        } finally {
            trafficContextManager.clearTrafficContext()
            container.stop()
        }
    }

    @Test
    fun `consumer should ignore test user events based on X-Traffic-Type header`() {
        try {
            // First, create a test user (with LOAD_TEST traffic type)
            trafficContextManager.setTrafficContext(TrafficContext.forTest("test-run-456"))

            val testUser = userService.createUser(
                username = "consumer_test_user",
                password = "password123",
                email = "consumer_test@example.com",
                isTest = true
            )
            flushAndClear()

            trafficContextManager.clearTrafficContext()

            // Then, create a production user (without LOAD_TEST traffic type)
            trafficContextManager.setTrafficContext(TrafficContext.forProduction())

            val prodUser = userService.createUser(
                username = "consumer_prod_user",
                password = "password123",
                email = "consumer_prod@example.com",
                isTest = false
            )
            flushAndClear()

            // Wait for the KafkaListener consumer to process both events
            await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted {
                    // Verify test event was ignored
                    val ignoredEvents = userCreatedEventConsumer.getIgnoredEvents()
                    assertTrue(ignoredEvents.any { it.id == testUser.id }, 
                        "Test user event should be in ignored list")

                    // Verify production event was processed
                    val processedEvents = userCreatedEventConsumer.getProcessedEvents()
                    assertTrue(processedEvents.any { it.id == prodUser.id }, 
                        "Production user event should be in processed list")
                }

            // Additional assertions
            val ignoredEvents = userCreatedEventConsumer.getIgnoredEvents()
            val processedEvents = userCreatedEventConsumer.getProcessedEvents()

            // Test user should NOT be in processed events
            assertFalse(processedEvents.any { it.id == testUser.id },
                "Test user event should NOT be processed")

            // Production user should NOT be in ignored events
            assertFalse(ignoredEvents.any { it.id == prodUser.id },
                "Production user event should NOT be ignored")

        } finally {
            trafficContextManager.clearTrafficContext()
        }
    }

    private fun createTestConsumer(records: BlockingQueue<ConsumerRecord<String, String>>): KafkaMessageListenerContainer<String, String> {
        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "test-consumer-${System.currentTimeMillis()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
        )

        val consumerFactory = DefaultKafkaConsumerFactory<String, String>(consumerProps)
        val containerProps = ContainerProperties(SingleTopicUserEventPublisher.USER_EVENTS_TOPIC)
        containerProps.setMessageListener(MessageListener<String, String> { record ->
            records.add(record)
        })

        return KafkaMessageListenerContainer(consumerFactory, containerProps)
    }
}
