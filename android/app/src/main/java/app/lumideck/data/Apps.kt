package app.lumideck.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import app.lumideck.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

data class AppEntry(
    /** Stable id used for pins: component plus user, so work-profile apps stay distinct. */
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
)

private fun loadApps(context: Context, iconSizePx: Int): List<AppEntry> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val userManager = context.getSystemService(UserManager::class.java)
    val density = context.resources.displayMetrics.densityDpi
    val apps = launcherApps.profiles.flatMap { user ->
        val serial = userManager.getSerialNumberForUser(user)
        launcherApps.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    key = "${info.componentName.flattenToString()}#$serial",
                    label = info.label.toString(),
                    component = info.componentName,
                    user = user,
                    icon = info.getBadgedIcon(density).toBitmap(iconSizePx, iconSizePx).asImageBitmap(),
                )
            }
    }
    return (apps + settingsEntry(context, userManager, iconSizePx)).sortedBy { it.label.lowercase() }
}

/** LumiDeck's own settings, listed like an app so it can be found, pinned and shown in the taskbar. */
private fun settingsEntry(context: Context, userManager: UserManager, iconSizePx: Int): AppEntry {
    val component = ComponentName(context, SettingsActivity::class.java)
    val user = Process.myUserHandle()
    return AppEntry(
        key = "${component.flattenToString()}#${userManager.getSerialNumberForUser(user)}",
        label = "Desktop Settings",
        component = component,
        user = user,
        icon = context.packageManager.getApplicationIcon(context.applicationInfo)
            .toBitmap(iconSizePx, iconSizePx).asImageBitmap(),
    )
}

/** Every launchable app, reloaded whenever a package is installed, removed or updated. */
@Composable
fun rememberInstalledApps(): State<List<AppEntry>> {
    val context = LocalContext.current
    val iconSizePx = with(LocalDensity.current) { 48.dp.roundToPx() }
    return produceState(initialValue = emptyList(), context, iconSizePx) {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val changes = Channel<Unit>(Channel.CONFLATED)
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) { changes.trySend(Unit) }
            override fun onPackageAdded(packageName: String, user: UserHandle) { changes.trySend(Unit) }
            override fun onPackageChanged(packageName: String, user: UserHandle) { changes.trySend(Unit) }
            override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { changes.trySend(Unit) }
            override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { changes.trySend(Unit) }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        // Before the first unlock after boot, only direct-boot-aware apps are listed.
        val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { changes.trySend(Unit) }
        }
        // On the app, not the activity: Android drops an activity's receivers when it's
        // destroyed, before Compose gets to unregister them, and unregistering then throws.
        val app = context.applicationContext
        ContextCompat.registerReceiver(
            app, unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            while (true) {
                value = withContext(Dispatchers.IO) { loadApps(context, iconSizePx) }
                changes.receive()
            }
        } finally {
            launcherApps.unregisterCallback(callback)
            app.unregisterReceiver(unlockReceiver)
        }
    }
}
