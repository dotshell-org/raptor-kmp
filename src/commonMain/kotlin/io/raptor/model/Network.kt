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
 * A container holding all network data for the router.
 */
class Network(
    val stops: List<Stop>,
    routes: List<Route>
) {
    // Total number of stops in the network
    val stopCount: Int = stops.size

    // Quick access: stopId -> index in stops list (O(1) lookup)
    private val stopIdToIndex: HashMap<Int, Int> = HashMap<Int, Int>(stops.size * 2).also { map ->
        for (i in stops.indices) {
            map[stops[i].id] = i
        }
    }
    // Routes indexed by internal position (0..routeCount-1)
    val routeList: Array<Route> = routes.toTypedArray()
    val routeCount: Int = routeList.size

    // routeId -> internal indices in routeList (multiple routes can share the same id, e.g. directions)
    internal val routeInternalIndices: HashMap<Int, IntArray> = HashMap<Int, IntArray>(routes.size * 2).also { map ->
        // First pass: count occurrences per routeId
        val counts = HashMap<Int, Int>(routes.size * 2)
        for (r in routes) {
            counts[r.id] = (counts[r.id] ?: 0) + 1
        }
        // Allocate arrays and fill
        val offsets = HashMap<Int, Int>(routes.size * 2)
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
    }

    // Pre-computed: routeStopIndices[routeInternalIdx][posInRoute] = global stopIndex
    val routeStopIndices: Array<IntArray> = Array(routeList.size) { r ->
        val stopIds = routeList[r].stopIds
        IntArray(stopIds.size) { i -> stopIdToIndex[stopIds[i]] ?: -1 }
    }

    // Pre-computed transfer data: transferData[stopIndex] = [targetIdx0, walkTime0, targetIdx1, walkTime1, ...]
    val transferData: Array<IntArray> = Array(stops.size) { si ->
        val transfers = stops[si].transfers
        val arr = IntArray(transfers.size * 2)
        for (t in transfers.indices) {
            arr[t * 2] = stopIdToIndex[transfers[t].targetStopId] ?: -1
            arr[t * 2 + 1] = transfers[t].walkTime
        }
        arr
    }

    // Reverse adjacency of transferData: reverseTransferData[stopIndex] = [sourceIdx0, walkTime0, ...]
    // lists every explicit transfer arriving AT this stop. GTFS transfers can be asymmetric, so the
    // backward (arrive-by) search needs incoming arcs; built as an exact transpose in two passes.
    val reverseTransferData: Array<IntArray> = run {
        val counts = IntArray(stops.size)
        for (si in stops.indices) {
            val arr = transferData[si]
            var t = 0
            while (t < arr.size) {
                val target = arr[t]
                if (target != -1) counts[target] += 2
                t += 2
            }
        }
        val result = Array(stops.size) { IntArray(counts[it]) }
        val fill = IntArray(stops.size)
        for (si in stops.indices) {
            val arr = transferData[si]
            var t = 0
            while (t < arr.size) {
                val target = arr[t]
                val walk = arr[t + 1]
                t += 2
                if (target == -1) continue
                val out = result[target]
                val pos = fill[target]
                out[pos] = si
                out[pos + 1] = walk
                fill[target] = pos + 2
            }
        }
        result
    }

    // Pre-computed: routeIndicesForStop[stopIndex] = array of internal route indices
    // Filtered: only includes routes where the stop actually appears in routeStopIndices
    val routeIndicesForStop: Array<IntArray> = Array(stops.size) { si ->
        val routeIds = stops[si].routeIds
        var total = 0
        for (rid in routeIds) {
            total += routeInternalIndices[rid]?.size ?: 0
        }
        val indices = IntArray(total)
        var count = 0
        for (rid in routeIds) {
            val arr = routeInternalIndices[rid] ?: continue
            for (rIdx in arr) {
                // Only include if stop actually appears in this route direction
                val rsi = routeStopIndices[rIdx]
                var found = false
                for (p in rsi.indices) {
                    if (rsi[p] == si) { found = true; break }
                }
                if (found) {
                    indices[count++] = rIdx
                }
            }
        }
        if (count == total) indices else indices.copyOf(count)
    }

    // Index stops by name for implicit transfers
    val stopsByName: Map<String, List<Int>> = stops.indices.groupBy { stops[it].name }

    // Pre-computed implicit transfer data: implicitTransferData[stopIndex] = IntArray of other stop indices with same name
    val implicitTransferData: Array<IntArray> = Array(stops.size) { si ->
        val sameNameStops = stopsByName[stops[si].name] ?: emptyList()
        val filtered = IntArray(sameNameStops.size - 1)
        var count = 0
        for (idx in sameNameStops) {
            if (idx != si) filtered[count++] = idx
        }
        if (count == filtered.size) filtered else filtered.copyOf(count)
    }

    fun getStopIndex(id: Int): Int = stopIdToIndex[id] ?: -1

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
        for (idx in 0 until stopCount) {
            val si = stopIndices[idx]
            val routeIndices = routeIndicesForStop[si]
            for (routeIdx in routeIndices) {
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