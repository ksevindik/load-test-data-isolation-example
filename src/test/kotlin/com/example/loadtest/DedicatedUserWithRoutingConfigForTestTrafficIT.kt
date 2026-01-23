package com.example.loadtest

import com.example.loadtest.service.UserService
import com.example.loadtest.traffic.TrafficContext
import com.example.loadtest.traffic.TrafficContextManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@ActiveProfiles(value = ["datasource-routing"], inheritProfiles = true)
/*
Spring TestContext starts transaction way before setUp method is invoked, and when transaction is created at that point
routing datasource will obtain connection from real datasource as traffic has not yet been marked as test. Once tx
is initiated, marking traffic as test won't help, as DB connection that has been acquired during transaction initiation
will be used throughout the execution of test method. Due to this we suspend tx, and create another tx right after traffic
is marked as test within the setUp method, and rollback it at the end of the test method within the tearDown method.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DedicatedUserWithRoutingConfigForTestTrafficIT : BaseIT(){

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var trafficContextManager: TrafficContextManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var transactionStatus: TransactionStatus

    @BeforeEach
    fun setUp() {
        trafficContextManager.setTrafficContext(TrafficContext.forTest(this.javaClass.simpleName))
        transactionStatus = transactionManager.getTransaction(TransactionDefinition.withDefaults())
        transactionStatus.setRollbackOnly()
    }

    @AfterEach
    fun tearDown() {
        try {
            trafficContextManager.clearTrafficContext()
        } catch (ex: kotlin.Exception) {
            //ignore...
        }
        transactionManager.rollback(transactionStatus)
    }

    @Test
    fun `it should fetch only test users when traffic type is marked as test`() {
        val users = userService.getUsers()
        Assertions.assertEquals("app_test_user",trafficContextManager.getCurrentUser())
        Assertions.assertTrue(trafficContextManager.isTestTraffic())
        Assertions.assertEquals(5, users.size)
        Assertions.assertEquals(5, users.filter { it.isTest }.size)
    }

    @Test
    fun `it should be able to create new test user when traffic type is marked as test`() {
        val userCreated = userService.createUser("a", "b", "a@b.com", true)
        flushAndClear()
        val userFetched = userService.getUserById(userCreated.id!!)
        Assertions.assertEquals("app_test_user",trafficContextManager.getCurrentUser())
        Assertions.assertTrue(trafficContextManager.isTestTraffic())
        Assertions.assertEquals(userCreated, userFetched)
    }

    @Test
    fun `it should not be able to create new real user when traffic type is marked as test`() {
        try {
            userService.createUser("a", "b", "a@b.com", false)
            flushAndClear()
            Assertions.fail<String>("Shouldn't have been able to create test user")
            Assertions.assertEquals("app_test_user",trafficContextManager.getCurrentUser())
        } catch (ex: Exception) {
            Assertions.assertTrue(ex.cause!!.message!!.contains("ERROR: new row violates row-level security policy"))
        }
    }

    @Test
    fun `it should be able to update test user when traffic type is marked as test`() {
        val userCreated = userService.createUser("a", "b", "a@b.com", true)
        flushAndClear()
        userService.updateUser(userCreated.id!!,userCreated.username,userCreated.password,"b@b.com")
        flushAndClear()
        val userFetched = userService.getUserById(userCreated.id!!)
        Assertions.assertEquals("app_test_user",trafficContextManager.getCurrentUser())
        Assertions.assertTrue(trafficContextManager.isTestTraffic())
        Assertions.assertEquals("b@b.com", userFetched!!.email)
    }

    @Test
    fun `it should not be able to delete test user even when traffic type is marked as test`() {
        try {
            val userCreated = userService.createUser("a", "b", "a@b.com", true)
            flushAndClear()
            userService.deleteUser(userCreated.id!!)
            flushAndClear()
            Assertions.fail<String>("Shouldn't have been able to delete test user")
            Assertions.assertEquals("app_test_user",trafficContextManager.getCurrentUser())
        } catch (ex: Exception) {
            Assertions.assertTrue(ex.message!!.contains("Unexpected row count (expected row count 1 but was 0) [delete from t_users where id=?]"))
        }
    }

}
