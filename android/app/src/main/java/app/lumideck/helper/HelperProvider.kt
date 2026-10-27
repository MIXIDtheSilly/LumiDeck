package app.lumideck.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Where the window helper ([WindowHelper]) hands itself to LumiDeck. Only ADB's shell user
 * may call it. Holds no data: it's a provider because that's what a process outside any
 * app can reach.
 */
class HelperProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val caller = Binder.getCallingUid()
        if (caller != SHELL_UID && caller != ROOT_UID) throw SecurityException("Only ADB may start the window helper")
        if (method != METHOD_ATTACH || extras == null) return null
        // A reply without a listener turns away a helper left over from an older LumiDeck.
        if (extras.getInt(KEY_VERSION) != IWindowHelper.VERSION) return Bundle()
        val helper = IWindowHelper.Stub.asInterface(extras.getBinder(KEY_HELPER) ?: return null)
        return Bundle().apply { putBinder(KEY_LISTENER, HelperLink.attach(helper)) }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?) = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "app.lumideck.helper"
        const val METHOD_ATTACH = "attach"
        const val KEY_HELPER = "helper"
        const val KEY_VERSION = "version"
        const val KEY_LISTENER = "listener"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
