package app.lumideck.server

import android.annotation.SuppressLint
import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi

/**
 * What the stream server needs to call Android's services as ADB's shell user: a
 * [Context] that names the shell's package, which matches its uid.
 *
 * Processes started by `app_process` have no app, so this sets up the parts of one that
 * the display and audio services look at.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object Shell {
    const val PACKAGE = "com.android.shell"

    private val activityThreadClass = Class.forName("android.app.ActivityThread")
    private lateinit var activityThread: Any

    lateinit var context: Context
        private set

    /** Call once, first thing, on the main thread. */
    fun init() {
        // ActivityThread makes a Handler, which needs a Looper.
        Looper.prepareMainLooper()
        activityThread = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        activityThreadClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, activityThread)
        activityThreadClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(activityThread, true)
        val system = activityThreadClass.getDeclaredMethod("getSystemContext").invoke(activityThread) as Context
        context = ShellContext(system)
    }

    /** Audio capture checks the app the process belongs to. */
    fun fillApp() {
        val bindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
        val bindData = bindDataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val info = ApplicationInfo().apply { packageName = PACKAGE }
        bindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(bindData, info)
        activityThreadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(activityThread, bindData)

        val app = Application()
        ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(app, context)
        activityThreadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(activityThread, app)
    }

    /** A system service's binder interface, as `IWindowManager.Stub.asInterface` would give it. */
    fun service(name: String, type: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name)
        return Class.forName("$type\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = PACKAGE

        override fun getOpPackageName() = PACKAGE

        // Only Android 12 and later ask for it.
        @RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(PACKAGE).build()

        override fun getApplicationContext(): Context = this
    }
}
