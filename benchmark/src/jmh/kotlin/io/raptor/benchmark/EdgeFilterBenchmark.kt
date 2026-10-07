package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.core.EdgePenalty
import io.raptor.core.EdgePenaltyProvider
import io.raptor.data.NetworkLoader
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * JMH benchmark evaluating RAPTOR routing latency with runtime edge penalties
 * and segment blacklisting (RFC 0001 / Issue #11).
 *
 * Compares:
 * - Clean baseline (no disruptions)
 * - Moderate disruption workload (50 delayed segments, 10 blocked segments)
 * - Heavy disruption workload (200 delayed segments, 50 blocked segments)
 *
 * Verifies that query latency remains well below the 15ms mobile budget.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class EdgeFilterBenchmark {

    @Param("LYON")
    lateinit var dataset: String

    private lateinit var library: RaptorLibrary
    private lateinit var queries: List<QueryPair>
    private lateinit var moderatePenalties: List<EdgePenalty>
    private lateinit var moderateProvider: EdgePenaltyProvider
    private lateinit var heavyPenalties: List<EdgePenalty>
    private lateinit var heavyProvider: EdgePenaltyProvider
    private var queryIndex = 0

    @Setup(Level.Trial)
    fun setup() {
        val config = DatasetConfig.valueOf(dataset)
        require(config.isAvailable()) {
            "Dataset $dataset not available at ${config.stopsPath()} / ${config.routesPath()}"
        }

        library = RaptorLibrary(
            stopsBytes = config.stopsPath().readBytes(),
            routesBytes = config.routesPath().readBytes()
        )

        val stops = NetworkLoader.loadStops(config.stopsPath().readBytes())
        val routes = NetworkLoader.loadRoutes(config.routesPath().readBytes())
        val allStopIds = stops.map { it.id }

        // Generate 1000 random queries with fixed seed for reproducibility
        val generator = RandomQueryGenerator(allStopIds, seed = 12345L)
        queries = generator.generate(1000)

        // Generate synthetic realistic edge disruptions across the network
        val rng = Random(42L)
        val candidateEdges = mutableListOf<Pair<Int, Int>>()
        for (route in routes) {
            val stopSeq = route.stopIds
            for (i in 0 until stopSeq.size - 1) {
                candidateEdges.add(stopSeq[i] to stopSeq[i + 1])
            }
        }
        val shuffledEdges = candidateEdges.distinct().shuffled(rng)

        // Moderate workload: 50 delayed segments (60-300s) + 10 blocked segments
        moderatePenalties = shuffledEdges.take(60).mapIndexed { idx, (from, to) ->
            if (idx < 10) {
                EdgePenalty.blocked(fromStopId = from, toStopId = to)
            } else {
                EdgePenalty.delay(fromStopId = from, toStopId = to, penaltySeconds = rng.nextInt(60, 301))
            }
        }
        moderateProvider = EdgePenaltyProvider.fromList(moderatePenalties)

        // Heavy workload: 200 delayed segments + 50 blocked segments
        heavyPenalties = shuffledEdges.take(250).mapIndexed { idx, (from, to) ->
            if (idx < 50) {
                EdgePenalty.blocked(fromStopId = from, toStopId = to)
            } else {
                EdgePenalty.delay(fromStopId = from, toStopId = to, penaltySeconds = rng.nextInt(60, 601))
            }
        }
        heavyProvider = EdgePenaltyProvider.fromList(heavyPenalties)

        // Prime cache
        library.getOptimizedPaths(queries[0].originIds, queries[0].destIds, queries[0].departureTime)
        queryIndex = 0
    }

    @Benchmark
    fun forwardBaseline(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        bh.consume(library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime))
    }

    @Benchmark
    fun forwardModerateDisruptions(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        bh.consume(
            library.getOptimizedPaths(
                q.originIds, q.destIds, q.departureTime,
                edgePenaltyProvider = moderateProvider
            )
        )
    }

    @Benchmark
    fun forwardHeavyDisruptions(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        bh.consume(
            library.getOptimizedPaths(
                q.originIds, q.destIds, q.departureTime,
                edgePenaltyProvider = heavyProvider
            )
        )
    }

    @Benchmark
    fun arriveByBaseline(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        bh.consume(library.getOptimizedPathsArriveBy(q.originIds, q.destIds, q.departureTime + 3600))
    }

    @Benchmark
    fun arriveByModerateDisruptions(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        bh.consume(
            library.getOptimizedPathsArriveBy(
                q.originIds, q.destIds, q.departureTime + 3600,
                edgePenaltyProvider = moderateProvider
            )
        )
    }
}
