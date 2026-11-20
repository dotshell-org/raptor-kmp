package io.raptor.core

import io.raptor.model.Network

/**
 * Kind of journey leg. TRANSIT and TRANSFER cover classic stop-to-stop journeys; the WALK_*
 * kinds are produced by coordinate-based queries (walk from/to an arbitrary point).
 */
enum class LegType {
    TRANSIT,
    TRANSFER,
    WALK_ACCESS,
    WALK_EGRESS,
    WALK_DIRECT
}

data class JourneyLeg(
    val fromStopIndex: Int,
    val toStopIndex: Int,
    val departureTime: Int,
    val arrivalTime: Int,
    val routeName: String?,
    val isTransfer: Boolean,
    val intermediateStopIndices: List<Int> = emptyList(),
    val intermediateArrivalTimes: List<Int> = emptyList(),
    val direction: String? = null,
    // A -1 stop index means "arbitrary coordinate endpoint"; walk legs carry the coordinates of
    // both ends so consumers never need to resolve a -1 index against the stop list.
    val legType: LegType = if (isTransfer) LegType.TRANSFER else LegType.TRANSIT,
    val fromLat: Double? = null,
    val fromLon: Double? = null,
    val toLat: Double? = null,
    val toLon: Double? = null
)

/**
 * Tracks the state of a single routing request across rounds.
 */
class RaptorState(val network: Network, val maxRounds: Int) {
    val stopCount: Int = network.stopCount

    // Flat bestArrival: bestArrival[round * stopCount + stopIndex]
    // Int.MAX_VALUE represents infinity (unreachable)
    val bestArrival: IntArray = IntArray((maxRounds + 1) * stopCount).also { it.fill(Int.MAX_VALUE) }

    // Parent tracking: flat interleaved IntArray
    // Layout: parentData[(round * stopCount + stopIndex) * 4 + field], 4 ints per entry:
    //   P_STOP   the stop the leg comes from (-1: no parent)
    //   P_ROUTE  internal route index of a ride, -1 for a walk
    //   P_DATA   a ride's trip index in the route; a walk's departure time
    //   P_POS    a ride's boarding and alighting positions in the route, 16 bits each
    // What 2.3 stored besides is derived: the parent round (round - 1 after a ride, the same
    // round after a walk) and a ride's departure (its trip's time at the boarding position).
    val parentData: IntArray = IntArray((maxRounds + 1) * stopCount * PARENT_STRIDE).also { it.fill(-1) }

    companion object {
        const val P_STOP = 0
        const val P_ROUTE = 1
        const val P_DATA = 2
        const val P_POS = 3
        const val PARENT_STRIDE = 4
        private const val SAVED_STRIDE = 7
        /** A journey holds at most a ride and a walk per round: well past that, a rebuild has gone round in circles. */
        private const val WALK_STEPS_GUARD = 2
    }

    // Boolean arrays for O(1) mark checks (var: swapped in clearMarks to avoid O(stopCount) copies)
    private var markedStops = BooleanArray(network.stopCount)
    private var markedStopsPrevious = BooleanArray(network.stopCount)

    // Incremental tracking of marked stop indices (IntArray-backed, no boxing)
    private var markedArray = IntArray(256)
    private var markedSize = 0
    private var markedArrayPrev = IntArray(256)
    private var markedSizePrev = 0

    // Track max round used for lazy reset of parentData
    private var lastMaxRound = maxRounds // first reset fills everything
    // Track max round written in bestArrival (separate from parentData because
    // copyArrivalTimesToNextRound writes bestArrival without calling setParent)
    private var lastBaRound = maxRounds

    fun reset() {
        // Only reset bestArrival for rounds actually used in previous query
        val baResetEnd = (lastBaRound + 1) * stopCount
        bestArrival.fill(Int.MAX_VALUE, 0, baResetEnd)
        lastBaRound = 0
        // Only reset parent data for rounds actually used in previous query
        val parentResetEnd = (lastMaxRound + 1) * stopCount * PARENT_STRIDE
        parentData.fill(-1, 0, parentResetEnd)
        lastMaxRound = 0
        markedStops.fill(false)
        markedStopsPrevious.fill(false)
        markedSize = 0
        markedSizePrev = 0
        savedCount = 0
        bestRide.fill(Int.MAX_VALUE)
    }

    fun markStop(stopIndex: Int) {
        if (!markedStops[stopIndex]) {
            markedStops[stopIndex] = true
            if (markedSize == markedArray.size) {
                markedArray = markedArray.copyOf(markedArray.size * 2)
            }
            markedArray[markedSize++] = stopIndex
        }
    }

    fun isMarkedInPreviousRound(stopIndex: Int): Boolean = markedStopsPrevious[stopIndex]

    fun clearMarks() {
        // Move current marks to previous and empty current, in O(marked) instead of 2×O(stopCount).
        // markedStopsPrevious still holds last round's marks: clear only its set entries (listed in
        // markedArrayPrev), then swap so it becomes the fresh empty "current" and the old "current"
        // boolean array becomes "previous".
        for (i in 0 until markedSizePrev) {
            markedStopsPrevious[markedArrayPrev[i]] = false
        }
        val tmpBool = markedStopsPrevious; markedStopsPrevious = markedStops; markedStops = tmpBool

        // Swap the index arrays in the same way
        val tmpArr = markedArrayPrev; markedArrayPrev = markedArray; markedArray = tmpArr
        markedSizePrev = markedSize
        markedSize = 0
    }

    fun getMarkedCount(): Int = markedSize
    fun getMarkedAt(i: Int): Int = markedArray[i]

    fun getMarkedPrevArray(): IntArray = markedArrayPrev
    fun getMarkedPrevSize(): Int = markedSizePrev

    /**
     * Propagates best arrival times from round k-1 to round k.
     */
    fun copyArrivalTimesToNextRound(round: Int) {
        if (round !in 1..maxRounds) return
        bestArrival.copyInto(
            destination = bestArrival,
            destinationOffset = round * stopCount,
            startIndex = (round - 1) * stopCount,
            endIndex = (round - 1) * stopCount + stopCount
        )
        if (round > lastBaRound) lastBaRound = round
    }

    fun getBestArrival(stopIndex: Int): Int {
        var minArrival = Int.MAX_VALUE
        for (k in 0..maxRounds) {
            val v = bestArrival[k * stopCount + stopIndex]
            if (v < minArrival) {
                minArrival = v
            }
        }
        return minArrival
    }

    fun getBestRound(stopIndex: Int): Int {
        var minArrival = Int.MAX_VALUE
        var bestRound = -1
        for (k in 0..maxRounds) {
            val v = bestArrival[k * stopCount + stopIndex]
            if (v < minArrival) {
                minArrival = v
                bestRound = k
            }
        }
        return bestRound
    }

    /**
     * Sets parent info for a stop in a given round (interleaved flat array write). The
     * signature is that of 2.3; [pRound] and a ride's [depTime] are not stored but derived
     * (see the layout above), so the callers stay as they were.
     */
    fun setParent(
        round: Int, stopIndex: Int,
        pStopIndex: Int, pRound: Int, routeIdx: Int,
        depTime: Int, tripIdx: Int, boardingPos: Int, alightingPos: Int
    ) {
        if (round > lastMaxRound) lastMaxRound = round
        val base = (round * stopCount + stopIndex) * PARENT_STRIDE
        parentData[base + P_STOP] = pStopIndex
        parentData[base + P_ROUTE] = routeIdx
        if (routeIdx == -1) {
            parentData[base + P_DATA] = depTime
            parentData[base + P_POS] = -1
        } else {
            parentData[base + P_DATA] = tripIdx
            parentData[base + P_POS] = (boardingPos shl 16) or (alightingPos and 0xFFFF)
        }
    }

    /** Per stop, the earliest arrival by a ride in any round so far: where walks leave from. */
    val bestRide: IntArray = IntArray(stopCount).also { it.fill(Int.MAX_VALUE) }

    // Rides walks may leave from that are not (or no longer) a stop's label: rides later than its
    // label, and ride labels a walk of the same round replaced. Entries of SAVED_STRIDE ints
    // (round, stop, the 4 parent fields, the arrival); a walk's departure time tells which ride it
    // left from.
    private var savedRides = IntArray(SAVED_STRIDE * 16)
    private var savedCount = 0

    private fun saveEntry(round: Int, stopIndex: Int, pStop: Int, pRoute: Int, pData: Int, pPos: Int, arrival: Int) {
        if (savedCount * SAVED_STRIDE == savedRides.size) savedRides = savedRides.copyOf(savedRides.size * 2)
        val s = savedCount++ * SAVED_STRIDE
        savedRides[s] = round; savedRides[s + 1] = stopIndex
        savedRides[s + 2] = pStop; savedRides[s + 3] = pRoute; savedRides[s + 4] = pData; savedRides[s + 5] = pPos
        savedRides[s + 6] = arrival
    }

    /** The stop the walk labelling [stopIndex] in [round] comes from, or -1 when that label is no walk of that round. */
    fun walkSourceAt(round: Int, stopIndex: Int): Int {
        val base = (round * stopCount + stopIndex) * PARENT_STRIDE
        return if (parentData[base + P_ROUTE] == -1) parentData[base + P_STOP] else -1
    }

    /** A ride into [stopIndex] at [arrival] that improves its best ride but not its label. */
    fun saveRide(round: Int, stopIndex: Int, boardingStop: Int, routeIdx: Int, tripIdx: Int, boardingPos: Int,
                 alightingPos: Int, arrival: Int) {
        if (round > lastMaxRound) lastMaxRound = round
        saveEntry(round, stopIndex, boardingStop, routeIdx, tripIdx, (boardingPos shl 16) or (alightingPos and 0xFFFF), arrival)
    }

    /**
     * Labels [stopIndex] in [round] with a walk from [fromStop], left at [departure] and arriving
     * at [arrival]. A ride label of this round it replaces is kept aside, see [savedRides].
     */
    fun setWalk(round: Int, stopIndex: Int, fromStop: Int, departure: Int, arrival: Int) {
        val base = (round * stopCount + stopIndex) * PARENT_STRIDE
        if (parentData[base + P_STOP] != -1 && parentData[base + P_ROUTE] != -1) {
            saveEntry(round, stopIndex, parentData[base + P_STOP], parentData[base + P_ROUTE], parentData[base + P_DATA],
                parentData[base + P_POS], bestArrival[round * stopCount + stopIndex])
        }
        bestArrival[round * stopCount + stopIndex] = arrival
        setParent(round, stopIndex, fromStop, round, -1, departure, -1, -1, -1)
    }

    private fun savedLabelAt(round: Int, stopIndex: Int, arrival: Int): Int {
        for (i in savedCount - 1 downTo 0) {
            val s = i * SAVED_STRIDE
            if (savedRides[s] == round && savedRides[s + 1] == stopIndex && savedRides[s + 6] == arrival) return s
        }
        return -1
    }

    fun reconstructJourney(destinationIndex: Int, round: Int? = null): List<JourneyLeg> {
        val legs = mutableListOf<JourneyLeg>()
        var currentRound = round ?: getBestRound(destinationIndex)
        var currentStop = destinationIndex

        if (currentRound == -1 || bestArrival[currentRound * stopCount + destinationIndex] == Int.MAX_VALUE) {
            return emptyList()
        }

        // When the leg found last is a walk, the time it left this stop: the label it left from.
        var leftAt = -1
        var steps = 0
        // Reconstruct backwards from destination
        while (currentRound > 0 && currentStop >= 0) {
            var data = parentData
            var base = (currentRound * stopCount + currentStop) * PARENT_STRIDE
            var arrival = bestArrival[currentRound * stopCount + currentStop]
            // Walks leave from rides. A ride later than the stop's label, or a ride label a walk
            // replaced since, is kept aside, found by the time the walk left. (Preferred even when
            // the label has that same time: a ride never leads back into the same round's walks.)
            if (leftAt != -1) {
                val s = savedLabelAt(currentRound, currentStop, leftAt)
                if (s != -1) { data = savedRides; base = s + 2; arrival = leftAt }
            }
            if (++steps > 4 * (maxRounds + 1) * (WALK_STEPS_GUARD)) break
            val pStop = data[base + P_STOP]
            if (pStop == -1) break

            val pRouteInternalIdx = data[base + P_ROUTE]
            val ride = pRouteInternalIdx != -1
            val pRound = if (ride) currentRound - 1 else currentRound
            val pTripIdx = if (ride) data[base + P_DATA] else -1
            val positions = data[base + P_POS]
            val pBoardingPos = if (ride) positions ushr 16 else -1
            val pAlightingPos = if (ride) positions and 0xFFFF else -1
            val pDepTime = if (ride) {
                val route = network.routeList[pRouteInternalIdx]
                route.flatStopTimes[pTripIdx * route.stopCountInRoute + pBoardingPos]
            } else data[base + P_DATA]

            val intermediateStopIndices = mutableListOf<Int>()
            val intermediateArrivalTimes = mutableListOf<Int>()

            var routeName: String? = null
            var direction: String? = null

            if (pRouteInternalIdx != -1) {
                val route = network.routeList[pRouteInternalIdx]
                routeName = route.name
                val flat = route.flatStopTimes
                val stride = route.stopCountInRoute

                // Direction: last stop of the route
                val lastStopId = route.stopIds.last()
                val lastStopIndex = network.getStopIndex(lastStopId)
                if (lastStopIndex != -1) {
                    direction = network.stops[lastStopIndex].name
                }

                // Intermediate stops between boarding and alighting
                val tripOffset = pTripIdx * stride
                for (i in pBoardingPos + 1 until pAlightingPos) {
                    val stopId = route.stopIds[i]
                    intermediateStopIndices.add(network.getStopIndex(stopId))
                    intermediateArrivalTimes.add(flat[tripOffset + i])
                }
            }

            legs.add(
                JourneyLeg(
                    fromStopIndex = pStop,
                    toStopIndex = currentStop,
                    departureTime = pDepTime,
                    arrivalTime = arrival,
                    routeName = routeName,
                    isTransfer = routeName == null,
                    intermediateStopIndices = intermediateStopIndices,
                    intermediateArrivalTimes = intermediateArrivalTimes,
                    direction = direction
                )
            )

            // Move to the parent stop and round
            currentStop = pStop
            currentRound = pRound
            leftAt = if (ride) -1 else pDepTime
        }

        return legs.reversed()
    }
}

/**
 * Lean state for the backward (arrive-by) search: latest-departure labels and per-round marks.
 *
 * Mirror of RaptorState with max/Int.MIN_VALUE semantics and NO parent tracking — the backward
 * pass only needs the optimal departure time; journeys are then reconstructed by a forward run.
 *
 * Deliberately a separate class rather than a parametrized RaptorState: sharing one label array
 * between min- and max-relaxation queries would let stale sentinels of the other polarity leak
 * into lazily-reset regions (MAX_VALUE is neutral for min but poison for max, and vice versa),
 * and keeping RaptorState untouched leaves the forward hot path byte-identical.
 */
class BackwardRaptorState(network: Network, val maxRounds: Int) {
    val stopCount: Int = network.stopCount

    // Flat labels: latestDeparture[round * stopCount + stopIndex]
    // Int.MIN_VALUE represents "cannot reach a destination in time from here"
    val latestDeparture: IntArray = IntArray((maxRounds + 1) * stopCount).also { it.fill(Int.MIN_VALUE) }

    /** Per stop, the latest departure by a ride in any round so far: what walks into it aim at. */
    val latestRide: IntArray = IntArray(stopCount).also { it.fill(Int.MIN_VALUE) }

    private var markedStops = BooleanArray(stopCount)
    private var markedStopsPrevious = BooleanArray(stopCount)
    private var markedArray = IntArray(256)
    private var markedSize = 0
    private var markedArrayPrev = IntArray(256)
    private var markedSizePrev = 0

    // Highest round written, for lazy label reset (same mechanism as RaptorState.lastBaRound)
    private var lastRound = maxRounds // first reset clears everything

    fun reset() {
        latestDeparture.fill(Int.MIN_VALUE, 0, (lastRound + 1) * stopCount)
        latestRide.fill(Int.MIN_VALUE)
        lastRound = 0
        // Set bits are exactly the entries listed in the mark arrays: clear incrementally
        for (i in 0 until markedSize) markedStops[markedArray[i]] = false
        for (i in 0 until markedSizePrev) markedStopsPrevious[markedArrayPrev[i]] = false
        markedSize = 0
        markedSizePrev = 0
    }

    fun markStop(stopIndex: Int) {
        if (!markedStops[stopIndex]) {
            markedStops[stopIndex] = true
            if (markedSize == markedArray.size) {
                markedArray = markedArray.copyOf(markedArray.size * 2)
            }
            markedArray[markedSize++] = stopIndex
        }
    }

    fun isMarkedInPreviousRound(stopIndex: Int): Boolean = markedStopsPrevious[stopIndex]

    fun clearMarks() {
        // O(marked) move of current marks to previous, mirroring RaptorState.clearMarks
        for (i in 0 until markedSizePrev) {
            markedStopsPrevious[markedArrayPrev[i]] = false
        }
        val tmpBool = markedStopsPrevious; markedStopsPrevious = markedStops; markedStops = tmpBool
        val tmpArr = markedArrayPrev; markedArrayPrev = markedArray; markedArray = tmpArr
        markedSizePrev = markedSize
        markedSize = 0
    }

    fun getMarkedCount(): Int = markedSize
    fun getMarkedAt(i: Int): Int = markedArray[i]
    fun getMarkedPrevArray(): IntArray = markedArrayPrev
    fun getMarkedPrevSize(): Int = markedSizePrev

    /**
     * Propagates labels from round k-1 to round k (round k starts as a copy of k-1,
     * so labels are monotone non-decreasing across rounds).
     */
    fun copyToNextRound(round: Int) {
        if (round !in 1..maxRounds) return
        latestDeparture.copyInto(
            destination = latestDeparture,
            destinationOffset = round * stopCount,
            startIndex = (round - 1) * stopCount,
            endIndex = round * stopCount
        )
        if (round > lastRound) lastRound = round
    }
}
