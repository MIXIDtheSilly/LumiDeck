package app.lumideck.desktop

import android.view.KeyEvent

/** Spots Meta being pressed and released on its own, like tapping Super on Plasma. */
class MetaTap(private val onTap: () -> Unit) {
    /** True while a Meta key is held and no other key has been pressed with it. */
    private var alone = false

    /** Returns true if it used [event]. */
    fun onKey(event: KeyEvent): Boolean {
        val isMeta = event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT
        when {
            isMeta && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> alone = true
            isMeta && event.action == KeyEvent.ACTION_UP -> {
                if (alone) onTap()
                alone = false
                return true
            }
            !isMeta && event.action == KeyEvent.ACTION_DOWN -> alone = false
        }
        return false
    }
}
