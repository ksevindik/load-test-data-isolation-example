package com.example.loadtest.benchmark

import ch.qos.logback.classic.Level
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariProxyConnection
import org.h2.tools.Server
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.opentest4j.AssertionFailedError
import org.postgresql.jdbc.PgConnection
import org.slf4j.LoggerFactory
import org.springframework.jdbc.datasource.DataSourceUtils
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDate
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Tag("benchmark")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RlsBenchmarkTest {

    private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("loadtest")
        .withUsername("db_admin_user")
        .withPassword("secret")
        .withReuse(false)
        .withCopyFileToContainer(
            MountableFile.forHostPath("docker/postgres/01-init-app-users.sql"),
            "/docker-entrypoint-initdb.d/01-init-app-users.sql"
        )

    private lateinit var adminDataSource: HikariDataSource

    private val yearStart: Timestamp = Timestamp.valueOf(LocalDate.now().withDayOfYear(1).atStartOfDay())
    private val yearEnd: Timestamp = Timestamp.valueOf(LocalDate.now().withDayOfYear(1).plusYears(1).atStartOfDay())

    private val config = BenchmarkConfig(
        totalOperations = 10_000,
        warmupOperations = 500,
        seedRowCount = 5_000
    )

    @BeforeAll
    fun setup() {
        (LoggerFactory.getLogger("com.zaxxer.hikari") as ch.qos.logback.classic.Logger).level = Level.WARN
        (LoggerFactory.getLogger("org.testcontainers") as ch.qos.logback.classic.Logger).level = Level.WARN
        (LoggerFactory.getLogger("org.springframework.kafka") as ch.qos.logback.classic.Logger).level = Level.WARN

        postgres.start()
        adminDataSource = createDataSource("db_admin_user", "secret", poolSize = 1)
    }

    @AfterAll
    fun teardown() {
        if (::adminDataSource.isInitialized) adminDataSource.close()
        postgres.stop()
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun benchmarkRlsPerformance() {
        val results = mutableListOf<BenchmarkResult>()
        val systemInfo = SystemInfoData.collect()
        val heapBefore = HeapStats.current()

        executeSqlFromMigration("V1__create_users_table.sql")
        seedData()

        // Phase 1: No RLS policies — app_user sees all rows without policy evaluation
        println("\n>>> Phase 1: Benchmarking without RLS...")
        benchmarkForRlsMode(RlsMode.DISABLED, results, "app_user", "secret")

        // Phase 2: Enable session-based RLS — same user, now with policy evaluation
        executeSqlFromMigration("V2__enable_rls_with_session_based_test_mode.sql")
        println("\n>>> Phase 2: Benchmarking with session-based RLS...")
        benchmarkForRlsMode(RlsMode.SESSION_BASED, results, "app_user", "secret", "SET app.test_mode = 'false'")

        // Phase 3: Add dedicated-user RLS — role-based policy, no session variable needed
        executeSqlFromMigration("V3__enable_rls_for_dedicated_users.sql")
        println("\n>>> Phase 3: Benchmarking with dedicated-user RLS...")
        benchmarkForRlsMode(RlsMode.DEDICATED_USER, results, "app_real_user", "secret")

        val heapAfter = HeapStats.current()
        printReport(systemInfo, results, heapBefore, heapAfter)
    }

    // ── Benchmark Execution ─────────────────────────────────────────────────

    private fun benchmarkForRlsMode(
        mode: RlsMode,
        results: MutableList<BenchmarkResult>,
        username: String,
        password: String,
        initSql: String? = null
    ) {
        val ds = createDataSource(username, password, 5, initSql)
        warmUpDB(ds, mode)
        for (opType in OperationType.entries) {
            print("  ${opType.displayName}... ")
            val result = runBenchmark(mode, opType, ds)
            results.add(result)
            println(
                "done (p50=${String.format("%.1f", result.latency.p50Micros)}us, " +
                        "throughput=${String.format("%,.0f", result.throughputOpsPerSec)} ops/s)"
            )
        }
        cleanupBenchmarkData(mode)
    }

    private fun runBenchmark(mode: RlsMode, opType: OperationType, ds: DataSource): BenchmarkResult {
        val collector = LatencyCollector(config.totalOperations)
        var totalRows = 0L

        val wallStart = System.nanoTime()
        ds.connection.use { conn ->
            conn.prepareStatement(sqlFor(opType)).use { ps ->
                for (i in 0 until config.totalOperations) {
                    val start = System.nanoTime()
                    val rows = executeOp(ps, opType, mode, i)
                    if(opType == OperationType.SELECT_ALL) {
                        val expected = if(mode == RlsMode.DISABLED) config.totalOperations + config.seedRowCount * 2 else config.totalOperations + config.seedRowCount
                        try {
                            Assertions.assertEquals(expected.toLong(),rows)
                        } catch (ex: AssertionFailedError) {
                            Server.startWebServer(
                                ds.connection)
                        }

                    }
                    collector.record(System.nanoTime() - start)
                    totalRows += rows
                }
            }
        }
        val wallTime = System.nanoTime() - wallStart

        return BenchmarkResult(mode, opType, wallTime, collector.calculatePercentiles(), config.totalOperations, totalRows)
    }

    // ── SQL Operations ──────────────────────────────────────────────────────

    private fun sqlFor(opType: OperationType): String = when (opType) {
        OperationType.INSERT ->
            "INSERT INTO t_users (id, username, password, email, is_test, created_date) " +
                    "VALUES (nextval('t_users_seq'), ?, ?, ?, false, NOW())"

        OperationType.SELECT_BY_ID ->
            "SELECT * FROM t_users WHERE username = ? AND created_date >= ? AND created_date < ?"

        OperationType.SELECT_ALL ->
            "SELECT * FROM t_users WHERE created_date >= ? AND created_date < ?"

        OperationType.UPDATE ->
            "UPDATE t_users SET email = ? WHERE username = ? AND created_date >= ? AND created_date < ?"
    }

    private fun executeOp(
        ps: PreparedStatement,
        opType: OperationType,
        mode: RlsMode,
        opIdx: Int
    ): Long {
        val name = "bench_${mode.name.lowercase()}_$opIdx"
        return when (opType) {
            OperationType.INSERT -> {
                ps.setString(1, name)
                ps.setString(2, "benchpass")
                ps.setString(3, "$name@bench.com")
                ps.executeUpdate().toLong()
            }

            OperationType.SELECT_BY_ID -> {
                ps.setString(1, name)
                ps.setTimestamp(2, yearStart)
                ps.setTimestamp(3, yearEnd)
                var count = 0L
                ps.executeQuery().use { rs -> while (rs.next()) { count++ } }
                count
            }

            OperationType.SELECT_ALL -> {
                ps.setTimestamp(1, yearStart)
                ps.setTimestamp(2, yearEnd)
                var count = 0L
                ps.executeQuery().use { rs -> while (rs.next()) { count++ } }
                count
            }

            OperationType.UPDATE -> {
                ps.setString(1, "updated_$opIdx@bench.com")
                ps.setString(2, name)
                ps.setTimestamp(3, yearStart)
                ps.setTimestamp(4, yearEnd)
                ps.executeUpdate().toLong()
            }
        }
    }

    // ── Data Seeding & Cleanup ──────────────────────────────────────────────

    private fun seedData() {
        print("Seeding data... ")
        adminDataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO t_users (id, username, password, email, is_test, created_date) " +
                        "VALUES (nextval('t_users_seq'), ?, ?, ?, ?, NOW())"
            ).use { ps ->
                for (i in 1..config.seedRowCount) {
                    val username = "prod_user_$i"
                    ps.setString(1, username)
                    ps.setString(2, "password_$i")
                    ps.setString(3, "$username@example.com")
                    ps.setBoolean(4, false)
                    ps.addBatch()
                    if (i % 1000 == 0) ps.executeBatch()
                }
                ps.executeBatch()

                for (i in 1..config.seedRowCount) {
                    ps.setString(1, "test_user_$i")
                    ps.setString(2, "password_$i")
                    ps.setString(3, "test_user_$i@loadtest.com")
                    ps.setBoolean(4, true)
                    ps.addBatch()
                    if (i % 1000 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        }
        println("${String.format("%,d", config.seedRowCount)} production + ${String.format("%,d", config.seedRowCount)} test rows")
    }

    private fun warmUpDB(ds: DataSource, mode: RlsMode) {
        print("  Warming up... ")
        ds.connection.use { conn ->
            conn.prepareStatement(sqlFor(OperationType.INSERT)).use { ps ->
                for (i in 0 until minOf(config.warmupOperations, 100)) {
                    ps.setString(1, "warmup_${mode.ordinal}_$i")
                    ps.setString(2, "pass")
                    ps.setString(3, "warmup_${mode.ordinal}_$i@test.com")
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement("SELECT * FROM t_users WHERE created_date >= ? AND created_date < ?")
                .use { ps ->
                    for (i in 0 until config.warmupOperations) {
                        ps.setTimestamp(1, yearStart)
                        ps.setTimestamp(2, yearEnd)
                        ps.executeQuery().use { rs -> while (rs.next()) {} }
                    }
                }
        }
        adminDataSource.connection.use { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM t_users WHERE username LIKE 'warmup_%'") }
        }
        println("done (data created during warm up has been deleted)")
    }

    private fun cleanupBenchmarkData(mode: RlsMode) {
        adminDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val deleted = stmt.executeUpdate("DELETE FROM t_users WHERE username LIKE 'bench_${mode.name.lowercase()}_%'")
                println("  Cleaned up ${String.format("%,d", deleted)} benchmark rows")
            }
        }
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    private fun createDataSource(
        username: String,
        password: String,
        poolSize: Int,
        initSql: String? = null
    ): HikariDataSource {
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = poolSize
            minimumIdle = poolSize
            if (initSql != null) connectionInitSql = initSql
        })
    }

    private fun executeSqlFromMigration(filename: String) {
        val sql = javaClass.classLoader.getResource("db/migration/$filename")?.readText()
            ?: throw IllegalStateException("Migration file not found: $filename")
        adminDataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    // ── Report Output ───────────────────────────────────────────────────────

    private fun printReport(
        systemInfo: SystemInfoData,
        results: List<BenchmarkResult>,
        heapBefore: HeapStats,
        heapAfter: HeapStats
    ) {
        val sep = "=".repeat(98)
        val thin = "-".repeat(98)

        println()
        println(sep)
        println("                        RLS Performance Benchmark")
        println(sep)
        println("OS:      ${systemInfo.osName} ${systemInfo.osVersion} (${systemInfo.osArch})")
        println("JVM:     ${systemInfo.jvmName} ${systemInfo.jvmVersion} (${systemInfo.jvmVendor})")
        println("CPUs:    ${systemInfo.availableProcessors} available processors")
        println("GC:      ${systemInfo.garbageCollectors.joinToString(", ")}")
        println("Heap:    ${String.format("%.1f", heapBefore.maxMB)} MB max")
        println(thin)
        println("Config:  ${String.format("%,d", config.totalOperations)} operations per type (single-threaded)")
        println(
            "Seed:    ${String.format("%,d", config.seedRowCount)} production + ${
                String.format(
                    "%,d",
                    config.seedRowCount
                )
            } test rows"
        )
        println("Warmup:  ${config.warmupOperations} ops per type")
        println(sep)

        for (opType in OperationType.entries) {
            println()
            println("-- ${opType.displayName} (latency in us) ${"-".repeat(68)}")
            println(
                String.format(
                    "%-23s %8s %8s %8s %8s %8s %10s %12s %12s",
                    "Mode", "p25", "p50", "p75", "p99", "p99.5", "wall(ms)", "ops/s", "rows"
                )
            )
            println(thin)

            for (mode in RlsMode.entries) {
                val r = results.find { it.rlsMode == mode && it.operationType == opType } ?: continue
                val l = r.latency
                println(
                    String.format(
                        "%-23s %8.1f %8.1f %8.1f %8.1f %8.1f %10.0f %12.0f %12s",
                        mode.displayName,
                        l.p25Micros, l.p50Micros, l.p75Micros, l.p99Micros, l.p99_5Micros,
                        r.wallClockTimeMs, r.throughputOpsPerSec,
                        String.format("%,d", r.affectedRows)
                    )
                )
            }

            val baseline = results.find { it.rlsMode == RlsMode.DISABLED && it.operationType == opType }
            if (baseline != null && baseline.latency.p50Micros > 0) {
                println()
                for (mode in listOf(RlsMode.SESSION_BASED, RlsMode.DEDICATED_USER)) {
                    val r = results.find { it.rlsMode == mode && it.operationType == opType } ?: continue
                    val overheadPct =
                        ((r.latency.p50Micros - baseline.latency.p50Micros) / baseline.latency.p50Micros) * 100
                    println(
                        String.format(
                            "  %s overhead vs baseline: %+.1f%% (p50)",
                            mode.displayName, overheadPct
                        )
                    )
                }
            }
        }

        println()
        println("-- Notes ${"-".repeat(89)}")
        println("  * All SELECT/UPDATE queries include created_date range for RANGE partition pruning.")
        println("  * SELECT ALL without RLS returns all rows (production + test) for the year,")
        println("    while RLS modes return only production rows. Latency difference")
        println("    for this operation includes result set size difference.")
        println("  * Each operation is auto-committed (one transaction per operation).")
        println("  * Latency includes parameter binding, execution, and result iteration.")

        println()
        println("-- Heap Usage ${"-".repeat(84)}")
        println(
            "  Before: ${String.format("%.1f", heapBefore.usedMB)} MB used / ${
                String.format(
                    "%.1f",
                    heapBefore.committedMB
                )
            } MB committed"
        )
        println(
            "  After:  ${String.format("%.1f", heapAfter.usedMB)} MB used / ${
                String.format(
                    "%.1f",
                    heapAfter.committedMB
                )
            } MB committed"
        )
        println(sep)
    }
}
