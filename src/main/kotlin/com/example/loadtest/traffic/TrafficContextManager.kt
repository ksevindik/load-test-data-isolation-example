package com.example.loadtest.traffic

import org.slf4j.MDC
import org.springframework.stereotype.Component

@Component
class TrafficContextManager(private val sessionBasedTestMode: SessionBasedTestMode) {

    companion object {
        private val TEST: ThreadLocal<Boolean> = ThreadLocal()

        private const val MDC_TRAFFIC_TYPE = "trafficType"
        private const val MDC_TEST_RUN_ID = "testRunId"

        fun isTestTraffic(): Boolean {
            return TEST.get() == true
        }
    }

    private fun markAsTest() {
        TEST.set(true)
        sessionBasedTestMode.attemptToMarkAsTest()
    }

    private fun clear() {
        TEST.remove()
        sessionBasedTestMode.attemptToClear()

        // Clear MDC
        MDC.remove(MDC_TRAFFIC_TYPE)
        MDC.remove(MDC_TEST_RUN_ID)
    }

    fun isTestTraffic(): Boolean {
        return TrafficContextManager.isTestTraffic()
    }

    /**
     * Sets traffic context from a TrafficContext object.
     * This method sets MDC values and marks test mode if applicable.
     */
    fun setTrafficContext(context: TrafficContext) {
        if (context.isTestTraffic()) {
            markAsTest()
        }

        // Set MDC values for logging
        MDC.put(MDC_TRAFFIC_TYPE, context.trafficType ?: TrafficContext.DEFAULT_TRAFFIC_TYPE)
        MDC.put(MDC_TEST_RUN_ID, context.testRunId ?: TrafficContext.DEFAULT_TEST_RUN_ID)
    }

    fun clearTrafficContext() {
        this.clear()
    }

    fun getCurrentUser(): String {
        return sessionBasedTestMode.getCurrentUser()
    }
}


