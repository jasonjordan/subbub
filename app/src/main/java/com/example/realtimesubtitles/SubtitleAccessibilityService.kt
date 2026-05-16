package com.example.realtimesubtitles

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Accessibility service that renders subtitles using TYPE_ACCESSIBILITY_OVERLAY.
 * This is far more reliable on Android TV than TYPE_APPLICATION_OVERLAY because
 * accessibility overlays are granted higher priority by the system window manager.
 *
 * The user must enable this service in Settings > Accessibility > subbub.
 */
class SubtitleAccessibilityService : AccessibilityService() {

    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private var overlayView: View? = null
    private var overlayText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "SubAccessibilitySvc"

        fun isEnabled(context: android.content.Context): Boolean {
            val am = context.getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = android.content.ComponentName(context.packageName, SubtitleAccessibilityService::class.java.name).flattenToString()
            return enabledServices.contains(component)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        serviceInfo = serviceInfo.apply {
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        // Show any text that was already set before we connected
        val existingText = SubtitleState.currentText.value
        if (existingText.isNotBlank()) {
            show(existingText)
        }

        scope.launch {
            SubtitleState.currentText.collect { text ->
                if (text.isNotBlank()) {
                    show(text)
                } else {
                    hide()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events; we just use this service
        // for its privileged overlay permission.
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        removeOverlay()
    }

    private fun show(text: String) {
        handler.post {
            ensureViewAdded()
            overlayText?.text = text
            overlayView?.visibility = View.VISIBLE
        }
    }

    private fun hide() {
        handler.post {
            overlayView?.visibility = View.GONE
        }
    }

    private fun removeOverlay() {
        handler.post {
            overlayView?.let { view ->
                try {
                    windowManager.removeViewImmediate(view)
                } catch (_: IllegalArgumentException) {
                    // Not attached
                }
            }
            overlayView = null
            overlayText = null
        }
    }

    private fun ensureViewAdded() {
        if (overlayView != null) {
            try {
                overlayView?.parent ?: throw IllegalStateException("Detached")
            } catch (_: Exception) {
                overlayView = null
                overlayText = null
            }
        }
        if (overlayView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_subtitle, null)
        overlayText = view.findViewById(R.id.overlayText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            Log.d(TAG, "Accessibility overlay attached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach accessibility overlay", e)
        }
    }
}
