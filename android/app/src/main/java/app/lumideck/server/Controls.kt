package app.lumideck.server

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Reads the PC's mouse and keyboard from [input] (see desktop/client/src/protocol.rs)
 * and plays them on the display. Returns when the PC goes away.
 */
internal class Controls(
    private val input: InputStream,
    private val displayId: Int,
    private val keyboard: HidKeyboard?,
    private val onKeyFrameRequest: () -> Unit,
    private val onClipboard: (String) -> Unit,
) {
    private val injector = Injector()
    private val keys = keyboard?.let { DesktopKeys(displayId, it) }
    /** Android's mouse buttons that are down. */
    private var buttons = 0
    private var downTime = 0L
    private var x = 0f
    private var y = 0f
    /** When the last message came, by [SystemClock.uptimeMillis]: the PC sends one at least every second. */
    @Volatile var heard = SystemClock.uptimeMillis()
        private set

    /**
     * Whether an app has taken the mouse ([PointerCapture]). It then gets the mouse's
     * movements, clicks and wheel as a captured mouse's, and where the pointer is means nothing.
     */
    @Volatile var captured = false

    fun run() {
        val data = DataInputStream(input.buffered())
        try {
            while (true) {
                val type = data.readUnsignedByte()
                heard = SystemClock.uptimeMillis()
                when (type) {
                    MOUSE_MOVE -> move(data.readInt(), data.readInt())
                    MOUSE_RELATIVE -> if (captured) mouse(MotionEvent.ACTION_MOVE, dx = data.readInt().toFloat(), dy = data.readInt().toFloat()) else data.skipBytes(8)
                    MOUSE_BUTTON -> button(data.readUnsignedByte(), data.readBoolean(), data.readInt(), data.readInt())
                    MOUSE_SCROLL -> scroll(data.readInt(), data.readInt(), data.readFloat(), data.readFloat())
                    MOUSE_LEAVE -> if (buttons == 0 && !captured) mouse(MotionEvent.ACTION_HOVER_EXIT)
                    KEY -> key(data.readUnsignedByte(), data.readBoolean())
                    RELEASE_KEYS -> keys?.releaseAll()
                    KEY_FRAME -> onKeyFrameRequest()
                    PING -> {}
                    CLIPBOARD -> {
                        val text = ByteArray(data.readInt()).also(data::readFully)
                        onClipboard(String(text, Charsets.UTF_8))
                    }
                    else -> throw IllegalStateException("Unknown control message $type")
                }
            }
        } catch (e: EOFException) {
            // The PC disconnected.
        }
    }

    private fun move(x: Int, y: Int) {
        this.x = x.toFloat()
        this.y = y.toFloat()
        if (captured) return
        mouse(if (buttons == 0) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_MOVE)
    }

    private fun button(button: Int, down: Boolean, x: Int, y: Int) {
        this.x = x.toFloat()
        this.y = y.toFloat()
        // The side buttons go back and forward, which Android only does for real mice.
        if (button == BUTTON_BACK || button == BUTTON_FORWARD) {
            injector.key(if (button == BUTTON_BACK) KeyEvent.KEYCODE_BACK else KeyEvent.KEYCODE_FORWARD, down, displayId)
            return
        }
        val android = when (button) {
            BUTTON_PRIMARY -> MotionEvent.BUTTON_PRIMARY
            BUTTON_SECONDARY -> MotionEvent.BUTTON_SECONDARY
            BUTTON_MIDDLE -> MotionEvent.BUTTON_TERTIARY
            else -> return
        }
        if (down == (buttons and android != 0)) return
        // As a real mouse reports it: down, then which button; which button, then up.
        if (down) {
            if (buttons == 0) {
                downTime = SystemClock.uptimeMillis()
                buttons = android
                mouse(MotionEvent.ACTION_DOWN)
            } else {
                buttons = buttons or android
            }
            mouse(MotionEvent.ACTION_BUTTON_PRESS, actionButton = android)
        } else {
            buttons = buttons and android.inv()
            mouse(MotionEvent.ACTION_BUTTON_RELEASE, actionButton = android)
            if (buttons == 0) mouse(MotionEvent.ACTION_UP)
        }
    }

    private fun scroll(x: Int, y: Int, horizontal: Float, vertical: Float) {
        this.x = x.toFloat()
        this.y = y.toFloat()
        mouse(MotionEvent.ACTION_SCROLL, horizontal = horizontal, vertical = vertical)
    }

    private fun key(usage: Int, down: Boolean) {
        keys?.key(usage, down)
    }

    /** Plays a mouse event: where the pointer is, or with an app holding the mouse, how far it moved ([dx], [dy]). */
    private fun mouse(action: Int, actionButton: Int = 0, horizontal: Float = 0f, vertical: Float = 0f, dx: Float = 0f, dy: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        val relative = captured
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            if (relative) {
                // As a captured mouse reports it: the movement, both as the position and on its own axes.
                x = dx
                y = dy
                setAxisValue(MotionEvent.AXIS_RELATIVE_X, dx)
                setAxisValue(MotionEvent.AXIS_RELATIVE_Y, dy)
            } else {
                x = this@Controls.x
                y = this@Controls.y
            }
            pressure = if (buttons != 0) 1f else 0f
            if (horizontal != 0f) setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            if (vertical != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        }
        val event = MotionEvent.obtain(
            if (buttons != 0 || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_BUTTON_RELEASE) downTime else now,
            now,
            action,
            1,
            arrayOf(properties),
            arrayOf(coords),
            keyboard?.metaState ?: 0,
            buttons,
            1f,
            1f,
            0,
            0,
            if (relative) InputDevice.SOURCE_MOUSE_RELATIVE else InputDevice.SOURCE_MOUSE,
            0,
        )
        if (actionButton != 0) injector.setActionButton(event, actionButton)
        injector.inject(event, displayId)
        event.recycle()
    }

    private companion object {
        // Messages, by their first byte.
        const val MOUSE_MOVE = 1
        const val MOUSE_BUTTON = 2
        const val MOUSE_SCROLL = 3
        const val MOUSE_LEAVE = 4
        const val KEY = 5
        const val RELEASE_KEYS = 6
        const val KEY_FRAME = 7
        const val PING = 8
        const val CLIPBOARD = 9
        const val MOUSE_RELATIVE = 10

        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2
        const val BUTTON_MIDDLE = 3
        const val BUTTON_BACK = 4
        const val BUTTON_FORWARD = 5
    }
}

/** Android's input injection, which the SDK hides and only the shell user (or the system) may use. */
private class Injector {
    private val manager: Any
    private val inject: java.lang.reflect.Method
    private val setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    private val setActionButton = MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)

    init {
        // Android 14 moved it to InputManagerGlobal.
        val type = try {
            Class.forName("android.hardware.input.InputManagerGlobal")
        } catch (e: ClassNotFoundException) {
            Class.forName("android.hardware.input.InputManager")
        }
        manager = type.getMethod("getInstance").invoke(null)!!
        inject = type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    fun inject(event: InputEvent, displayId: Int) {
        setDisplayId.invoke(event, displayId)
        inject.invoke(manager, event, INJECT_ASYNC)
    }

    fun setActionButton(event: MotionEvent, button: Int) {
        setActionButton.invoke(event, button)
    }

    fun key(code: Int, down: Boolean, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        inject(event, displayId)
    }

    private companion object {
        const val INJECT_ASYNC = 0
    }
}
