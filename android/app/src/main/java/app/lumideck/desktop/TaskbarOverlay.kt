package app.lumideck.desktop

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.lumideck.data.LumiPrefs
import kotlin.math.ceil

/**
 * Shows the taskbar and the app menu in windows of their own that stay above app windows,
 * so a maximised app can't cover them. Needs "Display over other apps", which
 * scripts/setup-device.ps1 grants; without it the desktop draws them itself.
 *
 * The taskbar window is only as tall as the taskbar and never takes keyboard focus, so
 * apps keep working around it. The menu window covers the screen, for typing and so a
 * click outside the menu closes it, and is hidden while the menu is closed. Two fixed
 * windows rather than one that grows: Android can't resize an overlay without drawing a
 * frame in the wrong place.
 */
class TaskbarOverlay(
    private val activity: Activity,
    /** Meta tapped on its own. */
    private val onMetaTap: () -> Unit,
    private val onBack: () -> Unit,
) {
    private val windowManager: WindowManager get() = activity.windowManager
    private val owner = AlwaysResumed()
    private var taskbar: View? = null
    private var menu: View? = null
    private var taskbarHeight = Dp.Unspecified

    /**
     * Shows [taskbarContent] and [menuContent] above app windows. [menuContent] reports
     * through its argument whether it has anything to show. Returns false if LumiDeck may
     * not draw over other apps.
     */
    fun show(
        taskbarContent: @Composable () -> Unit,
        menuContent: @Composable (onShownChange: (Boolean) -> Unit) -> Unit,
    ): Boolean {
        if (taskbar != null) return true
        if (!Settings.canDrawOverlays(activity)) return false
        if (taskbarHeight == Dp.Unspecified) taskbarHeight = panelFootprint(LumiPrefs(activity).taskbarSize, attached = false)

        val taskbar = TaskbarRoot().host { taskbarContent() }
        val menu = MenuRoot().host { menuContent(::setMenuShown) }
        menu.visibility = View.GONE
        try {
            windowManager.addView(taskbar, taskbarParams())
            this.taskbar = taskbar
            windowManager.addView(menu, menuParams())
            this.menu = menu
        } catch (e: RuntimeException) {
            // The permission was revoked since the check, or the display is going away.
            hide()
            return false
        }
        hideSystemBars(taskbar)
        hideSystemBars(menu)
        return true
    }

    fun hide() {
        taskbar?.let(windowManager::removeView)
        menu?.let(windowManager::removeView)
        taskbar = null
        menu = null
    }

    /**
     * Fits the taskbar window to the taskbar, which takes [height] above the navigation bar.
     * The taskbar is drawn along the window's bottom edge and the window is anchored there,
     * so it stays put on screen even if the window resizes a frame late.
     */
    fun setTaskbarHeight(height: Dp) {
        if (height == taskbarHeight) return
        taskbarHeight = height
        taskbar?.let { if (it.isAttachedToWindow) windowManager.updateViewLayout(it, taskbarParams()) }
    }

    private fun setMenuShown(shown: Boolean) {
        menu?.visibility = if (shown) View.VISIBLE else View.GONE
    }

    /**
     * The taskbar window overlaps the system navigation bar, which the taskbar sits above,
     * so it's that much taller. The bar's height comes from the desktop's full-screen window:
     * the taskbar window's own insets only count the part of the bar it already overlaps.
     */
    private fun taskbarParams(): LayoutParams {
        val density = activity.resources.displayMetrics.density
        val navBar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
        } else {
            0
        }
        val height = ceil(taskbarHeight.value * density).toInt() + navBar
        return overlayParams(height, LayoutParams.FLAG_NOT_FOCUSABLE).apply { title = "LumiDeck taskbar" }
    }

    private fun menuParams() = overlayParams(LayoutParams.MATCH_PARENT, 0).apply { title = "LumiDeck menu" }

    private fun overlayParams(height: Int, flags: Int) = LayoutParams(
        LayoutParams.MATCH_PARENT,
        height,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM
        softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        // Stay put when the navigation bar comes and goes; the content pads itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }

    private fun hideSystemBars(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
    }

    private fun FrameLayout.host(content: @Composable () -> Unit) = apply {
        addView(ComposeView(context).apply { setContent(content) })
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    private inner class TaskbarRoot : FrameLayout(activity) {
        /**
         * Android 13 keeps a lone Meta press from apps and starts the assistant instead,
         * which closes system dialogs with this reason. With no assistant set, that's the
         * only sign of the press, so treat it as the Meta tap. Only the taskbar window
         * listens, since every window gets it.
         *
         * Overrides View.onCloseSystemDialogs, which the SDK hides: hence no `override`, and
         * @Keep so R8 doesn't strip it as unused.
         */
        @Keep
        fun onCloseSystemDialogs(reason: String?) {
            if (reason == "assist") onMetaTap()
        }
    }

    /** Gets the keys while a menu is open. Back and Esc close it. */
    private inner class MenuRoot : FrameLayout(activity) {
        private val metaTap = MetaTap(onMetaTap)

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return metaTap.onKey(event) || super.dispatchKeyEvent(event)
        }
    }

    /**
     * Compose stops drawing when its lifecycle stops, and the desktop activity stops as
     * soon as an app covers it. The taskbar has to keep running, so it gets its own.
     */
    private class AlwaysResumed : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }
}
