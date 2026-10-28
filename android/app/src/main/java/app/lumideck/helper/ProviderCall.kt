package app.lumideck.helper

import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process

private const val LUMIDECK = "app.lumideck"
private const val SHELL_PACKAGE = "com.android.shell"

/**
 * Calls [HelperProvider]'s [method] from a process ADB started (the window helper, the
 * stream server), the way the `content call` command does. [onProvider] gets the
 * provider's details first. Returns null if LumiDeck can't be reached.
 */
internal fun callLumiDeck(method: String, arg: String?, extras: Bundle?, onProvider: (ProviderInfo) -> Unit = {}): Bundle? {
    val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
    val token = Binder()
    val holder = am.javaClass
        .getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
        .invoke(am, HelperProvider.AUTHORITY, 0, token, "LumiDeck") ?: return null
    try {
        val info = holder.javaClass.getField("info").get(holder) as ProviderInfo
        if (info.packageName != LUMIDECK) return null
        onProvider(info)
        val provider = holder.javaClass.getField("provider").get(holder)!!
        // Its parameters changed in Android 11 and again in 12.
        val call = provider.javaClass.methods.filter { it.name == "call" }.maxBy { it.parameterCount }
        val args = arrayOf(HelperProvider.AUTHORITY, method, arg, extras)
        return when {
            call.parameterCount == 6 -> call.invoke(provider, SHELL_PACKAGE, null, *args)
            call.parameterTypes[0] == String::class.java -> call.invoke(provider, SHELL_PACKAGE, *args)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> call.invoke(
                provider,
                AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build(),
                *args,
            )
            else -> null
        } as Bundle?
    } finally {
        runCatching {
            am.javaClass.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                .invoke(am, HelperProvider.AUTHORITY, token)
        }
    }
}
