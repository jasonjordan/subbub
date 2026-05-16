package com.example.realtimesubtitles

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Manages a system overlay window that displays subtitles on top of other apps.
 * Requires SYSTEM_ALERT_WINDOW permission.
 *
 * This is used as a fallback when the AccessibilityService is not enabled.
 * The AccessibilityService (SubtitleAccessibilityService) provides a much more
 * reliable overlay on Android TV via TYPE_ACCESSIBILITY_OVERLAY.
 */
class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var overlayView: View? = null
    private var overlayText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""

    companion object {
        const val TAG = "OverlayManager"
    }

    val canOverlay: Boolean
        get() = Settings.canDrawOverlays(context)

    val isAccessibilityPreferred: Boolean
        get() = SubtitleAccessibilityService.isEnabled(context)

    fun show(text: String) {
        if (isAccessibilityPreferred) {
            // Accessibility service handles the overlay; skip here
            return
        }
        if (!canOverlay) {
            Log.w(TAG, "Cannot draw overlays — permission not granted")
            return
        }
        if (text.isBlank()) {
            overlayView?.visibility = View.GONE
            return
        }
        lastText = text

        handler.post {
            ensureViewAdded()
            overlayText?.text = text
            overlayView?.visibility = View.VISIBLE
            overlayView?.bringToFront()
        }

        handler.removeCallbacks(reattachRunnable)
        handler.postDelayed(reattachRunnable, 500)
    }

    fun hide() {
        handler.post {
            overlayView?.visibility = View.GONE
        }
        handler.removeCallbacks(reattachRunnable)
    }

    fun remove() {
        handler.removeCallbacks(reattachRunnable)
        handler.post {
            overlayView?.let { view ->
                try {
                    windowManager?.removeViewImmediate(view)
                } catch (_: IllegalArgumentException) {
                    // View was not attached
                }
            }
            overlayView = null
            overlayText = null
        }
    }

    private val reattachRunnable = Runnable {
        if (overlayView == null && lastText.isNotBlank() && canOverlay && !isAccessibilityPreferred) {
            Log.d(TAG, "Re-attaching overlay (watchdog)")
            ensureViewAdded()
            overlayText?.text = lastText
            overlayView?.visibility = View.VISIBLE
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

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_subtitle, null)
        overlayText = view.findViewById(R.id.overlayText)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        try {
            windowManager?.addView(view, params)
            overlayView = view
            Log.d(TAG, "Overlay attached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay", e)
        }
    }
}
