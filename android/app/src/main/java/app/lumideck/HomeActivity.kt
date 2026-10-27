package app.lumideck

import android.Manifest
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lumideck.data.LumiPrefs
import app.lumideck.ui.LumiColors
import app.lumideck.ui.LumiLogo
import app.lumideck.ui.LumiTheme

/**
 * LumiDeck must be the default home app for Android to use its desktop on external
 * displays. On the device's own screen, this forwards Home presses to the launcher the
 * user picked. Opened from the app drawer instead, it shows LumiDeck's setup screen.
 */
class HomeActivity : ComponentActivity() {
    private val prefs by lazy { LumiPrefs(this) }

    /** Home was pressed before the device unlocked, when the other launcher can't start. */
    private var forwardOnUnlock = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (forwardOnUnlock) forwardHome(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Set even when forwarding, so something sensible sits behind the other launcher.
        // Only ask for permissions when opened from the app drawer, not on the way to it.
        val openedByUser = !intent.hasCategory(Intent.CATEGORY_HOME)
        setContent { LumiTheme { HandheldScreen(prefs, askForNotifications = openedByUser) } }
        forwardHome(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardHome(intent)
    }

    /** Hands a Home press to the user's handheld launcher, if one is picked. */
    private fun forwardHome(intent: Intent) {
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return
        val target = prefs.handheldLauncher ?: return
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val unlocked = getSystemService(UserManager::class.java).isUserUnlocked
        forwardOnUnlock = false
        if (packageManager.resolveActivity(launch, 0) == null) {
            // Before unlock, launchers that aren't direct-boot aware look missing too.
            if (unlocked) prefs.handheldLauncher = null else forwardOnUnlock = true
            return
        }
        try {
            startActivity(launch, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        } catch (_: ActivityNotFoundException) {
            forwardOnUnlock = !unlocked
        }
    }
}

private data class LauncherOption(val component: ComponentName, val label: String, val icon: android.graphics.drawable.Drawable)

@Composable
private fun HandheldScreen(prefs: LumiPrefs, askForNotifications: Boolean) {
    val context = LocalContext.current
    val pm = context.packageManager
    val homeIntent = remember { Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME) }

    var home by remember { mutableStateOf<HomeCheck.Result?>(null) }
    var launchers by remember { mutableStateOf(emptyList<LauncherOption>()) }
    var selected by remember { mutableStateOf(prefs.handheldLauncher) }

    // For the warning when Android stops using LumiDeck as home (see HomeCheck).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (askForNotifications && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LifecycleResumeEffect(Unit) {
        home = HomeCheck.check(context)
        // Clears the warning once it's fixed.
        HomeCheck.checkAndNotify(context)
        launchers = pm.queryIntentActivities(homeIntent, 0)
            .map { it.activityInfo }
            // Skip ourselves and Settings' "FallbackHome" placeholder.
            .filter { it.packageName != context.packageName && it.packageName != "com.android.settings" }
            .map { LauncherOption(ComponentName(it.packageName, it.name), it.loadLabel(pm).toString(), it.loadIcon(pm)) }
        onPauseOrDispose { }
    }

    val displays = rememberDisplays()

    Column(
        Modifier
            .fillMaxSize()
            .background(LumiColors.Night)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LumiLogo(size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("LumiDeck", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = LumiColors.Text)
                Text("Your device, on a big screen", color = LumiColors.TextDim, fontSize = 13.sp)
            }
        }

        Section("1 · Default home app") {
            val status = home?.status
            if (status == HomeCheck.Status.Ok) {
                Text("✓ LumiDeck is the default home app.", color = LumiColors.Mist)
            } else if (status == HomeCheck.Status.Lost) {
                val instead = home?.launcher ?: "another launcher"
                Text(
                    "LumiDeck is set as the home app, but Android opens $instead instead, so a monitor " +
                        "or your PC would show $instead rather than the desktop. Some devices do this " +
                        "after a restart.",
                    color = LumiColors.Warning,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "To fix it, open home app settings, pick any other launcher, then pick LumiDeck " +
                        "again. Or plug into your PC: the LumiDeck client fixes it when it starts.",
                    color = LumiColors.TextDim,
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }) {
                    Text("Open home app settings")
                }
            } else if (status == HomeCheck.Status.NotHome) {
                Text(
                    "Android only shows LumiDeck on external screens when it's the default home app. " +
                        "Pick a handheld launcher below first, so this screen still looks normal.",
                    color = LumiColors.TextDim,
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }) {
                    Text("Open home app settings")
                }
            }
        }

        Section("2 · Launcher on this screen") {
            Text("Pressing Home on the device itself opens:", color = LumiColors.TextDim)
            launchers.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = option.component
                            prefs.handheldLauncher = option.component
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == option.component, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Image(option.icon.toBitmap(96, 96).asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(option.label, color = LumiColors.Text)
                }
            }
            if (launchers.isEmpty()) Text("No other launcher found.", color = LumiColors.TextDim)
        }

        Section("3 · Screens") {
            displays.forEach { display ->
                val mode = display.mode
                val kind = if (display.displayId == Display.DEFAULT_DISPLAY) "this device" else "desktop"
                Text(
                    "#${display.displayId}  ${display.name}  ·  ${mode.physicalWidth}×${mode.physicalHeight}  ·  $kind",
                    color = LumiColors.Text,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                "Plug in a monitor over USB-C, or run desktop\\lumideck.ps1 on your PC.",
                color = LumiColors.TextDim,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = LumiColors.Slate.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = LumiColors.Text)
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
private fun rememberDisplays(): List<Display> {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(DisplayManager::class.java) }
    var displays by remember { mutableStateOf(manager.displays.toList()) }
    DisposableEffect(manager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { displays = manager.displays.toList() }
            override fun onDisplayRemoved(displayId: Int) { displays = manager.displays.toList() }
            override fun onDisplayChanged(displayId: Int) { displays = manager.displays.toList() }
        }
        manager.registerDisplayListener(listener, null)
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return displays
}
