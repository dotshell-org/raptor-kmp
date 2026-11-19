package io.raptor.model

import io.raptor.geo.Geo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

// Slightly under the true ~111,195 m per degree of latitude so the bounding-box prefilter
// over-covers; the exact haversine check filters afterwards.
private const val METERS_PER_DEGREE_LAT = 111000.0

// Side of a spatial-grid cell. Chosen around the default access/egress radius so a typical query
// touches ~3×3 cells; a larger query radius simply touches more cells (still correct, still bounded).
private const val CELL_SIZE_METERS = 500.0

/**
 * A stop within walking range of a query point.
 */
class NearbyStop(val stopIndex: Int, val distanceMeters: Double)

/**
 * Compressed adjacency (CSR): the values of row `i` are `data[start[i] until start[i + 1]]`.
 * One array of offsets and one of values for the whole network, instead of one array per row:
 * no object header per stop, and nothing allocated for an empty row.
 */
internal class Adjacency(val start: IntArray, val data: IntArray) {
    fun row(i: Int): IntArray = data.copyOfRange(start[i], start[i + 1])
}

/**
 * A container holding all network data for the router.
 *
 * Memory layout (2.4.0): the search reads flat arrays only. Transfers, routes per stop and
 * same-name groups are each one [Adjacency]; the per-stop arrays of earlier versions
 * ([transferData], [reverseTransferData], [routeIndicesForStop], [implicitTransferData],
 * [stopsByName]) are still there for callers, built on first access from the flat arrays, so a
 * network that is only routed on never holds them. When stop ids are their own positions (what
 * raptor-gtfs-pipeline writes), there is no id -> index map at all, and the transfers read from
 * a stops.bin are routed on in the very array [io.raptor.data.NetworkLoader] filled.
 */
class Network(
    val stops: List<Stop>,
    routes: List<Route>
) {
    // Total number of stops in the network
    val stopCount: Int = stops.size

    // Stop ids are positions (0..n-1) in pipeline output: then an id is its own index.
    private val identityIds: Boolean = run {
        for (i in stops.indices) if (stops[i].id != i) return@run false
        true
    }

    // Otherwise: stopId -> index in stops list (O(1) lookup)
    private val stopIdToIndex: HashMap<Int, Int>? = if (identityIds) null else
        HashMap<Int, Int>(stops.size * 2).also { map ->
            for (i in stops.indices) {
                map[stops[i].id] = i
            }
        }

    fun getStopIndex(id: Int): Int =
        if (identityIds) (if (id in 0 until stopCount) id else -1) else stopIdToIndex!![id] ?: -1

    // Routes indexed by internal position (0..routeCount-1)
    val routeList: Array<Route> = routes.toTypedArray()
    val routeCount: Int = routeList.size

    init {
        // The search state packs boarding and alighting positions in 16 bits each.
        for (r in routeList) require(r.stopCountInRoute < 65536) { "route ${r.id} has ${r.stopCountInRoute} stops (max 65535)" }
    }

    // routeId -> internal indices in routeList (multiple routes can share the same id, e.g.
    // directions). Built on first use: only edge penalties need it after construction.
    internal val routeInternalIndices: HashMap<Int, IntArray> by lazy { buildRouteInternalIndices(routeList) }

    // Pre-computed: routeStopIndices[routeInternalIdx][posInRoute] = global stopIndex. With ids
    // that are indices, the route's own stopIds array already is that row: it is shared.
    val routeStopIndices: Array<IntArray> = Array(routeList.size) { r ->
        val stopIds = routeList[r].stopIds
        if (identityIds && stopIds.all { it in 0 until stopCount }) stopIds
        else IntArray(stopIds.size) { i -> getStopIndex(stopIds[i]) }
    }

    // Explicit transfers: row = stop index, values = [targetIdx0, walkTime0, targetIdx1, ...]
    internal val transfers: Adjacency = buildTransfers()

    // Incoming transfers (arrive-by): exact transpose, built on the first backward search.
    internal val reverseTransfers: Adjacency by lazy { transposeTransfers() }

    // Internal route indices serving each stop (only where the stop appears in that direction).
    internal val stopRoutes: Adjacency = buildStopRoutes()

    // Same-name groups: implicitGroupOf[stopIndex] = group, members ascending by stop index.
    internal val implicitGroupOf: IntArray
    internal val implicitGroups: Adjacency

    init {
        val groupOfName = HashMap<String, Int>()
        val groupOf = IntArray(stopCount)
        val sizes = ArrayList<Int>()
        for (si in stops.indices) {
            val g = groupOfName.getOrPut(stops[si].name) { sizes.add(0); sizes.size - 1 }
            groupOf[si] = g
            sizes[g] = sizes[g] + 1
        }
        val start = IntArray(sizes.size + 1)
        for (g in sizes.indices) start[g + 1] = start[g] + sizes[g]
        val members = IntArray(stopCount)
        val fill = start.copyOf(sizes.size)
        for (si in stops.indices) members[fill[groupOf[si]]++] = si
        implicitGroupOf = groupOf
        implicitGroups = Adjacency(start, members)
    }

    // ── per-stop views, for callers: built on first access ──────────────────────────────

    // Pre-computed transfer data: transferData[stopIndex] = [targetIdx0, walkTime0, targetIdx1, walkTime1, ...]
    val transferData: Array<IntArray> by lazy { Array(stopCount) { transfers.row(it) } }

    // Reverse adjacency of transferData: reverseTransferData[stopIndex] = [sourceIdx0, walkTime0, ...]
    val reverseTransferData: Array<IntArray> by lazy { Array(stopCount) { reverseTransfers.row(it) } }

    // routeIndicesForStop[stopIndex] = array of internal route indices
    val routeIndicesForStop: Array<IntArray> by lazy { Array(stopCount) { stopRoutes.row(it) } }

    // Index stops by name for implicit transfers
    val stopsByName: Map<String, List<Int>> by lazy { stops.indices.groupBy { stops[it].name } }

    // implicitTransferData[stopIndex] = IntArray of other stop indices with same name
    val implicitTransferData: Array<IntArray> by lazy {
        Array(stopCount) { si ->
            val g = implicitGroupOf[si]
            val from = implicitGroups.start[g]
            val to = implicitGroups.start[g + 1]
            IntArray(to - from - 1).also { out ->
                var c = 0
                for (m in from until to) if (implicitGroups.data[m] != si) out[c++] = implicitGroups.data[m]
            }
        }
    }

    // ── construction ─────────────────────────────────────────────────────────────────────

    private fun buildTransfers(): Adjacency {
        val start = IntArray(stopCount + 1)
        for (si in stops.indices) start[si + 1] = start[si] + stops[si].transfers.size * 2
        val total = start[stopCount]
        // Fast path: transfers read from a stops.bin share one array, laid out stop after stop.
        // With ids that are indices and every target in range, that array is the adjacency.
        var shared: IntArray? = null
        var reusable = identityIds
        for (si in stops.indices) {
            if (!reusable) break
            val list = stops[si].transfers
            if (list.isEmpty()) continue
            if (list !is FlatTransferList || list.start != start[si] || (shared != null && list.pairs !== shared)) {
                reusable = false
            } else {
                shared = list.pairs
            }
        }
        if (reusable && shared != null && shared.size == total) {
            var t = 0
            while (t < total) {
                if (shared[t] !in 0 until stopCount) { reusable = false; break }
                t += 2
            }
            if (reusable) return Adjacency(start, shared)
        }
        val data = IntArray(total)
        for (si in stops.indices) {
            var at = start[si]
            for (transfer in stops[si].transfers) {
                data[at] = getStopIndex(transfer.targetStopId)
                data[at + 1] = transfer.walkTime
                at += 2
            }
        }
        return Adjacency(start, data)
    }

    private fun transposeTransfers(): Adjacency {
        val start = IntArray(stopCount + 1)
        val data = transfers.data
        for (si in 0 until stopCount) {
            var t = transfers.start[si]
            val end = transfers.start[si + 1]
            while (t < end) {
                val target = data[t]
                if (target != -1) start[target + 1] += 2
                t += 2
            }
        }
        for (i in 0 until stopCount) start[i + 1] += start[i]
        val out = IntArray(start[stopCount])
        val fill = start.copyOf(stopCount)
        for (si in 0 until stopCount) {
            var t = transfers.start[si]
            val end = transfers.start[si + 1]
            while (t < end) {
                val target = data[t]
                val walk = data[t + 1]
                t += 2
                if (target == -1) continue
                val pos = fill[target]
                out[pos] = si
                out[pos + 1] = walk
                fill[target] = pos + 2
            }
        }
        return Adjacency(start, out)
    }

    private fun buildStopRoutes(): Adjacency {
        val internal = buildRouteInternalIndices(routeList)
        val start = IntArray(stopCount + 1)
        var data = IntArray(maxOf(16, stopCount * 4))
        var used = 0
        for (si in stops.indices) {
            for (rid in stops[si].routeIds) {
                val arr = internal[rid] ?: continue
                for (rIdx in arr) {
                    // Only include if stop actually appears in this route direction
                    val rsi = routeStopIndices[rIdx]
                    var found = false
                    for (p in rsi.indices) {
                        if (rsi[p] == si) { found = true; break }
                    }
                    if (found) {
                        if (used == data.size) data = data.copyOf(data.size * 2)
                        data[used++] = rIdx
                    }
                }
            }
            start[si + 1] = used
        }
        return Adjacency(start, if (used == data.size) data else data.copyOf(used))
    }

    // Built lazily on the first walking query, so networks that never take an address query pay
    // nothing (mirrors the engine's lazy per-period construction).
    private val spatialGrid: SpatialGrid by lazy { SpatialGrid(stops) }

    /**
     * Finds every stop within [maxDistanceMeters] (great-circle) of the given WGS84 point.
     * Delegates to a uniform spatial grid so the work scales with the local stop density around the
     * point, not the whole network. Output is identical (same set, same distances) to a brute-force
     * haversine scan — the grid only narrows which stops the exact check is run on.
     */
    fun findNearbyStops(lat: Double, lon: Double, maxDistanceMeters: Double): List<NearbyStop> =
        spatialGrid.query(lat, lon, maxDistanceMeters)

    /**
     * Collects route internal indices serving any of the given stops.
     * Uses a reusable BooleanArray for deduplication (no HashSet allocation).
     * Returns count of results written to resultBuffer.
     */
    fun collectRouteIndices(stopIndices: IntArray, stopCount: Int, seenBuffer: BooleanArray, resultBuffer: IntArray): Int {
        var count = 0
        val start = stopRoutes.start
        val data = stopRoutes.data
        for (idx in 0 until stopCount) {
            val si = stopIndices[idx]
            var p = start[si]
            val end = start[si + 1]
            while (p < end) {
                val routeIdx = data[p++]
                if (!seenBuffer[routeIdx]) {
                    seenBuffer[routeIdx] = true
                    resultBuffer[count++] = routeIdx
                }
            }
        }
        // Reset seen entries
        for (i in 0 until count) {
            seenBuffer[resultBuffer[i]] = false
        }
        return count
    }
}

/** routeId -> internal indices, in route order (several directions share an id). */
private fun buildRouteInternalIndices(routes: Array<Route>): HashMap<Int, IntArray> {
    val counts = HashMap<Int, Int>(routes.size * 2)
    for (r in routes) {
        counts[r.id] = (counts[r.id] ?: 0) + 1
    }
    val map = HashMap<Int, IntArray>(counts.size * 2)
    val offsets = HashMap<Int, Int>(counts.size * 2)
    for ((id, count) in counts) {
        map[id] = IntArray(count)
        offsets[id] = 0
    }
    for (i in routes.indices) {
        val id = routes[i].id
        val arr = map[id]!!
        arr[offsets[id]!!] = i
        offsets[id] = offsets[id]!! + 1
    }
    return map
}

/**
 * Uniform spatial grid over the stops: they are bucketed into ~[CELL_SIZE_METERS] square cells so a
 * radius query visits only the cells overlapping its bounding box instead of scanning every stop.
 *
 * CSR layout ([cellStart] + [stopIndices]) — one int per cell plus one per stop, no per-cell object,
 * built in the same count → prefix-sum → fill pattern as the network's other flat indices. Cells are
 * grown uniformly if the bounding box is sparse/wide so the cell count stays O(stops). A query point
 * near the poles (no transit network is there) falls back to scanning the full longitude span.
 */
private class SpatialGrid(private val stops: List<Stop>) {
    private val rows: Int
    private val cols: Int
    private val minLat: Double
    private val minLon: Double
    private val cellLatDeg: Double
    private val cellLonDeg: Double        // Double.MAX_VALUE for a near-pole network (single column)
    private val cellStart: IntArray       // size rows*cols + 1; CSR row offsets
    private val stopIndices: IntArray     // stops grouped by cell, ascending index within a cell

    init {
        val n = stops.size
        var loLat = Double.MAX_VALUE; var hiLat = -Double.MAX_VALUE
        var loLon = Double.MAX_VALUE; var hiLon = -Double.MAX_VALUE
        for (s in stops) {
            if (s.lat < loLat) loLat = s.lat
            if (s.lat > hiLat) hiLat = s.lat
            if (s.lon < loLon) loLon = s.lon
            if (s.lon > hiLon) hiLon = s.lon
        }
        if (n == 0) { loLat = 0.0; hiLat = 0.0; loLon = 0.0; hiLon = 0.0 }
        minLat = loLat
        minLon = loLon

        val cosMeanLat = cos(((loLat + hiLat) / 2.0) * kotlin.math.PI / 180.0)
        var cLat = CELL_SIZE_METERS / METERS_PER_DEGREE_LAT
        var cLon = if (cosMeanLat > 1e-6) CELL_SIZE_METERS / (METERS_PER_DEGREE_LAT * cosMeanLat)
                   else Double.MAX_VALUE
        val latSpan = hiLat - loLat
        val lonSpan = hiLon - loLon

        var r = (latSpan / cLat).toInt() + 1
        var c = if (cLon.isFinite()) (lonSpan / cLon).toInt() + 1 else 1
        // Keep the cell count ~O(stops): a sparse, wide box would otherwise allocate a huge grid.
        val maxCells = 4L * n + 64L
        if (r.toLong() * c > maxCells) {
            val scale = sqrt(r.toLong() * c / maxCells.toDouble())
            cLat *= scale
            if (cLon.isFinite()) cLon *= scale
            r = (latSpan / cLat).toInt() + 1
            c = if (cLon.isFinite()) (lonSpan / cLon).toInt() + 1 else 1
        }
        rows = r.coerceAtLeast(1)
        cols = c.coerceAtLeast(1)
        cellLatDeg = cLat
        cellLonDeg = cLon

        val cellCount = rows * cols
        val counts = IntArray(cellCount)
        for (i in stops.indices) counts[cellOf(stops[i].lat, stops[i].lon)]++
        val starts = IntArray(cellCount + 1)
        var acc = 0
        for (ci in 0 until cellCount) { starts[ci] = acc; acc += counts[ci] }
        starts[cellCount] = acc
        val idx = IntArray(n)
        val cursor = IntArray(cellCount)
        for (i in stops.indices) {
            val ci = cellOf(stops[i].lat, stops[i].lon)
            idx[starts[ci] + cursor[ci]] = i
            cursor[ci]++
        }
        cellStart = starts
        stopIndices = idx
    }

    private fun rowOf(lat: Double): Int = ((lat - minLat) / cellLatDeg).toInt().coerceIn(0, rows - 1)

    private fun colOf(lon: Double): Int =
        if (cellLonDeg.isFinite()) ((lon - minLon) / cellLonDeg).toInt().coerceIn(0, cols - 1) else 0

    private fun cellOf(lat: Double, lon: Double): Int = rowOf(lat) * cols + colOf(lon)

    /**
     * Every stop within [maxDistanceMeters] of the point, with its exact haversine distance.
     * The cell range is derived from the same over-covering bounding box the old brute-force scan
     * used, so any in-range stop is guaranteed to sit in a scanned cell; the exact haversine below
     * can then only filter. Result is sorted by stop index to match the historical output order.
     */
    fun query(lat: Double, lon: Double, maxDistanceMeters: Double): List<NearbyStop> {
        val result = ArrayList<NearbyStop>()
        if (stops.isEmpty()) return result

        val dLatMax = maxDistanceMeters / METERS_PER_DEGREE_LAT
        val cosLat = cos(lat * kotlin.math.PI / 180.0)
        // Longitude degrees shrink with latitude; near the poles skip the prefilter instead of
        // dividing by ~0.
        val dLonMax = if (cosLat > 1e-6) dLatMax / cosLat else Double.MAX_VALUE

        val rMin = rowOf(lat - dLatMax)
        val rMax = rowOf(lat + dLatMax)
        val cMin: Int
        val cMax: Int
        if (dLonMax.isFinite() && cellLonDeg.isFinite()) {
            cMin = colOf(lon - dLonMax)
            cMax = colOf(lon + dLonMax)
        } else {
            cMin = 0
            cMax = cols - 1
        }

        for (row in rMin..rMax) {
            val rowBase = row * cols
            for (col in cMin..cMax) {
                val cell = rowBase + col
                var p = cellStart[cell]
                val end = cellStart[cell + 1]
                while (p < end) {
                    val si = stopIndices[p]; p++
                    val stop = stops[si]
                    if (abs(stop.lat - lat) > dLatMax || abs(stop.lon - lon) > dLonMax) continue
                    val distance = Geo.distanceMeters(lat, lon, stop.lat, stop.lon)
                    if (distance <= maxDistanceMeters) result.add(NearbyStop(si, distance))
                }
            }
        }
        result.sortBy { it.stopIndex }
        return result
    }
}