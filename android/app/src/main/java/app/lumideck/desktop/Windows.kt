package app.lumideck.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.pm.LauncherApps
import android.graphics.Rect
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.lumideck.data.AppEntry
import app.lumideck.helper.HelperLink
import app.lumideck.helper.TaskWindow
import app.lumideck.ui.LumiDimens

/**
 * The app windows on the desktop's display. Opens apps as windows, cascading each new one
 * so they don't stack exactly on top of each other. While the window helper runs (see
 * [HelperLink]), it also focuses, minimises, maximises and closes them.
 *
 * Bounds only apply when freeform windowing is enabled (see scripts/setup-device.ps1);
 * otherwise apps open fullscreen.
 */
class Windows(private val activity: Activity) {
    private var cascade = 0

    /** Where maximised windows go back to, by task id. */
    private val restoreBounds = mutableMapOf<Int, Rect>()

    val displayId: Int get() = ContextCompat.getDisplayOrDefault(activity).displayId

    fun launch(app: AppEntry) {
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(displayId)
            .setLaunchBounds(nextBounds())
        activity.getSystemService(LauncherApps::class.java)
            .startMainActivity(app.component, app.user, null, options.toBundle())
    }

    /** Brings [window] to the front, or minimises it if it's already there, like a taskbar click on Plasma. */
    fun activate(window: TaskWindow) {
        if (window.focused && window.visible) HelperLink.minimize(window.taskId) else HelperLink.focus(window.taskId)
    }

    fun minimize(window: TaskWindow) = HelperLink.minimize(window.taskId)

    fun close(window: TaskWindow) = HelperLink.close(window.taskId)

    fun isMaximized(window: TaskWindow) = window.bounds == workArea()

    /** Maximised means filling the screen above the taskbar, which stays in view. */
    fun toggleMaximized(window: TaskWindow) {
        val bounds = if (isMaximized(window)) {
            restoreBounds.remove(window.taskId) ?: nextBounds()
        } else {
            restoreBounds[window.taskId] = window.bounds
            workArea()
        }
        HelperLink.resize(window.taskId, bounds)
        HelperLink.focus(window.taskId)
    }

    /**
     * The screen above the taskbar. Windows stay inside it because the taskbar (and the
     * system nav bar under it) stays on top and would otherwise cover their bottom edge.
     */
    private fun workArea(): Rect {
        // The desktop's window covers the whole screen, nav bar included, unlike displayMetrics.
        val desktop = activity.window.decorView
        val navBar = ViewCompat.getRootWindowInsets(desktop)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val density = activity.resources.displayMetrics.density
        val taskbar = ((LumiDimens.TaskbarHeight.value + LumiDimens.TaskbarMargin.value * 2) * density).toInt()
        return Rect(0, 0, desktop.width, desktop.height - navBar - taskbar)
    }

    private fun nextBounds(): Rect {
        val density = activity.resources.displayMetrics.density
        val area = workArea()
        val width = (area.width() * 0.6f).toInt()
        val height = (area.height() * 0.8f).toInt()
        val step = (32 * density).toInt()
        val steps = 6
        val i = cascade++ % steps

        val left = (area.width() - width) / 2 - step * (steps / 2) + step * i
        val top = (area.height() - height) / 2 - step * (steps / 2) + step * i
        return Rect(left, top, left + width, top + height).apply {
            offset(maxOf(0, -this.left), maxOf(0, -this.top))
        }
    }
}
