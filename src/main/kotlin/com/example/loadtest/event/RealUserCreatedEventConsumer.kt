package com.example.loadtest.event

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Consumer for production user events from user-events.real topic.
 * 
 * This consumer:
 * - Only receives events from the production topic (user-events.real)
 * - Uses prod_consumer credentials with ACL-enforced read access
 * - Processes all events it receives (no header-based filtering needed since topic isolation is guaranteed)
 */
@Component
@Profile("topic-routing")
class RealUserCreatedEventConsumer {

    private val logger = LoggerFactory.getLogger(RealUserCreatedEventConsumer::class.java)

    // Track processed events for testing purposes
    private val processedEvents = CopyOnWriteArrayList<UserCreatedEvent>()

    @KafkaListener(
        topics = ["\${app.kafka.topics.real}"],
        groupId = "\${app.kafka.consumer.real.group-id}",
        containerFactory = "realKafkaListenerContainerFactory"
    )
    fun onRealUserCreated(
        @Payload event: UserCreatedEvent,
        @Header(name = "X-Traffic-Type", required = false) trafficType: String?,
        @Header(name = "X-Test-Run-Id", required = false) testRunId: String?
    ) {
        logger.info(
            "Processing REAL user created event: userId={}, username={}, email={}, trafficType={}",
            event.id,
            event.username,
            event.email,
            trafficType ?: "PRODUCTION"
        )

        // Business logic for production user creation
        processRealUserCreatedEvent(event)
    }

    private fun processRealUserCreatedEvent(event: UserCreatedEvent) {
        processedEvents.add(event)
        
        // Here you would add actual business logic:
        // - Send welcome email
        // - Sync to CRM
        // - Update analytics
        // - Trigger downstream workflows
        
        logger.info("Business logic executed for REAL user: {} (id: {})", event.username, event.id)
    }

    // Methods for testing
    fun getProcessedEvents(): List<UserCreatedEvent> = processedEvents.toList()
    fun clearEvents() = processedEvents.clear()
}
