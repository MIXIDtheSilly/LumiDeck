package app.lumideck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/*
 * The tray's and quick settings' icons, drawn rather than loaded, like the battery
 * (desktop/Tray.kt), so they take any colour and size.
 */

/** A fan, filled up to [bars] of 4, or crossed out while Wi-Fi is off. */
@Composable
fun WifiIcon(enabled: Boolean, connected: Boolean, bars: Int, color: Color, size: Dp = 18.dp) = Icon(size) {
    val radius = this.size.width * 0.66f
    val centre = Offset(this.size.width / 2, this.size.height * 0.84f)
    fun fan(fraction: Float, fill: Color) = drawArc(
        fill, startAngle = 225f, sweepAngle = 90f, useCenter = true,
        topLeft = centre - Offset(radius * fraction, radius * fraction), size = Size(radius * fraction * 2, radius * fraction * 2),
    )
    when {
        !enabled -> {
            fan(1f, color.copy(alpha = 0.3f))
            slash(color)
        }
        !connected -> fan(1f, color.copy(alpha = 0.3f))
        else -> {
            fan(1f, color.copy(alpha = 0.3f))
            if (bars > 0) fan(0.25f + 0.75f * bars / 4, color)
        }
    }
}

/** A speaker with up to two waves for [level] (0 to 1), or a cross when [muted]. */
@Composable
fun SpeakerIcon(level: Float, muted: Boolean, color: Color, size: Dp = 18.dp) = Icon(size) {
    val w = this.size.width
    val h = this.size.height
    val body = Path().apply {
        moveTo(w * 0.12f, h * 0.38f)
        lineTo(w * 0.3f, h * 0.38f)
        lineTo(w * 0.52f, h * 0.17f)
        lineTo(w * 0.52f, h * 0.83f)
        lineTo(w * 0.3f, h * 0.62f)
        lineTo(w * 0.12f, h * 0.62f)
        close()
    }
    drawPath(body, color)
    val stroke = Stroke(w * 0.08f, cap = StrokeCap.Round)
    if (muted || level <= 0f) {
        val (x, y, r) = Triple(w * 0.78f, h * 0.5f, w * 0.13f)
        drawLine(color, Offset(x - r, y - r), Offset(x + r, y + r), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(x - r, y + r), Offset(x + r, y - r), stroke.width, StrokeCap.Round)
        return@Icon
    }
    val waves = if (level < 0.5f) 1 else 2
    for (i in 1..waves) {
        val r = w * (0.12f + 0.16f * i)
        val centre = Offset(w * 0.5f, h * 0.5f)
        drawArc(color, -50f, 100f, useCenter = false, topLeft = centre - Offset(r, r), size = Size(r * 2, r * 2), style = stroke)
    }
}

/** Bluetooth's rune. */
@Composable
fun BluetoothIcon(color: Color, size: Dp = 18.dp) = Icon(size) {
    val w = this.size.width
    val h = this.size.height
    val rune = Path().apply {
        moveTo(w * 0.28f, h * 0.31f)
        lineTo(w * 0.72f, h * 0.69f)
        lineTo(w * 0.5f, h * 0.88f)
        lineTo(w * 0.5f, h * 0.12f)
        lineTo(w * 0.72f, h * 0.31f)
        lineTo(w * 0.28f, h * 0.69f)
    }
    drawPath(rune, color, style = Stroke(w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Do not disturb: a disc with a bar across it, the bar in [background]. */
@Composable
fun DoNotDisturbIcon(color: Color, background: Color, size: Dp = 18.dp) = Icon(size) {
    val w = this.size.width
    drawCircle(color, radius = w * 0.42f)
    drawLine(background, Offset(w * 0.27f, w * 0.5f), Offset(w * 0.73f, w * 0.5f), w * 0.12f, StrokeCap.Round)
}

/** A sun, for brightness. */
@Composable
fun SunIcon(color: Color, size: Dp = 18.dp) = Icon(size) {
    val w = this.size.width
    val centre = Offset(w / 2, this.size.height / 2)
    drawCircle(color, radius = w * 0.19f, center = centre)
    for (i in 0 until 8) {
        val angle = Math.toRadians(i * 45.0)
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(color, centre + direction * (w * 0.31f), centre + direction * (w * 0.43f), w * 0.08f, StrokeCap.Round)
    }
}

/** A magnifying glass, for search. */
@Composable
fun SearchIcon(color: Color, size: Dp = 18.dp) = Icon(size) {
    val w = this.size.width
    val stroke = w * 0.1f
    val centre = Offset(w * 0.42f, w * 0.42f)
    drawCircle(color, radius = w * 0.26f, center = centre, style = Stroke(stroke))
    drawLine(color, Offset(w * 0.62f, w * 0.62f), Offset(w * 0.86f, w * 0.86f), stroke, StrokeCap.Round)
}

/** A cross, for clearing or closing. */
@Composable
fun CloseIcon(color: Color, size: Dp = 18.dp) = Icon(size) {
    val w = this.size.width
    val stroke = w * 0.1f
    drawLine(color, Offset(w * 0.25f, w * 0.25f), Offset(w * 0.75f, w * 0.75f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.75f, w * 0.25f), Offset(w * 0.25f, w * 0.75f), stroke, StrokeCap.Round)
}

/** A diagonal line across the icon, for "off". */
private fun DrawScope.slash(color: Color) {
    drawLine(color, Offset(size.width * 0.18f, size.height * 0.14f), Offset(size.width * 0.82f, size.height * 0.86f), size.width * 0.09f, StrokeCap.Round)
}

@Composable
private fun Icon(size: Dp, draw: DrawScope.() -> Unit) = Canvas(Modifier.size(size), onDraw = draw)
