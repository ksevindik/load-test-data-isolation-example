package com.example.loadtest.benchmark

import java.lang.management.ManagementFactory

enum class RlsMode(val displayName: String) {
    DISABLED("RLS Disabled"),
    SESSION_BASED("Session-based RLS"),
    DEDICATED_USER("Dedicated-user RLS")
}

enum class OperationType(val displayName: String) {
    INSERT("INSERT"),
    SELECT_BY_ID("SELECT by ID"),
    SELECT_ALL("SELECT ALL"),
    UPDATE("UPDATE")
}

data class BenchmarkConfig(
    val totalOperations: Int = 10_000,
    val warmupOperations: Int = 500,
    val seedRowCount: Int = 5_000
)

data class LatencyPercentiles(
    val p25Nanos: Long,
    val p50Nanos: Long,
    val p75Nanos: Long,
    val p99Nanos: Long,
    val p99_5Nanos: Long
) {
    val p25Micros: Double get() = p25Nanos / 1_000.0
    val p50Micros: Double get() = p50Nanos / 1_000.0
    val p75Micros: Double get() = p75Nanos / 1_000.0
    val p99Micros: Double get() = p99Nanos / 1_000.0
    val p99_5Micros: Double get() = p99_5Nanos / 1_000.0
}

class LatencyCollector(private val expectedSize: Int) {
    private val latencies = LongArray(expectedSize)
    private var index = 0

    fun record(latencyNanos: Long) {
        if (index < latencies.size) {
            latencies[index++] = latencyNanos
        }
    }

    fun calculatePercentiles(): LatencyPercentiles {
        val count = minOf(index, latencies.size)
        val sorted = latencies.copyOf(count).apply { sort() }
        return LatencyPercentiles(
            p25Nanos = percentile(sorted, 25.0),
            p50Nanos = percentile(sorted, 50.0),
            p75Nanos = percentile(sorted, 75.0),
            p99Nanos = percentile(sorted, 99.0),
            p99_5Nanos = percentile(sorted, 99.5)
        )
    }

    private fun percentile(sortedArray: LongArray, percentile: Double): Long {
        if (sortedArray.isEmpty()) return 0L
        val index = (percentile / 100.0 * (sortedArray.size - 1)).toInt()
        return sortedArray[index.coerceIn(0, sortedArray.lastIndex)]
    }
}

data class BenchmarkResult(
    val rlsMode: RlsMode,
    val operationType: OperationType,
    val wallClockTimeNanos: Long,
    val latency: LatencyPercentiles,
    val totalOperations: Int,
    val affectedRows: Long
) {
    val wallClockTimeMs: Double get() = wallClockTimeNanos / 1_000_000.0
    val throughputOpsPerSec: Double get() = totalOperations / (wallClockTimeNanos / 1_000_000_000.0)
    val rowsPerOperation: Long get() = if (totalOperations > 0) affectedRows / totalOperations else 0
}

data class SystemInfoData(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val availableProcessors: Int,
    val maxHeapGB: Double,
    val jvmName: String,
    val jvmVersion: String,
    val jvmVendor: String,
    val garbageCollectors: List<String>
) {
    companion object {
        fun collect(): SystemInfoData {
            val memoryBean = ManagementFactory.getMemoryMXBean()
            val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
            return SystemInfoData(
                osName = System.getProperty("os.name"),
                osVersion = System.getProperty("os.version"),
                osArch = System.getProperty("os.arch"),
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                maxHeapGB = memoryBean.heapMemoryUsage.max / (1024.0 * 1024.0 * 1024.0),
                jvmName = System.getProperty("java.vm.name"),
                jvmVersion = System.getProperty("java.version"),
                jvmVendor = System.getProperty("java.vm.vendor"),
                garbageCollectors = gcBeans.map { it.name }
            )
        }
    }
}

data class HeapStats(val used: Long, val committed: Long, val max: Long) {
    val usedMB: Double get() = used / (1024.0 * 1024.0)
    val committedMB: Double get() = committed / (1024.0 * 1024.0)
    val maxMB: Double get() = max / (1024.0 * 1024.0)

    companion object {
        fun current(): HeapStats {
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            return HeapStats(heap.used, heap.committed, heap.max)
        }
    }
}
