package com.example.loadtest.event

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

@Component
class UserCreatedEventConsumer {

    private val logger = LoggerFactory.getLogger(UserCreatedEventConsumer::class.java)

    // Lists to track events for testing purposes
    private val processedEvents = CopyOnWriteArrayList<UserCreatedEvent>()
    private val ignoredEvents = CopyOnWriteArrayList<UserCreatedEvent>()

    companion object {
        const val HEADER_TRAFFIC_TYPE = "X-Traffic-Type"
        const val LOAD_TEST_VALUE = "LOAD_TEST"
    }

    @KafkaListener(
        topics = [SingleTopicUserEventPublisher.USER_EVENTS_TOPIC],
        groupId = "user-event-processor"
    )
    fun onUserCreated(
        @Payload event: UserCreatedEvent,
        @Header(name = HEADER_TRAFFIC_TYPE, required = false) trafficType: String?
    ) {
        // Check if this is test traffic - if so, ignore the event
        if (trafficType == LOAD_TEST_VALUE) {
            logger.info("Ignoring test user created event for user ${event.id} (username: ${event.username}) - X-Traffic-Type: $trafficType")
            ignoredEvents.add(event)
            return
        }

        // Process production user created event
        logger.info("Processing PRODUCTION user created event for user ${event.id} (username: ${event.username}, email: ${event.email})")
        
        // Here you would add your actual business logic for handling new user creation
        // For example: send welcome email, sync to external system, update analytics, etc.
        processUserCreatedEvent(event)
    }

    private fun processUserCreatedEvent(event: UserCreatedEvent) {
        processedEvents.add(event)
        logger.info("Business logic executed for new production user: ${event.username}")
    }

    // Methods for testing
    fun getProcessedEvents(): List<UserCreatedEvent> = processedEvents.toList()
    fun getIgnoredEvents(): List<UserCreatedEvent> = ignoredEvents.toList()
    fun clearEvents() {
        processedEvents.clear()
        ignoredEvents.clear()
    }
}
