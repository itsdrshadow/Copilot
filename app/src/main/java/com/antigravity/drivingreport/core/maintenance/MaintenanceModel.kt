package com.antigravity.drivingreport.core.maintenance

enum class MaintenanceType(
    val id: String,
    val title: String,
    val defaultIntervalKm: Double,
    val defaultIntervalMonths: Int,
    val description: String
) {
    ENGINE_OIL(
        id = "engine_oil",
        title = "Engine Oil & Filter",
        defaultIntervalKm = 10000.0,
        defaultIntervalMonths = 12,
        description = "Change synthetic/mineral oil and replace oil filter element"
    ),
    TYRE_ROTATION(
        id = "tyre_rotation",
        title = "Tyre Rotation & Alignment",
        defaultIntervalKm = 5000.0,
        defaultIntervalMonths = 6,
        description = "Cross-rotate wheels and check toe/camber alignment"
    ),
    AIR_FILTER(
        id = "air_filter",
        title = "Engine & Cabin Air Filter",
        defaultIntervalKm = 15000.0,
        defaultIntervalMonths = 12,
        description = "Replace air intake and AC cabin pollen filters"
    ),
    BRAKE_PADS(
        id = "brake_pads",
        title = "Brake Pads & Fluid",
        defaultIntervalKm = 20000.0,
        defaultIntervalMonths = 24,
        description = "Inspect disc brake pad thickness and DOT4 fluid moisture"
    ),
    COOLANT(
        id = "coolant",
        title = "Radiator Coolant Flush",
        defaultIntervalKm = 30000.0,
        defaultIntervalMonths = 24,
        description = "Flush ethylene glycol radiator coolant"
    ),
    INSURANCE(
        id = "insurance",
        title = "Vehicle Insurance Policy",
        defaultIntervalKm = 0.0,
        defaultIntervalMonths = 12,
        description = "Comprehensive motor insurance policy renewal countdown"
    ),
    PUC(
        id = "puc",
        title = "Pollution (PUC) Certificate",
        defaultIntervalKm = 0.0,
        defaultIntervalMonths = 6,
        description = "Pollution Under Control testing certificate validity"
    )
}

data class MaintenanceItem(
    val id: String,
    val type: MaintenanceType,
    val customTitle: String = type.title,
    val lastServicedOdoKm: Double = 0.0,
    val intervalKm: Double = type.defaultIntervalKm,
    val lastServicedDateMs: Long = System.currentTimeMillis(),
    val intervalMonths: Int = type.defaultIntervalMonths,
    val isDateOnly: Boolean = type == MaintenanceType.INSURANCE || type == MaintenanceType.PUC
) {
    fun remainingKm(currentOdoKm: Double): Double {
        if (isDateOnly || intervalKm <= 0.0) return 0.0
        val targetOdo = lastServicedOdoKm + intervalKm
        return (targetOdo - currentOdoKm).coerceAtLeast(0.0)
    }

    fun remainingDays(currentTimeMs: Long = System.currentTimeMillis()): Int {
        val expiryMs = lastServicedDateMs + (intervalMonths * 30L * 24L * 60L * 60L * 1000L)
        val diffMs = expiryMs - currentTimeMs
        return (diffMs / (24L * 60L * 60L * 1000L)).toInt()
    }

    fun healthPercentage(currentOdoKm: Double, currentTimeMs: Long = System.currentTimeMillis()): Int {
        if (isDateOnly || intervalKm <= 0.0) {
            val totalDays = intervalMonths * 30
            val remDays = remainingDays(currentTimeMs)
            return if (totalDays > 0) ((remDays.toDouble() / totalDays.toDouble()) * 100).toInt().coerceIn(0, 100) else 100
        }

        val remKm = remainingKm(currentOdoKm)
        val kmHealth = ((remKm / intervalKm) * 100).toInt()
        val totalDays = intervalMonths * 30
        val remDays = remainingDays(currentTimeMs)
        val dayHealth = if (totalDays > 0) ((remDays.toDouble() / totalDays.toDouble()) * 100).toInt() else 100

        return minOf(kmHealth, dayHealth).coerceIn(0, 100)
    }

    fun isDue(currentOdoKm: Double, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        if (isDateOnly || intervalKm <= 0.0) {
            return remainingDays(currentTimeMs) <= 7
        }
        return remainingKm(currentOdoKm) <= 200.0 || remainingDays(currentTimeMs) <= 7
    }
}
