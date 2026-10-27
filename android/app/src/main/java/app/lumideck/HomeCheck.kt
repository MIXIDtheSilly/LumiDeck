package app.lumideck

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Checks that Android still uses LumiDeck as the home app, which it needs to put the
 * desktop on other screens. Some firmware (seen on the Anbernic RG556) keeps LumiDeck as
 * the home *role holder* after a reboot but opens another launcher anyway, and nothing on
 * the device's own screen shows it, because Home goes to the user's launcher either way.
 */
object HomeCheck {
    enum class Status {
        Ok,
        /** Another app is the home app: the user's choice, or never set up. */
        NotHome,
        /** LumiDeck is the home app on paper, but Android opens [launcher] instead. */
        Lost,
    }

    class Result(val status: Status, val launcher: String?)

    fun check(context: Context): Result {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        if (resolved?.packageName == context.packageName) return Result(Status.Ok, null)
        val held = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        // With no default, Android resolves to its "choose an app" screen.
        val launcher = resolved?.takeIf { it.packageName != "android" }?.loadLabel(pm)?.toString()
        return Result(if (held) Status.Lost else Status.NotHome, launcher)
    }

    /** Checks, and warns with a notification if LumiDeck has been [Status.Lost] as home. */
    fun checkAndNotify(context: Context) {
        val result = check(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (result.status != Status.Lost) {
            notifications.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Home app problems", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context, HomeActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val instead = result.launcher?.let { " opens $it instead" } ?: " stopped using it"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lumideck_glyph)
            .setContentTitle("LumiDeck won't show on a monitor")
            .setContentText("Android$instead. Tap to fix.")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "LumiDeck is still set as the home app, but Android$instead, so a monitor or PC would " +
                        "show that launcher rather than the desktop. Tap to fix.",
                ),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
    }

    private const val CHANNEL_ID = "home"
    private const val NOTIFICATION_ID = 1
}

/** Runs [HomeCheck] after each boot, when the firmware drops LumiDeck as home, and after updates. */
class HomeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            HomeCheck.checkAndNotify(context)
        }
    }
}
