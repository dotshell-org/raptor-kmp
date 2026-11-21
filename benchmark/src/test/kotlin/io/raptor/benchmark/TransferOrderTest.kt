package io.raptor.benchmark

import io.raptor.benchmark.SyntheticNetworkBuilder.StopDef
import io.raptor.core.RaptorAlgorithm
import io.raptor.model.Network
import io.raptor.model.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walks after a round of rides leave from the rides' arrivals, one walk between two rides,
 * whatever order the routes and stops come in. 2.4.0 relaxed walks from live labels, so a stop
 * a walk had just improved passed that walk on or not depending on the order the stops were
 * marked in — which follows the route numbers: the same network numbered otherwise gave other
 * journeys. Times are seconds from midnight.
 */
class TransferOrderTest {

    /**
     * From O, X rides to P (30100) and Y to Q (30200). P–Q is a 60 s walk, Q–D another. Two walks
     * in a row (P, Q, D) would reach D at 30220; one walk from Q's ride reaches it at 30260.
     */
    private fun network(xFirst: Boolean): Network {
        val x = SyntheticNetworkBuilder.route(100, "X", intArrayOf(1, 2), listOf(intArrayOf(30000, 30100)))
        val y = SyntheticNetworkBuilder.route(101, "Y", intArrayOf(1, 3), listOf(intArrayOf(30000, 30200)))
        return SyntheticNetworkBuilder.network(
            stops = listOf(
                StopDef(1, "O"),
                StopDef(2, "P", transfers = listOf(Transfer(3, 60))),
                StopDef(3, "Q", transfers = listOf(Transfer(2, 60), Transfer(4, 60))),
                StopDef(4, "D", transfers = listOf(Transfer(3, 60)))
            ),
            routes = if (xFirst) listOf(x, y) else listOf(y, x)
        )
    }

    private fun Network.index(id: Int) = listOf(getStopIndex(id))

    @Test
    fun theOrderOfTheRoutesDoesNotChangeTheResult() {
        val results = listOf(true, false).map { xFirst ->
            val network = network(xFirst)
            RaptorAlgorithm(network).route(network.index(1), network.index(4), 29000)
        }
        assertEquals("one walk from Q's ride, in either order", listOf(30260, 30260), results)
    }

    @Test
    fun aJourneyWalkingOnFromARideIsRebuiltFromThatRide() {
        for (xFirst in listOf(true, false)) {
            val network = network(xFirst)
            val algorithm = RaptorAlgorithm(network)
            algorithm.route(network.index(1), network.index(4), 29000)
            // Q's own label is the earlier walk from P (30160); the journey left Q from Y's ride.
            val legs = algorithm.getJourney(network.getStopIndex(4))!!
            assertEquals(listOf("Y", null), legs.map { it.routeName })
            assertEquals(30200, legs[0].arrivalTime)
            assertEquals(30200, legs[1].departureTime)
            assertEquals(30260, legs[1].arrivalTime)
        }
    }

    @Test
    fun arriveByDoesNotDependOnTheOrderEither() {
        val results = listOf(true, false).map { xFirst ->
            val network = network(xFirst)
            RaptorAlgorithm(network).routeBackward(network.index(1), network.index(4), 30300, 29000)
        }
        assertEquals(results[0], results[1])
        assertEquals(30000, results[0])
    }

    /**
     * Round 1: R rides O to A (30000), a walk reaches X at 30060. Round 2: S rides A to X, later
     * (30500). D is a walk from X only. Walking on from X's earlier walk would be a second walk, so
     * D is reached from S's ride: the ride counts though it is later than X's label.
     */
    @Test
    fun aRideLaterThanAWalkStillLeadsOn() {
        val network = SyntheticNetworkBuilder.network(
            stops = listOf(
                StopDef(1, "O"),
                StopDef(2, "A", transfers = listOf(Transfer(3, 60))),
                StopDef(3, "X", transfers = listOf(Transfer(4, 60))),
                StopDef(4, "D")
            ),
            routes = listOf(
                SyntheticNetworkBuilder.route(100, "R", intArrayOf(1, 2), listOf(intArrayOf(29900, 30000))),
                SyntheticNetworkBuilder.route(101, "S", intArrayOf(2, 3), listOf(intArrayOf(30400, 30500)))
            )
        )
        val algorithm = RaptorAlgorithm(network)
        val arrival = algorithm.route(network.index(1), network.index(4), 29000)
        assertEquals(30560, arrival)
        val legs = algorithm.getJourney(network.getStopIndex(4))!!
        assertEquals(listOf("R", "S", null), legs.map { it.routeName })
        assertTrue(legs.zipWithNext().all { (a, b) -> a.arrivalTime <= b.departureTime })
    }
}
