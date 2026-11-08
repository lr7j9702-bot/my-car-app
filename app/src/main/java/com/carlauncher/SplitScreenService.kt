package com.carlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Android only lets an app trigger "split screen" through an accessibility service.
 * This service never reads the screen: it only performs the split-screen action
 * and then opens the second app next to the first one.
 */
class SplitScreenService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Opens [firstPkg], docks it as one half of the screen, then opens [secondPkg] in the other half. */
    fun startSplit(firstPkg: String, secondPkg: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        handler.removeCallbacksAndMessages(null)

        launch(firstPkg, adjacent = false)
        handler.postDelayed({
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        }, TOGGLE_DELAY_MS)
        handler.postDelayed({
            launch(secondPkg, adjacent = true)
        }, SECOND_DELAY_MS)
        return true
    }

    private fun launch(pkg: String, adjacent: Boolean) {
        val intent: Intent = (if (pkg == packageName) {
            Intent(this, MainActivity::class.java)
        } else {
            packageManager.getLaunchIntentForPackage(pkg)
        }) ?: return

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (adjacent) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // App cannot be opened; nothing more to do.
        }
    }

    companion object {
        @Volatile
        var instance: SplitScreenService? = null

        // Head units differ in speed. Raise these if the second app opens too early.
        private const val TOGGLE_DELAY_MS = 900L
        private const val SECOND_DELAY_MS = 2000L
    }
}
