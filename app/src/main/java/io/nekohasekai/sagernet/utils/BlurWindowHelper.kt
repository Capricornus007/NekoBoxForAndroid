package io.nekohasekai.sagernet.utils

import android.app.Dialog
import android.os.Build
import android.view.Window
import android.view.WindowManager
import io.nekohasekai.sagernet.database.DataStore

object BlurWindowHelper {

    /**
     * Apply hardware-accelerated window backdrop blur (Android 12+ / API 31+).
     * If blur mode is enabled in settings and supported by the device OS,
     * the system compositor (SurfaceFlinger) will blur the entire behind surface at 120 FPS.
     */
    fun applyBlur(window: Window?, blurRadius: Int = 45) {
        if (window == null) return
        if (!DataStore.blurEffectMode) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = blurRadius
                }
            } catch (_: Throwable) {
                // Graceful fallback on devices where OEM disabled cross-window blur
            }
        }
    }

    fun applyBlur(dialog: Dialog?, blurRadius: Int = 45) {
        applyBlur(dialog?.window, blurRadius)
    }
}
