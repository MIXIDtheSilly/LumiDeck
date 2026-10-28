package app.lumideck.server

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** Where one of the server's streams goes. Each write is one whole message. */
internal fun interface Sink {
    fun write(vararg buffers: ByteBuffer)

    companion object {
        /** A stream with a connection of its own. */
        fun of(fd: FileDescriptor) = Sink { buffers ->
            for (buffer in buffers) while (buffer.hasRemaining()) Os.write(fd, buffer)
        }
    }
}

/**
 * The connection to the PC straight over USB, without ADB in between: Android's accessory
 * mode (AOA), which the PC switches the device to (see desktop/client/src/usb.rs).
 *
 * One pipe each way carries everything. Going to the PC, each message is tagged with its
 * stream's channel and its size, and goes in one write, so it ends a USB transfer and the
 * PC gets it at once. Coming from the PC there are only the controls.
 */
internal class UsbLink private constructor(private val file: ParcelFileDescriptor) {
    val input: InputStream = FileInputStream(file.fileDescriptor)
    private var buffer = ByteBuffer.allocateDirect(1 shl 20)

    fun sink(channel: Int) = Sink { buffers -> write(channel, buffers) }

    @Synchronized
    private fun write(channel: Int, buffers: Array<out ByteBuffer>) {
        val size = buffers.sumOf { it.remaining() }
        if (buffer.capacity() < 2 * CHANNEL_HEADER + size) buffer = ByteBuffer.allocateDirect((2 * CHANNEL_HEADER + size) * 2)
        buffer.clear()
        buffer.put(channel.toByte()).putInt(size)
        for (b in buffers) buffer.put(b)
        // A transfer ends with a packet shorter than the rest (512 bytes or 1024), and one
        // that fills its last packet might wait for the next message: an empty one ends it.
        if (buffer.position() % 512 == 0) buffer.put(PADDING.toByte()).putInt(0)
        buffer.flip()
        while (buffer.hasRemaining()) Os.write(file.fileDescriptor, buffer)
    }

    fun close() = file.close()

    companion object {
        const val VIDEO = 0
        const val AUDIO = 1
        /** Nothing, which the PC skips. */
        private const val PADDING = 2
        private const val CHANNEL_HEADER = 5
        /** What the PC calls itself when it switches the device over. */
        private const val MANUFACTURER = "LumiDeck"
        private const val MODEL = "LumiDeck stream"

        fun open(): UsbLink = Shell.notSystem {
            val type = Class.forName("android.hardware.usb.IUsbManager")
            val usb = Shell.service(Context.USB_SERVICE, type.name)
            val accessory = type.getMethod("getCurrentAccessory").invoke(usb) as UsbAccessory?
            if (accessory == null || accessory.manufacturer != MANUFACTURER || accessory.model != MODEL) {
                throw IllegalStateException("The PC hasn't switched the device's USB over to LumiDeck")
            }
            // Apps ask the user for this; the shell user may give it to itself.
            type.getMethod("grantAccessoryPermission", UsbAccessory::class.java, Int::class.javaPrimitiveType)
                .invoke(usb, accessory, Process.myUid())
            val file = type.getMethod("openAccessory", UsbAccessory::class.java).invoke(usb, accessory) as ParcelFileDescriptor?
                ?: throw IllegalStateException("Couldn't open the USB connection")
            UsbLink(file)
        }
    }
}
