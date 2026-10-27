package app.lumideck.helper

import android.app.ActivityManager
import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import app.lumideck.SettingsActivity
import kotlin.system.exitProcess

private const val LUMIDECK = "app.lumideck"
private const val SHELL_PACKAGE = "com.android.shell"

/**
 * Lists and controls app windows for the taskbar, which an app isn't allowed to do. Runs
 * outside the app as ADB's shell user, started by the PC client (desktop/lumideck.ps1):
 *
 *     CLASSPATH=<LumiDeck's APK> app_process / app.lumideck.helper.WindowHelper
 *
 * It hands itself to LumiDeck through [HelperProvider], then reports every change to the
 * windows. If LumiDeck restarts, it hands itself over again. It keeps running after the
 * PC disconnects, until the device reboots or a newer helper takes over.
 */
class WindowHelper private constructor() : IWindowHelper.Stub() {
    private val handler = Handler(Looper.getMainLooper())
    private val tasks = Tasks()
    @Volatile private var appUid = -1
    private var listener: IWindowListener? = null
    private var sent: List<TaskWindow>? = null
    private val refresh = Runnable(::send)
    private var refreshAt = Long.MAX_VALUE

    override fun focus(taskId: Int) = command { tasks.moveToFront(taskId) }

    override fun minimize(taskId: Int) = command { tasks.moveToBack(taskId) }

    override fun resize(taskId: Int, bounds: Rect) = command { tasks.resize(taskId, bounds) }

    override fun close(taskId: Int) = command { tasks.remove(taskId) }

    override fun exit() {
        checkCaller()
        Log.i(TAG, "A newer helper took over")
        exitProcess(0)
    }

    private fun command(action: () -> Unit) {
        checkCaller()
        try {
            action()
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Window command failed", e)
        }
        // Not every change (resizing, for one) makes Android report it.
        handler.post { scheduleRefresh() }
    }

    private fun checkCaller() {
        if (Binder.getCallingUid() != appUid) throw SecurityException("Only LumiDeck may use the window helper")
    }

    private fun start(): Boolean {
        if (!attach()) return false
        tasks.listen { handler.post { scheduleRefresh() } }
        scheduleRefresh()
        Log.i(TAG, "Running")
        return true
    }

    /**
     * Lists the windows again within [delay] ms, batching the bursts of changes Android
     * reports for one window opening or closing. An earlier refresh already due stands, so a
     * stream of changes, such as a window being dragged, doesn't hold it off.
     */
    private fun scheduleRefresh(delay: Long = 50) {
        val time = SystemClock.uptimeMillis() + delay
        if (time >= refreshAt) return
        handler.removeCallbacks(refresh)
        handler.postAtTime(refresh, time)
        refreshAt = time
    }

    private fun send() {
        refreshAt = Long.MAX_VALUE
        val listener = listener ?: return
        val windows = try {
            tasks.list()
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Couldn't list windows", e)
            return
        }
        // Android doesn't report windows being moved, resized or maximised from their title
        // bar, so check again shortly while any are showing.
        if (windows.any { it.visible }) scheduleRefresh(POLL_MS)
        if (windows == sent) return
        try {
            listener.onWindowsChanged(windows)
            sent = windows
        } catch (e: Exception) {
            // LumiDeck died; its death notice reconnects.
        }
    }

    /**
     * Hands this helper to LumiDeck, starting LumiDeck if needed. Returns false if it
     * can't be reached, and exits if LumiDeck turns this helper away as outdated.
     */
    private fun attach(): Boolean {
        val reply = try {
            callLumiDeck(Bundle().apply {
                putBinder(HelperProvider.KEY_HELPER, this@WindowHelper)
                putInt(HelperProvider.KEY_VERSION, IWindowHelper.VERSION)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't reach LumiDeck", e)
            return false
        } ?: return false
        val binder = reply.getBinder(HelperProvider.KEY_LISTENER)
        if (binder == null) {
            Log.i(TAG, "LumiDeck was updated; start the helper again")
            exitProcess(0)
        }
        try {
            binder.linkToDeath({ handler.post { reconnect(0) } }, 0)
        } catch (e: Exception) {
            return false
        }
        listener = IWindowListener.Stub.asInterface(binder)
        sent = null
        return true
    }

    private fun reconnect(attempt: Int) {
        listener = null
        if (attach()) {
            Log.i(TAG, "Reconnected to LumiDeck")
            scheduleRefresh()
        } else if (attempt < 10) {
            handler.postDelayed({ reconnect(attempt + 1) }, 2000)
        } else {
            Log.i(TAG, "LumiDeck is gone")
            exitProcess(0)
        }
    }

    /** Calls [HelperProvider], the way the `content call` command does. */
    private fun callLumiDeck(extras: Bundle): Bundle? {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = am.javaClass
            .getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(am, HelperProvider.AUTHORITY, 0, token, TAG) ?: return null
        try {
            val info = holder.javaClass.getField("info").get(holder) as ProviderInfo
            if (info.packageName != LUMIDECK) return null
            appUid = info.applicationInfo.uid
            val provider = holder.javaClass.getField("provider").get(holder)!!
            // Its parameters changed in Android 11 and again in 12.
            val call = provider.javaClass.methods.filter { it.name == "call" }.maxBy { it.parameterCount }
            val args = listOf(HelperProvider.AUTHORITY, HelperProvider.METHOD_ATTACH, null, extras)
            return when {
                call.parameterCount == 6 -> call.invoke(provider, SHELL_PACKAGE, null, *args.toTypedArray())
                call.parameterTypes[0] == String::class.java -> call.invoke(provider, SHELL_PACKAGE, *args.toTypedArray())
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> call.invoke(
                    provider,
                    AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build(),
                    *args.toTypedArray(),
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

    companion object {
        private const val TAG = "LumiDeckHelper"
        private const val POLL_MS = 250L

        @JvmStatic
        fun main(args: Array<String>) {
            Looper.prepareMainLooper()
            if (!WindowHelper().start()) {
                System.err.println("Couldn't reach LumiDeck. Is it installed?")
                exitProcess(1)
            }
            Looper.loop()
        }
    }
}

/**
 * Android's task calls. They aren't in the SDK, so they're found by name; only the shell
 * user (or the system) has the permissions they need.
 */
private class Tasks {
    private val atm: Any = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    private val getTasks = method("getTasks")
    private var stackListener: Binder? = null

    /** Every app window on every display, oldest first. */
    fun list(): List<TaskWindow> {
        val layers = layers()
        return running().mapNotNull { it.toWindow(layers) }.sortedBy { it.taskId }
    }

    /**
     * Each task's place in the stack, by task id: Android lists each display's top-level
     * tasks from the front. A window's task is usually one of them; otherwise it's inside one.
     */
    private fun layers(): Map<Int, Int> {
        val roots = method("getAllRootTaskInfos").invoke(atm) as List<*>
        val layers = mutableMapOf<Int, Int>()
        roots.forEachIndexed { i, root ->
            if (root == null) return@forEachIndexed
            root.hidden<Int>("taskId")?.let { layers.putIfAbsent(it, i) }
            root.hidden<IntArray>("childTaskIds")?.forEach { layers.putIfAbsent(it, i) }
        }
        return layers
    }

    fun moveToFront(taskId: Int) {
        val move = method("moveTaskToFront")
        if (move.parameterCount == 5) {
            move.invoke(atm, null, SHELL_PACKAGE, taskId, 0, null)
        } else {
            move.invoke(atm, taskId, 0, null)
        }
    }

    /** Minimises: moves the window below the desktop, which then covers it. */
    fun moveToBack(taskId: Int) {
        val token = running().firstOrNull { it.taskId == taskId }?.hidden<Any>("token") ?: return
        val transaction = Class.forName("android.window.WindowContainerTransaction")
        val change = transaction.getConstructor().newInstance()
        transaction.getMethod("reorder", Class.forName("android.window.WindowContainerToken"), Boolean::class.javaPrimitiveType)
            .invoke(change, token, false)
        val organizer = method("getWindowOrganizerController").invoke(atm)!!
        organizer.javaClass.methods.first { it.name == "applyTransaction" && it.parameterCount == 1 }.invoke(organizer, change)
    }

    fun resize(taskId: Int, bounds: Rect) {
        method("resizeTask").invoke(atm, taskId, bounds, RESIZE_MODE_SYSTEM)
    }

    fun remove(taskId: Int) {
        method("removeTask").invoke(atm, taskId)
    }

    /**
     * Calls [onChange] whenever a window opens, closes, moves to the front or changes
     * focus. Android's listener interface isn't in the SDK, so this takes every call to
     * it as "something changed" instead of implementing it.
     */
    fun listen(onChange: () -> Unit) {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
                onChange()
                return true
            }
        }
        val listener = Class.forName("android.app.ITaskStackListener\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        method("registerTaskStackListener").invoke(atm, listener)
        stackListener = binder
    }

    private fun running(): List<ActivityManager.RunningTaskInfo> = when (getTasks.parameterCount) {
        1 -> getTasks.invoke(atm, MAX_TASKS)
        3 -> getTasks.invoke(atm, MAX_TASKS, false, false)
        else -> getTasks.invoke(atm, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
    }.let { (it as List<*>).filterIsInstance<ActivityManager.RunningTaskInfo>() }

    private fun ActivityManager.RunningTaskInfo.toWindow(layers: Map<Int, Int>): TaskWindow? {
        val config = hidden<Any>("configuration") ?: return null
        val window = config.javaClass.getField("windowConfiguration").get(config)!!
        // Leaves out home screens (LumiDeck's own desktop among them), recents and the assistant.
        if (window.javaClass.getMethod("getActivityType").invoke(window) != ACTIVITY_TYPE_STANDARD) return null
        val component = baseIntent.component ?: baseActivity ?: return null
        // LumiDeck's setup screen isn't a window on the desktop, but its settings are.
        if (component.packageName == LUMIDECK && component.className != SettingsActivity::class.java.name) return null
        return TaskWindow(
            taskId = taskId,
            displayId = hidden("displayId") ?: Display.DEFAULT_DISPLAY,
            component = component,
            userId = hidden("userId") ?: 0,
            focused = hidden("isFocused") ?: false,
            visible = hidden("isVisible") ?: true,
            freeform = window.javaClass.getMethod("getWindowingMode").invoke(window) == WINDOWING_MODE_FREEFORM,
            bounds = window.javaClass.getMethod("getBounds").invoke(window) as Rect,
            lastActive = hidden("lastActiveTime") ?: 0L,
            layer = layers[taskId] ?: Int.MAX_VALUE,
        )
    }

    private fun method(name: String) =
        atm.javaClass.methods.firstOrNull { it.name == name } ?: throw NoSuchMethodException(name)

    /** Reads a public field the SDK hides. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.hidden(name: String): T? = try {
        javaClass.getField(name).get(this) as T?
    } catch (e: ReflectiveOperationException) {
        null
    }

    private companion object {
        const val MAX_TASKS = 100
        const val RESIZE_MODE_SYSTEM = 0
        const val ACTIVITY_TYPE_STANDARD = 1
        const val WINDOWING_MODE_FREEFORM = 5
    }
}
