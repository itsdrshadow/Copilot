package com.antigravity.copilot.feature.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.widget.Toast
import com.antigravity.copilot.core.model.AppDrawerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LauncherManager(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    companion object {
        const val APPS_PER_PAGE = 12
        const val GRID_COLUMNS = 3
        const val GRID_ROWS = 4

        private val iconCache = LruCache<String, Bitmap>(200)

        fun calculatePages(appCount: Int, pageSize: Int = APPS_PER_PAGE): Int {
            if (appCount <= 0) return 1
            return (appCount + pageSize - 1) / pageSize
        }

        fun <T> paginate(items: List<T>, pageSize: Int = APPS_PER_PAGE): List<List<T>> {
            if (items.isEmpty()) return emptyList()
            return items.chunked(pageSize)
        }

        fun clearIconCache() {
            iconCache.evictAll()
        }
    }

    suspend fun getInstalledApps(): List<AppDrawerItem> = withContext(Dispatchers.IO) {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
        val appList = mutableListOf<AppDrawerItem>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            // Skip our own app in the drawer list
            if (pkg == context.packageName) continue

            val label = info.loadLabel(packageManager).toString()
            val activityName = info.activityInfo.name
            val cacheKey = "$pkg/$activityName"

            val bitmap = iconCache.get(cacheKey) ?: run {
                try {
                    val d = info.loadIcon(packageManager)
                    val w = if (d.intrinsicWidth > 0) d.intrinsicWidth.coerceIn(48, 128) else 96
                    val h = if (d.intrinsicHeight > 0) d.intrinsicHeight.coerceIn(48, 128) else 96
                    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bm)
                    d.setBounds(0, 0, w, h)
                    d.draw(canvas)
                    iconCache.put(cacheKey, bm)
                    bm
                } catch (e: Throwable) {
                    null
                }
            }

            appList.add(
                AppDrawerItem(
                    packageName = pkg,
                    activityName = activityName,
                    label = label,
                    iconBitmap = bitmap
                )
            )
        }

        appList.distinctBy { "${it.packageName}/${it.activityName}" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun launchApp(packageName: String, activityName: String) {
        try {
            val intent = if (activityName.isNotBlank()) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(packageName, activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            if (intent != null) {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun launchNavigation() {
        val navIntent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("geo:0,0?q=")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(navIntent)
            return
        } catch (e: Exception) {
            // Fallback to Google Maps or navigation package
            val mapsIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            if (mapsIntent != null) {
                try {
                    mapsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(mapsIntent)
                    return
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }

            // Fallback gracefully to a generic geo intent
            val genericGeoIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("geo:0,0")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(genericGeoIntent)
                return
            } catch (e3: Exception) {
                e3.printStackTrace()
            }

            // If no navigation application could be resolved, display Toast informing the user
            try {
                Toast.makeText(context, "No navigation app found", Toast.LENGTH_SHORT).show()
            } catch (t: Exception) {
                t.printStackTrace()
            }
        }
    }

    fun launchSettings() {
        val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
