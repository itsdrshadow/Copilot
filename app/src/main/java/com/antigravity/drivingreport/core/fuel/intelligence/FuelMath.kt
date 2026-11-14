package com.antigravity.drivingreport.core.fuel.intelligence

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * High-precision, decimal-safe arithmetic utilities for automotive fuel and financial calculations.
 * Prevents IEEE 754 floating-point drift over thousands of 1 Hz GPS ticks and multiple partial refills.
 */
object FuelMath {

    private const val FUEL_SCALE = 3       // 3 decimal places for fuel volume (e.g., 9.158 Liters)
    private const val CURRENCY_SCALE = 2   // 2 decimal places for financial amounts (e.g., ₹1000.00)
    private const val DISTANCE_SCALE = 2   // 2 decimal places for distance (e.g., 120.45 km)
    private const val MILEAGE_SCALE = 2    // 2 decimal places for mileage (e.g., 16.42 km/L)

    fun roundFuel(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return BigDecimal.valueOf(value)
            .setScale(FUEL_SCALE, RoundingMode.HALF_UP)
            .toDouble()
    }

    fun roundCurrency(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return BigDecimal.valueOf(value)
            .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP)
            .toDouble()
    }

    fun roundDistance(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return BigDecimal.valueOf(value)
            .setScale(DISTANCE_SCALE, RoundingMode.HALF_UP)
            .toDouble()
    }

    fun roundMileage(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return BigDecimal.valueOf(value)
            .setScale(MILEAGE_SCALE, RoundingMode.HALF_UP)
            .toDouble()
    }

    /**
     * Liters Added = Total Amount / Price per Liter.
     * Guaranteed decimal-safe.
     */
    fun calculateLitersFromAmount(totalAmount: Double, pricePerLiter: Double): Double {
        if (totalAmount <= 0.0 || pricePerLiter <= 0.0 || !totalAmount.isFinite() || !pricePerLiter.isFinite()) {
            return 0.0
        }
        val amountBd = BigDecimal.valueOf(totalAmount)
        val priceBd = BigDecimal.valueOf(pricePerLiter)
        return amountBd.divide(priceBd, FUEL_SCALE, RoundingMode.HALF_UP).toDouble()
    }

    /**
     * Total Amount = Liters * Price per Liter.
     */
    fun calculateAmountFromLiters(liters: Double, pricePerLiter: Double): Double {
        if (liters <= 0.0 || pricePerLiter <= 0.0 || !liters.isFinite() || !pricePerLiter.isFinite()) {
            return 0.0
        }
        val litersBd = BigDecimal.valueOf(liters)
        val priceBd = BigDecimal.valueOf(pricePerLiter)
        return litersBd.multiply(priceBd).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP).toDouble()
    }

    /**
     * Fuel Consumed = Distance (km) / Mileage (km/L).
     */
    fun calculateConsumption(distanceKm: Double, mileageKmpl: Double): Double {
        if (distanceKm <= 0.0 || mileageKmpl <= 0.0 || !distanceKm.isFinite() || !mileageKmpl.isFinite()) {
            return 0.0
        }
        val distBd = BigDecimal.valueOf(distanceKm)
        val mileageBd = BigDecimal.valueOf(mileageKmpl)
        return distBd.divide(mileageBd, FUEL_SCALE, RoundingMode.HALF_UP).toDouble()
    }

    fun calculateFuelConsumed(distanceKm: Double, mileageKmpl: Double): Double = calculateConsumption(distanceKm, mileageKmpl)

    /**
     * Continuous Partial Refill Fuel Consumed = Starting Fuel + Total Added - Ending Fuel.
     */
    fun calculatePartialRefillFuelConsumed(startingFuel: Double, totalAdded: Double, endingFuel: Double): Double {
        val consumed = (startingFuel + totalAdded) - endingFuel
        return roundFuel(consumed.coerceAtLeast(0.0))
    }

    /**
     * Mileage = Distance (km) / Fuel Consumed (L).
     */
    fun calculateMileage(distanceKm: Double, fuelConsumedLiters: Double): Double {
        if (distanceKm <= 0.0 || fuelConsumedLiters <= 0.001 || !distanceKm.isFinite() || !fuelConsumedLiters.isFinite()) {
            return 0.0
        }
        val distBd = BigDecimal.valueOf(distanceKm)
        val fuelBd = BigDecimal.valueOf(fuelConsumedLiters)
        return distBd.divide(fuelBd, MILEAGE_SCALE, RoundingMode.HALF_UP).toDouble()
    }
}
