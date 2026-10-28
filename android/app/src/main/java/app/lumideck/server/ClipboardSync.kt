package app.lumideck.server

import android.content.ClipData
import android.content.ClipDescription
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.PersistableBundle

/**
 * Keeps the device's clipboard and the PC's the same, for text. The PC sends its text
 * whenever its clipboard changes ([setText]), and [onChange] gets the device's whenever
 * the device's changes. Neither side sends back what it just got.
 *
 * Goes through the clipboard service's binder interface, which the SDK hides, as the
 * shell user, which may read the clipboard from the background.
 */
internal class ClipboardSync(private val onChange: (String) -> Unit) {
    private val service = Shell.service("clipboard", "android.content.IClipboard")

    /** The text on both sides. */
    @Volatile private var shared: String? = null

    /** Kept, so the service's reference to it stays alive. */
    private var listener: Binder? = null

    /**
     * Reports changes from now on. Android's listener interface isn't in the SDK, so this
     * takes every call to it as "the clipboard changed" instead of implementing it.
     */
    fun start() {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
                runCatching(::changed).onFailure { System.err.println("Couldn't read the clipboard: $it") }
                return true
            }
        }
        val listener = Class.forName("android.content.IOnPrimaryClipChangedListener\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        call("addPrimaryClipChangedListener", listener)
        this.listener = binder
    }

    fun setText(text: String) {
        shared = text
        val clip = ClipData.newPlainText("LumiDeck", text)
        // Android 13 shows what was copied in a corner of the device's own screen; for text
        // from another device it needn't. The first extra only works from the shell.
        clip.description.extras = PersistableBundle().apply {
            putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true)
        }
        call("setPrimaryClip", clip)
    }

    private fun changed() {
        val clip = call("getPrimaryClip") as ClipData? ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).text?.toString() ?: return
        if (text == shared) return
        shared = text
        onChange(text)
    }

    /**
     * Calls the service's [name] with [first] (if any), then the shell's package, no
     * attribution tag, user 0 and the default device, as far as this Android version's
     * method takes them.
     */
    private fun call(name: String, first: Any? = null): Any? {
        val method = service.javaClass.methods.filter { it.name == name }.maxBy { it.parameterCount }
        var strings = 0
        val args = method.parameterTypes.mapIndexed { i, type ->
            when {
                i == 0 && first != null -> first
                type == String::class.java -> if (strings++ == 0) Shell.PACKAGE else null
                type == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        return method.invoke(service, *args.toTypedArray())
    }
}
