package io.raptor.benchmark

import io.raptor.model.Network
import io.raptor.model.Route
import io.raptor.model.Stop
import io.raptor.model.Transfer

/**
 * Builds small hand-crafted networks for unit tests — no .bin dataset required.
 */
object SyntheticNetworkBuilder {

    class StopDef(
        val id: Int,
        val name: String,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val transfers: List<Transfer> = emptyList()
    )

    /**
     * @param trips one IntArray of stop times per trip, parallel to [stopIds]. Trips must be
     *        sorted by time at every stop position (FIFO), as the loader guarantees for real data.
     */
    fun route(
        id: Int,
        name: String,
        stopIds: IntArray,
        trips: List<IntArray>,
        tripIds: IntArray? = null
    ): Route {
        val stopCount = stopIds.size
        val flat = IntArray(trips.size * stopCount)
        for (t in trips.indices) {
            require(trips[t].size == stopCount) { "trip $t has ${trips[t].size} times for $stopCount stops" }
            trips[t].copyInto(flat, t * stopCount)
        }
        var overnight = false
        for (row in trips) {
            for (i in 1 until row.size) {
                if (row[i] < row[i - 1]) overnight = true
            }
        }
        val actualTripIds = tripIds ?: IntArray(trips.size) { it }
        require(actualTripIds.size == trips.size) { "tripIds size must match trips count" }
        return Route(id, name, stopIds, trips.size, stopCount, flat, actualTripIds, overnight)
    }

    /**
     * Builds a Network, computing each stop's routeIds from the routes that serve it
     * (routeIndicesForStop is empty otherwise and the stop can never board anything).
     */
    fun network(stops: List<StopDef>, routes: List<Route>): Network =
        Network(fullStops(stops, routes), routes)

    private fun fullStops(stops: List<StopDef>, routes: List<Route>): List<Stop> =
        stops.map { def ->
            val serving = routes.filter { r -> r.stopIds.any { it == def.id } }.map { it.id }.distinct()
            Stop(def.id, def.name, def.lat, def.lon, serving.toIntArray(), def.transfers)
        }

    /**
     * Encodes the same synthetic network as v1 binary payloads (RSTS/RRTS little-endian, route
     * times delta-encoded per row) so tests can exercise the RaptorLibrary facade end-to-end.
     * Layout mirrors NetworkLoader.loadStopsV1/loadRoutesV1.
     */
    fun encodeBinary(stops: List<StopDef>, routes: List<Route>): Pair<ByteArray, ByteArray> =
        encodeStopsV1(fullStops(stops, routes)) to encodeRoutesV1(routes)

    private fun encodeStopsV1(stops: List<Stop>): ByteArray {
        val w = ByteWriter()
        w.magic("RSTS")
        w.u16(1)
        w.i32(stops.size)
        for (s in stops) {
            w.i32(s.id)
            w.utf8(s.name)
            w.f64(s.lat)
            w.f64(s.lon)
            w.i32(s.routeIds.size)
            for (rid in s.routeIds) w.i32(rid)
            w.i32(s.transfers.size)
            for (t in s.transfers) {
                w.i32(t.targetStopId)
                w.i32(t.walkTime)
            }
        }
        return w.toByteArray()
    }

    private fun encodeRoutesV1(routes: List<Route>): ByteArray {
        val w = ByteWriter()
        w.magic("RRTS")
        w.u16(1)
        w.i32(routes.size)
        for (r in routes) {
            w.i32(r.id)
            w.utf8(r.name)
            w.i32(r.stopCountInRoute)
            w.i32(r.tripCount)
            for (sid in r.stopIds) w.i32(sid)
            for (t in 0 until r.tripCount) {
                w.i32(r.tripIds[t])
                var previous = 0
                for (s in 0 until r.stopCountInRoute) {
                    val time = r.flatStopTimes[t * r.stopCountInRoute + s]
                    w.i32(time - previous)
                    previous = time
                }
            }
        }
        return w.toByteArray()
    }

    private class ByteWriter {
        private val bytes = ArrayList<Byte>()

        fun magic(s: String) {
            for (c in s) bytes.add(c.code.toByte())
        }

        fun u16(v: Int) {
            bytes.add((v and 0xFF).toByte())
            bytes.add(((v shr 8) and 0xFF).toByte())
        }

        fun i32(v: Int) {
            for (i in 0 until 4) bytes.add(((v shr (8 * i)) and 0xFF).toByte())
        }

        fun f64(v: Double) {
            val bits = v.toRawBits()
            for (i in 0 until 8) bytes.add(((bits shr (8 * i)) and 0xFF).toByte())
        }

        fun utf8(s: String) {
            val encoded = s.encodeToByteArray()
            u16(encoded.size)
            for (b in encoded) bytes.add(b)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
