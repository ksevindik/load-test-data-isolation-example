package com.example.loadtest.event

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Consumer for test user events from user-events.test topic.
 * 
 * This consumer:
 * - Only receives events from the test topic (user-events.test)
 * - Uses test_consumer credentials with ACL-enforced read access
 * - Can be used for test verification, metrics collection, or test data cleanup
 * - Does NOT trigger production business logic (e.g., no emails, no CRM sync)
 */
@Component
@Profile("topic-routing")
class TestUserCreatedEventConsumer {

    private val logger = LoggerFactory.getLogger(TestUserCreatedEventConsumer::class.java)

    // Track consumed events for testing purposes
    private val consumedEvents = CopyOnWriteArrayList<UserCreatedEvent>()

    @KafkaListener(
        topics = ["\${app.kafka.topics.test}"],
        groupId = "\${app.kafka.consumer.test.group-id}",
        containerFactory = "testKafkaListenerContainerFactory"
    )
    fun onTestUserCreated(
        @Payload event: UserCreatedEvent,
        @Header(name = "X-Traffic-Type", required = false) trafficType: String?,
        @Header(name = "X-Test-Run-Id", required = false) testRunId: String?
    ) {
        logger.info(
            "Received TEST user created event: userId={}, username={}, email={}, trafficType={}, testRunId={}",
            event.id,
            event.username,
            event.email,
            trafficType ?: "UNKNOWN",
            testRunId ?: "-"
        )

        // Track the event but don't execute production business logic
        consumedEvents.add(event)
        
        // Optional: You could add test-specific logic here:
        // - Collect metrics about test events
        // - Verify event structure
        // - Clean up test data after test runs
        
        logger.debug("Test event consumed and tracked (no business logic executed)")
    }

    // Methods for testing and verification
    fun getConsumedEvents(): List<UserCreatedEvent> = consumedEvents.toList()
    fun clearEvents() = consumedEvents.clear()
    fun getEventsByTestRunId(testRunId: String): List<UserCreatedEvent> {
        // Note: This would require storing testRunId with the event
        return consumedEvents.toList()
    }
}
