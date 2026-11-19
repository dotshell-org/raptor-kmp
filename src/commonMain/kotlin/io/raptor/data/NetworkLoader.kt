package io.raptor.data

import io.raptor.model.*

object NetworkLoader {

    fun loadStops(bytes: ByteArray): List<Stop> {
        val reader = BinaryReader(bytes)
        val magic = reader.peekMagic()
        return when (magic) {
            "RSTS" -> loadStopsV1(reader)
            "RST2" -> loadStopsFlat(reader, "RST2", hasZone = false)
            "RST3" -> loadStopsFlat(reader, "RST3", hasZone = true)
            else -> throw IllegalStateException("Unknown stops format: '$magic'. Expected RSTS (v1), RST2 (v2) or RST3 (v3).")
        }
    }

    fun loadRoutes(bytes: ByteArray): List<Route> {
        val reader = BinaryReader(bytes)
        val magic = reader.peekMagic()
        return when (magic) {
            "RRTS" -> loadRoutesV1(reader)
            "RRT2" -> loadRoutesV2(reader)
            else -> throw IllegalStateException("Unknown routes format: '$magic'. Expected RRTS (v1) or RRT2 (v2).")
        }
    }

    // ── V1 format (original) ──────────────────────────────────────────

    private fun loadStopsV1(reader: BinaryReader): List<Stop> {
        reader.readMagic("RSTS")
        reader.readUInt16()
        return readStops(reader, reader.readUInt32(), hasZone = false)
    }

    /**
     * The stop records of every stops format (V1 is V2 without the zone field). Built for a
     * small heap: every transfer of the file goes into one shared array of (target, walk) pairs
     * that each stop's [Stop.transfers] reads through (no object per transfer), stops with no
     * route share one empty array, and a name or zone repeated across stops is kept once.
     */
    private fun readStops(reader: BinaryReader, count: Int, hasZone: Boolean): List<Stop> {
        val ids = IntArray(count)
        val names = arrayOfNulls<String>(count)
        val lats = DoubleArray(count)
        val lons = DoubleArray(count)
        val zones = arrayOfNulls<String>(count)
        val routeIds = arrayOfNulls<IntArray>(count)
        val transferStart = IntArray(count + 1)
        var pairs = IntArray(maxOf(16, count * 8))
        var used = 0
        val strings = HashMap<String, String>()
        for (i in 0 until count) {
            ids[i] = reader.readUInt32()
            val name = reader.readUTF8(reader.readUInt16())
            names[i] = strings.getOrPut(name) { name }
            lats[i] = reader.readFloat64()
            lons[i] = reader.readFloat64()
            if (hasZone) {
                val zone = reader.readUTF8(reader.readUInt16())
                zones[i] = if (zone.isEmpty()) null else strings.getOrPut(zone) { zone }
            }
            val routeRefCount = reader.readUInt32()
            routeIds[i] = if (routeRefCount == 0) EMPTY_INTS else IntArray(routeRefCount) { reader.readUInt32() }
            val transferCount = reader.readUInt32()
            transferStart[i] = used
            if (used + 2 * transferCount > pairs.size) {
                pairs = pairs.copyOf(maxOf(pairs.size * 2, used + 2 * transferCount))
            }
            repeat(transferCount) {
                pairs[used++] = reader.readUInt32()
                pairs[used++] = reader.readInt32()
            }
        }
        transferStart[count] = used
        val flat = if (used == pairs.size) pairs else pairs.copyOf(used)
        return List(count) { i ->
            val n = (transferStart[i + 1] - transferStart[i]) / 2
            val transfers: List<Transfer> = if (n == 0) emptyList() else FlatTransferList(flat, transferStart[i], n)
            Stop(ids[i], names[i]!!, lats[i], lons[i], routeIds[i]!!, transfers, zones[i])
        }
    }

    private fun loadRoutesV1(reader: BinaryReader): List<Route> {
        reader.readMagic("RRTS")
        reader.readUInt16()
        val count = reader.readUInt32()

        return List(count) {
            val routeId = reader.readUInt32()
            val nameLen = reader.readUInt16()
            val name = reader.readUTF8(nameLen)
            val stopCount = reader.readUInt32()
            val tripCount = reader.readUInt32()
            val stopIds = IntArray(stopCount) { reader.readUInt32() }

            // Read all trip data into flat arrays
            val tripIds = IntArray(tripCount)
            val flatStopTimes = IntArray(tripCount * stopCount)
            for (t in 0 until tripCount) {
                tripIds[t] = reader.readUInt32()
                var currentTime = 0
                for (s in 0 until stopCount) {
                    currentTime += reader.readInt32()
                    flatStopTimes[t * stopCount + s] = currentTime
                }
            }

            // Sort trips by first stop departure time using index sort
            val sortedIndices = (0 until tripCount).sortedBy { flatStopTimes[it * stopCount] }
            val sortedTripIds = IntArray(tripCount) { tripIds[sortedIndices[it]] }
            val sortedFlat = IntArray(tripCount * stopCount)
            for (i in 0 until tripCount) {
                flatStopTimes.copyInto(
                    destination = sortedFlat,
                    destinationOffset = i * stopCount,
                    startIndex = sortedIndices[i] * stopCount,
                    endIndex = sortedIndices[i] * stopCount + stopCount
                )
            }

            val overnight = hasOvernight(sortedFlat, tripCount, stopCount)
            Route(routeId, name, stopIds, tripCount, stopCount, sortedFlat, sortedTripIds, overnight)
        }
    }

    // ── V2/V3 format (pre-sorted, flat layout) ────────────────────────

    /**
     * Shared reader for the flat stops layout. RST3 adds a per-stop fare zone string right after
     * `lon`; RST2 has no such field ([hasZone] = false → [Stop.zone] stays null). An empty zone
     * string decodes to null so unzoned stops are indistinguishable from older formats.
     */
    private fun loadStopsFlat(reader: BinaryReader, magic: String, hasZone: Boolean): List<Stop> {
        reader.readMagic(magic)
        reader.readUInt16() // version
        return readStops(reader, reader.readUInt32(), hasZone)
    }

    private fun loadRoutesV2(reader: BinaryReader): List<Route> {
        reader.readMagic("RRT2")
        reader.readUInt16() // version
        val count = reader.readUInt32()
        val names = HashMap<String, String>()  // a line name shared by its directions and patterns is kept once

        return List(count) {
            val routeId = reader.readUInt32()
            val nameLen = reader.readUInt16()
            val name = reader.readUTF8(nameLen).let { names.getOrPut(it) { it } }
            val stopCount = reader.readUInt32()
            val tripCount = reader.readUInt32()
            val stopIds = IntArray(stopCount) { reader.readUInt32() }

            // V2: trip IDs are stored separately, pre-sorted
            val tripIds = IntArray(tripCount) { reader.readUInt32() }

            // V2: flat stop times are pre-sorted, delta-encoded per row
            val flatStopTimes = IntArray(tripCount * stopCount)
            for (t in 0 until tripCount) {
                var currentTime = 0
                for (s in 0 until stopCount) {
                    currentTime += reader.readInt32()
                    flatStopTimes[t * stopCount + s] = currentTime
                }
            }
            // No sorting needed — pre-sorted in binary file

            val overnight = hasOvernight(flatStopTimes, tripCount, stopCount)
            Route(routeId, name, stopIds, tripCount, stopCount, flatStopTimes, tripIds, overnight)
        }
    }

    private fun hasOvernight(flat: IntArray, tripCount: Int, stopCount: Int): Boolean {
        for (t in 0 until tripCount) {
            val base = t * stopCount
            for (s in 1 until stopCount) {
                if (flat[base + s] < flat[base + s - 1]) return true
            }
        }
        return false
    }
}
