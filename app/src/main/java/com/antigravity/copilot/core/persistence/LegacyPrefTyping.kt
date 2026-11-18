package com.antigravity.copilot.core.persistence

/**
 * Type resolution for untyped (legacy) preference values in old backup archives (audit N4).
 * New archives always carry a TypedPrefEntry; for old ones the type the key already has on this
 * device wins, so a restored value can never make `getLong` / `getFloat` throw ClassCastException,
 * and a large number is never truncated into an Int.
 */
object LegacyPrefTyping {

    /** @return Boolean / Int / Long / Float / String to store, or null to skip the entry. */
    fun coerce(key: String, raw: String, isBoolean: Boolean, isNumber: Boolean, existing: Any?): Any? {
        if (existing != null) {
            return when (existing) {
                is Boolean -> raw.toBooleanStrictOrNull()
                is Int -> raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && it == Math.floor(it) }?.toInt()
                is Long -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
                is Float -> raw.toFloatOrNull()
                is String -> raw
                else -> null // string sets etc. are only restored from typed entries
            }
        }
        if (isBoolean) return raw.toBooleanStrictOrNull()
        if (!isNumber) return raw
        if (raw.contains('.') || raw.contains('e') || raw.contains('E')) return raw.toFloatOrNull()
        val asLong = raw.toLongOrNull() ?: return raw.toDoubleOrNull()?.toFloat()
        val k = key.lowercase()
        val longByName = k.endsWith("_timestamp") || k.endsWith("_time") || k == "timestamp" ||
            k.endsWith("_id") || k == "id" || k.endsWith("_bits") || k.endsWith("_ms")
        return if (longByName || asLong !in Int.MIN_VALUE..Int.MAX_VALUE) asLong else asLong.toInt()
    }
}
