package app.lumideck

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.lumideck.desktop.DesktopScreen
import app.lumideck.desktop.DesktopState
import app.lumideck.desktop.MenuLayer
import app.lumideck.desktop.MetaTap
import app.lumideck.desktop.TaskbarLayer
import app.lumideck.desktop.TaskbarOverlay
import app.lumideck.desktop.WindowDecor
import app.lumideck.desktop.Windows
import app.lumideck.ui.LumiTheme

/** The desktop shown on an external monitor or on the virtual display streamed to a PC. */
class DesktopActivity : ComponentActivity() {
    private val state = DesktopState()
    private val windows = Windows(this)
    private val overlay = TaskbarOverlay(this, onMetaTap = ::onMetaTap, onBack = state::closeMenus)
    private val decor = WindowDecor(this, overlay, state, windows)
    private val overlayShown = mutableStateOf(false)
    private val metaTap = MetaTap(::onMetaTap)
    private var lastMetaTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android gives every desktop-mode display its own navigation bar. The desktop hides
        // it (see below) and draws behind it, so the taskbar sits above it whenever Android
        // forces it back, which it does while app windows are open.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        overlay.onTaskbarResize = decor::layout

        // Back never leaves the desktop; it only closes the menus.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                state.closeMenus()
            }
        })

        setContent {
            LumiTheme {
                DesktopScreen(showShell = !overlayShown.value, state = state, windows = windows)
            }
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        // Also picks up "Display over other apps" being allowed while the desktop was open.
        overlayShown.value = overlay.show(
            taskbarContent = { LumiTheme { TaskbarLayer(state, windows, onHeightChange = overlay::setTaskbarHeight) } },
            menuContent = { onShownChange -> LumiTheme { MenuLayer(state, windows, onShownChange) } },
        )
        // Title bars need the overlay too; without it, windows keep Android's own.
        if (overlayShown.value) decor.start()
    }

    override fun onDestroy() {
        decor.stop()
        overlay.hide()
        super.onDestroy()
    }

    /** Home pressed while the desktop is already showing. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        state.closeMenus()
    }

    // This is Activity's public key-dispatch override; AndroidX marks its implementation restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Esc closes the menus, as it does in the overlay (see TaskbarOverlay).
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && (state.menuOpen || state.taskMenu != null)) {
            if (event.action == KeyEvent.ACTION_UP) state.closeMenus()
            return true
        }
        return metaTap.onKey(event) || super.dispatchKeyEvent(event)
    }

    /**
     * Tapping Meta on its own toggles the menu, like Super on Plasma. One press can arrive
     * both as a key and as the assistant signal (see [TaskbarOverlay]), so ignore repeats.
     */
    private fun onMetaTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMetaTap < 300) return
        lastMetaTap = now
        state.toggleMenu()
    }
}
