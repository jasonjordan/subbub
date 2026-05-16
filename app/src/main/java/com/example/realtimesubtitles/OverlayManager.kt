package com.example.realtimesubtitles

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages a system overlay window that displays subtitles on top of other apps.
 * Requires SYSTEM_ALERT_WINDOW permission.
 */
class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var overlayView: View? = null
    private var overlayText: TextView? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val canOverlay: Boolean
        get() = Settings.canDrawOverlays(context)

    fun show(text: String) {
        if (!canOverlay) return
        if (text.isBlank()) {
            overlayView?.visibility = View.GONE
            return
        }

        scope.launch {
            ensureViewAdded()
            overlayText?.text = text
            overlayView?.visibility = View.VISIBLE
        }
    }

    fun hide() {
        scope.launch {
            overlayView?.visibility = View.GONE
        }
    }

    fun remove() {
        scope.launch {
            overlayView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (_: IllegalArgumentException) {
                    // View was not attached
                }
            }
            overlayView = null
            overlayText = null
        }
    }

    private fun ensureViewAdded() {
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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 64 // lift slightly above bottom edge
        }

        windowManager?.addView(view, params)
        overlayView = view
    }
}
