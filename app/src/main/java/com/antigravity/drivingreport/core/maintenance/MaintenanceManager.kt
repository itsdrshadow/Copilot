package com.antigravity.drivingreport.core.maintenance

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.drivingreport.core.odometer.OdometerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object MaintenanceManager {

    private const val PREFS_NAME = "driving_report_maintenance_prefs"
    private const val KEY_ITEMS_JSON = "maintenance_items_json"

    private val _items = MutableStateFlow<List<MaintenanceItem>>(emptyList())
    val items: StateFlow<List<MaintenanceItem>> = _items.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadItems()
        }
    }

    private fun loadItems() {
        val sp = prefs ?: return
        val jsonStr = sp.getString(KEY_ITEMS_JSON, null)
        if (jsonStr.isNullOrBlank()) {
            val defaults = MaintenanceType.entries.map { type ->
                val currentOdo = OdometerManager.odometerConfig.value.baselineOdometerKm
                MaintenanceItem(
                    id = type.id,
                    type = type,
                    customTitle = type.title,
                    lastServicedOdoKm = currentOdo,
                    intervalKm = type.defaultIntervalKm,
                    lastServicedDateMs = System.currentTimeMillis(),
                    intervalMonths = type.defaultIntervalMonths,
                    isDateOnly = type == MaintenanceType.INSURANCE || type == MaintenanceType.PUC
                )
            }
            _items.value = defaults
            saveItems(defaults)
        } else {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<MaintenanceItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val typeId = obj.getString("type")
                    val type = MaintenanceType.entries.firstOrNull { it.id == typeId } ?: MaintenanceType.ENGINE_OIL
                    list.add(
                        MaintenanceItem(
                            id = obj.optString("id", type.id),
                            type = type,
                            customTitle = obj.optString("customTitle", type.title),
                            lastServicedOdoKm = obj.optDouble("lastServicedOdoKm", 0.0),
                            intervalKm = obj.optDouble("intervalKm", type.defaultIntervalKm),
                            lastServicedDateMs = obj.optLong("lastServicedDateMs", System.currentTimeMillis()),
                            intervalMonths = obj.optInt("intervalMonths", type.defaultIntervalMonths),
                            isDateOnly = obj.optBoolean("isDateOnly", type == MaintenanceType.INSURANCE || type == MaintenanceType.PUC)
                        )
                    )
                }
                _items.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveItems(list: List<MaintenanceItem>) {
        val sp = prefs ?: return
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("type", item.type.id)
                    put("customTitle", item.customTitle)
                    put("lastServicedOdoKm", item.lastServicedOdoKm)
                    put("intervalKm", item.intervalKm)
                    put("lastServicedDateMs", item.lastServicedDateMs)
                    put("intervalMonths", item.intervalMonths)
                    put("isDateOnly", item.isDateOnly)
                }
                array.put(obj)
            }
            sp.edit().putString(KEY_ITEMS_JSON, array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun markServiced(itemId: String, currentOdoKm: Double, servicedDateMs: Long = System.currentTimeMillis()) {
        val current = _items.value
        val updated = current.map { item ->
            if (item.id == itemId) {
                item.copy(
                    lastServicedOdoKm = currentOdoKm,
                    lastServicedDateMs = servicedDateMs
                )
            } else {
                item
            }
        }
        _items.value = updated
        saveItems(updated)
    }

    fun updateInterval(itemId: String, newIntervalKm: Double, newIntervalMonths: Int) {
        val current = _items.value
        val updated = current.map { item ->
            if (item.id == itemId) {
                item.copy(
                    intervalKm = newIntervalKm,
                    intervalMonths = newIntervalMonths
                )
            } else {
                item
            }
        }
        _items.value = updated
        saveItems(updated)
    }

    fun getDueItemsCount(currentOdoKm: Double): Int {
        val now = System.currentTimeMillis()
        return _items.value.count { it.isDue(currentOdoKm, now) }
    }
}
