package com.antigravity.copilot.core.map.intel

import android.util.Log
import java.io.File

/**
 * Offline gazetteer built from the map's own place points. Tile queries only see the few loaded
 * tiles (at driving zoom often no village label at all), so every place seen is remembered here
 * and "Near X" is picked from memory. Bounded LRU (~100 B/entry) persisted as a small TSV so a
 * car parked at home still knows its nearest village after a restart.
 *
 * Thread-safe: filled on Dispatchers.Default, loaded/saved on Dispatchers.IO.
 */
class PlaceMemory(private val file: File?, private val capacity: Int = 2_000) {

    private val places = object : LinkedHashMap<String, PlaceCandidate>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaceCandidate>?) = size > capacity
    }
    private var dirty = false
    private var loaded = false

    /** Adds [list]; returns how many were new. */
    @Synchronized
    fun addAll(list: List<PlaceCandidate>): Int {
        var added = 0
        for (p in list) {
            val k = key(p)
            if (places.put(k, p) == null) added++
        }
        if (added > 0) dirty = true
        return added
    }

    @Synchronized
    fun near(car: LonLat, radiusM: Double): List<PlaceCandidate> =
        places.values.filter { RoadGeometry.distanceM(car, it.position) <= radiusM }

    @Synchronized
    fun size(): Int = places.size

    /** Blocking file read: call on Dispatchers.IO. */
    fun load() {
        val f = file ?: return
        val read = try {
            if (!f.isFile) emptyList() else f.readLines().mapNotNull(::parse)
        } catch (e: Exception) {
            Log.w(TAG, "Place memory not loaded: ${e.message}")
            emptyList()
        }
        synchronized(this) {
            // Entries seen this session (already in the map) stay most recent.
            val session = LinkedHashMap(places)
            places.clear()
            read.forEach { places[key(it)] = it }
            places.putAll(session)
            loaded = true
        }
    }

    /** Blocking file write if anything changed: call on Dispatchers.IO. */
    fun saveIfDirty() {
        val f = file ?: return
        val lines = synchronized(this) {
            if (!dirty || !loaded) return
            dirty = false
            places.values.map { "${clean(it.name)}\t${it.placeClass.orEmpty()}\t${it.position.lon}\t${it.position.lat}" }
        }
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(lines.joinToString("\n"))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            Log.w(TAG, "Place memory not saved: ${e.message}")
            synchronized(this) { dirty = true }
        }
    }

    private fun parse(line: String): PlaceCandidate? {
        val c = line.split('\t')
        if (c.size != 4) return null
        val lon = c[2].toDoubleOrNull() ?: return null
        val lat = c[3].toDoubleOrNull() ?: return null
        if (c[0].isBlank()) return null
        return PlaceCandidate(c[0], c[1].ifEmpty { null }, LonLat(lon, lat))
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun key(p: PlaceCandidate) =
        "${p.name}|${(p.position.lon * 1e4).toLong()}|${(p.position.lat * 1e4).toLong()}"

    private companion object {
        const val TAG = "PlaceMemory"
    }
}
