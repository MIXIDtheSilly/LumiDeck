package app.lumideck.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.SystemClock
import android.system.Os
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** What the PC client asked for, from `key=value` arguments. */
internal class Options(args: Array<String>) {
    private val values = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }

    val width = int("width", 1920)
    /** The desktop's height: what the PC shows. */
    val height = int("height", 1080)
    /**
     * Android draws its own navigation bar along the bottom of the desktop and forces it
     * on while app windows are open. The display is this much taller, and the strip with
     * the bar is left out of the video; the desktop keeps it clear, so nothing else is lost.
     */
    val navBar = int("nav_bar", 56)
    val dpi = int("dpi", 160)
    /** "hevc" (falls back to H.264 if the device has no HEVC encoder) or "h264". */
    val codec = values["codec"] ?: "hevc"
    val bitRate = int("bitrate", 15_000_000)
    val maxFps = int("max_fps", 0)
    /** The frame rate the encoder is told to expect, which needn't be the real one (see [VideoStream]). */
    val frameRate = int("frame_rate", 30)
    /** How long the screen stays still before the encoder repeats the last frame (see [VideoStream]). */
    val repeatAfterMs = int("repeat_after", 100)
    val audio = bool("audio", true)
    val stayAwake = bool("stay_awake", true)
    val stats = bool("stats", false)
    /** "adb" (sockets that the PC reaches through `adb forward`) or "usb" (see [UsbLink]). */
    val link = values["link"] ?: "adb"
    /** Tells this session's data from what an earlier one left in the USB pipe. */
    val session = values["session"] ?: ""
    /**
     * Encoder settings to use, for finding out which ones a device's encoder dislikes.
     * "prepend" is off by default: the RG556's HEVC encoder blanks the key frames it
     * prepends to.
     */
    val tune = (values["tune"] ?: "repeat,priority,latency,bframes").split(',').toSet()

    private fun int(key: String, default: Int) = values[key]?.toIntOrNull() ?: default

    private fun bool(key: String, default: Boolean) = values[key]?.toBooleanStrictOrNull() ?: default
}

/**
 * Streams a new display, with LumiDeck's desktop on it, to the PC client
 * (desktop/client), and plays the PC's mouse and keyboard on it. Runs from LumiDeck's
 * APK as ADB's shell user, started by the client:
 *
 *     CLASSPATH=<LumiDeck's APK> app_process / app.lumideck.server.StreamServer width=1920 ...
 *
 * The client reaches it through `adb forward` on the abstract socket [SOCKET]: first a
 * connection for the video one way and the controls the other, then one for the sound.
 * Or, with `link=usb`, straight over USB ([UsbLink]), all on one connection.
 * When the client goes, the display goes too, its windows move to the device's own
 * screen and this exits.
 */
object StreamServer {
    private const val SOCKET = "lumideck"
    /** The client's first check that it reached this server, and which version. */
    private val HELLO = "LumiDeck".toByteArray() + byteArrayOf(0, PROTOCOL_VERSION.toByte())
    private const val CONNECT_TIMEOUT_MS = 15_000L
    /** Over USB the PC can go without the connection closing: it sends a message every second. */
    private const val SILENCE_TIMEOUT_MS = 4_000L

    private val cleanups = ArrayList<() -> Unit>()

    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options(args)
        Shell.init()
        Runtime.getRuntime().addShutdownHook(Thread(::cleanUp))
        try {
            run(options)
        } catch (e: Throwable) {
            System.err.println("LumiDeck server stopped: $e")
            e.cause?.let { System.err.println("  caused by $it") }
        }
        cleanUp()
        exitProcess(0)
    }

    /** Where the streams go, and where the controls come from. */
    private class Link(val video: Sink, val audio: Sink?, val controls: InputStream)

    private fun run(options: Options) {
        val link = if (options.link == "usb") connectUsb(options) else connectAdb(options)
        val keyboard = try {
            HidKeyboard().also { onCleanUp(it::close) }
        } catch (e: Exception) {
            System.err.println("No keyboard: $e")
            null
        }
        val screen = VirtualScreen(options.width, options.height + options.navBar, options.dpi)
        onCleanUp(screen::release)
        println("Display ${screen.id}: ${options.width}x${options.height + options.navBar}, ${options.dpi} dpi")
        runCatching(screen::hideIme).onFailure { System.err.println("Couldn't hide the keyboard UI: $it") }
        if (options.stayAwake) stayAwake()

        val video = link.video.shared()
        val clipboard = try {
            ClipboardSync { text ->
                val data = text.toByteArray(Charsets.UTF_8)
                video.write(ByteBuffer.allocate(12).putLong(VideoStream.CLIPBOARD).putInt(data.size).flip() as ByteBuffer, ByteBuffer.wrap(data))
            }
        } catch (e: Exception) {
            System.err.println("No clipboard: $e")
            null
        }
        lateinit var capture: PointerCapture
        val stream = VideoStream(screen, options.width, options.height, options, video, onStarted = {
            runCatching { clipboard?.start() }.onFailure { System.err.println("No clipboard: $it") }
            capture.start()
        })
        val onClipboard: (String) -> Unit = { text ->
            runCatching { clipboard?.setText(text) }.onFailure { System.err.println("Couldn't set the clipboard: $it") }
        }
        val controls = Controls(link.controls, screen.id, keyboard, stream::requestKeyFrame, onClipboard)
        capture = PointerCapture(screen.id) { captured ->
            controls.captured = captured
            val header = ByteBuffer.allocate(12).putLong(VideoStream.POINTER_CAPTURE).putInt(1).flip() as ByteBuffer
            video.write(header, ByteBuffer.wrap(byteArrayOf(if (captured) 1 else 0)))
        }
        thread(name = "controls", isDaemon = true) {
            try {
                controls.run()
            } catch (e: Exception) {
                System.err.println("Controls stopped: $e")
            }
            // The PC went away.
            cleanUp()
            exitProcess(0)
        }
        if (options.link == "usb") {
            thread(name = "silence", isDaemon = true) {
                while (SystemClock.uptimeMillis() - controls.heard < SILENCE_TIMEOUT_MS) Thread.sleep(500)
                System.err.println("The PC stopped answering")
                cleanUp()
                exitProcess(0)
            }
        }
        val audio = link.audio
        if (audio != null) {
            thread(name = "audio", isDaemon = true) {
                try {
                    AudioStream(audio).run()
                } catch (e: Exception) {
                    System.err.println("No sound: $e")
                }
            }
        }
        stream.run()
    }

    private fun connectAdb(options: Options): Link {
        val server = LocalServerSocket(SOCKET)
        // Gives up if the client never comes, so a stray server doesn't hold the socket.
        val waiting = thread(isDaemon = true) {
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS)
                System.err.println("No client connected")
                exitProcess(1)
            } catch (e: InterruptedException) {
                // Connected.
            }
        }
        val video = accept(server)
        val audio = if (options.audio) accept(server) else null
        waiting.interrupt()
        server.close()
        return Link(Sink.of(video.fileDescriptor), audio?.let { Sink.of(it.fileDescriptor) }, video.inputStream)
    }

    private fun connectUsb(options: Options): Link {
        val usb = UsbLink.open()
        onCleanUp(usb::close)
        val video = usb.sink(UsbLink.VIDEO)
        // The PC skips everything before it.
        video.write(ByteBuffer.wrap(HELLO + options.session.toByteArray()))
        return Link(video, if (options.audio) usb.sink(UsbLink.AUDIO) else null, usb.input)
    }

    private fun accept(server: LocalServerSocket): LocalSocket {
        val socket = server.accept()
        Os.write(socket.fileDescriptor, ByteBuffer.wrap(HELLO))
        return socket
    }

    /** Keeps the device awake while it's plugged in, as it would sleep and blank the display. */
    private fun stayAwake() {
        val setting = "stay_on_while_plugged_in"
        val before = shell("settings", "get", "global", setting).trim()
        // Any power source: AC, USB and wireless.
        if (before == "7") return
        shell("settings", "put", "global", setting, "7")
        onCleanUp {
            if (before.toIntOrNull() != null) shell("settings", "put", "global", setting, before)
        }
    }

    private fun shell(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }

    @Synchronized
    fun onCleanUp(action: () -> Unit) {
        cleanups += action
    }

    /** Undoes what this server changed, newest first, once. */
    @Synchronized
    private fun cleanUp() {
        for (action in cleanups.asReversed()) runCatching(action)
        cleanups.clear()
    }
}

/** Bumped whenever the client and the server stop understanding each other. */
internal const val PROTOCOL_VERSION = 4
