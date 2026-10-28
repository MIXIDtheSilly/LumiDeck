package app.lumideck.data

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * What the taskbar's tray and quick settings show, and the changes an app may make itself.
 * Turning Wi-Fi and Bluetooth on and off isn't one of them: the window helper does that
 * (see HelperLink). Brightness and Do not disturb need permissions that the PC client
 * grants (desktop/client/src/adb.rs), and without them they only show.
 */

/** Wi-Fi: off, on but not connected, or connected with a signal of 0–4 [bars]. */
data class WifiState(val enabled: Boolean, val connected: Boolean, val bars: Int)

@Composable
fun rememberWifiState(): State<WifiState> {
    val context = LocalContext.current.applicationContext
    val wifi = context.getSystemService(WifiManager::class.java)
    return produceState(WifiState(wifi.isWifiEnabled, connected = false, bars = 0), context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val networks = mutableMapOf<Network, NetworkCapabilities>()
        fun update() {
            val signal = networks.values.maxOfOrNull { it.signalStrength }
            value = WifiState(wifi.isWifiEnabled, connected = signal != null, bars = signal?.let(::signalBars) ?: 0)
        }
        val receiver = receiver { update() }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                networks[network] = capabilities
                update()
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                update()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        update()
        awaitDispose {
            context.unregisterReceiver(receiver)
            connectivity.unregisterNetworkCallback(callback)
        }
    }
}

/** Android's own thresholds, in dBm. Some drivers don't report a signal: that shows as full. */
private fun signalBars(rssi: Int) = when {
    rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> 4
    rssi >= -55 -> 4
    rssi >= -66 -> 3
    rssi >= -77 -> 2
    rssi >= -88 -> 1
    else -> 0
}

/** The media volume, which is what the PC and a monitor play. */
data class VolumeState(val level: Int, val max: Int, val muted: Boolean) {
    val fraction get() = if (muted || max == 0) 0f else level.toFloat() / max
}

@Composable
fun rememberVolume(): State<VolumeState> {
    val context = LocalContext.current.applicationContext
    val audio = context.getSystemService(AudioManager::class.java)
    fun read() = VolumeState(
        audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        audio.isStreamMute(AudioManager.STREAM_MUSIC),
    )
    return produceState(read(), context) {
        val receiver = receiver { value = read() }
        // Android's own, not in the SDK, but what every volume widget listens for.
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitDispose { context.unregisterReceiver(receiver) }
    }
}

object Volume {
    fun set(context: Context, level: Int) = adjust(context) {
        if (it.isStreamMute(AudioManager.STREAM_MUSIC) && level > 0) {
            it.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        it.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
    }

    /** One step up or down, as a volume key would. */
    fun step(context: Context, up: Boolean) = adjust(context) {
        it.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
    }

    fun toggleMute(context: Context) = adjust(context) {
        it.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
    }

    private fun adjust(context: Context, action: (AudioManager) -> Unit) {
        // Fails while Do not disturb holds the volume, on some firmware.
        runCatching { action(context.getSystemService(AudioManager::class.java)) }
    }
}

/**
 * The device's own screen brightness, from 0 to 1, or null if LumiDeck may not change it
 * ("Modify system settings"). A monitor's brightness is the monitor's own.
 */
@Composable
fun rememberBrightness(): State<Float?> {
    val context = LocalContext.current.applicationContext
    return produceState(Brightness.read(context), context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = Brightness.read(context)
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        awaitDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
}

object Brightness {
    /** Android's brightness setting runs from 1 to 255. */
    private const val MAX = 255

    fun read(context: Context): Float? {
        if (!Settings.System.canWrite(context)) return null
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, MAX)
        return sqrt((raw - 1).coerceAtLeast(0) / (MAX - 1f))
    }

    /**
     * Squared, so the slider's low end, where the eye notices each step, gets more of its
     * length, as Android's own slider does. Turns automatic brightness off, as moving
     * Android's own slider would.
     */
    fun set(context: Context, fraction: Float) {
        if (!Settings.System.canWrite(context)) return
        val resolver = context.contentResolver
        runCatching {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 1 + (fraction * fraction * (MAX - 1)).roundToInt())
        }
    }
}

/** Do not disturb, and whether LumiDeck may change it ("Do Not Disturb access"). */
data class DndState(val on: Boolean, val allowed: Boolean)

@Composable
fun rememberDoNotDisturb(): State<DndState> {
    val context = LocalContext.current.applicationContext
    val notifications = context.getSystemService(NotificationManager::class.java)
    fun read() = DndState(
        notifications.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN },
        notifications.isNotificationPolicyAccessGranted,
    )
    return produceState(read(), context) {
        val receiver = receiver { value = read() }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitDispose { context.unregisterReceiver(receiver) }
    }
}

object DoNotDisturb {
    /** Priority only, like the quick settings tile. Returns false if LumiDeck may not. */
    fun set(context: Context, on: Boolean): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.isNotificationPolicyAccessGranted) return false
        notifications.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return true
    }
}

/**
 * Whether Bluetooth is on, or null if the device has none. Read twice a second: its change
 * notice needs a permission the app may not have, and this only runs while quick settings
 * are open.
 */
@Composable
fun rememberBluetooth(): State<Boolean?> {
    val context = LocalContext.current.applicationContext
    fun read() = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
    return produceState(read(), context) {
        while (true) {
            value = read()
            delay(500)
        }
    }
}

private fun receiver(onReceive: () -> Unit) = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = onReceive()
}
