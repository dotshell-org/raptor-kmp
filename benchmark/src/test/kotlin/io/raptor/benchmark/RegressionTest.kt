package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.core.JourneyLeg
import io.raptor.data.NetworkLoader
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/**
 * Regression guard: computes a single deterministic hash over the routing output of many
 * seeded random queries. Dataset-agnostic (runs on whatever dataset is available locally — LYON).
 *
 * Purpose: prove that a performance optimization does NOT change routing results. Any change to the
 * journeys produced (arrival times, legs, transfers, route names) flips the hash → test fails.
 *
 * Workflow:
 * 1. First run: EXPECTED_* are null, the test only prints the observed hashes (never fails).
 * 2. Lock the printed hashes into EXPECTED_FORWARD / EXPECTED_ARRIVE_BY below.
 * 3. Subsequent runs (after each optimization): the hash must match → identical output guaranteed.
 */
class RegressionTest {

    companion object {
        // Baseline hashes (LYON) — any optimization must reproduce them exactly.
        // Re-locked alongside the edge disruptions and Gradle 9.6 updates:
        // verified identical byte-for-byte on unmodified code before and after edge disruptions.
        private val EXPECTED_FORWARD: String? = "30337ac4be89420b"
        private val EXPECTED_ARRIVE_BY: String? = "e4cb9e7df2820bbb"

        private const val FORWARD_QUERIES = 500
        private const val ARRIVE_BY_QUERIES = 200

        private lateinit var library: RaptorLibrary
        private lateinit var stopIds: List<Int>

        @BeforeClass
        @JvmStatic
        fun setup() {
            val config = DatasetConfig.LYON
            require(config.isAvailable()) { "LYON data not available at ${config.stopsPath()}" }

            library = RaptorLibrary(
                config.stopsPath().readBytes(),
                config.routesPath().readBytes()
            )
            stopIds = NetworkLoader.loadStops(config.stopsPath().readBytes()).map { it.id }
        }

        /** Deterministic 64-bit rolling hash over the full structure of a result set. */
        private fun hashResults(results: List<List<List<JourneyLeg>>>): String {
            var h = 1125899906842597L // large prime
            for (journeys in results) {
                h = h * 31 + journeys.size
                for (journey in journeys) {
                    h = h * 31 + journey.size
                    for (leg in journey) {
                        h = h * 31 + leg.fromStopIndex
                        h = h * 31 + leg.toStopIndex
                        h = h * 31 + leg.departureTime
                        h = h * 31 + leg.arrivalTime
                        h = h * 31 + (if (leg.isTransfer) 1 else 0)
                        h = h * 31 + (leg.routeName?.hashCode()?.toLong() ?: 0L)
                    }
                }
            }
            return "%016x".format(h)
        }
    }

    @Test
    fun forwardRoutingOutputIsStable() {
        val queries = RandomQueryGenerator(stopIds, seed = 12345L).generate(FORWARD_QUERIES)
        val results = queries.map { q ->
            library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime)
        }
        val hash = hashResults(results)
        println("=== Forward regression hash (LYON, $FORWARD_QUERIES queries): $hash ===")

        EXPECTED_FORWARD?.let {
            assertEquals("Forward routing output changed vs baseline", it, hash)
        }
    }

    @Test
    fun arriveByRoutingOutputIsStable() {
        val queries = RandomQueryGenerator(stopIds, seed = 54321L).generate(ARRIVE_BY_QUERIES)
        val results = queries.map { q ->
            library.getOptimizedPathsArriveBy(q.originIds, q.destIds, q.departureTime + 3600)
        }
        val hash = hashResults(results)
        println("=== Arrive-by regression hash (LYON, $ARRIVE_BY_QUERIES queries): $hash ===")

        EXPECTED_ARRIVE_BY?.let {
            assertEquals("Arrive-by routing output changed vs baseline", it, hash)
        }
    }
}
