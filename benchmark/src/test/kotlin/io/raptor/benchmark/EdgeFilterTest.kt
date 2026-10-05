package io.raptor.benchmark

import io.raptor.PeriodData
import io.raptor.RaptorLibrary
import io.raptor.benchmark.SyntheticNetworkBuilder.StopDef
import io.raptor.core.EdgeFilter
import io.raptor.core.EdgeKey
import io.raptor.core.EdgePenalty
import io.raptor.core.EdgePenaltyProvider
import io.raptor.core.RaptorAlgorithm
import io.raptor.core.buildEdgeFilter
import io.raptor.model.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for dynamic edge penalties and segment blacklisting (RFC 0001 / Issue #11).
 *
 * Verifies:
 * - Traversing a penalized edge increases arrival time by the penalty duration.
 * - Penalties accumulate across consecutive traversed edges on a leg.
 * - Boarding after a penalized edge does not pay the upstream penalty.
 * - Blocked edges (`isBlocked = true`) prevent traversing that segment.
 * - Boarding before a blocked edge cannot reach stops after the blockage.
 * - Alternative routes are chosen when the primary route is penalized or blocked.
 * - Route-less penalties apply to all routes traversing that edge.
 * - Route-specific penalties only affect the specified route.
 * - Trip-specific penalties/blockages only affect the specified trip.
 * - Backward (arrive-by) search correctly accounts for edge penalties and cutoffs.
 * - RaptorLibrary facade works end-to-end with List<EdgePenalty>, EdgePenaltyProvider, and Map<Pair<Int, Int>, Int>.
 */
class EdgeFilterTest {

    /**
     * Line X: Alpha(1) -> Beta(2) -> Gamma(3), one trip at 30000 / 30300 / 30600.
     */
    private fun lineNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma")),
        routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(intArrayOf(30000, 30300, 30600)),
                tripIds = intArrayOf(1001)
            )
        )
    )

    /**
     * Four-stop corridor:
     * Line X: Alpha(1) -> Beta(2) -> Gamma(3) -> Delta(4), scheduled 30000, 30300, 30600, 30900.
     */
    private fun corridorNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma"), StopDef(4, "Delta")),
        routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3, 4),
                listOf(intArrayOf(30000, 30300, 30600, 30900)),
                tripIds = intArrayOf(1001)
            )
        )
    )

    /**
     * Two competing routes between Alpha(1) and Gamma(3):
     * - Route X (via Beta 2): 1 -> 2 -> 3, departs 30000, arrives 30600 (fastest by default).
     * - Route Y (via Delta 4): 1 -> 4 -> 3, departs 30000, arrives 30650 (slower alternative).
     */
    private fun competingNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma"), StopDef(4, "Delta")),
        routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(intArrayOf(30000, 30300, 30600)),
                tripIds = intArrayOf(1001)
            ),
            SyntheticNetworkBuilder.route(
                200, "Y", intArrayOf(1, 4, 3),
                listOf(intArrayOf(30000, 30400, 30650)),
                tripIds = intArrayOf(2001)
            )
        )
    )

    /**
     * Multi-trip route:
     * Line X: Alpha(1) -> Beta(2) -> Gamma(3)
     * - Trip 1001: 30000 / 30300 / 30600
     * - Trip 1002: 31000 / 31300 / 31600
     */
    private fun multiTripNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma")),
        routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(
                    intArrayOf(30000, 30300, 30600),
                    intArrayOf(31000, 31300, 31600)
                ),
                tripIds = intArrayOf(1001, 1002)
            )
        )
    )

    /**
     * Shared corridor with two different routes serving Alpha(1) -> Beta(2):
     * - Route X (100): 1 -> 2 -> 3
     * - Route Z (300): 1 -> 2 -> 4
     */
    private fun sharedCorridorNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma"), StopDef(4, "Delta")),
        routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(intArrayOf(30000, 30300, 30600))
            ),
            SyntheticNetworkBuilder.route(
                300, "Z", intArrayOf(1, 2, 4),
                listOf(intArrayOf(30000, 30350, 30700))
            )
        )
    )

    private fun Network.index(id: Int) = listOf(getStopIndex(id))

    // ── 1. Edge Delay Penalties ──────────────────────────────────────────────────

    @Test
    fun edgePenaltyIncreasesArrivalTimeForDownstreamStops() {
        val network = lineNetwork()
        // Penalty on Beta(2) -> Gamma(3) of 120 seconds
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.delay(fromStopId = 2, toStopId = 3, penaltySeconds = 120))
        )

        val algo = RaptorAlgorithm(network)
        val arrivalBeta = algo.route(network.index(1), network.index(2), 29000, edgeFilter = filter)
        val arrivalGamma = algo.route(network.index(1), network.index(3), 29000, edgeFilter = filter)

        // Traversal 1 -> 2 is untouched
        assertEquals("Alpha to Beta arrival is unaffected by Beta->Gamma penalty", 30300, arrivalBeta)
        // Traversal 1 -> 3 crosses 2 -> 3, so arrival is delayed by 120s
        assertEquals("Alpha to Gamma arrival includes the 120s edge penalty", 30600 + 120, arrivalGamma)
    }

    @Test
    fun multipleEdgePenaltiesAccumulateAlongLeg() {
        val network = corridorNetwork()
        // 100s on 1 -> 2, 200s on 2 -> 3
        val filter = network.buildEdgeFilter(
            listOf(
                EdgePenalty.delay(fromStopId = 1, toStopId = 2, penaltySeconds = 100),
                EdgePenalty.delay(fromStopId = 2, toStopId = 3, penaltySeconds = 200)
            )
        )

        val algo = RaptorAlgorithm(network)
        // Alpha(1) -> Delta(4) traverses 1->2 (100s) + 2->3 (200s) + 3->4 (0s) = 300s penalty
        val arrivalAlphaToDelta = algo.route(network.index(1), network.index(4), 29000, edgeFilter = filter)
        assertEquals("Full leg traversal accumulates both penalties (30900 + 300)", 31200, arrivalAlphaToDelta)

        // Beta(2) -> Delta(4) traverses only 2->3 (200s) + 3->4 (0s) = 200s penalty
        val arrivalBetaToDelta = algo.route(network.index(2), network.index(4), 29000, edgeFilter = filter)
        assertEquals("Boarding at Beta avoids the upstream 1->2 penalty (30900 + 200)", 31100, arrivalBetaToDelta)
    }

    // ── 2. Edge Blockages (isBlocked = true) ─────────────────────────────────────

    @Test
    fun blockedEdgePreventsDownstreamTraversal() {
        val network = lineNetwork()
        // Cutoff between Beta(2) and Gamma(3)
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.blocked(fromStopId = 2, toStopId = 3))
        )

        val algo = RaptorAlgorithm(network)
        // Can reach Beta
        val arrivalBeta = algo.route(network.index(1), network.index(2), 29000, edgeFilter = filter)
        assertEquals("Alpha to Beta is before the cutoff and succeeds", 30300, arrivalBeta)

        // Cannot reach Gamma from Alpha
        val arrivalGamma = algo.route(network.index(1), network.index(3), 29000, edgeFilter = filter)
        assertEquals("Alpha to Gamma cannot traverse the blocked edge 2->3", Int.MAX_VALUE, arrivalGamma)

        // Cannot reach Gamma from Beta either
        val arrivalFromBeta = algo.route(network.index(2), network.index(3), 29000, edgeFilter = filter)
        assertEquals("Beta to Gamma cannot traverse the blocked edge 2->3", Int.MAX_VALUE, arrivalFromBeta)
    }

    // ── 3. Routing Decisions & Alternative Selection ────────────────────────────

    @Test
    fun penalizedRouteIsAvoidedInFavorOfFasterAlternative() {
        val network = competingNetwork()

        // Without penalties: Route X arrives at 30600, Route Y arrives at 30650 -> Route X wins
        val algo = RaptorAlgorithm(network)
        val defaultArrival = algo.route(network.index(1), network.index(3), 29000)
        assertEquals("Default fastest is Route X at 30600", 30600, defaultArrival)

        // Add 100s penalty to Route X's edge 2 -> 3: Route X now arrives at 30700
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.delay(fromStopId = 2, toStopId = 3, penaltySeconds = 100))
        )
        val penalizedArrival = algo.route(network.index(1), network.index(3), 29000, edgeFilter = filter)
        assertEquals("Route Y (30650) wins because Route X is delayed to 30700", 30650, penalizedArrival)
    }

    @Test
    fun blockedRouteIsAvoidedInFavorOfAlternative() {
        val network = competingNetwork()
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.blocked(fromStopId = 2, toStopId = 3))
        )

        val algo = RaptorAlgorithm(network)
        val arrival = algo.route(network.index(1), network.index(3), 29000, edgeFilter = filter)
        assertEquals("Route Y is chosen when Route X is blocked", 30650, arrival)
    }

    // ── 4. Route Scoping (routeId filtering) ────────────────────────────────────

    @Test
    fun routeSpecificPenaltyOnlyAffectsTargetRoute() {
        val network = sharedCorridorNetwork()
        // Penalize 1 -> 2 ONLY for Route X (id 100) by 500s
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.delay(fromStopId = 1, toStopId = 2, penaltySeconds = 500, routeId = 100))
        )

        val algo = RaptorAlgorithm(network)
        val arrivalX = algo.route(network.index(1), network.index(3), 29000, edgeFilter = filter)
        val arrivalZ = algo.route(network.index(1), network.index(4), 29000, edgeFilter = filter)

        assertEquals("Route X to Gamma is penalized (30600 + 500)", 31100, arrivalX)
        assertEquals("Route Z to Delta is unaffected on the same physical edge", 30700, arrivalZ)
    }

    @Test
    fun routelessPenaltyAffectsAllRoutesOnSameEdge() {
        val network = sharedCorridorNetwork()
        // Road closure/delay on 1 -> 2 without specifying routeId
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.delay(fromStopId = 1, toStopId = 2, penaltySeconds = 300, routeId = null))
        )

        val algo = RaptorAlgorithm(network)
        val arrivalX = algo.route(network.index(1), network.index(3), 29000, edgeFilter = filter)
        val arrivalZ = algo.route(network.index(1), network.index(4), 29000, edgeFilter = filter)

        assertEquals("Route X is penalized by 300s (30600 + 300)", 30900, arrivalX)
        assertEquals("Route Z is also penalized by 300s (30700 + 300)", 31000, arrivalZ)
    }

    // ── 5. Trip Scoping (tripId filtering) ──────────────────────────────────────

    @Test
    fun tripSpecificPenaltyOnlyAffectsTargetTrip() {
        val network = multiTripNetwork()
        // Trip 1001 is penalized on 2 -> 3 by 400s; Trip 1002 is normal
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.delay(fromStopId = 2, toStopId = 3, penaltySeconds = 400, tripId = 1001))
        )

        val algo = RaptorAlgorithm(network)
        // Departure at 29000 catches Trip 1001
        val arrival1 = algo.route(network.index(1), network.index(3), 29000, edgeFilter = filter)
        assertEquals("Trip 1001 is delayed by 400s (30600 + 400)", 31000, arrival1)

        // Departure at 30500 catches Trip 1002
        val arrival2 = algo.route(network.index(1), network.index(3), 30500, edgeFilter = filter)
        assertEquals("Trip 1002 arrives at its normal scheduled time", 31600, arrival2)
    }

    @Test
    fun tripSpecificBlockageFallsBackToNextTrip() {
        val network = multiTripNetwork()
        // Trip 1001 is blocked on 1 -> 2
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.blocked(fromStopId = 1, toStopId = 2, tripId = 1001))
        )

        val algo = RaptorAlgorithm(network)
        // Departure at 29000 cannot take Trip 1001, so it takes Trip 1002 (departing 31000, arriving 31600)
        val arrival = algo.route(network.index(1), network.index(3), 29000, edgeFilter = filter)
        assertEquals("Falls back to Trip 1002 arriving at 31600", 31600, arrival)
    }

    // ── 6. Arrive-By (Backward) Search with Edge Disruptions ────────────────────

    @Test
    fun backwardSearchAccountsForEdgePenalties() {
        val network = lineNetwork()
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.delay(fromStopId = 2, toStopId = 3, penaltySeconds = 300))
        )

        val algo = RaptorAlgorithm(network)
        // Scheduled arrival at Gamma is 30600. With 300s penalty, it arrives at 30900.
        // If we want to arrive by 30800, we miss Trip 1001 because it arrives at 30900!
        val missDeparture = algo.routeBackward(
            network.index(1), network.index(3),
            arrivalTime = 30800, earliestDeparture = 25000, edgeFilter = filter
        )
        assertEquals("Trip arriving at 30900 misses deadline 30800", Int.MIN_VALUE, missDeparture)

        // If we want to arrive by 30900, we can catch it by departing at 30000!
        val hitDeparture = algo.routeBackward(
            network.index(1), network.index(3),
            arrivalTime = 30900, earliestDeparture = 25000, edgeFilter = filter
        )
        assertEquals("Trip arriving at 30900 meets deadline 30900 departing at 30000", 30000, hitDeparture)
    }

    @Test
    fun backwardSearchAccountsForBlockedEdges() {
        val network = competingNetwork()
        // Block Route X on 2 -> 3
        val filter = network.buildEdgeFilter(
            listOf(EdgePenalty.blocked(fromStopId = 2, toStopId = 3))
        )

        val algo = RaptorAlgorithm(network)
        // Route Y arrives at 30650, departing at 30000
        val departure = algo.routeBackward(
            network.index(1), network.index(3),
            arrivalTime = 30700, earliestDeparture = 25000, edgeFilter = filter
        )
        assertEquals("Route Y is chosen in backward search when Route X is blocked", 30000, departure)
    }

    // ── 7. EdgePenaltyProvider & End-to-End RaptorLibrary ───────────────────────

    @Test
    fun raptorLibraryEndToEndWithEdgePenaltyProvider() {
        val stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma"))
        val routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(intArrayOf(30000, 30300, 30600))
            )
        )
        val (stopsBytes, routesBytes) = SyntheticNetworkBuilder.encodeBinary(stops, routes)
        val library = RaptorLibrary(listOf(PeriodData("default", stopsBytes, routesBytes)))

        // Using in-memory test map Map<Pair<StopId, StopId>, Int> (Issue #11 prerequisite)
        val penaltyMap = mapOf((2 to 3) to 180)
        val provider = EdgePenaltyProvider.fromMap(penaltyMap)

        val journeys = library.getOptimizedPaths(
            originStopIds = listOf(1),
            destinationStopIds = listOf(3),
            departureTime = 29000,
            edgePenaltyProvider = provider
        )

        assertEquals("One Pareto journey returned", 1, journeys.size)
        val leg = journeys.first().first()
        assertEquals("Arrival includes 180s penalty (30600 + 180)", 30780, leg.arrivalTime)
    }

    @Test
    fun raptorLibraryEndToEndWithEdgeKeyMap() {
        val stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma"))
        val routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(intArrayOf(30000, 30300, 30600))
            )
        )
        val (stopsBytes, routesBytes) = SyntheticNetworkBuilder.encodeBinary(stops, routes)
        val library = RaptorLibrary(listOf(PeriodData("default", stopsBytes, routesBytes)))

        val edgeKeyMap = mapOf(EdgeKey(2, 3) to 250)
        val provider = EdgePenaltyProvider.fromEdgeKeyMap(edgeKeyMap)

        val journeys = library.getOptimizedPaths(
            originStopIds = listOf(1),
            destinationStopIds = listOf(3),
            departureTime = 29000,
            edgePenaltyProvider = provider
        )

        assertEquals(1, journeys.size)
        assertEquals(30600 + 250, journeys.first().first().arrivalTime)
    }

    @Test
    fun raptorLibraryEndToEndWithBlockedEdgePenaltyList() {
        val stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma"))
        val routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(intArrayOf(30000, 30300, 30600))
            )
        )
        val (stopsBytes, routesBytes) = SyntheticNetworkBuilder.encodeBinary(stops, routes)
        val library = RaptorLibrary(listOf(PeriodData("default", stopsBytes, routesBytes)))

        val journeys = library.getOptimizedPaths(
            originStopIds = listOf(1),
            destinationStopIds = listOf(3),
            departureTime = 29000,
            edgePenalties = listOf(EdgePenalty.blocked(fromStopId = 2, toStopId = 3))
        )

        assertTrue("Destination unreachable because edge is blocked", journeys.isEmpty())
    }

    @Test
    fun raptorLibraryArriveByEndToEndWithEdgePenalties() {
        val stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma"))
        val routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(intArrayOf(30000, 30300, 30600))
            )
        )
        val (stopsBytes, routesBytes) = SyntheticNetworkBuilder.encodeBinary(stops, routes)
        val library = RaptorLibrary(listOf(PeriodData("default", stopsBytes, routesBytes)))

        // Arrive-by 30750 with 150s penalty on 2->3 (arrival is 30600 + 150 = 30750)
        val journeys = library.getOptimizedPathsArriveBy(
            originStopIds = listOf(1),
            destinationStopIds = listOf(3),
            arrivalTime = 30750,
            edgePenalties = listOf(EdgePenalty.delay(2, 3, 150))
        )

        assertEquals("Journey found in arrive-by", 1, journeys.size)
        assertEquals("Departs at 30000", 30000, journeys.first().first().departureTime)
        assertEquals("Arrives at 30750", 30750, journeys.first().first().arrivalTime)
    }
}
