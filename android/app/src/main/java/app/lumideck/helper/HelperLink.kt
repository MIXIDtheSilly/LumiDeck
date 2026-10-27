package app.lumideck.helper

import android.graphics.Rect
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** LumiDeck's end of the window helper ([WindowHelper]). Calls do nothing while none runs. */
object HelperLink {
    private val state = MutableStateFlow<List<TaskWindow>?>(null)

    /** Every app window on every display, oldest first, or null while no helper runs. */
    val windows: StateFlow<List<TaskWindow>?> = state

    @Volatile
    private var helper: IWindowHelper? = null

    private val listener = object : IWindowListener.Stub() {
        override fun onWindowsChanged(windows: List<TaskWindow>) {
            val caller = Binder.getCallingUid()
            if (caller == HelperProvider.SHELL_UID || caller == HelperProvider.ROOT_UID) state.value = windows
        }
    }

    /** Takes over from any older helper. Returns where the helper reports changes. */
    fun attach(newHelper: IWindowHelper): IBinder {
        val old = helper
        helper = newHelper
        try {
            newHelper.asBinder().linkToDeath({
                if (helper === newHelper) {
                    helper = null
                    state.value = null
                }
            }, 0)
        } catch (e: RemoteException) {
            helper = null
        }
        if (old != null && old.asBinder() != newHelper.asBinder()) call(old) { it.exit() }
        return listener
    }

    fun focus(taskId: Int) = call { it.focus(taskId) }
    fun minimize(taskId: Int) = call { it.minimize(taskId) }
    fun resize(taskId: Int, bounds: Rect) = call { it.resize(taskId, bounds) }
    fun close(taskId: Int) = call { it.close(taskId) }

    private inline fun call(target: IWindowHelper? = helper, action: (IWindowHelper) -> Unit) {
        try {
            target?.let(action)
        } catch (e: RemoteException) {
            // It died; its death notice clears it.
        }
    }
}
