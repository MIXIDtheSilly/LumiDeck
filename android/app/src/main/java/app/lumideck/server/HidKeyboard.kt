package app.lumideck.server

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * A USB keyboard made up through Linux's uhid, which the shell user may use. Android
 * treats it as a real keyboard, so it applies the keyboard layout picked for it in
 * Settings, the Meta key works, and the on-screen keyboard stays away. The PC sends
 * which keys are down, by their USB (HID) codes; this turns them into the keyboard's
 * reports.
 */
internal class HidKeyboard : AutoCloseable {
    private val fd: FileDescriptor = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
    /** Ctrl, Shift, Alt and Meta, left and right: one bit each. */
    private var modifiers = 0
    private val keys = ArrayList<Int>(6)

    init {
        val name = NAME.toByteArray()
        val create = ByteBuffer.allocate(CREATE_SIZE + DESCRIPTOR.size).order(ByteOrder.LITTLE_ENDIAN)
        create.putInt(UHID_CREATE2)
        create.put(name.copyOf(128))
        create.position(create.position() + 64 + 64) // phys, uniq
        create.putShort(DESCRIPTOR.size.toShort())
        create.putShort(BUS_VIRTUAL)
        create.putInt(0) // vendor
        create.putInt(0) // product
        create.putInt(0) // version
        create.putInt(0) // country
        create.put(DESCRIPTOR)
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

    /** The meta state that goes with these keys, for clicks such as Ctrl+click. */
    @get:Synchronized
    val metaState: Int
        get() {
            var state = 0
            for ((bit, meta) in META.withIndex()) if (modifiers and (1 shl bit) != 0) state = state or meta
            return state
        }

    @Synchronized
    fun key(usage: Int, down: Boolean) {
        if (usage in MODIFIER_FIRST..MODIFIER_LAST) {
            val bit = 1 shl (usage - MODIFIER_FIRST)
            modifiers = if (down) modifiers or bit else modifiers and bit.inv()
        } else if (down) {
            if (usage in keys) return
            // Six at once at most, like a real keyboard.
            if (keys.size == 6) keys.removeAt(0)
            keys.add(usage)
        } else if (!keys.remove(usage)) {
            return
        }
        report()
    }

    @Synchronized
    fun releaseAll() {
        modifiers = 0
        keys.clear()
        report()
    }

    override fun close() {
        runCatching { write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY)) }
        runCatching { Os.close(fd) }
    }

    private fun report() {
        val event = ByteBuffer.allocate(6 + REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        event.putInt(UHID_INPUT2).putShort(REPORT_SIZE.toShort())
        event.put(modifiers.toByte()).put(0)
        for (i in 0 until 6) event.put(keys.getOrElse(i) { 0 }.toByte())
        write(event)
    }

    private fun write(event: ByteBuffer) {
        event.flip()
        while (event.hasRemaining()) Os.write(fd, event)
    }

    private companion object {
        const val NAME = "LumiDeck keyboard"
        const val UHID_DESTROY = 1
        const val UHID_CREATE2 = 11
        const val UHID_INPUT2 = 12
        const val BUS_VIRTUAL: Short = 6
        /** struct uhid_create2_req up to its report descriptor, after the event type. */
        const val CREATE_SIZE = 4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4
        const val REPORT_SIZE = 8
        const val MODIFIER_FIRST = 0xE0
        const val MODIFIER_LAST = 0xE7

        /** By HID modifier bit: left Ctrl, Shift, Alt, Meta, then the right ones. */
        val META = intArrayOf(
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON,
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON,
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON,
            KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON,
        )

        /** A boot-protocol keyboard: a modifier byte, a spare byte and six keys, plus the lights. */
        val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x06, // Usage (Keyboard)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x05, 0x07, //   Usage Page (Keyboard)
            0x19, 0xE0.toByte(), //   Usage Minimum (Left Control)
            0x29, 0xE7.toByte(), //   Usage Maximum (Right GUI)
            0x15, 0x00, //   Logical Minimum (0)
            0x25, 0x01, //   Logical Maximum (1)
            0x75, 0x01, //   Report Size (1)
            0x95.toByte(), 0x08, //   Report Count (8)
            0x81.toByte(), 0x02, //   Input (Data, Variable, Absolute): modifiers
            0x75, 0x08, //   Report Size (8)
            0x95.toByte(), 0x01, //   Report Count (1)
            0x81.toByte(), 0x01, //   Input (Constant): reserved
            0x05, 0x08, //   Usage Page (LEDs)
            0x19, 0x01, //   Usage Minimum (Num Lock)
            0x29, 0x05, //   Usage Maximum (Kana)
            0x75, 0x01, //   Report Size (1)
            0x95.toByte(), 0x05, //   Report Count (5)
            0x91.toByte(), 0x02, //   Output (Data, Variable, Absolute): lights
            0x75, 0x03, //   Report Size (3)
            0x95.toByte(), 0x01, //   Report Count (1)
            0x91.toByte(), 0x01, //   Output (Constant): padding
            0x05, 0x07, //   Usage Page (Keyboard)
            0x19, 0x00, //   Usage Minimum (0)
            0x2A, 0xFF.toByte(), 0x00, //   Usage Maximum (255)
            0x15, 0x00, //   Logical Minimum (0)
            0x26, 0xFF.toByte(), 0x00, //   Logical Maximum (255)
            0x75, 0x08, //   Report Size (8)
            0x95.toByte(), 0x06, //   Report Count (6)
            0x81.toByte(), 0x00, //   Input (Data, Array): keys
            0xC0.toByte(), // End Collection
        )
    }
}
