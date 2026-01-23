package com.example.loadtest.cache

import com.example.loadtest.traffic.TrafficContextManager
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager

/**
 * A CacheManager that routes to different underlying CacheManagers based on traffic type.
 * 
 * - Test traffic (X-Traffic-Type: LOAD_TEST) → testCacheManager (test:* keys, short TTL)
 * - Real traffic (default) → realCacheManager (real:* keys, long TTL)
 * 
 * This provides transparent cache isolation without changing application code.
 * Combined with Redis ACLs, it ensures complete separation at the Redis level.
 */
class RoutingCacheManager(
    private val realCacheManager: CacheManager,
    private val testCacheManager: CacheManager,
    private val trafficContextManager: TrafficContextManager
) : CacheManager {

    private val logger = LoggerFactory.getLogger(RoutingCacheManager::class.java)

    override fun getCache(name: String): Cache? {
        val isTestTraffic = trafficContextManager.isTestTraffic()
        val targetCacheManager = if (isTestTraffic) {
            logger.debug("Routing cache '{}' to testCacheManager (test traffic)", name)
            testCacheManager
        } else {
            logger.debug("Routing cache '{}' to realCacheManager (real traffic)", name)
            realCacheManager
        }
        return targetCacheManager.getCache(name)
    }

    override fun getCacheNames(): Collection<String> {
        val isTestTraffic = trafficContextManager.isTestTraffic()
        val targetCacheManager = if (isTestTraffic) {
            testCacheManager
        } else {
            realCacheManager
        }
        return targetCacheManager.cacheNames
    }
}
