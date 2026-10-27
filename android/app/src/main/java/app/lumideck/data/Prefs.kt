package app.lumideck.data

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import java.io.File

private const val FILE = "lumideck"
private const val KEY_PINS = "pinned_apps"
private const val KEY_HANDHELD_LAUNCHER = "handheld_launcher"
private const val KEY_WALLPAPER = "wallpaper"
private const val KEY_TASKBAR_SIZE = "taskbar_size"
private const val KEY_PANEL_FLOAT = "panel_float"
private const val KEY_CLOCK = "clock"

/** How tall the taskbar is, and its app icons. Buttons are square, so this sets their width too. */
enum class TaskbarSize(val label: String, val height: Dp, val icon: Dp) {
    Small("Small", 36.dp, 22.dp),
    Medium("Medium", 44.dp, 28.dp),
    Large("Large", 52.dp, 34.dp),
}

/** Whether the taskbar floats clear of the screen edges, like Plasma's floating panel. */
enum class PanelFloat(val label: String) {
    /** Floats, and attaches to the bottom edge while a window reaches down to it. */
    Adaptive("Float, and attach to the edge when a window reaches it"),
    Always("Always float"),
    Never("Always attached to the edge"),
}

enum class ClockFormat(val label: String) {
    System("Same as the device"),
    H12("12-hour"),
    H24("24-hour"),
}

sealed interface Wallpaper {
    /** The LumiDeck wallpaper (res/drawable/wallpaper.xml). */
    data object Builtin : Wallpaper
    data class Plain(val argb: Int) : Wallpaper
    /** A picture the user picked, copied into LumiDeck's own storage. */
    data class Picture(val file: File) : Wallpaper
}

/** The desktop's settings, changed in [app.lumideck.SettingsActivity]. */
data class DesktopSettings(
    val wallpaper: Wallpaper,
    val taskbarSize: TaskbarSize,
    val panelFloat: PanelFloat,
    val clock: ClockFormat,
)

class LumiPrefs(context: Context) {
    /**
     * Device-protected storage, because the home screen can start before the device is
     * unlocked at boot, when normal app storage can't be read yet.
     */
    private val storage: Context = context.applicationContext.createDeviceProtectedStorageContext()

    val prefs: SharedPreferences = storage.run {
        // Carry over prefs written to normal storage by older builds.
        runCatching { moveSharedPreferencesFrom(context.applicationContext, FILE) }
        getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var pinnedKeys: List<String>
        get() = prefs.getString(KEY_PINS, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(value) = prefs.edit { putString(KEY_PINS, value.joinToString("\n")) }

    /** Launcher shown on the device's own screen, since LumiDeck must be the default home app. */
    var handheldLauncher: ComponentName?
        get() = prefs.getString(KEY_HANDHELD_LAUNCHER, null)?.let(ComponentName::unflattenFromString)
        set(value) = prefs.edit { putString(KEY_HANDHELD_LAUNCHER, value?.flattenToString()) }

    val settings: DesktopSettings
        get() = DesktopSettings(wallpaper, taskbarSize, panelFloat, clock)

    var wallpaper: Wallpaper
        get() {
            val value = prefs.getString(KEY_WALLPAPER, null).orEmpty()
            return when {
                value.startsWith("plain:") -> value.removePrefix("plain:").toLongOrNull(16)?.let { Wallpaper.Plain(it.toInt()) }
                value.startsWith("picture:") -> Wallpaper.Picture(File(storage.filesDir, value.removePrefix("picture:")))
                else -> null
            } ?: Wallpaper.Builtin
        }
        set(value) {
            val old = wallpaper
            prefs.edit {
                putString(KEY_WALLPAPER, when (value) {
                    Wallpaper.Builtin -> null
                    is Wallpaper.Plain -> "plain:" + Integer.toHexString(value.argb)
                    is Wallpaper.Picture -> "picture:" + value.file.name
                })
            }
            if (old is Wallpaper.Picture && old != value) old.file.delete()
        }

    var taskbarSize: TaskbarSize
        get() = enumPref(KEY_TASKBAR_SIZE, TaskbarSize.Medium)
        set(value) = prefs.edit { putString(KEY_TASKBAR_SIZE, value.name) }

    var panelFloat: PanelFloat
        get() = enumPref(KEY_PANEL_FLOAT, PanelFloat.Adaptive)
        set(value) = prefs.edit { putString(KEY_PANEL_FLOAT, value.name) }

    var clock: ClockFormat
        get() = enumPref(KEY_CLOCK, ClockFormat.System)
        set(value) = prefs.edit { putString(KEY_CLOCK, value.name) }

    /**
     * A new file for a picked wallpaper, in device-protected storage so the desktop can show
     * it before the device is unlocked. Each picture gets a new name, so the desktop notices.
     */
    fun newWallpaperFile() = File(storage.filesDir, "wallpaper-${System.currentTimeMillis()}.jpg")

    fun togglePin(key: String) {
        val pins = pinnedKeys
        pinnedKeys = if (key in pins) pins - key else pins + key
    }

    /** Puts the pins in [order]. Pins it leaves out, such as apps that aren't installed now, go last. */
    fun reorderPins(order: List<String>) {
        val pins = pinnedKeys
        pinnedKeys = order.filter { it in pins } + pins.filter { it !in order }
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}

/** Pinned app keys in taskbar order. Stays in sync across desktops on several displays. */
@Composable
fun rememberPinnedKeys(prefs: LumiPrefs): List<String> = rememberPref(prefs, setOf(KEY_PINS)) { pinnedKeys }

/** The desktop's settings, updated as soon as they change. */
@Composable
fun rememberDesktopSettings(prefs: LumiPrefs): DesktopSettings =
    rememberPref(prefs, setOf(KEY_WALLPAPER, KEY_TASKBAR_SIZE, KEY_PANEL_FLOAT, KEY_CLOCK)) { settings }

@Composable
private fun <T> rememberPref(prefs: LumiPrefs, keys: Set<String>, read: LumiPrefs.() -> T): T {
    val state = remember(prefs) { mutableStateOf(prefs.read()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in keys) state.value = prefs.read()
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
