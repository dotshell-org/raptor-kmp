package io.raptor

import io.raptor.core.EdgeFilter
import io.raptor.core.EdgePenalty
import io.raptor.core.EdgePenaltyProvider
import io.raptor.core.JourneyLeg
import io.raptor.core.LegType
import io.raptor.core.RaptorAlgorithm
import io.raptor.core.buildEdgeFilter
import io.raptor.data.NetworkLoader
import io.raptor.geo.Geo
import io.raptor.model.Network
import io.raptor.model.Route
import io.raptor.model.Stop

/**
 * Binary data for a specific time period. The stops/routes bytes are supplied through providers
 * so a period's `.bin` files are read only when that period is actually built — see
 * [RaptorLibrary]'s lazy per-period construction. Callers holding the bytes already in memory can
 * use the eager convenience constructor.
 *
 * @param periodId Identifier for this time period (e.g., "winter2024", "summer2024")
 * @param stopsProvider Supplies the raw bytes of the stops binary file (invoked at most once)
 * @param routesProvider Supplies the raw bytes of the routes binary file (invoked at most once)
 */
class PeriodData(
    val periodId: String,
    val stopsProvider: () -> ByteArray,
    val routesProvider: () -> ByteArray
) {
    /**
     * Eager convenience constructor for callers that already hold the bytes in memory.
     * Backward-compatible with the previous `PeriodData(periodId, stopsBytes, routesBytes)`.
     */
    constructor(periodId: String, stopsBytes: ByteArray, routesBytes: ByteArray) :
        this(periodId, { stopsBytes }, { routesBytes })
}

/**
 * RAPTOR library for routing search with support for multiple time periods.
 * Cross-platform: pass the raw bytes of each period's `.bin` files.
 * Example:
 * ```
 * RaptorLibrary(listOf(
 *     PeriodData("winter", stopsWinterBytes, routesWinterBytes),
 *     PeriodData("summer", stopsSummerBytes, routesSummerBytes)
 * ))
 * ```
 */
class RaptorLibrary(periodDataList: List<PeriodData>) {
    // Each period's Network is built lazily on first access, so cold start only pays for the
    // active period; the other periods (e.g. weekend/holiday schedules) are read from their
    // providers and indexed only when they are actually selected via [setPeriod] or queried.
    private val networks: Map<String, Lazy<Network>>
    private val algorithmCache = mutableMapOf<String, RaptorAlgorithm>()
    private var currentPeriodId: String

    init {
        require(periodDataList.isNotEmpty()) { "At least one period data must be provided" }

        networks = periodDataList.associate { periodData ->
            periodData.periodId to lazy {
                val stops = NetworkLoader.loadStops(periodData.stopsProvider())
                val routes = NetworkLoader.loadRoutes(periodData.routesProvider())
                Network(stops, routes)
            }
        }

        // Par défaut, utilise la première période
        currentPeriodId = periodDataList.first().periodId
    }
    
    /**
     * Alternative constructor for single period (backward compatibility)
     */
    constructor(stopsBytes: ByteArray, routesBytes: ByteArray) : this(
        listOf(PeriodData("default", stopsBytes, routesBytes))
    )
    
    /**
     * Sets the active period for route calculations
     * @param periodId The identifier of the period to use
     * @return true if the period was found and set, false otherwise
     */
    fun setPeriod(periodId: String): Boolean {
        return if (networks.containsKey(periodId)) {
            currentPeriodId = periodId
            true
        } else {
            false
        }
    }
    
    /**
     * Gets the currently active period identifier
     */
    fun getCurrentPeriod(): String = currentPeriodId
    
    /**
     * Gets all available period identifiers
     */
    fun getAvailablePeriods(): Set<String> = networks.keys

    /**
     * Parsed routes of [periodId]. Triggers the (lazy) build of that period's network on first
     * access, and returns an empty list if the period is unknown. Lets callers reuse the engine's
     * already-parsed data instead of re-parsing the `.bin` bytes themselves.
     */
    fun getRoutes(periodId: String): List<Route> =
        networks[periodId]?.value?.routeList?.toList() ?: emptyList()

    /**
     * Parsed stops of [periodId]. Triggers the (lazy) build of that period's network on first
     * access, and returns an empty list if the period is unknown.
     */
    fun getStops(periodId: String): List<Stop> =
        networks[periodId]?.value?.stops ?: emptyList()

    /**
     * Gets the network for the current period
     */
    private fun getCurrentNetwork(): Network = networks[currentPeriodId]!!.value

    /**
     * Searches for optimized paths between stop identifiers.
     * Returns a list of journeys (Pareto-optimal with respect to the number of transfers).
     * Uses the currently active period.
     * 
     * @param originStopIds List of origin stop identifiers
     * @param destinationStopIds List of destination stop identifiers
     * @param departureTime Departure time in seconds from midnight
     * @param maxRounds Maximum number of transfers + 1
     * @param blockedStopIds Stops the vehicle no longer serves (closed platform, incident).
     * @param stopPenaltySeconds Extra seconds charged for arriving at or transferring at a stop,
     *        for disruptions that slow a stop down without making it unusable.
     */
    fun getOptimizedPaths(
        originStopIds: List<Int>,
        destinationStopIds: List<Int>,
        departureTime: Int,
        maxRounds: Int = 5,
        allowedRouteIds: Set<Int>? = null,
        allowedRouteNames: Set<String>? = null,
        blockedRouteIds: Set<Int> = emptySet(),
        blockedRouteNames: Set<String> = emptySet(),
        blockedStopIds: Set<Int> = emptySet(),
        stopPenaltySeconds: Map<Int, Int> = emptyMap(),
        edgePenalties: List<EdgePenalty> = emptyList(),
        edgePenaltyProvider: EdgePenaltyProvider? = null,
        edgePenaltyMap: Map<Pair<Int, Int>, Int>? = null
    ): List<List<JourneyLeg>> {
        val network = getCurrentNetwork()
        val originIndices = network.mapStopIdsToIndices(originStopIds)
        val destinationIndices = network.mapStopIdsToIndices(destinationStopIds)

        if (originIndices.isEmpty() || destinationIndices.isEmpty()) {
            return emptyList()
        }

        val algorithm = algorithmCache.getOrPut(currentPeriodId) { RaptorAlgorithm(network, debug = false) }
        val routeFilter = buildRouteFilter(allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames)
        val stopFilter = network.buildStopFilter(blockedStopIds, stopPenaltySeconds)
        val edgeFilter = network.buildEdgeFilter(edgePenalties, edgePenaltyProvider, edgePenaltyMap)
        val bestArrivalAtAnyRound = algorithm.route(
            originIndices, destinationIndices, departureTime, routeFilter, maxRounds,
            stopFilter = stopFilter, edgeFilter = edgeFilter
        )

        if (bestArrivalAtAnyRound == Int.MAX_VALUE) {
            return emptyList()
        }

        val paretoJourneys = mutableListOf<List<JourneyLeg>>()
        var lastBestArrival = Int.MAX_VALUE

        for (k in 1..maxRounds) {
            // Find best destination with manual loop (avoids iterator + lambda allocation)
            var bestDestIndex = -1
            var bestTime = Int.MAX_VALUE
            for (idx in destinationIndices) {
                val t = algorithm.getArrivalTime(idx, k)
                if (t < bestTime) { bestTime = t; bestDestIndex = idx }
            }

            if (bestDestIndex != -1 && bestTime < lastBestArrival) {
                val journey = algorithm.getJourney(bestDestIndex, k)
                if (!journey.isNullOrEmpty()) {
                    paretoJourneys.add(journey)
                    lastBestArrival = bestTime
                }
            }
        }

        return paretoJourneys
    }

    /**
     * Searches for optimized paths between two locations — stop sets and/or arbitrary WGS84
     * points (e.g. geocoded addresses) — walking to/from nearby stops as needed.
     *
     * Walking competes inside the optimization: a journey ending with a longer egress walk can
     * beat one taking a later extra ride. A pure-walk journey is returned first when the two
     * locations are within [WalkingParams.maxDirectWalkDistanceMeters] of each other. When both
     * locations are [Location.StopIds] this delegates to the classic stop-id overload.
     *
     * Walk legs have [LegType.WALK_ACCESS]/[LegType.WALK_EGRESS]/[LegType.WALK_DIRECT], carry
     * the coordinates of both ends, and use stop index -1 for a coordinate endpoint.
     *
     * @param departureTime Departure time from [origin] in seconds from midnight
     * @param walking Walking speed/radius model used to resolve [Location.Point] endpoints
     * @param directWalkSecondsOverride Caller-provided duration for the pure-walk journey (e.g.
     *        from a street-network router). Replaces the great-circle duration estimate; the
     *        walk's eligibility (distance ≤ [WalkingParams.maxDirectWalkDistanceMeters]) and
     *        endpoints are still derived from coordinates.
     */
    fun getOptimizedPaths(
        origin: Location,
        destination: Location,
        departureTime: Int,
        maxRounds: Int = 5,
        walking: WalkingParams = WalkingParams.DEFAULT,
        allowedRouteIds: Set<Int>? = null,
        allowedRouteNames: Set<String>? = null,
        blockedRouteIds: Set<Int> = emptySet(),
        blockedRouteNames: Set<String> = emptySet(),
        blockedStopIds: Set<Int> = emptySet(),
        stopPenaltySeconds: Map<Int, Int> = emptyMap(),
        edgePenalties: List<EdgePenalty> = emptyList(),
        edgePenaltyProvider: EdgePenaltyProvider? = null,
        edgePenaltyMap: Map<Pair<Int, Int>, Int>? = null,
        directWalkSecondsOverride: Int? = null
    ): List<List<JourneyLeg>> {
        if (origin is Location.StopIds && destination is Location.StopIds) {
            return getOptimizedPaths(
                origin.ids, destination.ids, departureTime, maxRounds,
                allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames,
                blockedStopIds, stopPenaltySeconds,
                edgePenalties, edgePenaltyProvider, edgePenaltyMap
            )
        }

        val network = getCurrentNetwork()
        val o = resolveEndpoint(network, origin, walking)
        val d = resolveEndpoint(network, destination, walking)

        // Pure-walk candidate; its arrival is also the initial pruning bound for the search
        val directWalk = buildDirectWalkLeg(network, o, d, departureTime, walking, directWalkSecondsOverride)

        if (o.stopIndices.isEmpty() || d.stopIndices.isEmpty()) {
            return if (directWalk != null) listOf(listOf(directWalk)) else emptyList()
        }

        val algorithm = algorithmCache.getOrPut(currentPeriodId) { RaptorAlgorithm(network, debug = false) }
        val routeFilter = buildRouteFilter(allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames)
        val stopFilter = network.buildStopFilter(blockedStopIds, stopPenaltySeconds)
        val edgeFilter = network.buildEdgeFilter(edgePenalties, edgePenaltyProvider, edgePenaltyMap)
        val walkArrival = directWalk?.arrivalTime ?: Int.MAX_VALUE
        val best = algorithm.route(
            o.stopIndices, d.stopIndices, departureTime, routeFilter, maxRounds,
            accessSeconds = o.walkSeconds, egressSeconds = d.walkSeconds,
            initialBestArrival = walkArrival, stopFilter = stopFilter, edgeFilter = edgeFilter
        )

        val journeys = mutableListOf<List<JourneyLeg>>()
        if (directWalk != null) journeys.add(listOf(directWalk))
        if (best != Int.MAX_VALUE) {
            extractWalkingParetoJourneys(
                algorithm, network, o, d, maxRounds,
                initialBound = walkArrival, maxArrivalTime = Int.MAX_VALUE, journeys = journeys
            )
        }
        return journeys
    }

    /**
     * Endpoint resolved to concrete stop indices. [walkSeconds] is parallel to [stopIndices] and
     * null for stop-id endpoints (no access/egress walk); [lat]/[lon] are set for Point endpoints.
     */
    private class ResolvedEndpoint(
        val stopIndices: List<Int>,
        val walkSeconds: IntArray?,
        val lat: Double?,
        val lon: Double?
    ) {
        fun walkSecondsAt(stopIndex: Int): Int {
            val walks = walkSeconds ?: return 0
            for (i in stopIndices.indices) {
                if (stopIndices[i] == stopIndex) return walks[i]
            }
            return 0
        }
    }

    private fun resolveEndpoint(network: Network, location: Location, walking: WalkingParams): ResolvedEndpoint =
        when (location) {
            is Location.StopIds -> {
                // De-duplicated: route() requires unique indices when walk arrays are in play
                val seen = HashSet<Int>()
                val indices = ArrayList<Int>(location.ids.size)
                for (id in location.ids) {
                    val ix = network.getStopIndex(id)
                    if (ix != -1 && seen.add(ix)) indices.add(ix)
                }
                ResolvedEndpoint(indices, null, null, null)
            }
            is Location.Point -> {
                val nearby = network.findNearbyStops(location.lat, location.lon, walking.maxAccessEgressDistanceMeters)
                val indices = ArrayList<Int>(nearby.size)
                val walks = IntArray(nearby.size)
                for (i in nearby.indices) {
                    indices.add(nearby[i].stopIndex)
                    walks[i] = walking.walkSeconds(nearby[i].distanceMeters)
                }
                ResolvedEndpoint(indices, walks, location.lat, location.lon)
            }
            is Location.ResolvedPoint -> {
                // Caller-provided walk times; de-duplicated keeping the smallest walk per stop
                val positionByIndex = HashMap<Int, Int>(location.stops.size * 2)
                val indices = ArrayList<Int>(location.stops.size)
                val walks = ArrayList<Int>(location.stops.size)
                for (stopWalk in location.stops) {
                    val ix = network.getStopIndex(stopWalk.stopId)
                    if (ix == -1) continue
                    val pos = positionByIndex[ix]
                    if (pos == null) {
                        positionByIndex[ix] = indices.size
                        indices.add(ix)
                        walks.add(stopWalk.walkSeconds)
                    } else if (stopWalk.walkSeconds < walks[pos]) {
                        walks[pos] = stopWalk.walkSeconds
                    }
                }
                ResolvedEndpoint(indices, walks.toIntArray(), location.lat, location.lon)
            }
        }

    private class WalkCandidate(val stopIndex: Int, val lat: Double, val lon: Double)

    private fun walkCandidates(network: Network, endpoint: ResolvedEndpoint): List<WalkCandidate> =
        if (endpoint.lat != null && endpoint.lon != null) {
            listOf(WalkCandidate(-1, endpoint.lat, endpoint.lon))
        } else {
            endpoint.stopIndices.map { WalkCandidate(it, network.stops[it].lat, network.stops[it].lon) }
        }

    /**
     * Builds the pure-walk leg between the two locations when they are within walking range.
     * For stop-set endpoints the closest resolved stop is used as the walk end.
     * [overrideSeconds] replaces the great-circle duration estimate when provided.
     */
    private fun buildDirectWalkLeg(
        network: Network,
        origin: ResolvedEndpoint,
        destination: ResolvedEndpoint,
        departureTime: Int,
        walking: WalkingParams,
        overrideSeconds: Int? = null
    ): JourneyLeg? {
        val from = walkCandidates(network, origin)
        val to = walkCandidates(network, destination)

        var bestDist = Double.MAX_VALUE
        var bestFrom: WalkCandidate? = null
        var bestTo: WalkCandidate? = null
        for (f in from) {
            for (t in to) {
                val dist = Geo.distanceMeters(f.lat, f.lon, t.lat, t.lon)
                if (dist < bestDist) {
                    bestDist = dist; bestFrom = f; bestTo = t
                }
            }
        }
        if (bestFrom == null || bestTo == null || bestDist > walking.maxDirectWalkDistanceMeters) return null

        return JourneyLeg(
            fromStopIndex = bestFrom.stopIndex,
            toStopIndex = bestTo.stopIndex,
            departureTime = departureTime,
            arrivalTime = departureTime + (overrideSeconds ?: walking.walkSeconds(bestDist)),
            routeName = null,
            isTransfer = true,
            legType = LegType.WALK_DIRECT,
            fromLat = bestFrom.lat, fromLon = bestFrom.lon,
            toLat = bestTo.lat, toLon = bestTo.lon
        )
    }

    /**
     * Extracts Pareto-optimal journeys (egress-adjusted arrival vs number of rounds) from the
     * last forward run and wraps them with access/egress walk legs. Only journeys strictly
     * beating [initialBound] (e.g. the direct-walk arrival) and arriving by [maxArrivalTime]
     * are kept.
     */
    private fun extractWalkingParetoJourneys(
        algorithm: RaptorAlgorithm,
        network: Network,
        origin: ResolvedEndpoint,
        destination: ResolvedEndpoint,
        maxRounds: Int,
        initialBound: Int,
        maxArrivalTime: Int,
        journeys: MutableList<List<JourneyLeg>>
    ) {
        var lastBestArrival = initialBound
        for (k in 1..maxRounds) {
            var bestPos = -1
            var bestAdjusted = Int.MAX_VALUE
            for (i in destination.stopIndices.indices) {
                val t = algorithm.getArrivalTime(destination.stopIndices[i], k)
                if (t == Int.MAX_VALUE) continue
                val adjusted = t + (destination.walkSeconds?.get(i) ?: 0)
                if (adjusted < bestAdjusted) {
                    bestAdjusted = adjusted; bestPos = i
                }
            }
            if (bestPos == -1 || bestAdjusted >= lastBestArrival || bestAdjusted > maxArrivalTime) continue

            val destIndex = destination.stopIndices[bestPos]
            val transit = algorithm.getJourney(destIndex, k)
            if (transit.isNullOrEmpty()) continue

            val legs = ArrayList<JourneyLeg>(transit.size + 2)
            // Journeys always board at an origin stop (round-0 marks receive no transfers)
            val boardStop = transit.first().fromStopIndex
            val accessSecs = origin.walkSecondsAt(boardStop)
            if (accessSecs > 0) {
                val stop = network.stops[boardStop]
                // Anchored to the boarding: leave as late as possible and wait at the origin
                // rather than at the stop (a midnight query whose first bus runs at 6 am shows
                // a ~5:5x departure, not an absurd six-hour "journey" starting at midnight).
                // Always valid: the boarding departs at or after seed = query time + walk.
                val boardingDeparture = transit.first().departureTime
                legs.add(
                    JourneyLeg(
                        fromStopIndex = -1, toStopIndex = boardStop,
                        departureTime = boardingDeparture - accessSecs, arrivalTime = boardingDeparture,
                        routeName = null, isTransfer = true, legType = LegType.WALK_ACCESS,
                        fromLat = origin.lat, fromLon = origin.lon,
                        toLat = stop.lat, toLon = stop.lon
                    )
                )
            }
            legs.addAll(transit)
            val egressSecs = destination.walkSeconds?.get(bestPos) ?: 0
            if (egressSecs > 0) {
                val stop = network.stops[destIndex]
                val stopArrival = algorithm.getArrivalTime(destIndex, k)
                legs.add(
                    JourneyLeg(
                        fromStopIndex = destIndex, toStopIndex = -1,
                        departureTime = stopArrival, arrivalTime = stopArrival + egressSecs,
                        routeName = null, isTransfer = true, legType = LegType.WALK_EGRESS,
                        fromLat = stop.lat, fromLon = stop.lon,
                        toLat = destination.lat, toLon = destination.lon
                    )
                )
            }
            journeys.add(legs)
            lastBestArrival = bestAdjusted
        }
    }

    /**
     * Searches for optimized paths that arrive before a specified time.
     * A single backward RAPTOR pass finds the exact latest departure that still arrives on time,
     * then one forward run at that departure reconstructs the journeys.
     * Returns a list of journeys (Pareto-optimal with respect to the number of transfers).
     *
     * @param originStopIds List of origin stop identifiers
     * @param destinationStopIds List of destination stop identifiers
     * @param arrivalTime Desired arrival time in seconds from midnight
     * @param maxRounds Maximum number of transfers + 1
     * @param searchWindowMinutes How far back to search for departures (default: 120 minutes)
     */
    fun getOptimizedPathsArriveBy(
        originStopIds: List<Int>,
        destinationStopIds: List<Int>,
        arrivalTime: Int,
        maxRounds: Int = 5,
        searchWindowMinutes: Int = 120,
        allowedRouteIds: Set<Int>? = null,
        allowedRouteNames: Set<String>? = null,
        blockedRouteIds: Set<Int> = emptySet(),
        blockedRouteNames: Set<String> = emptySet(),
        blockedStopIds: Set<Int> = emptySet(),
        stopPenaltySeconds: Map<Int, Int> = emptyMap(),
        edgePenalties: List<EdgePenalty> = emptyList(),
        edgePenaltyProvider: EdgePenaltyProvider? = null,
        edgePenaltyMap: Map<Pair<Int, Int>, Int>? = null
    ): List<List<JourneyLeg>> {
        val network = getCurrentNetwork()
        val originIndices = network.mapStopIdsToIndices(originStopIds)
        val destinationIndices = network.mapStopIdsToIndices(destinationStopIds)

        if (originIndices.isEmpty() || destinationIndices.isEmpty()) {
            return emptyList()
        }

        val searchWindowSeconds = searchWindowMinutes * 60
        val earliestDeparture = maxOf(0, arrivalTime - searchWindowSeconds)
        val routeFilter = buildRouteFilter(allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames)
        val stopFilter = network.buildStopFilter(blockedStopIds, stopPenaltySeconds)
        val edgeFilter = network.buildEdgeFilter(edgePenalties, edgePenaltyProvider, edgePenaltyMap)
        val algorithm = algorithmCache.getOrPut(currentPeriodId) { RaptorAlgorithm(network, debug = false) }

        // Single backward pass: a fast HINT for the latest departure that still arrives on time.
        // The backward pass is admissible for almost every query, but on real data it can be off in
        // EITHER direction (a same-station implicit transfer can make a connection look catchable-or-
        // missable that the forward direction resolves the other way): overshoot (too late), under-
        // shoot (too early), or — in the extreme — a spurious Int.MIN_VALUE (misses the journey).
        val bestDeparture = algorithm.routeBackward(
            originIndices, destinationIndices, arrivalTime, earliestDeparture, routeFilter, maxRounds,
            stopFilter = stopFilter, edgeFilter = edgeFilter
        )

        // Fast path: trust the hint only when two forward runs confirm it is exactly the latest
        // feasible departure — on-time here, late one grid step later. (2 forward runs.)
        if (bestDeparture != Int.MIN_VALUE) {
            val laterIsLate = algorithm.route(
                originIndices, destinationIndices, bestDeparture + ARRIVE_BY_STEP_SECONDS, routeFilter, maxRounds,
                stopFilter = stopFilter, edgeFilter = edgeFilter
            ) > arrivalTime
            // This run leaves the forward state at bestDeparture, ready for extraction.
            val hintArrival = algorithm.route(
                originIndices, destinationIndices, bestDeparture, routeFilter, maxRounds,
                stopFilter = stopFilter, edgeFilter = edgeFilter
            )
            if (hintArrival <= arrivalTime && laterIsLate) {
                return extractParetoJourneys(algorithm, destinationIndices, maxRounds, arrivalTime)
            }
        }

        // Hint absent, too late, or too early → authoritative bounded forward search. This is the
        // ground truth (an exhaustive bisection over the window, cheap-rejecting infeasible queries),
        // so it is correct no matter how the backward hint erred — including a spurious MIN.
        val departure = latestFeasibleDeparture(
            algorithm, originIndices, destinationIndices, arrivalTime, earliestDeparture, routeFilter, maxRounds,
            stopFilter = stopFilter, edgeFilter = edgeFilter
        )
        if (departure == Int.MIN_VALUE) return emptyList()
        algorithm.route(
            originIndices, destinationIndices, departure, routeFilter, maxRounds,
            stopFilter = stopFilter, edgeFilter = edgeFilter
        )
        return extractParetoJourneys(algorithm, destinationIndices, maxRounds, arrivalTime)
    }

    /**
     * Latest 60s-grid departure in [[earliestDeparture], [arrivalTime]] whose forward run reaches a
     * destination by [arrivalTime] (arrival includes the egress walk when [egressSeconds] is given).
     * Feasibility is monotone in departure — an earlier departure never loses reachability — so a
     * binary search on the forward API finds the boundary in ~log2(window) runs. Mirrors the
     * historical bisection and is used only to correct a rare backward-pass overshoot.
     */
    private fun latestFeasibleDeparture(
        algorithm: RaptorAlgorithm,
        originIndices: List<Int>,
        destinationIndices: List<Int>,
        arrivalTime: Int,
        earliestDeparture: Int,
        routeFilter: io.raptor.core.RouteFilter?,
        maxRounds: Int,
        accessSeconds: IntArray? = null,
        egressSeconds: IntArray? = null,
        stopFilter: io.raptor.core.StopFilter? = null,
        edgeFilter: io.raptor.core.EdgeFilter? = null
    ): Int {
        // Monotone feasibility: the earliest departure has the most time, so if even it misses the
        // deadline nothing can — a one-run reject for genuinely infeasible queries.
        if (algorithm.route(
                originIndices, destinationIndices, earliestDeparture, routeFilter, maxRounds,
                accessSeconds, egressSeconds, stopFilter = stopFilter, edgeFilter = edgeFilter
            ) > arrivalTime
        ) return Int.MIN_VALUE

        var low = earliestDeparture
        var high = arrivalTime
        var best = Int.MIN_VALUE
        while (low <= high) {
            val mid = low + (high - low) / 2
            val arrival = algorithm.route(
                originIndices, destinationIndices, mid, routeFilter, maxRounds,
                accessSeconds, egressSeconds, stopFilter = stopFilter, edgeFilter = edgeFilter
            )
            if (arrival <= arrivalTime) {
                best = mid
                low = mid + ARRIVE_BY_STEP_SECONDS
            } else {
                high = mid - ARRIVE_BY_STEP_SECONDS
            }
        }
        return best
    }

    /**
     * Arrive-by counterpart of the location-based [getOptimizedPaths] overload: finds journeys
     * between two locations (stop sets and/or WGS84 points) arriving by [arrivalTime], walking
     * to/from nearby stops as needed.
     *
     * A pure-walk journey (departing as late as possible) is returned when the locations are
     * within [WalkingParams.maxDirectWalkDistanceMeters]; it is returned alone when no transit
     * journey departs later than it. When both locations are [Location.StopIds] this delegates
     * to the classic stop-id overload.
     *
     * @param arrivalTime Desired arrival time at [destination] in seconds from midnight
     */
    fun getOptimizedPathsArriveBy(
        origin: Location,
        destination: Location,
        arrivalTime: Int,
        maxRounds: Int = 5,
        searchWindowMinutes: Int = 120,
        walking: WalkingParams = WalkingParams.DEFAULT,
        allowedRouteIds: Set<Int>? = null,
        allowedRouteNames: Set<String>? = null,
        blockedRouteIds: Set<Int> = emptySet(),
        blockedRouteNames: Set<String> = emptySet(),
        blockedStopIds: Set<Int> = emptySet(),
        stopPenaltySeconds: Map<Int, Int> = emptyMap(),
        edgePenalties: List<EdgePenalty> = emptyList(),
        edgePenaltyProvider: EdgePenaltyProvider? = null,
        edgePenaltyMap: Map<Pair<Int, Int>, Int>? = null,
        directWalkSecondsOverride: Int? = null
    ): List<List<JourneyLeg>> {
        if (origin is Location.StopIds && destination is Location.StopIds) {
            return getOptimizedPathsArriveBy(
                origin.ids, destination.ids, arrivalTime, maxRounds, searchWindowMinutes,
                allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames,
                blockedStopIds, stopPenaltySeconds,
                edgePenalties, edgePenaltyProvider, edgePenaltyMap
            )
        }

        val network = getCurrentNetwork()
        val o = resolveEndpoint(network, origin, walking)
        val d = resolveEndpoint(network, destination, walking)

        // Pure-walk candidate, departing as late as possible: built at departure 0 (arrival is
        // then the duration) and shifted so it arrives exactly at arrivalTime.
        val directWalk = buildDirectWalkLeg(network, o, d, 0, walking, directWalkSecondsOverride)
            ?.let { it.copy(departureTime = arrivalTime - it.arrivalTime, arrivalTime = arrivalTime) }
        val walkDeparture = directWalk?.departureTime ?: Int.MIN_VALUE

        if (o.stopIndices.isEmpty() || d.stopIndices.isEmpty()) {
            return if (directWalk != null) listOf(listOf(directWalk)) else emptyList()
        }

        val searchWindowSeconds = searchWindowMinutes * 60
        val earliestDeparture = maxOf(0, arrivalTime - searchWindowSeconds)
        val routeFilter = buildRouteFilter(allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames)
        val stopFilter = network.buildStopFilter(blockedStopIds, stopPenaltySeconds)
        val edgeFilter = network.buildEdgeFilter(edgePenalties, edgePenaltyProvider, edgePenaltyMap)
        val algorithm = algorithmCache.getOrPut(currentPeriodId) { RaptorAlgorithm(network, debug = false) }

        // Single backward pass: a fast HINT for the latest coordinate departure still on time.
        val bestDeparture = algorithm.routeBackward(
            o.stopIndices, d.stopIndices, arrivalTime, earliestDeparture, routeFilter, maxRounds,
            accessSeconds = o.walkSeconds, egressSeconds = d.walkSeconds,
            initialBestDeparture = walkDeparture, stopFilter = stopFilter, edgeFilter = edgeFilter
        )

        val journeys = mutableListOf<List<JourneyLeg>>()
        if (directWalk != null) journeys.add(listOf(directWalk))

        // Same over/under/spurious-MIN handling as the stop-id overload. Walk arrays are carried
        // through every run so arrivals are egress-adjusted.
        var transitDeparture = Int.MIN_VALUE
        // Fast path: a hint that strictly beats the pure walk, verified by two forward runs.
        if (bestDeparture != Int.MIN_VALUE && bestDeparture > walkDeparture) {
            val laterIsLate = algorithm.route(
                o.stopIndices, d.stopIndices, bestDeparture + ARRIVE_BY_STEP_SECONDS, routeFilter, maxRounds,
                accessSeconds = o.walkSeconds, egressSeconds = d.walkSeconds,
                stopFilter = stopFilter, edgeFilter = edgeFilter
            ) > arrivalTime
            // Leaves the forward state at bestDeparture, ready for extraction on the fast path.
            val hintArrival = algorithm.route(
                o.stopIndices, d.stopIndices, bestDeparture, routeFilter, maxRounds,
                accessSeconds = o.walkSeconds, egressSeconds = d.walkSeconds,
                stopFilter = stopFilter, edgeFilter = edgeFilter
            )
            if (hintArrival <= arrivalTime && laterIsLate) transitDeparture = bestDeparture
        }
        if (transitDeparture == Int.MIN_VALUE) {
            // Authoritative bounded search — correct no matter how the hint erred (too late, too
            // early, or a spurious MIN / one that failed to beat the walk).
            val found = latestFeasibleDeparture(
                algorithm, o.stopIndices, d.stopIndices, arrivalTime, earliestDeparture, routeFilter, maxRounds,
                accessSeconds = o.walkSeconds, egressSeconds = d.walkSeconds,
                stopFilter = stopFilter, edgeFilter = edgeFilter
            )
            if (found != Int.MIN_VALUE) {
                algorithm.route(
                    o.stopIndices, d.stopIndices, found, routeFilter, maxRounds,
                    accessSeconds = o.walkSeconds, egressSeconds = d.walkSeconds,
                    stopFilter = stopFilter, edgeFilter = edgeFilter
                )
                transitDeparture = found
            }
        }
        // A transit journey is worth returning only when it departs later than the pure walk.
        if (transitDeparture != Int.MIN_VALUE && transitDeparture > walkDeparture) {
            extractWalkingParetoJourneys(
                algorithm, network, o, d, maxRounds,
                initialBound = Int.MAX_VALUE, maxArrivalTime = arrivalTime, journeys = journeys
            )
        }
        return journeys
    }

    /**
     * Helper function to extract Pareto-optimal journeys that arrive before a given time.
     */
    private fun extractParetoJourneys(
        algorithm: RaptorAlgorithm,
        destinationIndices: List<Int>,
        maxRounds: Int,
        maxArrivalTime: Int
    ): List<List<JourneyLeg>> {
        val paretoJourneys = mutableListOf<List<JourneyLeg>>()
        var lastBestArrival = Int.MAX_VALUE

        for (k in 1..maxRounds) {
            var bestDestIndex = -1
            var bestTime = Int.MAX_VALUE
            for (idx in destinationIndices) {
                val t = algorithm.getArrivalTime(idx, k)
                if (t < bestTime) { bestTime = t; bestDestIndex = idx }
            }

            if (bestDestIndex != -1 && bestTime <= maxArrivalTime && bestTime < lastBestArrival) {
                val journey = algorithm.getJourney(bestDestIndex, k)
                if (!journey.isNullOrEmpty()) {
                    paretoJourneys.add(journey)
                    lastBestArrival = bestTime
                }
            }
        }

        return paretoJourneys
    }

    /**
     * Maps stop IDs to internal indices in a single pass (drops unknown ids), allocating one list
     * instead of the two produced by map { }.filter { } plus its lambdas.
     */
    private fun Network.mapStopIdsToIndices(ids: List<Int>): List<Int> {
        val out = ArrayList<Int>(ids.size)
        for (i in ids.indices) {
            val ix = getStopIndex(ids[i])
            if (ix != -1) out.add(ix)
        }
        return out
    }

    /**
     * Translates a caller's stop *ids* into the index space the algorithm works in.
     *
     * Unknown ids are dropped rather than rejected: a live disruption feed names stops the loaded
     * timetable may not have (a stop removed in a newer dataset, or one belonging to another
     * period), and losing one penalty is a far better outcome than failing the whole query.
     */
    private fun Network.buildStopFilter(
        blockedStopIds: Set<Int>,
        stopPenaltySeconds: Map<Int, Int>
    ): io.raptor.core.StopFilter? {
        if (blockedStopIds.isEmpty() && stopPenaltySeconds.isEmpty()) return null

        val blockedIndices = HashSet<Int>(blockedStopIds.size)
        for (id in blockedStopIds) {
            val ix = getStopIndex(id)
            if (ix != -1) blockedIndices.add(ix)
        }

        val penalties = HashMap<Int, Int>(stopPenaltySeconds.size)
        for ((id, seconds) in stopPenaltySeconds) {
            if (seconds <= 0) continue
            val ix = getStopIndex(id)
            // A blocked stop is already unreachable; a penalty on top would be dead arithmetic.
            if (ix != -1 && ix !in blockedIndices) penalties[ix] = seconds
        }

        if (blockedIndices.isEmpty() && penalties.isEmpty()) return null
        return io.raptor.core.StopFilter(blockedIndices, penalties)
    }

    private fun buildRouteFilter(
        allowedRouteIds: Set<Int>?,
        allowedRouteNames: Set<String>?,
        blockedRouteIds: Set<Int>,
        blockedRouteNames: Set<String>
    ): io.raptor.core.RouteFilter? {
        if (allowedRouteIds == null &&
            allowedRouteNames == null &&
            blockedRouteIds.isEmpty() &&
            blockedRouteNames.isEmpty()
        ) {
            return null
        }
        return io.raptor.core.RouteFilter(
            allowedRouteIds = allowedRouteIds,
            allowedRouteNames = allowedRouteNames,
            blockedRouteIds = blockedRouteIds,
            blockedRouteNames = blockedRouteNames
        )
    }

    /**
     * Searches for stops by their name in the current period.
     */
    fun searchStopsByName(name: String): List<Stop> {
        val network = getCurrentNetwork()
        return network.stops.filter { it.name.contains(name, ignoreCase = true) }
    }

    /**
     * Searches and displays optimized routes between two stops (by name).
     */
    fun searchAndDisplayRoute(
        originName: String,
        destinationName: String,
        departureTime: Int,
        showIntermediateStops: Boolean = false,
        allowedRouteIds: Set<Int>? = null,
        allowedRouteNames: Set<String>? = null,
        blockedRouteIds: Set<Int> = emptySet(),
        blockedRouteNames: Set<String> = emptySet()
    ) {
        val originStops = searchStopsByName(originName)
        val destinationStops = searchStopsByName(destinationName)

        if (originStops.isEmpty()) {
            println("Origin stop not found: $originName")
        }
        if (destinationStops.isEmpty()) {
            println("Destination stop not found: $destinationName")
        }

        if (originStops.isNotEmpty() && destinationStops.isNotEmpty()) {
            val originIds = originStops.map { it.id }
            val destinationIds = destinationStops.map { it.id }

            val paretoJourneys = getOptimizedPaths(
                originStopIds = originIds,
                destinationStopIds = destinationIds,
                departureTime = departureTime,
                allowedRouteIds = allowedRouteIds,
                allowedRouteNames = allowedRouteNames,
                blockedRouteIds = blockedRouteIds,
                blockedRouteNames = blockedRouteNames
            )

            if (paretoJourneys.isEmpty()) {
                println("\nNo route found at ${formatTime(departureTime)}.")
            } else {
                println("\n=== ROUTES FOUND (Pareto Optimal) ===")
                for ((idx, journey) in paretoJourneys.withIndex()) {
                    val arrival = journey.last().arrivalTime
                    val transfers = journey.count { !it.isTransfer } - 1
                    println("\nOption ${idx + 1}: Arrival at ${formatTime(arrival)} | $transfers transfers")
                    displayJourney(journey, showIntermediateStops)
                }
            }
        }
    }

    /**
     * Displays a journey in a readable format.
     */
    fun displayJourney(journey: List<JourneyLeg>, showIntermediateStops: Boolean = false) {
        val network = getCurrentNetwork()
        val stops = network.stops
        for ((index, leg) in journey.withIndex()) {
            // Stop index -1 = arbitrary coordinate endpoint of a walk leg
            val fromName = if (leg.fromStopIndex >= 0) stops[leg.fromStopIndex].name
                else "(${leg.fromLat}, ${leg.fromLon})"
            val toName = if (leg.toStopIndex >= 0) stops[leg.toStopIndex].name
                else "(${leg.toLat}, ${leg.toLon})"
            val depTime = formatTime(leg.departureTime)
            val arrTime = formatTime(leg.arrivalTime)

            if (leg.isTransfer) {
                val label = if (leg.legType == LegType.TRANSFER) "Transfer" else "Walk"
                println("${index + 1}. 🚶 $label: $fromName → $toName")
                println("   Departure: $depTime | Arrival: $arrTime (${(leg.arrivalTime - leg.departureTime) / 60} min)")
            } else {
                val directionInfo = if (leg.direction != null) " to ${leg.direction}" else ""
                println("${index + 1}. 🚍 Line ${leg.routeName}$directionInfo: $fromName → $toName")
                if (showIntermediateStops && leg.intermediateStopIndices.isNotEmpty()) {
                    println("   Departure: $depTime from $fromName")
                    for (i in leg.intermediateStopIndices.indices) {
                        val intermediateStop = stops[leg.intermediateStopIndices[i]]
                        val intermediateTime = formatTime(leg.intermediateArrivalTimes[i])
                        println("     - $intermediateTime: ${intermediateStop.name}")
                    }
                    println("   Arrival: $arrTime at $toName (${(leg.arrivalTime - leg.departureTime) / 60} min)")
                } else {
                    println("   Departure: $depTime | Arrival: $arrTime (${(leg.arrivalTime - leg.departureTime) / 60} min)")
                }
            }
        }
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        fun p(v: Int): String = v.toString().padStart(2, '0')
        return if (h >= 24) "${p(h - 24)}:${p(m)}:${p(s)}(+1)"
        else "${p(h)}:${p(m)}:${p(s)}"
    }

    private companion object {
        // Grid step for arrive-by departure search — matches the trip-time granularity (seconds
        // are whole minutes) and the historical bisection reference.
        private const val ARRIVE_BY_STEP_SECONDS = 60
    }
}
