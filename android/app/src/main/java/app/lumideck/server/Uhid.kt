package app.lumideck.server

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * A USB input device made up through Linux's uhid, which the shell user may use. Android
 * treats it as real hardware: [HidKeyboard] and [HidMouse] build their reports on it.
 */
internal class UhidDevice(name: String, descriptor: ByteArray) : AutoCloseable {
    private val fd: FileDescriptor = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)

    init {
        val create = ByteBuffer.allocate(CREATE_SIZE + descriptor.size).order(ByteOrder.LITTLE_ENDIAN)
        create.putInt(UHID_CREATE2)
        create.put(name.toByteArray().copyOf(128))
        create.position(create.position() + 64 + 64) // phys, uniq
        create.putShort(descriptor.size.toShort())
        create.putShort(BUS_VIRTUAL)
        create.putInt(0) // vendor
        create.putInt(0) // product
        create.putInt(0) // version
        create.putInt(0) // country
        create.put(descriptor)
        write(create)
        // The kernel queues events for us (such as the Caps Lock light); nobody needs them,
        // but they're read so the queue doesn't fill up.
        thread(name = "uhid", isDaemon = true) {
            val buffer = ByteArray(4380)
            try {
                while (Os.read(fd, buffer, 0, buffer.size) > 0) Unit
            } catch (e: ErrnoException) {
                // Closed.
            }
        }
    }

    /** Sends one input report, laid out as the descriptor says. */
    fun send(report: ByteArray) {
        val event = ByteBuffer.allocate(6 + report.size).order(ByteOrder.LITTLE_ENDIAN)
        event.putInt(UHID_INPUT2).putShort(report.size.toShort()).put(report)
        write(event)
    }

    override fun close() {
        runCatching { write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY)) }
        runCatching { Os.close(fd) }
    }

    private fun write(event: ByteBuffer) {
        event.flip()
        while (event.hasRemaining()) Os.write(fd, event)
    }

    private companion object {
        const val UHID_DESTROY = 1
        const val UHID_CREATE2 = 11
        const val UHID_INPUT2 = 12
        const val BUS_VIRTUAL: Short = 6
        /** struct uhid_create2_req up to its report descriptor, after the event type. */
        const val CREATE_SIZE = 4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4
    }
}
