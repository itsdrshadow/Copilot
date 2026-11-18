package com.antigravity.copilot.feature.fuel

/** The three linked refuel inputs. */
enum class SyncField { AMOUNT, RATE, LITRES }

/**
 * Amount (₹) / Rate (₹/L) / Litres auto-sync: the two most recently typed fields are inputs and
 * the third is derived (amount = litres × rate, rate = amount / litres, litres = amount / rate).
 */
object RefuelInputSync {
    data class Values(val amount: Double, val rate: Double, val litres: Double)

    /** [recent] = typed fields, oldest first; the field not among the last two is derived. */
    fun derivedField(recent: List<SyncField>): SyncField {
        val inputs = recent.takeLast(2).toSet()
        // Preference order only matters with fewer than two typed fields.
        return listOf(SyncField.LITRES, SyncField.AMOUNT, SyncField.RATE).first { it !in inputs }
    }

    /** Marks [field] as just typed; keeps the last two distinct entries. */
    fun touch(recent: List<SyncField>, field: SyncField): List<SyncField> =
        (recent.filter { it != field } + field).takeLast(2)

    fun resolve(amount: Double, rate: Double, litres: Double, derived: SyncField): Values = when (derived) {
        SyncField.AMOUNT -> Values(litres * rate, rate, litres)
        SyncField.RATE -> Values(amount, if (litres > 0.0) amount / litres else 0.0, litres)
        SyncField.LITRES -> Values(amount, rate, if (rate > 0.0) amount / rate else 0.0)
    }
}
