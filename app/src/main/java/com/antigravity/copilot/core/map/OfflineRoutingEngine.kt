package com.antigravity.copilot.core.map

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OfflineRoutingEngine:
 * Pure-JVM turn-by-turn routing engine powered by GraphHopper Core.
 * Compatible with 32-bit armeabi-v7a head units with zero native JNI crashes.
 */
object OfflineRoutingEngine {
    private const val TAG = "OfflineRoutingEngine"

    @Volatile
    private var hopperInstance: GraphHopper? = null
    @Volatile
    private var isInitializing = false
    @Volatile
    private var isLoaded = false

    data class RouteResult(
        val isSuccess: Boolean,
        val points: List<Pair<Double, Double>> = emptyList(),
        val distanceMeters: Double = 0.0,
        val timeMillis: Long = 0,
        val instructions: List<String> = emptyList(),
        val errorMessage: String? = null
    )

    /**
     * Checks if a valid routing graph is extracted in Scoped Storage.
     */
    fun hasRoutingGraph(context: Context): Boolean {
        val routingDir = File(context.getExternalFilesDir("routing"), "india")
        if (!routingDir.exists() || !routingDir.isDirectory) return false
        // Check for essential GraphHopper graph files
        val nodesFile = File(routingDir, "nodes")
        val edgesFile = File(routingDir, "edges")
        val propertiesFile = File(routingDir, "properties")
        return (nodesFile.exists() && edgesFile.exists()) || propertiesFile.exists()
    }

    /**
     * Initializes the GraphHopper JVM routing engine from extracted graph files.
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded && hopperInstance != null) return@withContext true
        if (isInitializing) return@withContext false

        val routingDir = File(context.getExternalFilesDir("routing"), "india")
        if (!hasRoutingGraph(context)) {
            Log.w(TAG, "Routing graph not found at ${routingDir.absolutePath}")
            return@withContext false
        }

        isInitializing = true
        try {
            Log.i(TAG, "Loading GraphHopper routing graph from ${routingDir.absolutePath}...")
            val hopper = GraphHopper().apply {
                setGraphHopperLocation(routingDir.absolutePath)
                profiles = listOf(
                    Profile("car")
                        .setVehicle("car")
                        .setWeighting("fastest")
                )
            }
            hopper.load()
            hopperInstance = hopper
            isLoaded = true
            Log.i(TAG, "GraphHopper routing graph successfully loaded into memory.")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load GraphHopper routing graph: ${e.message}", e)
            isLoaded = false
            hopperInstance = null
            false
        } finally {
            isInitializing = false
        }
    }

    /**
     * Calculates an offline turn-by-turn route between two coordinates.
     */
    suspend fun calculateRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double
    ): RouteResult = withContext(Dispatchers.Default) {
        val hopper = hopperInstance
        if (hopper == null || !isLoaded) {
            // Provide simulated fallback route for demonstration or when graph is pending
            return@withContext RouteResult(
                isSuccess = false,
                errorMessage = "Offline routing graph not loaded. Please download routing data."
            )
        }

        try {
            val req = GHRequest(startLat, startLon, destLat, destLon)
                .setProfile("car")
            val rsp: GHResponse = hopper.route(req)

            if (rsp.hasErrors()) {
                val errors = rsp.errors.joinToString("; ") { it.message ?: "Unknown routing error" }
                Log.e(TAG, "Routing calculation error: $errors")
                return@withContext RouteResult(isSuccess = false, errorMessage = errors)
            }

            val path = rsp.best
            val points = mutableListOf<Pair<Double, Double>>()
            val pointList = path.points
            for (i in 0 until pointList.size()) {
                points.add(Pair(pointList.getLat(i), pointList.getLon(i)))
            }

            val instructionStrings = mutableListOf<String>()
            val instructions = path.instructions
            for (instruction in instructions) {
                val desc = if (!instruction.name.isNullOrBlank()) {
                    "${instruction.name} (${instruction.distance.toInt()}m)"
                } else {
                    "Proceed straight (${instruction.distance.toInt()}m)"
                }
                instructionStrings.add(desc)
            }

            RouteResult(
                isSuccess = true,
                points = points,
                distanceMeters = path.distance,
                timeMillis = path.time,
                instructions = instructionStrings
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during route calculation: ${e.message}", e)
            RouteResult(isSuccess = false, errorMessage = e.message ?: "Route calculation failed")
        }
    }

    fun isEngineReady(): Boolean = isLoaded && hopperInstance != null
}
