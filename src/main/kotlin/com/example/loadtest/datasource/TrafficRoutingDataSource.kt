package com.example.loadtest.datasource

import com.example.loadtest.traffic.TrafficContextManager
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource

class TrafficRoutingDataSource() : AbstractRoutingDataSource() {

    override fun determineCurrentLookupKey(): Any {
        return if (TrafficContextManager.isTestTraffic()) "TEST" else "REAL"
    }
}
