package app.lumideck.server

/**
 * A USB mouse made up through Linux's uhid ([UhidDevice]), for the handheld's touchpad.
 * Android draws its own pointer for it, on the desktop display when desktop mode is on.
 */
internal class HidMouse : AutoCloseable {
    private val device = UhidDevice(NAME, DESCRIPTOR)

    /**
     * Moves by [dx], [dy], with [buttons] held (bit 0 left, 1 right, 2 middle), and turns the
     * wheel by [wheel] clicks (up is positive) and [hWheel] (right is positive).
     */
    fun send(dx: Int, dy: Int, buttons: Int, wheel: Int, hWheel: Int) {
        val x = dx.coerceIn(-MAX_MOVE, MAX_MOVE)
        val y = dy.coerceIn(-MAX_MOVE, MAX_MOVE)
        device.send(
            byteArrayOf(
                buttons.toByte(),
                x.toByte(), (x shr 8).toByte(),
                y.toByte(), (y shr 8).toByte(),
                wheel.coerceIn(-127, 127).toByte(),
                hWheel.coerceIn(-127, 127).toByte(),
            ),
        )
    }

    override fun close() = device.close()

    private companion object {
        const val NAME = "LumiDeck touchpad"
        const val MAX_MOVE = 32767

        /** Three buttons, 16-bit relative X and Y, a wheel and a horizontal wheel (AC Pan). */
        val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x02, // Usage (Mouse)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x09, 0x01, //   Usage (Pointer)
            0xA1.toByte(), 0x00, //   Collection (Physical)
            0x05, 0x09, //     Usage Page (Button)
            0x19, 0x01, //     Usage Minimum (1)
            0x29, 0x03, //     Usage Maximum (3)
            0x15, 0x00, //     Logical Minimum (0)
            0x25, 0x01, //     Logical Maximum (1)
            0x95.toByte(), 0x03, //     Report Count (3)
            0x75, 0x01, //     Report Size (1)
            0x81.toByte(), 0x02, //     Input (Data, Variable, Absolute): buttons
            0x95.toByte(), 0x01, //     Report Count (1)
            0x75, 0x05, //     Report Size (5)
            0x81.toByte(), 0x01, //     Input (Constant): padding
            0x05, 0x01, //     Usage Page (Generic Desktop)
            0x09, 0x30, //     Usage (X)
            0x09, 0x31, //     Usage (Y)
            0x16, 0x01, 0x80.toByte(), //     Logical Minimum (-32767)
            0x26, 0xFF.toByte(), 0x7F, //     Logical Maximum (32767)
            0x75, 0x10, //     Report Size (16)
            0x95.toByte(), 0x02, //     Report Count (2)
            0x81.toByte(), 0x06, //     Input (Data, Variable, Relative): X, Y
            0x09, 0x38, //     Usage (Wheel)
            0x15, 0x81.toByte(), //     Logical Minimum (-127)
            0x25, 0x7F, //     Logical Maximum (127)
            0x75, 0x08, //     Report Size (8)
            0x95.toByte(), 0x01, //     Report Count (1)
            0x81.toByte(), 0x06, //     Input (Data, Variable, Relative): wheel
            0x05, 0x0C, //     Usage Page (Consumer)
            0x0A, 0x38, 0x02, //     Usage (AC Pan)
            0x15, 0x81.toByte(), //     Logical Minimum (-127)
            0x25, 0x7F, //     Logical Maximum (127)
            0x75, 0x08, //     Report Size (8)
            0x95.toByte(), 0x01, //     Report Count (1)
            0x81.toByte(), 0x06, //     Input (Data, Variable, Relative): horizontal wheel
            0xC0.toByte(), //   End Collection
            0xC0.toByte(), // End Collection
        )
    }
}
