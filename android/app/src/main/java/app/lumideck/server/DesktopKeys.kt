package app.lumideck.server

import android.os.Bundle
import app.lumideck.helper.HelperProvider
import app.lumideck.helper.callLumiDeck

/**
 * Takes the desktop's own shortcuts out of the PC's keys and hands them to LumiDeck, as
 * Android would otherwise act on them (Alt+Tab opens the handheld's recent apps) or
 * give them to the app in front:
 *
 * - Alt+Tab and Alt+Shift+Tab go through the windows, and letting go of Alt picks one.
 *   Esc while Alt is held gives up.
 * - Meta+Left and Meta+Right snap the window in front to that half of the screen, Meta+Up
 *   maximises it, and Meta+Down restores it, or minimises it if it wasn't snapped.
 *
 * Meta itself only goes on once another key comes with it, or as a tap once it's let go:
 * Android takes a Meta press with nothing else for a tap, which opens LumiDeck's menu.
 */
internal class DesktopKeys(private val displayId: Int, private val keyboard: HidKeyboard) {
    private val down = BooleanArray(256)

    /** Keys that went down as a shortcut, whose release mustn't reach the keyboard either. */
    private val taken = BooleanArray(256)

    /** Meta, held back until it's known whether it's a tap, a shortcut or neither. */
    private var heldMeta = -1

    /** Meta has made a shortcut since it went down, so it's no tap. */
    private var metaUsed = false

    private var switching = false

    fun key(usage: Int, isDown: Boolean) {
        if (usage !in 0..255) return
        down[usage] = isDown
        if (!isDown && taken[usage]) {
            taken[usage] = false
            return
        }
        if (isDown && take(usage)) {
            taken[usage] = true
            return
        }
        when {
            usage == LEFT_META || usage == RIGHT_META -> meta(usage, isDown)
            usage == LEFT_ALT && !isDown && switching -> {
                switching = false
                shortcut("switch_commit")
                keyboard.key(usage, false)
            }
            else -> {
                // Meta with another key: a shortcut for Android or the app.
                if (isDown && heldMeta >= 0) releaseMeta()
                keyboard.key(usage, isDown)
            }
        }
    }

    /** Every key up, as when the PC's window loses focus. */
    fun releaseAll() {
        down.fill(false)
        taken.fill(false)
        heldMeta = -1
        metaUsed = false
        if (switching) {
            switching = false
            shortcut("switch_cancel")
        }
        keyboard.releaseAll()
    }

    /** Whether the key going down makes a shortcut, which is then done. */
    private fun take(usage: Int): Boolean {
        val alt = down[LEFT_ALT]
        val ctrl = down[LEFT_CTRL] || down[RIGHT_CTRL]
        val shift = down[LEFT_SHIFT] || down[RIGHT_SHIFT]
        val meta = down[LEFT_META] || down[RIGHT_META]
        if (usage == TAB && alt && !ctrl && !meta) {
            switching = true
            shortcut(if (shift) "switch_previous" else "switch_next")
            return true
        }
        if (usage == ESCAPE && switching) {
            switching = false
            shortcut("switch_cancel")
            return true
        }
        if (meta && !alt && !ctrl && heldMeta >= 0) {
            val name = when (usage) {
                LEFT -> "snap_left"
                RIGHT -> "snap_right"
                UP -> "maximize"
                DOWN -> "restore"
                else -> return false
            }
            metaUsed = true
            shortcut(name)
            return true
        }
        return false
    }

    private fun meta(usage: Int, isDown: Boolean) {
        if (isDown) {
            if (heldMeta < 0 && !(down[LEFT_META] && down[RIGHT_META])) {
                heldMeta = usage
                metaUsed = false
            } else {
                keyboard.key(usage, true)
            }
            return
        }
        if (heldMeta == usage) {
            heldMeta = -1
            // A tap: down and up together.
            if (!metaUsed) {
                keyboard.key(usage, true)
                keyboard.key(usage, false)
            }
        } else {
            keyboard.key(usage, false)
        }
    }

    private fun releaseMeta() {
        keyboard.key(heldMeta, true)
        heldMeta = -1
    }

    private fun shortcut(name: String) {
        try {
            callLumiDeck(HelperProvider.METHOD_SHORTCUT, name, Bundle().apply { putInt(HelperProvider.KEY_DISPLAY, displayId) })
        } catch (e: Exception) {
            System.err.println("Couldn't reach LumiDeck for $name: $e")
        }
    }

    private companion object {
        // USB HID usage codes.
        const val TAB = 0x2B
        const val ESCAPE = 0x29
        const val RIGHT = 0x4F
        const val LEFT = 0x50
        const val DOWN = 0x51
        const val UP = 0x52
        const val LEFT_CTRL = 0xE0
        const val LEFT_SHIFT = 0xE1
        const val LEFT_ALT = 0xE2
        const val LEFT_META = 0xE3
        const val RIGHT_CTRL = 0xE4
        const val RIGHT_SHIFT = 0xE5
        const val RIGHT_META = 0xE7
    }
}
