package app.lumideck.desktop

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.lumideck.helper.HelperLink
import app.lumideck.ui.LumiColors
import app.lumideck.ui.LumiLogo
import app.lumideck.ui.LumiTheme

/**
 * The handheld's own screen while the desktop is on a monitor plugged into it: a touchpad
 * for the desktop's pointer ([TouchpadView]), a bar of window buttons above it and click
 * buttons below, like a laptop's.
 *
 * An overlay window rather than an activity: it mustn't take the keyboard's focus from the
 * desktop when touched, and an activity whose window can't take focus leaves keys sent to
 * the handheld's screen with nowhere to go, until Android gives up on LumiDeck (an ANR).
 */
class TouchpadOverlay(private val activity: Activity, private val onShortcut: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val switchCommit = Runnable { onShortcut("switch_commit") }
    private val owner = AlwaysResumed()
    private var root: FrameLayout? = null
    private var windowManager: WindowManager? = null

    /** Shows it on the handheld's screen. Needs "Display over other apps", as the taskbar does. */
    fun show() {
        if (root != null) return
        val handheld = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val context = activity.createDisplayContext(handheld).let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null) else it
        }
        val manager = context.getSystemService(WindowManager::class.java)
        val view = FrameLayout(context).apply {
            addView(ComposeView(context).apply { setContent { LumiTheme { TouchpadScreen(onShortcut, ::switchWindows, ::back) } } })
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: touching it leaves the keyboard on the desktop.
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            title = "LumiDeck touchpad"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        try {
            manager.addView(view, params)
        } catch (e: RuntimeException) {
            // No permission to draw over other apps.
            return
        }
        root = view
        windowManager = manager
    }

    fun hide() {
        handler.removeCallbacks(switchCommit)
        root?.let { runCatching { windowManager?.removeView(it) } }
        root = null
        windowManager = null
    }

    /** Like Alt+Tab: each tap steps on, and the window picked comes up once the taps stop. */
    private fun switchWindows() {
        onShortcut("switch_next")
        handler.removeCallbacks(switchCommit)
        handler.postDelayed(switchCommit, SWITCH_COMMIT_MS)
    }

    /** Esc, which Android turns into Back when the app doesn't use it. */
    private fun back() {
        HelperLink.key(KEY_ESCAPE, true)
        HelperLink.key(KEY_ESCAPE, false)
    }

    companion object {
        private const val SWITCH_COMMIT_MS = 900L
        private const val KEY_ESCAPE = 0x29
        /** Display.TYPE_EXTERNAL, a monitor plugged in, which the SDK hides. */
        private const val TYPE_EXTERNAL = 2

        /** Whether [display] is a monitor plugged into the device, not a PC's virtual display. */
        fun isMonitor(display: Display): Boolean = try {
            Display::class.java.getMethod("getType").invoke(display) == TYPE_EXTERNAL
        } catch (e: ReflectiveOperationException) {
            false
        }

        fun isMonitor(context: Context, displayId: Int): Boolean =
            context.getSystemService(DisplayManager::class.java).getDisplay(displayId)?.let(::isMonitor) == true
    }
}

@Composable
private fun TouchpadScreen(shortcut: (String) -> Unit, switchWindows: () -> Unit, back: () -> Unit) {
    val helper = HelperLink.windows.collectAsState().value != null
    var pad by remember { mutableStateOf<TouchpadView?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(LumiColors.Night)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(onClick = { shortcut("menu") }) { LumiLogo(size = 28.dp) }
            PadButton(onClick = switchWindows) { Label("Windows") }
            PadButton(onClick = { shortcut("fullscreen") }) { Label("Fullscreen") }
            PadButton(onClick = { shortcut("toggle_maximize") }) { Label("Maximize") }
            PadButton(onClick = { shortcut("minimize") }) { Label("Minimize") }
            PadButton(onClick = { shortcut("close") }) { Label("Close") }
            PadButton(onClick = back) { Label("Back") }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(PadShape)
                .background(LumiColors.Slate)
                .border(1.dp, LumiColors.Teal, PadShape),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(factory = { TouchpadView(it).also { view -> pad = view } }, modifier = Modifier.fillMaxSize())
            if (!helper) {
                Text(
                    "The touchpad needs LumiDeck's helper.\nConnect the device to the LumiDeck app on your PC once.",
                    color = LumiColors.TextDim,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldButton(onPress = { pad?.setButton(0, it) })
            HoldButton(onPress = { pad?.setButton(1, it) })
        }
    }
}

/** A window button: acts on the desktop's front window, as its keyboard shortcut would. */
@Composable
private fun RowScope.PadButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .clip(ButtonShape)
            .background(if (pressed) LumiColors.Teal else LumiColors.Slate)
            .pointerInput(onClick) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A click button under the pad: held down for as long as it's touched, to drag with. */
@Composable
private fun RowScope.HoldButton(onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .clip(ButtonShape)
            .background(if (pressed) LumiColors.Teal else LumiColors.Slate)
            .pointerInput(onPress) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPress(true)
                    // Held until this finger lifts, whatever the other hand does on the pad.
                    while (true) {
                        val finger = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (finger != null && !finger.pressed) break
                    }
                    pressed = false
                    onPress(false)
                }
            },
    )
}

@Composable
private fun Label(text: String) = Text(text, color = LumiColors.Text, fontSize = 14.sp)

private val PadShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(12.dp)
