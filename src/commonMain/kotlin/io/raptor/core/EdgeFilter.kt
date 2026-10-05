package io.raptor.core

import io.raptor.model.Network

/**
 * Compiled edge disruptions applied to a single query, indexed by internal route index and position in route.
 *
 * Implements the edge-level equivalent of [StopFilter]:
 * - [disruptedRoutes]: map from internal route index to its [RouteEdgeDisruption].
 *
 * An undisrupted query has an empty or null [EdgeFilter], allowing the inner search loops to execute
 * with zero penalty overhead and zero allocations.
 */
class EdgeFilter(
    val disruptedRoutes: Map<Int, RouteEdgeDisruption> = emptyMap()
) {
    val isEmpty: Boolean
        get() = disruptedRoutes.isEmpty()

    fun getDisruption(routeIdx: Int): RouteEdgeDisruption? = disruptedRoutes[routeIdx]

    companion object {
        val NONE = EdgeFilter(emptyMap())
    }
}

/**
 * Disruptions on edges within a single route.
 *
 * For an edge entering stop index [posInRoute] along the route (i.e. traversing from [posInRoute - 1] to [posInRoute]):
 * - [penaltiesByPosition]: additional traversal delay in seconds (0 = no delay).
 * - [blockedByPosition]: true if traversal is blocked / cut off.
 * - Optional trip-specific overrides/penalties keyed by `(posInRoute.toLong() shl 32) or (tripId.toLong() and 0xFFFFFFFFL)`.
 */
class RouteEdgeDisruption(
    val penaltiesByPosition: IntArray,
    val blockedByPosition: BooleanArray,
    val tripPenalties: Map<Long, Int>? = null,
    val tripBlocked: Set<Long>? = null
) {
    fun isBlocked(posInRoute: Int, tripId: Int): Boolean {
        if (blockedByPosition[posInRoute]) return true
        if (tripBlocked != null) {
            val key = (posInRoute.toLong() shl 32) or (tripId.toLong() and 0xFFFFFFFFL)
            if (tripBlocked.contains(key)) return true
        }
        return false
    }

    fun getPenalty(posInRoute: Int, tripId: Int): Int {
        var p = penaltiesByPosition[posInRoute]
        if (tripPenalties != null) {
            val key = (posInRoute.toLong() shl 32) or (tripId.toLong() and 0xFFFFFFFFL)
            p += tripPenalties[key] ?: 0
        }
        return p
    }
}

/**
 * Compiles a collection of [EdgePenalty] items, an [EdgePenaltyProvider], or a raw penalty map into an [EdgeFilter]
 * bound to this [Network]'s internal route and stop indices.
 *
 * Unknown stop IDs and route IDs are dropped gracefully as specified in RFC 0001 §2.4.
 */
fun Network.buildEdgeFilter(
    edgePenalties: Collection<EdgePenalty> = emptyList(),
    edgePenaltyProvider: EdgePenaltyProvider? = null,
    edgePenaltyMap: Map<Pair<Int, Int>, Int>? = null
): EdgeFilter? {
    val allPenalties = ArrayList<EdgePenalty>()
    if (edgePenalties.isNotEmpty()) {
        allPenalties.addAll(edgePenalties)
    }
    if (edgePenaltyProvider != null && edgePenaltyProvider !== EdgePenaltyProvider.NONE) {
        val providerPenalties = edgePenaltyProvider.getPenalties()
        if (providerPenalties.isNotEmpty()) {
            allPenalties.addAll(providerPenalties)
        }
    }
    if (edgePenaltyMap != null && edgePenaltyMap.isNotEmpty()) {
        for ((pair, delay) in edgePenaltyMap) {
            allPenalties.add(EdgePenalty(fromStopId = pair.first, toStopId = pair.second, penaltySeconds = delay))
        }
    }
    if (allPenalties.isEmpty()) return null

    class RouteDisruptionBuilder(val stride: Int) {
        val penalties = IntArray(stride)
        val blocked = BooleanArray(stride)
        var tripPenalties: MutableMap<Long, Int>? = null
        var tripBlocked: MutableSet<Long>? = null

        fun addDisruption(pos: Int, tripId: Int?, penalty: Int, isBlocked: Boolean) {
            if (tripId == null) {
                if (isBlocked) blocked[pos] = true
                penalties[pos] += penalty
            } else {
                val key = (pos.toLong() shl 32) or (tripId.toLong() and 0xFFFFFFFFL)
                if (isBlocked) {
                    val tb = tripBlocked ?: HashSet<Long>().also { tripBlocked = it }
                    tb.add(key)
                }
                if (penalty > 0) {
                    val tp = tripPenalties ?: HashMap<Long, Int>().also { tripPenalties = it }
                    tp[key] = (tp[key] ?: 0) + penalty
                }
            }
        }

        fun build(): RouteEdgeDisruption = RouteEdgeDisruption(
            penaltiesByPosition = penalties,
            blockedByPosition = blocked,
            tripPenalties = tripPenalties,
            tripBlocked = tripBlocked
        )
    }

    val builders = HashMap<Int, RouteDisruptionBuilder>()

    for (p in allPenalties) {
        if (p.penaltySeconds <= 0 && !p.isBlocked) continue
        val fromIdx = getStopIndex(p.fromStopId)
        val toIdx = getStopIndex(p.toStopId)
        if (fromIdx == -1 || toIdx == -1) continue

        val candidateRouteIndices: IntArray? = if (p.routeId != null) {
            routeInternalIndices[p.routeId]
        } else {
            routeIndicesForStop[fromIdx]
        }
        if (candidateRouteIndices == null) continue

        for (rIdx in candidateRouteIndices) {
            val rsi = routeStopIndices[rIdx]
            val route = routeList[rIdx]

            // If p.tripId is specified, check that this route contains this trip
            if (p.tripId != null && !route.tripIds.contains(p.tripId)) {
                continue
            }

            // Find all matching consecutive positions (rsi[pos] == fromIdx && rsi[pos + 1] == toIdx)
            for (pos in 0 until rsi.size - 1) {
                if (rsi[pos] == fromIdx && rsi[pos + 1] == toIdx) {
                    val enterPos = pos + 1
                    val builder = builders.getOrPut(rIdx) { RouteDisruptionBuilder(route.stopCountInRoute) }
                    builder.addDisruption(enterPos, p.tripId, p.penaltySeconds, p.isBlocked)
                }
            }
        }
    }

    if (builders.isEmpty()) return null

    val map = HashMap<Int, RouteEdgeDisruption>(builders.size * 2)
    for ((rIdx, b) in builders) {
        map[rIdx] = b.build()
    }
    return EdgeFilter(map)
}
