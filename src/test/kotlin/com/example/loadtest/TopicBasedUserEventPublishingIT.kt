package com.example.loadtest

import com.example.loadtest.event.RealUserCreatedEventConsumer
import com.example.loadtest.event.TestUserCreatedEventConsumer
import com.example.loadtest.event.TopicRoutingUserEventPublisher
import com.example.loadtest.event.UserCreatedEvent
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Integration tests for topic-based event routing.
 * 
 * These tests verify that:
 * - Production events are published to user-events.real topic
 * - Test events are published to user-events.test topic
 * - RealUserCreatedEventConsumer receives only production events
 * - TestUserCreatedEventConsumer receives only test events
 * 
 * Note: SASL/ACL verification should be done with docker-compose-topic-routing.yml
 */
@ActiveProfiles(value = ["test", "topic-routing"])
class TopicBasedUserEventPublishingIT : BaseIT() {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var trafficContextManager: TrafficContextManager

    @Autowired
    private lateinit var realUserCreatedEventConsumer: RealUserCreatedEventConsumer

    @Autowired
    private lateinit var testUserCreatedEventConsumer: TestUserCreatedEventConsumer

    companion object {
        const val REAL_TOPIC = "user-events.real"
        const val TEST_TOPIC = "user-events.test"
    }

    @BeforeEach
    fun setup() {
        // Clear consumer tracking lists
        realUserCreatedEventConsumer.clearEvents()
        testUserCreatedEventConsumer.clearEvents()

        // Purge existing records from topics (topics are auto-created by @KafkaListener)
        val adminProps = mapOf(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers
        )
        AdminClient.create(adminProps).use { adminClient ->
            val existingTopics = adminClient.listTopics().names().get()

            // Purge existing records from both topics
            listOf(REAL_TOPIC, TEST_TOPIC).forEach { topicName ->
                if (existingTopics.contains(topicName)) {
                    try {
                        val topicPartition = TopicPartition(topicName, 0)
                        val endOffsets = adminClient.listOffsets(
                            mapOf(topicPartition to org.apache.kafka.clients.admin.OffsetSpec.latest())
                        ).all().get()

                        val endOffset = endOffsets[topicPartition]?.offset() ?: 0L
                        if (endOffset > 0) {
                            val recordsToDelete = mapOf(topicPartition to RecordsToDelete.beforeOffset(endOffset))
                            adminClient.deleteRecords(recordsToDelete).all().get()
                        }
                    } catch (e: Exception) {
                        // Topic might not have partition 0 yet, ignore
                    }
                }
            }
        }

        // Small delay to ensure cleanup is complete
        Thread.sleep(500)
    }

    @Test
    fun `should publish production user event to user-events-real topic`() {
        val records: BlockingQueue<ConsumerRecord<String, String>> = LinkedBlockingQueue()
        val container = createTestConsumer(REAL_TOPIC, records)
        container.start()

        try {
            Thread.sleep(2000)

            // Set production traffic context
            trafficContextManager.setTrafficContext(TrafficContext.forProduction())

            val user = userService.createUser(
                username = "topic_routing_prod_user",
                password = "password123",
                email = "topic_routing_prod@example.com",
                isTest = false
            )
            flushAndClear()

            // Wait for message on real topic
            val record = records.poll(10, TimeUnit.SECONDS)

            assertNotNull(record, "Should receive message on user-events.real topic")
            val event = objectMapper.readValue(record!!.value(), UserCreatedEvent::class.java)
            assertEquals(user.id, event.id)
            assertEquals("topic_routing_prod_user", event.username)
            assertFalse(event.isTest)

            // Verify header
            val trafficTypeHeader = record.headers().lastHeader(TopicRoutingUserEventPublisher.HEADER_TRAFFIC_TYPE)
            assertNotNull(trafficTypeHeader)
            assertEquals("PRODUCTION", String(trafficTypeHeader!!.value()))

        } finally {
            trafficContextManager.clearTrafficContext()
            container.stop()
        }
    }

    @Test
    fun `should publish test user event to user-events-test topic`() {
        val records: BlockingQueue<ConsumerRecord<String, String>> = LinkedBlockingQueue()
        val container = createTestConsumer(TEST_TOPIC, records)
        container.start()

        try {
            Thread.sleep(2000)

            // Set test traffic context
            trafficContextManager.setTrafficContext(TrafficContext.forTest("topic-routing-test-123"))

            val user = userService.createUser(
                username = "topic_routing_test_user",
                password = "password123",
                email = "topic_routing_test@example.com",
                isTest = true
            )
            flushAndClear()

            // Wait for message on test topic
            val record = records.poll(10, TimeUnit.SECONDS)

            assertNotNull(record, "Should receive message on user-events.test topic")
            val event = objectMapper.readValue(record!!.value(), UserCreatedEvent::class.java)
            assertEquals(user.id, event.id)
            assertEquals("topic_routing_test_user", event.username)
            assertTrue(event.isTest)

            // Verify headers
            val trafficTypeHeader = record.headers().lastHeader(TopicRoutingUserEventPublisher.HEADER_TRAFFIC_TYPE)
            assertNotNull(trafficTypeHeader)
            assertEquals("LOAD_TEST", String(trafficTypeHeader!!.value()))

            val testRunIdHeader = record.headers().lastHeader(TopicRoutingUserEventPublisher.HEADER_TEST_RUN_ID)
            assertNotNull(testRunIdHeader)
            assertEquals("topic-routing-test-123", String(testRunIdHeader!!.value()))

        } finally {
            trafficContextManager.clearTrafficContext()
            container.stop()
        }
    }

    @Test
    fun `RealUserCreatedEventConsumer should receive only production events`() {
        try {
            // Create production user
            trafficContextManager.setTrafficContext(TrafficContext.forProduction())

            val prodUser = userService.createUser(
                username = "consumer_real_topic_user",
                password = "password123",
                email = "consumer_real_topic@example.com",
                isTest = false
            )
            flushAndClear()
            trafficContextManager.clearTrafficContext()

            // Create test user
            trafficContextManager.setTrafficContext(TrafficContext.forTest("consumer-test-456"))

            val testUser = userService.createUser(
                username = "consumer_test_topic_user",
                password = "password123",
                email = "consumer_test_topic@example.com",
                isTest = true
            )
            flushAndClear()

            // Wait for consumers to process
            await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted {
                    // Real consumer should have production event
                    val realProcessed = realUserCreatedEventConsumer.getProcessedEvents()
                    assertTrue(realProcessed.any { it.id == prodUser.id },
                        "RealUserCreatedEventConsumer should process production user")

                    // Test consumer should have test event
                    val testConsumed = testUserCreatedEventConsumer.getConsumedEvents()
                    assertTrue(testConsumed.any { it.id == testUser.id },
                        "TestUserCreatedEventConsumer should receive test user")
                }

            // Verify isolation
            val realProcessed = realUserCreatedEventConsumer.getProcessedEvents()
            val testConsumed = testUserCreatedEventConsumer.getConsumedEvents()

            // Real consumer should NOT have test event
            assertFalse(realProcessed.any { it.id == testUser.id },
                "RealUserCreatedEventConsumer should NOT receive test events")

            // Test consumer should NOT have production event
            assertFalse(testConsumed.any { it.id == prodUser.id },
                "TestUserCreatedEventConsumer should NOT receive production events")

        } finally {
            trafficContextManager.clearTrafficContext()
        }
    }

    private fun createTestConsumer(
        topic: String,
        records: BlockingQueue<ConsumerRecord<String, String>>
    ): KafkaMessageListenerContainer<String, String> {
        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "test-consumer-${topic}-${System.currentTimeMillis()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
        )

        val consumerFactory = DefaultKafkaConsumerFactory<String, String>(consumerProps)
        val containerProps = ContainerProperties(topic)
        containerProps.setMessageListener(MessageListener<String, String> { record ->
            records.add(record)
        })

        return KafkaMessageListenerContainer(consumerFactory, containerProps)
    }
}
