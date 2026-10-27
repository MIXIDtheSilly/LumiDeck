package app.lumideck.server

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.system.Os
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures what the device plays and writes it to [out], uncompressed: USB has the
 * bandwidth, and it saves the device the work. Android stops playing the sound itself
 * while it's captured, and starts again afterwards.
 */
internal class AudioStream(private val out: FileDescriptor) {
    /** Streams until the PC goes away; throws then. */
    @SuppressLint("MissingPermission") // The shell user has it.
    fun run() {
        Shell.fillApp()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(format)
            .setBufferSizeInBytes(CHUNK * 8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setContext(Shell.context)
        val record = builder.build()
        try {
            record.startRecording()
            write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(SAMPLE_RATE).putInt(CHANNELS).flip() as ByteBuffer)
            // Each chunk goes out after its size.
            val chunk = ByteBuffer.allocateDirect(4 + CHUNK).order(ByteOrder.BIG_ENDIAN)
            chunk.position(4)
            val samples = chunk.slice()
            while (true) {
                val read = record.read(samples, CHUNK)
                if (read < 0) throw IllegalStateException("Audio capture failed: $read")
                chunk.putInt(0, read)
                chunk.position(0).limit(4 + read)
                write(chunk)
            }
        } finally {
            record.release()
        }
    }

    private fun write(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) Os.write(out, buffer)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        /** 10 ms of sound. */
        const val CHUNK = SAMPLE_RATE / 100 * CHANNELS * 2
    }
}
