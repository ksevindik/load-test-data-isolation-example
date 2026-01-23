package com.example.loadtest.service

import com.example.loadtest.event.UserCreatedEvent
import com.example.loadtest.event.UserEventPublisher
import com.example.loadtest.model.User
import com.example.loadtest.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull

/**
 * Service for user operations with cache isolation between test and real traffic.
 * 
 * When cache-routing profile is active:
 * - Real traffic: Keys are prefixed with "real:" (e.g., real:users::123)
 * - Test traffic: Keys are prefixed with "test:" (e.g., test:users::456)
 * 
 * Combined with Redis ACLs:
 * - app_real_user can only access real:* keys
 * - app_test_user can only access test:* keys
 * 
 * Different TTLs ensure test data expires faster:
 * - Real traffic: 1 hour TTL
 * - Test traffic: 10 minutes TTL
 */
@Service
class UserService(
    private val userRepository: UserRepository,
    private val userEventPublisher: UserEventPublisher
) {
    private val logger = LoggerFactory.getLogger(UserService::class.java)

    companion object {
        const val CACHE_NAME = "users"
    }

    fun getUsers() : List<User> = userRepository.findAll()

    fun getUser(email: String): User? {
        return userRepository.findByEmail(email).getOrNull()
    }

    /**
     * Get user by ID with caching.
     * 
     * Cache key format:
     * - Real traffic: real:users::123
     * - Test traffic: test:users::456
     */
    @Cacheable(cacheNames = [CACHE_NAME], key = "#id")
    fun getUserById(id: Long): User? {
        logger.info("Cache MISS for user ID: {} - fetching from database", id)
        return userRepository.findById(id).getOrNull()
    }

    fun createUser(username: String, password: String, email: String, isTest: Boolean = false): User {
        val user = User(username, password, email)
        user.isTest = isTest
        val savedUser = userRepository.save(user)
        
        // Publish Kafka event
        userEventPublisher.publishUserCreated(
            UserCreatedEvent(
                id = savedUser.id!!,
                username = savedUser.username,
                email = savedUser.email,
                isTest = savedUser.isTest
            )
        )
        
        return savedUser
    }

    fun updateUser(id: Long, username: String, password: String, email: String): User? {
        val existingUser = userRepository.findById(id).getOrNull() ?: return null
        existingUser.username = username
        existingUser.password = password
        existingUser.email = email
        return userRepository.save(existingUser)
    }

    fun deleteUser(id:Long) {
        userRepository.deleteById(id)
    }

    /**
     * Evict all users from cache.
     * Note: This will only evict from the current traffic type's cache (real or test).
     */
    @CacheEvict(cacheNames = [CACHE_NAME], allEntries = true)
    fun evictAllUsersCache() {
        logger.info("Evicting all users from cache")
    }
}