package com.carlauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

object AppRepository {
    /** All apps that have a launcher icon, except this launcher itself, sorted by name. */
    fun loadLaunchable(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info -> AppInfo(info.loadLabel(pm).toString(), info.packageName, info.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}

/** Keeps the ordered list of pinned package names. */
class PinnedStore(context: Context) {
    private val prefs = context.getSharedPreferences("pinned", Context.MODE_PRIVATE)

    fun get(): List<String> =
        (prefs.getString(KEY, "") ?: "").split(",").filter { it.isNotBlank() }

    /** Pins the app if it is not pinned, unpins it otherwise. Returns true if it is now pinned. */
    fun toggle(pkg: String): Boolean {
        val list = get().toMutableList()
        val nowPinned = if (list.remove(pkg)) {
            false
        } else {
            list.add(pkg)
            true
        }
        prefs.edit().putString(KEY, list.joinToString(",")).apply()
        return nowPinned
    }

    private companion object {
        const val KEY = "packages"
    }
}
