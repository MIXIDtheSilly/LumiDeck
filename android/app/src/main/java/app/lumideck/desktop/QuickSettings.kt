package app.lumideck.desktop

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumideck.data.Brightness
import app.lumideck.data.DoNotDisturb
import app.lumideck.data.Volume
import app.lumideck.data.rememberBluetooth
import app.lumideck.data.rememberBrightness
import app.lumideck.data.rememberDoNotDisturb
import app.lumideck.data.rememberVolume
import app.lumideck.data.rememberWifiState
import app.lumideck.helper.HelperLink
import app.lumideck.ui.BluetoothIcon
import app.lumideck.ui.DeskColors
import app.lumideck.ui.DoNotDisturbIcon
import app.lumideck.ui.ItemShape
import app.lumideck.ui.PanelShape
import app.lumideck.ui.SpeakerIcon
import app.lumideck.ui.SunIcon
import app.lumideck.ui.WifiIcon
import kotlin.math.roundToInt

/**
 * Opened from the tray: switches for Wi-Fi, Bluetooth and Do not disturb, and sliders for
 * the volume and the device's screen brightness. Without the window helper, Wi-Fi and
 * Bluetooth open Android's settings for them instead, as apps can't switch them.
 */
@Composable
fun QuickSettings(windows: Windows, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wifi by rememberWifiState()
    val bluetooth by rememberBluetooth()
    val dnd by rememberDoNotDisturb()
    val volume by rememberVolume()
    val brightness by rememberBrightness()
    val helper = HelperLink.windows.collectAsState().value != null
    fun openSettings(action: String) {
        onDismiss()
        windows.openSystemSettings(action)
    }

    Column(
        modifier
            .width(380.dp)
            .shadow(8.dp, PanelShape)
            .clip(PanelShape)
            .background(DeskColors.Panel)
            .border(1.dp, DeskColors.Border, PanelShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                label = "Wi-Fi",
                status = when {
                    !wifi.enabled -> "Off"
                    wifi.connected -> "Connected"
                    else -> "Not connected"
                },
                on = wifi.enabled,
                onClick = { if (helper) HelperLink.setWifi(!wifi.enabled) else openSettings(Settings.Panel.ACTION_WIFI) },
                modifier = Modifier.weight(1f),
            ) { color, _ -> WifiIcon(wifi.enabled, wifi.connected, wifi.bars, color, size = 20.dp) }
            bluetooth?.let { on ->
                Tile(
                    label = "Bluetooth",
                    status = if (on) "On" else "Off",
                    on = on,
                    onClick = { if (helper) HelperLink.setBluetooth(!on) else openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
                    modifier = Modifier.weight(1f),
                ) { color, _ -> BluetoothIcon(color, size = 20.dp) }
            }
            Tile(
                label = "Do not disturb",
                status = if (dnd.on) "On" else "Off",
                on = dnd.on,
                onClick = {
                    if (!DoNotDisturb.set(context, !dnd.on)) openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                },
                modifier = Modifier.weight(1f),
            ) { color, background -> DoNotDisturbIcon(color, background, size = 20.dp) }
        }

        SliderRow(
            label = "Volume",
            value = volume.fraction,
            onChange = { fraction ->
                val level = (fraction * volume.max).roundToInt()
                if (level != volume.level || volume.muted) Volume.set(context, level)
            },
            onIconClick = { Volume.toggleMute(context) },
        ) { SpeakerIcon(volume.fraction, volume.muted, DeskColors.Text) }
        brightness?.let { value ->
            SliderRow(label = "Device screen", value = value, onChange = { Brightness.set(context, it) }) {
                SunIcon(DeskColors.Text)
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(DeskColors.Border))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryIndicator(Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            TextButton("Android settings") { openSettings(Settings.ACTION_SETTINGS) }
            TextButton("Desktop") {
                onDismiss()
                windows.openSettings()
            }
        }
    }
}

/** A switch: filled with the accent colour while [on]. [icon] gets its colours. */
@Composable
private fun Tile(
    label: String,
    status: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (color: Color, background: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        on && hovered -> lerp(DeskColors.Accent, Color.Black, 0.12f)
        on -> DeskColors.Accent
        hovered -> lerp(DeskColors.PanelAlt, DeskColors.Accent, 0.14f)
        else -> DeskColors.PanelAlt
    }
    val text = if (on) Color.White else DeskColors.Text
    Column(
        modifier
            .clip(ItemShape)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        icon(text, background)
        Spacer(Modifier.height(8.dp))
        Text(label, color = text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, color = text.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * An icon, which [onIconClick] makes a button, and a slider. While it's dragged it shows
 * where it's dragged to, not the setting, which catches up a moment later.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onIconClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged ?: value
    Row(verticalAlignment = Alignment.CenterVertically) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
            Modifier
                .size(32.dp)
                .clip(ItemShape)
                .background(if (hovered && onIconClick != null) DeskColors.Hover else Color.Transparent)
                .hoverable(interaction)
                .then(if (onIconClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onIconClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = DeskColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp))
            DeskSlider(
                value = shown,
                onChange = {
                    dragged = it
                    onChange(it)
                },
                onFinished = { dragged = null },
            )
        }
        Text(
            "${(shown * 100).roundToInt()}%",
            color = DeskColors.Text,
            fontSize = 13.sp,
            modifier = Modifier.width(48.dp).padding(start = 8.dp),
        )
    }
}

/** Plasma's slider: a thin track, filled up to a round handle. Click or drag anywhere on it. */
@Composable
private fun DeskSlider(value: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val handle = 8.dp
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                val inset = handle.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change(at(down.position.x))
                    drag(down.id) {
                        it.consume()
                        change(at(it.position.x))
                    }
                    finished()
                }
            },
    ) {
        val inset = handle.toPx()
        val y = size.height / 2
        val x = inset + (size.width - 2 * inset) * value
        val track = 4.dp.toPx()
        drawLine(DeskColors.Border, Offset(inset, y), Offset(size.width - inset, y), track, StrokeCap.Round)
        drawLine(DeskColors.Accent, Offset(inset, y), Offset(x, y), track, StrokeCap.Round)
        drawCircle(Color.White, inset, Offset(x, y))
        drawCircle(DeskColors.Accent, inset - 1.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun TextButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        label,
        color = DeskColors.Text,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(ItemShape)
            .background(if (hovered) DeskColors.Hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
