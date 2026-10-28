package app.lumideck.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.lumideck.SettingsActivity
import app.lumideck.data.AppEntry
import app.lumideck.data.LumiPrefs
import app.lumideck.data.PanelFloat
import app.lumideck.helper.HelperLink
import app.lumideck.helper.TaskWindow
import kotlin.math.roundToInt

/**
 * The app windows on the desktop's display. Opens apps as windows, cascading each new one
 * so they don't stack exactly on top of each other. While the window helper runs (see
 * [HelperLink]), it also focuses, minimises, maximises and closes them.
 *
 * Bounds only apply when freeform windowing is enabled (see scripts/setup-device.ps1);
 * otherwise apps open fullscreen.
 */
class Windows(private val activity: Activity) {
    private val prefs by lazy { LumiPrefs(activity) }
    private var cascade = 0

    /** Where maximised windows go back to, by task id. */
    private val restoreBounds = mutableMapOf<Int, Rect>()

    /** Fullscreen windows [adopt] has made windows again, by task id. */
    private val adopted = mutableSetOf<Int>()

    val displayId: Int get() = ContextCompat.getDisplayOrDefault(activity).displayId

    /**
     * Opens [app]'s window, through the window helper while it runs: Android slides new
     * windows in from the side of the screen, and only the helper may open them without.
     */
    fun launch(app: AppEntry) {
        when {
            // LauncherApps only starts activities listed in the app drawer, and the helper
            // can't start LumiDeck's, which other apps can't.
            app.component.packageName == activity.packageName -> launchOwn(app.component)
            HelperLink.running -> HelperLink.launch(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                launchOptions(),
                app.user,
            )
            else -> activity.getSystemService(LauncherApps::class.java)
                .startMainActivity(app.component, app.user, null, launchOptions())
        }
    }

    fun openSettings() = launchOwn(ComponentName(activity, SettingsActivity::class.java))

    /** Opens one of Android's settings screens, such as [android.provider.Settings.ACTION_SETTINGS], as a window. */
    fun openSystemSettings(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (HelperLink.running) return HelperLink.launch(intent, launchOptions(), Process.myUserHandle())
        // Not every firmware has every screen.
        runCatching { activity.startActivity(intent, launchOptions()) }
    }

    private fun launchOwn(component: ComponentName) =
        activity.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), launchOptions())

    private fun launchOptions() = ActivityOptions.makeBasic()
        .setLaunchDisplayId(displayId)
        .setLaunchBounds(nextBounds())
        .toBundle()

    /** Brings [window] to the front, or minimises it if it's already there, like a taskbar click on Plasma. */
    fun activate(window: TaskWindow) {
        if (window.focused && window.visible) HelperLink.minimize(window.taskId) else HelperLink.focus(window.taskId)
    }

    fun focus(window: TaskWindow) = HelperLink.focus(window.taskId)

    fun minimize(window: TaskWindow) = HelperLink.minimize(window.taskId)

    fun close(window: TaskWindow) = HelperLink.close(window.taskId)

    /** Minimises every window in [windows] that's showing, like Plasma's "Show desktop". */
    fun showDesktop(windows: List<TaskWindow>) = windows.filter { it.visible }.forEach(::minimize)

    fun isMaximized(window: TaskWindow) = window.bounds == maximizedArea()

    /** Whether [window] fills the screen or half of it, and goes back to its own size when it's moved or restored. */
    fun isArranged(window: TaskWindow) = isMaximized(window) || Snap.entries.any { window.bounds == snapArea(it) }

    /** Maximised means filling the screen above the taskbar, which stays in view. */
    fun toggleMaximized(window: TaskWindow) = if (isMaximized(window)) restore(window) else maximize(window)

    fun maximize(window: TaskWindow) = arrange(window, maximizedArea())

    /** Fills [side]'s half of the screen with [window]. */
    fun snap(window: TaskWindow, side: Snap) = arrange(window, snapArea(side))

    /** Puts an arranged [window] back to its own size and place. */
    fun restore(window: TaskWindow) {
        if (!isArranged(window)) return
        HelperLink.resize(window.taskId, unmaximized(window))
        HelperLink.focus(window.taskId)
    }

    /** Where an arranged [window] goes back to, which it's then no longer expected to. */
    fun unmaximized(window: TaskWindow): Rect = restoreBounds.remove(window.taskId) ?: nextBounds()

    /**
     * Moves the window [taskId] to [bounds], filling the screen or half of it, and remembers
     * [restore] for when it's restored.
     */
    fun place(taskId: Int, bounds: Rect, restore: Rect) {
        restoreBounds[taskId] = restore
        HelperLink.resize(taskId, bounds)
        HelperLink.focus(taskId)
    }

    /**
     * Maximises the fullscreen windows among [shown] on this display, which have no title bar
     * and can't be moved. Android leaves windows fullscreen when it moves them over from the
     * device's own screen, as it does when a PC session ends. Once each: without freeform
     * windowing (see the class notes) they stay fullscreen. Also moves windows down that
     * start too high for their title bar.
     */
    fun adopt(shown: List<TaskWindow>) {
        val area = maximizedArea()
        if (area.isEmpty) return
        for (window in shown) {
            if (window.displayId != displayId || !window.visible) continue
            if (window.freeform) {
                // Windows Android places itself can start too high for a title bar above them.
                if (window.bounds.top < area.top) HelperLink.resize(window.taskId, Rect(window.bounds).apply { offset(0, area.top - top) })
            } else if (adopted.add(window.taskId)) {
                HelperLink.resize(window.taskId, area)
            }
        }
    }

    private fun arrange(window: TaskWindow, bounds: Rect) {
        // Windows that fill the screen whatever their bounds say can't be arranged.
        if (!window.freeform) return
        place(window.taskId, bounds, if (isArranged(window)) restoreBounds[window.taskId] ?: nextBounds() else window.bounds)
    }

    /** [side]'s half of the screen, which reaches down to the taskbar as a maximised window does. */
    fun snapArea(side: Snap): Rect {
        val area = maximizedArea()
        return when (side) {
            Snap.Left -> Rect(area.left, area.top, area.centerX(), area.bottom)
            Snap.Right -> Rect(area.centerX(), area.top, area.right, area.bottom)
        }
    }

    /** Whether [window] reaches down to where the floating taskbar sits, so the taskbar attaches to the edge. */
    fun reachesPanel(window: TaskWindow) = window.visible && window.bounds.bottom > areaAbove(attached = false).bottom

    /** Maximised windows reach down to the taskbar, which attaches to the edge for them unless it always floats. */
    fun maximizedArea() = areaAbove(attached = prefs.panelFloat != PanelFloat.Always)

    /**
     * The screen above the taskbar, [attached] to the edge or floating, below the title bars
     * when they go above windows ([titleBarsAbove]). Windows stay inside it because the
     * taskbar (and the system nav bar under it) stays on top and would otherwise cover their
     * bottom edge.
     */
    private fun areaAbove(attached: Boolean): Rect {
        // The desktop's window covers the whole screen, nav bar included, unlike displayMetrics.
        val desktop = activity.window.decorView
        val navBar = ViewCompat.getRootWindowInsets(desktop)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val density = activity.resources.displayMetrics.density
        val taskbar = (panelFootprint(prefs.taskbarSize, attached).value * density).toInt()
        val top = if (titleBarsAbove) (TitleBarHeight.value * density).roundToInt() else 0
        return Rect(0, top, desktop.width, desktop.height - navBar - taskbar)
    }

    private fun nextBounds(): Rect {
        val density = activity.resources.displayMetrics.density
        val area = areaAbove(attached = false)
        val width = (area.width() * 0.6f).toInt()
        val height = (area.height() * 0.8f).toInt()
        val step = (32 * density).toInt()
        val steps = 6
        val i = cascade++ % steps

        val left = (area.width() - width) / 2 - step * (steps / 2) + step * i
        val top = area.top + (area.height() - height) / 2 - step * (steps / 2) + step * i
        return Rect(left, top, left + width, top + height).apply {
            offset(maxOf(0, -this.left), maxOf(0, area.top - this.top))
        }
    }
}

/** Which half of the screen a window snaps to. */
enum class Snap { Left, Right }
