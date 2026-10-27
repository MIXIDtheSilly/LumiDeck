package app.lumideck.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.lumideck.ui.LumiColors
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - value % 1000)
        }
    }
    // Recomputed each tick so a change to the 24-hour setting shows up straight away.
    val time = DateFormat.getTimeFormat(context).format(Date(now))
    val datePattern = remember { DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM") }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(time, color = LumiColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
        Text(DateFormat.format(datePattern, now).toString(), color = LumiColors.TextDim, fontSize = 11.sp, lineHeight = 13.sp)
    }
}

private data class BatteryState(val percent: Int, val charging: Boolean)

private fun Intent.toBatteryState(): BatteryState? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryState(level * 100 / scale, charging)
}

/**
 * Always visible: the RG556 (and other handhelds) cannot charge while driving a monitor,
 * so battery is the main limit on a direct-to-monitor session.
 */
@Composable
fun BatteryIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val battery by produceState<BatteryState?>(null, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                value = intent.toBatteryState()
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        value = sticky?.toBatteryState()
        awaitDispose { context.unregisterReceiver(receiver) }
    }
    val state = battery ?: return
    val color = when {
        state.charging -> LumiColors.Mist
        state.percent <= 15 -> LumiColors.Warning
        else -> LumiColors.Text
    }
    val description = "Battery ${state.percent}%" + if (state.charging) ", charging" else ""
    Row(
        modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(width = 22.dp, height = 12.dp)) {
            val stroke = 1.5.dp.toPx()
            val nub = 2.dp.toPx()
            val body = Size(size.width - nub - stroke / 2, size.height - stroke)
            drawRoundRect(color, Offset(stroke / 2, stroke / 2), body, CornerRadius(3.dp.toPx()), style = Stroke(stroke))
            val inset = stroke + 1.dp.toPx()
            val fillMax = body.width - 2 * inset + stroke
            drawRoundRect(
                color,
                Offset(inset, inset),
                Size(fillMax * state.percent / 100f, size.height - 2 * inset),
                CornerRadius(1.5.dp.toPx()),
            )
            drawRoundRect(
                color,
                Offset(size.width - nub, size.height * 0.3f),
                Size(nub, size.height * 0.4f),
                CornerRadius(1.dp.toPx()),
            )
        }
        Text("${state.percent}%", color = color, fontSize = 13.sp)
    }
}
