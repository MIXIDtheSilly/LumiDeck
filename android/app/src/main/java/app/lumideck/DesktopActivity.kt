package app.lumideck

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.lumideck.desktop.DesktopScreen
import app.lumideck.desktop.WindowPlacer
import app.lumideck.ui.LumiTheme

/** The desktop shown on an external monitor or on the virtual display streamed to a PC. */
class DesktopActivity : ComponentActivity() {
    private val menuOpen = mutableStateOf(false)
    private val windows = WindowPlacer(this)

    /** True while a Meta key is held and no other key has been pressed with it. */
    private var metaAlone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android gives every desktop-mode display its own navigation bar, and shows it
        // whenever another app's window has focus. Hiding it here would only make the
        // taskbar jump, so the desktop draws behind it and the pill sits just above it.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Back never leaves the desktop; it only closes the menu.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                menuOpen.value = false
            }
        })

        setContent {
            LumiTheme {
                DesktopScreen(
                    menuOpen = menuOpen.value,
                    onMenuOpenChange = { menuOpen.value = it },
                    onLaunch = windows::launch,
                )
            }
        }
    }

    /** Home pressed while the desktop is already showing. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        menuOpen.value = false
    }

    /** Tapping Meta on its own toggles the menu, like Super on Plasma. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isMeta = event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT
        when {
            isMeta && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> metaAlone = true
            isMeta && event.action == KeyEvent.ACTION_UP -> {
                if (metaAlone) menuOpen.value = !menuOpen.value
                metaAlone = false
                return true
            }
            !isMeta && event.action == KeyEvent.ACTION_DOWN -> metaAlone = false
        }
        return super.dispatchKeyEvent(event)
    }
}
