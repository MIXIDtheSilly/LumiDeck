package app.lumideck.data

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

private const val FILE = "lumideck"
private const val KEY_PINS = "pinned_apps"
private const val KEY_HANDHELD_LAUNCHER = "handheld_launcher"

class LumiPrefs(context: Context) {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var pinnedKeys: List<String>
        get() = prefs.getString(KEY_PINS, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_PINS, value.joinToString("\n")) }

    /** Launcher shown on the device's own screen, since LumiDeck must be the default home app. */
    var handheldLauncher: ComponentName?
        get() = prefs.getString(KEY_HANDHELD_LAUNCHER, null)?.let(ComponentName::unflattenFromString)
        set(value) = prefs.edit { putString(KEY_HANDHELD_LAUNCHER, value?.flattenToString()) }

    fun togglePin(key: String) {
        val pins = pinnedKeys
        pinnedKeys = if (key in pins) pins - key else pins + key
    }
}

/** Pinned app keys in taskbar order. Stays in sync across desktops on several displays. */
@Composable
fun rememberPinnedKeys(prefs: LumiPrefs): List<String> {
    val state = remember(prefs) { mutableStateOf(prefs.pinnedKeys) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PINS) state.value = prefs.pinnedKeys
        }
        prefs.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state.value
}

@Composable
fun rememberLumiPrefs(): LumiPrefs {
    val context = LocalContext.current
    return remember(context) { LumiPrefs(context) }
}
