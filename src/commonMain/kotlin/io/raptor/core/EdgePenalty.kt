package io.raptor.core

/**
 * Key identifying an edge between two consecutive stops along a transit route.
 *
 * Implements RFC 0001 §2.3:
 * - [fromStopId]: Dataset stop id where the traversal begins.
 * - [toStopId]: Dataset stop id where traversal ends (consecutive to [fromStopId]).
 * - [routeId]: Dataset route id (null = applies to all routes serving this pair).
 * - [tripId]: Optional filter narrowing to a single trip (null = all trips on matched routes).
 */
data class EdgeKey(
    val fromStopId: Int,
    val toStopId: Int,
    val routeId: Int? = null,
    val tripId: Int? = null
) {
    constructor(pair: Pair<Int, Int>) : this(pair.first, pair.second, null, null)
}

/**
 * Dynamic disruption applied to a directed edge traversal between two stops.
 *
 * Implements RFC 0001 §3.2:
 * - [fromStopId]: Start stop id of the edge traversal.
 * - [toStopId]: End stop id of the edge traversal.
 * - [routeId]: Specific dataset route id, or null for every route serving this stop pair.
 * - [tripId]: Specific dataset trip id, or null for all trips on matched routes.
 * - [penaltySeconds]: Extra duration in seconds added to the traversal of this edge.
 * - [isBlocked]: When true, this edge cannot be traversed (cut off / blocked segment).
 */
data class EdgePenalty(
    val fromStopId: Int,
    val toStopId: Int,
    val routeId: Int? = null,
    val tripId: Int? = null,
    val penaltySeconds: Int = 0,
    val isBlocked: Boolean = false
) {
    val key: EdgeKey
        get() = EdgeKey(fromStopId, toStopId, routeId, tripId)

    companion object {
        fun delay(
            fromStopId: Int,
            toStopId: Int,
            penaltySeconds: Int,
            routeId: Int? = null,
            tripId: Int? = null
        ): EdgePenalty = EdgePenalty(
            fromStopId = fromStopId,
            toStopId = toStopId,
            routeId = routeId,
            tripId = tripId,
            penaltySeconds = penaltySeconds,
            isBlocked = false
        )

        fun blocked(
            fromStopId: Int,
            toStopId: Int,
            routeId: Int? = null,
            tripId: Int? = null
        ): EdgePenalty = EdgePenalty(
            fromStopId = fromStopId,
            toStopId = toStopId,
            routeId = routeId,
            tripId = tripId,
            penaltySeconds = 0,
            isBlocked = true
        )
    }
}

/**
 * Provider interface for dynamic edge penalties and blockages.
 *
 * Allows callers to provide real-time disruptions or query-time edge adjustments.
 */
interface EdgePenaltyProvider {
    /**
     * Additional delay in seconds incurred when traversing from [fromStopId] to [toStopId]
     * on route [routeId] for trip [tripId].
     */
    fun getPenalty(fromStopId: Int, toStopId: Int, routeId: Int, tripId: Int): Int = 0

    /**
     * Returns true if traversal from [fromStopId] to [toStopId] on route [routeId] for trip [tripId]
     * is blocked.
     */
    fun isBlocked(fromStopId: Int, toStopId: Int, routeId: Int, tripId: Int): Boolean = false

    /**
     * Returns the list of penalties represented by this provider, if enumerable.
     */
    fun getPenalties(): Collection<EdgePenalty> = emptyList()

    companion object {
        val NONE: EdgePenaltyProvider = object : EdgePenaltyProvider {}

        fun fromList(penalties: Collection<EdgePenalty>): EdgePenaltyProvider =
            ListEdgePenaltyProvider(penalties)

        fun fromMap(penalties: Map<Pair<Int, Int>, Int>): EdgePenaltyProvider =
            fromList(penalties.map { (pair, delay) ->
                EdgePenalty(fromStopId = pair.first, toStopId = pair.second, penaltySeconds = delay)
            })

        fun fromEdgeKeyMap(penalties: Map<EdgeKey, Int>): EdgePenaltyProvider =
            fromList(penalties.map { (key, delay) ->
                EdgePenalty(
                    fromStopId = key.fromStopId,
                    toStopId = key.toStopId,
                    routeId = key.routeId,
                    tripId = key.tripId,
                    penaltySeconds = delay
                )
            })
    }
}

class ListEdgePenaltyProvider(private val penalties: Collection<EdgePenalty>) : EdgePenaltyProvider {
    override fun getPenalties(): Collection<EdgePenalty> = penalties

    override fun getPenalty(fromStopId: Int, toStopId: Int, routeId: Int, tripId: Int): Int {
        var total = 0
        for (p in penalties) {
            if (p.fromStopId == fromStopId && p.toStopId == toStopId &&
                (p.routeId == null || p.routeId == routeId) &&
                (p.tripId == null || p.tripId == tripId)
            ) {
                total += p.penaltySeconds
            }
        }
        return total
    }

    override fun isBlocked(fromStopId: Int, toStopId: Int, routeId: Int, tripId: Int): Boolean {
        for (p in penalties) {
            if (p.fromStopId == fromStopId && p.toStopId == toStopId &&
                (p.routeId == null || p.routeId == routeId) &&
                (p.tripId == null || p.tripId == tripId) &&
                p.isBlocked
            ) {
                return true
            }
        }
        return false
    }
}
