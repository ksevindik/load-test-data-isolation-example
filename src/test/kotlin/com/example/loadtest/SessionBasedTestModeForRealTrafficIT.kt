package com.example.loadtest

import com.example.loadtest.service.UserService
import com.example.loadtest.traffic.TrafficContext
import com.example.loadtest.traffic.TrafficContextManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class SessionBasedTestModeForRealTrafficIT : BaseIT() {
    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var trafficContextManager: TrafficContextManager

    @BeforeEach
    fun setUp() {
        trafficContextManager.clearTrafficContext()
    }

    @Test
    fun `it should be able to fetch only real users when traffic type is not set`() {
        val users = userService.getUsers()
        Assertions.assertFalse(trafficContextManager.isTestTraffic())
        Assertions.assertEquals(5,users.size)
        Assertions.assertEquals(5,users.filter { !it.isTest }.size)
    }

    @Test
    fun `it should be able to create new real user when traffic type is not set`() {
        Assertions.assertEquals("app_user",trafficContextManager.getCurrentUser())
        val userCreated = userService.createUser("a", "b", "a@b.com", false)
        flushAndClear()
        val userFetched = userService.getUserById(userCreated.id!!)
        Assertions.assertFalse(trafficContextManager.isTestTraffic())
        Assertions.assertEquals(userCreated, userFetched)
    }

    @Test
    fun `it should not be able to create new real user when traffic type is marked as test`() {
        try {
            trafficContextManager.setTrafficContext(TrafficContext.forTest(this.javaClass.simpleName))
            Assertions.assertEquals("app_user",trafficContextManager.getCurrentUser())
            userService.createUser("a", "b", "a@b.com", false)
            flushAndClear()
            Assertions.fail<String>("Shouldn't have been able to create real user")
        } catch (ex: Exception) {
            Assertions.assertTrue(ex.cause!!.message!!.contains("ERROR: new row violates row-level security policy"))
        }
    }

    @Test
    fun `it should be able to update real user when traffic type is not set`() {
        Assertions.assertEquals("app_user",trafficContextManager.getCurrentUser())
        val userCreated = userService.createUser("a", "b", "a@b.com", false)
        flushAndClear()
        userService.updateUser(userCreated.id!!,userCreated.username,userCreated.password,"b@b.com")
        flushAndClear()
        val userFetched = userService.getUserById(userCreated.id!!)
        Assertions.assertFalse(trafficContextManager.isTestTraffic())
        Assertions.assertEquals("b@b.com", userFetched!!.email)
    }

    @Test
    fun `it should not be able to delete real user even when traffic type is not set`() {
        try {
            Assertions.assertEquals("app_user",trafficContextManager.getCurrentUser())
            val userCreated = userService.createUser("a", "b", "a@b.com", false)
            flushAndClear()
            userService.deleteUser(userCreated.id!!)
            flushAndClear()
            Assertions.fail<String>("Shouldn't have been able to delete real user")
        } catch (ex: Exception) {
            Assertions.assertTrue(ex.message!!.contains("Unexpected row count (expected row count 1 but was 0) [delete from t_users where id=?]"))
        }
    }
}