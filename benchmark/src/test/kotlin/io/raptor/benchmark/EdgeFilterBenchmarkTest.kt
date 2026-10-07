package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.core.EdgePenalty
import io.raptor.core.EdgePenaltyProvider
import io.raptor.data.NetworkLoader
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.random.Random

/**
 * Performance benchmark test verifying that dynamic edge penalties and segment blacklisting
 * meet the < 15ms query latency requirement on mobile workloads.
 */
class EdgeFilterBenchmarkTest {

    companion object {
        private lateinit var library: RaptorLibrary
        private lateinit var stopIds: List<Int>
        private lateinit var moderateProvider: EdgePenaltyProvider
        private lateinit var heavyProvider: EdgePenaltyProvider

        @BeforeClass
        @JvmStatic
        fun setup() {
            val config = DatasetConfig.LYON
            require(config.isAvailable()) { "LYON data not available at ${config.stopsPath()}" }

            library = RaptorLibrary(
                config.stopsPath().readBytes(),
                config.routesPath().readBytes()
            )
            val stops = NetworkLoader.loadStops(config.stopsPath().readBytes())
            val routes = NetworkLoader.loadRoutes(config.routesPath().readBytes())
            stopIds = stops.map { it.id }

            val rng = Random(42L)
            val candidateEdges = mutableListOf<Pair<Int, Int>>()
            for (route in routes) {
                val stopSeq = route.stopIds
                for (i in 0 until stopSeq.size - 1) {
                    candidateEdges.add(stopSeq[i] to stopSeq[i + 1])
                }
            }
            val shuffledEdges = candidateEdges.distinct().shuffled(rng)

            // Moderate: 50 delays, 10 cutoffs
            val moderatePenalties = shuffledEdges.take(60).mapIndexed { idx, (from, to) ->
                if (idx < 10) {
                    EdgePenalty.blocked(fromStopId = from, toStopId = to)
                } else {
                    EdgePenalty.delay(fromStopId = from, toStopId = to, penaltySeconds = rng.nextInt(60, 301))
                }
            }
            moderateProvider = EdgePenaltyProvider.fromList(moderatePenalties)

            // Heavy: 200 delays, 50 cutoffs
            val heavyPenalties = shuffledEdges.take(250).mapIndexed { idx, (from, to) ->
                if (idx < 50) {
                    EdgePenalty.blocked(fromStopId = from, toStopId = to)
                } else {
                    EdgePenalty.delay(fromStopId = from, toStopId = to, penaltySeconds = rng.nextInt(60, 601))
                }
            }
            heavyProvider = EdgePenaltyProvider.fromList(heavyPenalties)
        }
    }

    private data class LatencyStats(
        val p50Ms: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val avgMs: Double,
        val maxMs: Double
    )

    private fun computeStats(nanos: LongArray): LatencyStats {
        nanos.sort()
        val n = nanos.size
        fun p(pct: Double) = nanos[(pct * (n - 1)).toInt()] / 1_000_000.0
        val sum = nanos.sum().toDouble()
        val avg = (sum / n) / 1_000_000.0
        val max = nanos.last() / 1_000_000.0
        return LatencyStats(p(0.50), p(0.95), p(0.99), avg, max)
    }

    @Test
    fun forwardQueryLatencyUnderDisruptionsIsWithinBudget() {
        val queries = RandomQueryGenerator(stopIds, seed = 12345L).generate(300)

        // Warmup
        repeat(50) { i ->
            val q = queries[i]
            library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime, edgePenaltyProvider = heavyProvider)
        }

        // Measure baseline
        val baselineNanos = LongArray(queries.size)
        for (i in queries.indices) {
            val q = queries[i]
            val t0 = System.nanoTime()
            library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime)
            baselineNanos[i] = System.nanoTime() - t0
        }

        // Measure moderate disruptions
        val moderateNanos = LongArray(queries.size)
        for (i in queries.indices) {
            val q = queries[i]
            val t0 = System.nanoTime()
            library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime, edgePenaltyProvider = moderateProvider)
            moderateNanos[i] = System.nanoTime() - t0
        }

        // Measure heavy disruptions
        val heavyNanos = LongArray(queries.size)
        for (i in queries.indices) {
            val q = queries[i]
            val t0 = System.nanoTime()
            library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime, edgePenaltyProvider = heavyProvider)
            heavyNanos[i] = System.nanoTime() - t0
        }

        val baseStats = computeStats(baselineNanos)
        val modStats = computeStats(moderateNanos)
        val hvyStats = computeStats(heavyNanos)

        println("=== Forward Routing Latency Benchmark (LYON, ${queries.size} queries) ===")
        println("Baseline:    avg=%.2fms, p50=%.2fms, p95=%.2fms, p99=%.2fms, max=%.2fms".format(
            baseStats.avgMs, baseStats.p50Ms, baseStats.p95Ms, baseStats.p99Ms, baseStats.maxMs
        ))
        println("Moderate:    avg=%.2fms, p50=%.2fms, p95=%.2fms, p99=%.2fms, max=%.2fms".format(
            modStats.avgMs, modStats.p50Ms, modStats.p95Ms, modStats.p99Ms, modStats.maxMs
        ))
        println("Heavy:       avg=%.2fms, p50=%.2fms, p95=%.2fms, p99=%.2fms, max=%.2fms".format(
            hvyStats.avgMs, hvyStats.p50Ms, hvyStats.p95Ms, hvyStats.p99Ms, hvyStats.maxMs
        ))

        // Mobile latency budget is < 15ms
        assertTrue("Average forward latency with heavy disruptions must remain well below 15ms (was ${hvyStats.avgMs}ms)", hvyStats.avgMs < 5.0)
        assertTrue("p95 forward latency with heavy disruptions must be < 15ms (was ${hvyStats.p95Ms}ms)", hvyStats.p95Ms < 15.0)
    }

    @Test
    fun arriveByQueryLatencyUnderDisruptionsIsWithinBudget() {
        val queries = RandomQueryGenerator(stopIds, seed = 54321L).generate(150)

        // Warmup
        repeat(30) { i ->
            val q = queries[i]
            library.getOptimizedPathsArriveBy(q.originIds, q.destIds, q.departureTime + 3600, edgePenaltyProvider = moderateProvider)
        }

        // Measure moderate disruptions
        val modNanos = LongArray(queries.size)
        for (i in queries.indices) {
            val q = queries[i]
            val t0 = System.nanoTime()
            library.getOptimizedPathsArriveBy(q.originIds, q.destIds, q.departureTime + 3600, edgePenaltyProvider = moderateProvider)
            modNanos[i] = System.nanoTime() - t0
        }

        val modStats = computeStats(modNanos)
        println("=== Arrive-By Routing Latency Benchmark (LYON, ${queries.size} queries) ===")
        println("Moderate:    avg=%.2fms, p50=%.2fms, p95=%.2fms, p99=%.2fms, max=%.2fms".format(
            modStats.avgMs, modStats.p50Ms, modStats.p95Ms, modStats.p99Ms, modStats.maxMs
        ))

        assertTrue("Average arrive-by latency with disruptions must be < 15ms (was ${modStats.avgMs}ms)", modStats.avgMs < 15.0)
    }
}
