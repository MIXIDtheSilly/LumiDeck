package app.lumideck.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.pm.LauncherApps
import android.graphics.Rect
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.lumideck.data.AppEntry
import app.lumideck.ui.LumiDimens

/**
 * Opens apps as windows on the desktop's display, cascading each new window so they
 * don't stack exactly on top of each other. Bounds only apply when freeform windowing
 * is enabled (see scripts/setup-device.ps1); otherwise apps open fullscreen.
 */
class WindowPlacer(private val activity: Activity) {
    private var cascade = 0

    fun launch(app: AppEntry) {
        val display = ContextCompat.getDisplayOrDefault(activity)
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(display.displayId)
            .setLaunchBounds(nextBounds())
        activity.getSystemService(LauncherApps::class.java)
            .startMainActivity(app.component, app.user, null, options.toBundle())
    }

    private fun nextBounds(): Rect {
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        // Open windows clear of the taskbar (and the system nav bar under it), which
        // stays on top and would otherwise cover their bottom edge.
        val navBar = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val reserved = navBar + ((LumiDimens.TaskbarHeight.value + LumiDimens.TaskbarMargin.value * 2) * density).toInt()
        val usableH = metrics.heightPixels - reserved

        val width = (screenW * 0.6f).toInt()
        val height = (usableH * 0.8f).toInt()
        val step = (32 * density).toInt()
        val steps = 6
        val i = cascade++ % steps

        val left = (screenW - width) / 2 - step * (steps / 2) + step * i
        val top = (usableH - height) / 2 - step * (steps / 2) + step * i
        return Rect(left, top, left + width, top + height).apply {
            offset(maxOf(0, -this.left), maxOf(0, -this.top))
        }
    }
}
