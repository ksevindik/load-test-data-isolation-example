package com.example.loadtest.traffic

import jakarta.servlet.http.HttpServletRequest

/**
 * Represents traffic context containing traffic type and test run identifier.
 * Used to distinguish between production and test traffic for data isolation.
 */
data class TrafficContext(
    val trafficType: String?,
    val testRunId: String?
) {
    /**
     * Returns true if this context represents test traffic (LOAD_TEST).
     */
    fun isTestTraffic(): Boolean = trafficType == LOAD_TEST_VALUE

    companion object {
        const val TRAFFIC_TYPE_HEADER = "X-Traffic-Type"
        const val TEST_RUN_ID_HEADER = "X-Test-Run-Id"
        const val LOAD_TEST_VALUE = "LOAD_TEST"
        const val DEFAULT_TRAFFIC_TYPE = "PRODUCTION"
        const val DEFAULT_TEST_RUN_ID = "-"

        /**
         * Creates a TrafficContext from an HttpServletRequest by extracting headers.
         */
        fun of(request: HttpServletRequest): TrafficContext {
            return TrafficContext(
                trafficType = request.getHeader(TRAFFIC_TYPE_HEADER),
                testRunId = request.getHeader(TEST_RUN_ID_HEADER)
            )
        }

        /**
         * Creates a TrafficContext for test traffic.
         */
        fun forTest(testRunId: String = "test-run-${System.currentTimeMillis()}"): TrafficContext {
            return TrafficContext(LOAD_TEST_VALUE, testRunId)
        }

        /**
         * Creates a TrafficContext for production traffic.
         */
        fun forProduction(): TrafficContext {
            return TrafficContext(DEFAULT_TRAFFIC_TYPE, DEFAULT_TEST_RUN_ID)
        }
    }
}
