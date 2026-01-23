package com.example.loadtest.event

/**
 * Interface for publishing user created events to Kafka.
 * 
 * Implementations:
 * - SingleTopicUserEventPublisher: Publishes to single topic (user-events) with traffic type headers
 * - TopicRoutingUserEventPublisher: Routes to separate topics based on traffic type (topic-routing profile)
 */
interface UserEventPublisher {
    fun publishUserCreated(event: UserCreatedEvent)
}
