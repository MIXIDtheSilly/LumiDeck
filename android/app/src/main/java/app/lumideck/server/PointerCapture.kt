package app.lumideck.server

import android.os.SystemClock
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Tells [onChange] when an app on [displayId] takes the mouse for itself, as games do to
 * turn the camera with it (pointer capture), and when it lets go. The app then only gets
 * the mouse's movements, not where it is.
 *
 * Android has no way to ask but `dumpsys input`, which takes ~30 ms and holds up input
 * meanwhile, so it's only asked when Android logs an app asking for the mouse or letting
 * it go, and every [RECHECK_MS] while an app has it: Android takes it back without a word
 * when the app's window loses focus.
 */
internal class PointerCapture(private val displayId: Int, private val onChange: (Boolean) -> Unit) {
    private val lock = Object()
    private var captured = false
    /** A log line asked for a look. The first is for an app that had the mouse before this started. */
    private var asked = true

    fun start() {
        thread(name = "capture log", isDaemon = true) { watchLog() }
        thread(name = "capture", isDaemon = true) { run() }
    }

    private fun watchLog() {
        // From now on: InputDispatcher's lines only.
        val process = ProcessBuilder("logcat", "-v", "brief", "-T", "1", "-s", "InputDispatcher:I")
            .redirectErrorStream(true).start()
        StreamServer.onCleanUp(process::destroy)
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                // Games ask again and again while they have it, so asking only counts when it would change something.
                val enable = "Request to enable Pointer Capture" in line
                val disable = "Request to disable Pointer Capture" in line
                if (enable || disable) {
                    synchronized(lock) {
                        if (enable != captured) {
                            asked = true
                            lock.notifyAll()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // The clean-up ending logcat cuts the reading short.
        }
    }

    private fun run() {
        while (true) {
            synchronized(lock) {
                if (!asked) lock.wait(if (captured) RECHECK_MS else 0L)
                asked = false
            }
            // The app's request is logged before it's carried out.
            SystemClock.sleep(SETTLE_MS)
            val now = try {
                capturedHere()
            } catch (e: Exception) {
                System.err.println("Couldn't check for pointer capture: $e")
                continue
            }
            val changed = synchronized(lock) { (now != captured).also { captured = now } }
            if (changed) onChange(now)
        }
    }

    /** Whether the window with the mouse is the one with focus on this display. */
    private fun capturedHere(): Boolean {
        val process = ProcessBuilder("dumpsys", "input").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        val holder = lines.map { it.trim() }.firstOrNull { it.startsWith(HOLDER) }?.removePrefix(HOLDER)
        if (holder == null || holder == "None") return false
        // "FocusedWindows:" and under it, a "displayId=N, name='…'" line per display.
        val focused = lines.dropWhile { it.trim() != "FocusedWindows:" }.drop(1)
            .takeWhile { it.trim().startsWith("displayId=") }
            .firstOrNull { it.trim().startsWith("displayId=$displayId,") }
            ?: return false
        return focused.substringAfter("name='").substringBeforeLast('\'') == holder
    }

    private companion object {
        const val RECHECK_MS = 2_000L
        const val SETTLE_MS = 30L
        const val HOLDER = "Current Window with Pointer Capture: "
    }
}
