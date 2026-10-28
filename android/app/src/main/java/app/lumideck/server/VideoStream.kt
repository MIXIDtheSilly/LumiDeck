package app.lumideck.server

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The screen the desktop runs on. Android treats it like a monitor that was plugged in:
 * with desktop mode on, it gets LumiDeck's desktop and apps open on it as windows.
 */
internal class VirtualScreen(width: Int, height: Int, dpi: Int) {
    private val display: VirtualDisplay

    init {
        val manager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(Shell.context)
        display = manager.createVirtualDisplay(NAME, width, height, dpi, null, flags())
    }

    val id: Int get() = display.display.displayId

    fun setSurface(surface: android.view.Surface?) = display.setSurface(surface)

    /** Windows still open on it move to the device's own screen. */
    fun release() = display.release()

    /** Keeps the keyboard UI (and Gboard's toolbar) off it; typing still works. */
    fun hideIme() {
        val wm = Shell.service("window", "android.view.IWindowManager")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, id, DISPLAY_IME_POLICY_HIDE)
        } else {
            wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(wm, id, false)
        }
    }

    private fun flags(): Int {
        // Public and trusted, with system decorations: what makes Android put a desktop on it.
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or
            FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP
        }
        return flags
    }

    private companion object {
        const val NAME = "LumiDeck"
        const val DISPLAY_IME_POLICY_HIDE = 2

        // DisplayManager's flags that the SDK hides.
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}

/**
 * Encodes [screen] and writes it to [out]: a header, then one packet per frame (see
 * desktop/client/src/protocol.rs).
 *
 * Tuned for latency over size: the hardware encoder runs at real-time priority with no
 * frames held back, and HEVC comes first because this kind of hardware encodes it faster
 * (the RG556 manages about 65 frames a second at 1080p in HEVC and 45 in H.264).
 *
 * The RG556's encoder has two cores, and when told to expect more than 30 frames a second
 * at 1080p, it works on frames two at a time, one on each: the first of a pair waits until
 * the second comes, then both come out together, ~10 ms later. So it's told 30
 * ([Options.frameRate]): one core alone takes 7–16 ms a frame, enough for 60 a second,
 * and each frame comes out as soon as it's done. Rate control then gives each frame twice
 * the bits, which only makes the stream bigger. None of the encoder's other settings
 * change the pairing: operating rate, low latency, bit rate mode, scene mode.
 *
 * When the screen stops changing, the encoder repeats the last frame after
 * [Options.repeatAfterMs], in better quality each time. With pairs, the first repeat also
 * gets the last frame out. It goes to the PC as a copy, decoded but not shown. The delay
 * is longer than a slow game's frame: a repeat between two frames keeps the encoder busy
 * when the next one comes, which made a 30–40 fps game's frames ~12 ms later.
 */
internal class VideoStream(
    private val screen: VirtualScreen,
    private val width: Int,
    private val height: Int,
    private val options: Options,
    private val out: Sink,
    /** Called once the video's header has gone out, after which others may use [out] too. */
    private val onStarted: () -> Unit = {},
) {
    @Volatile private var codec: MediaCodec? = null
    /** The time stamp of the last frame the PC shows, which the encoder repeats. */
    private var shownPts = -1L
    private val stats = Stats()

    /** Asks for a full frame next, which the PC can start decoding from. */
    fun requestKeyFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    /** Streams until the PC goes away; throws then. */
    fun run() {
        val (codec, codecId) = open()
        this.codec = codec
        writeHeader(codecId)
        onStarted()
        // The screen is taller than the video by the navigation bar (Options.navBar).
        // SurfaceFlinger draws it into the encoder's frames from the top without scaling,
        // so the bar falls off the bottom.
        val surface = codec.createInputSurface()
        screen.setSurface(surface)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val header = ByteBuffer.allocate(PACKET_HEADER).order(ByteOrder.BIG_ENDIAN)
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, -1)
                if (index < 0) continue
                val data = codec.getOutputBuffer(index)!!
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                var ptsFlags = if (config) 0L else info.presentationTimeUs
                if (config) ptsFlags = ptsFlags or FLAG_CONFIG
                if (key) ptsFlags = ptsFlags or FLAG_KEY_FRAME
                // A repeat carries the time stamp of the frame it repeats, plus the delay.
                val copy = !config && repeating && info.presentationTimeUs == shownPts + repeatAfterUs
                if (copy) ptsFlags = ptsFlags or FLAG_COPY else if (!config) shownPts = info.presentationTimeUs
                header.clear()
                header.putLong(ptsFlags).putInt(info.size).flip()
                data.position(info.offset).limit(info.offset + info.size)
                out.write(header, data)
                if (!config) stats.frame(info.presentationTimeUs, info.size, copy)
                codec.releaseOutputBuffer(index, false)
            }
        } finally {
            screen.setSurface(null)
            runCatching { codec.stop() }
            codec.release()
            surface.release()
        }
    }

    /** The first encoder that takes the settings, by order of preference. */
    private fun open(): Pair<MediaCodec, Int> {
        val mimes = when (options.codec) {
            "h264" -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
            else -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        var error: Exception? = null
        for (mime in mimes) {
            val name = encoderFor(mime) ?: continue
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.configure(format(mime), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                println("Video: $name, ${width}x$height, ${options.bitRate / 1_000_000} Mbit/s")
                return codec to if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) CODEC_HEVC else CODEC_H264
            } catch (e: Exception) {
                codec.release()
                error = e
            }
        }
        throw IllegalStateException("No video encoder takes ${width}x$height", error)
    }

    private val repeating get() = "repeat" in options.tune
    private val repeatAfterUs get() = options.repeatAfterMs * 1000L

    private fun format(mime: String) = MediaFormat.createVideoFormat(mime, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, options.bitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, options.frameRate)
        // Full frames only when the PC asks (it starts with one), plus one every so often.
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
        val tune = options.tune
        // Sends the last frame again when the screen stops changing: the first repeat gets it
        // out of the encoder, the next ones let its quality catch up.
        if (repeating) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatAfterUs)
        if ("priority" in tune) setInteger(MediaFormat.KEY_PRIORITY, 0)
        if ("latency" in tune) setInteger(KEY_LATENCY, 0)
        if ("bframes" in tune) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if ("prepend" in tune) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        // Drops frames before the encoder rather than letting them queue up behind it.
        if (options.maxFps > 0) setFloat(KEY_MAX_FPS_TO_ENCODER, options.maxFps.toFloat())
    }

    private fun encoderFor(mime: String): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstOrNull()?.name

    private fun writeHeader(codecId: Int) {
        out.write(ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(codecId).putInt(width).putInt(height).flip() as ByteBuffer)
    }

    /** Prints how the encoder keeps up every few seconds, for the PC client's console. */
    private inner class Stats {
        private var since = System.nanoTime()
        private var frames = 0
        private var bytes = 0L
        private var latencySum = 0L
        private var latencyMax = 0L
        private var lastOut = 0L
        private val gaps = ArrayList<Long>()

        /** Copies count towards the bit rate only. */
        fun frame(ptsUs: Long, size: Int, copy: Boolean) {
            bytes += size
            if (copy) return
            val now = System.nanoTime()
            // A frame's time stamp is when Android finished drawing it.
            val latency = now / 1000 - ptsUs
            if (latency in 0..1_000_000) {
                latencySum += latency
                latencyMax = maxOf(latencyMax, latency)
            }
            if (lastOut != 0L) gaps += now - lastOut
            lastOut = now
            frames++
            val elapsed = now - since
            if (elapsed < STATS_NS) return
            if (options.stats) {
                gaps.sort()
                // How evenly the frames come out of the encoder.
                val spread = gaps.getOrElse(gaps.size * 95 / 100) { 0L } / 1e6
                println(
                    "Video: %.0f fps, %.1f Mbit/s, encoding %.1f ms (max %.1f), 95%% within %.1f ms of each other".format(
                        frames * 1e9 / elapsed, bytes * 8e3 / elapsed, latencySum / 1000.0 / frames, latencyMax / 1000.0, spread,
                    ),
                )
            }
            since = now
            frames = 0
            bytes = 0
            latencySum = 0
            latencyMax = 0
            gaps.clear()
        }
    }

    companion object {
        const val CODEC_H264 = 0x68323634 // "h264"
        const val CODEC_HEVC = 0x68323635 // "h265"
        const val FLAG_CONFIG = 1L shl 63
        const val FLAG_KEY_FRAME = 1L shl 62
        const val FLAG_COPY = 1L shl 61
        /** All three flags: not a frame but the device's clipboard text (see [ClipboardSync]). */
        const val CLIPBOARD = FLAG_CONFIG or FLAG_KEY_FRAME or FLAG_COPY
        /** Not a frame: one byte, 1 if an app took the mouse ([PointerCapture]), 0 when it lets go. */
        const val POINTER_CAPTURE = FLAG_CONFIG or FLAG_COPY
        private const val PACKET_HEADER = 12
        private const val KEY_LATENCY = "latency"
        private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"
        private const val STATS_NS = 5_000_000_000L
    }
}
