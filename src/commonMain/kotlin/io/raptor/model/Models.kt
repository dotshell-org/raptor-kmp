package io.raptor.model

/**
 * Represents a transit stop.
 */
data class Stop(
    val id: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val routeIds: IntArray,       // IDs of routes serving this stop
    val transfers: List<Transfer>, // List of possible walking transfers
    val zone: String? = null       // Fare zone (RST3+); null for older formats or unzoned stops
)

/**
 * Represents a walking transfer between two stops.
 */
data class Transfer(
    val targetStopId: Int, // Destination stop ID
    val walkTime: Int      // Walking time in seconds
)

/** Shared by every stop with no route, instead of one empty array each. */
internal val EMPTY_INTS = IntArray(0)

/**
 * The transfers of one stop as [NetworkLoader] reads them: a window of `size` (target id, walk
 * time) pairs in one array shared by every stop of the file, starting at int [start]. A
 * [Transfer] is made only when an element is asked for, so a loaded network holds two ints per
 * transfer instead of an object each (plus a list per stop), and [Network] can route on the
 * shared array as it is.
 */
internal class FlatTransferList(
    internal val pairs: IntArray,
    internal val start: Int,
    override val size: Int
) : AbstractList<Transfer>() {
    override fun get(index: Int): Transfer {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index $index, size $size")
        val at = start + 2 * index
        return Transfer(pairs[at], pairs[at + 1])
    }
}

/**
 * Represents a transit route (a line with a specific sequence of stops).
 * (`@JvmField` was removed for Kotlin Multiplatform — it is JVM-only and not available in
 * commonMain; the JIT inlines the generated accessors on the hot path anyway.)
 */
class Route(
    val id: Int,
    val name: String,
    val stopIds: IntArray,         // Ordered list of stop IDs for this route
    val tripCount: Int,            // Number of trips
    val stopCountInRoute: Int,     // Number of stops in this route
    val flatStopTimes: IntArray,   // Row-major: [trip0_stop0, trip0_stop1, ..., trip1_stop0, ...] sorted by first stop time
    val tripIds: IntArray,         // Trip IDs in sorted order (parallel to flatStopTimes rows)
    val hasOvernightTrips: Boolean // true if any trip has a time decrease (midnight wrap)
)