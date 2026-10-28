package app.lumideck.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumideck.ui.DeskColors

/**
 * An app window's title bar (see [WindowDecor]), Breeze-style: the app's icon, its name in
 * the middle, then minimise, maximise and close. The window in front gets a bar a shade
 * darker, with its name in full colour. Double-clicking the bar maximises the window.
 */
@Composable
fun TitleBar(
    title: String,
    icon: ImageBitmap?,
    focused: Boolean,
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximized: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggleMaximized by rememberUpdatedState(onToggleMaximized)
    val color = if (focused) DeskColors.Text else DeskColors.TextDim
    Box(
        modifier
            .background(if (focused) DeskColors.PanelAlt else DeskColors.Panel)
            .drawBehind {
                val y = size.height - 0.5f
                drawLine(DeskColors.Border, Offset(0f, y), Offset(size.width, y), 1f)
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleMaximized() }) },
    ) {
        // Centred on the whole bar, clear of the icon and buttons on either side.
        Text(
            title,
            color = color,
            fontSize = 13.sp,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 108.dp),
        )
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.align(Alignment.CenterStart).padding(start = 11.dp).size(20.dp))
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp)) {
            TitleButton(Glyph.Minimize, "Minimise", color, onMinimize)
            TitleButton(if (maximized) Glyph.Restore else Glyph.Maximize, if (maximized) "Restore" else "Maximise", color, onToggleMaximized)
            TitleButton(Glyph.Close, "Close", color, onClose, danger = true)
        }
    }
}

private enum class Glyph { Minimize, Maximize, Restore, Close }

/** A round button that lights up under the pointer: red for close, as in Breeze. */
@Composable
private fun TitleButton(glyph: Glyph, label: String, color: Color, onClick: () -> Unit, danger: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        danger && pressed -> DeskColors.WarningPressed
        danger && hovered -> DeskColors.Warning
        pressed -> DeskColors.Pressed
        hovered -> DeskColors.Hover
        else -> Color.Transparent
    }
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(background)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val stroke = if (danger && (hovered || pressed)) Color.White else color
        Canvas(Modifier.size(10.dp)) { drawGlyph(glyph, stroke) }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        when (glyph) {
            Glyph.Minimize -> {
                moveTo(0f, h * 0.3f)
                lineTo(w / 2, h * 0.75f)
                lineTo(w, h * 0.3f)
            }
            Glyph.Maximize -> {
                moveTo(0f, h * 0.7f)
                lineTo(w / 2, h * 0.25f)
                lineTo(w, h * 0.7f)
            }
            Glyph.Restore -> {
                moveTo(w / 2, 0f)
                lineTo(w, h / 2)
                lineTo(w / 2, h)
                lineTo(0f, h / 2)
                close()
            }
            Glyph.Close -> {
                moveTo(0f, 0f)
                lineTo(w, h)
                moveTo(w, 0f)
                lineTo(0f, h)
            }
        }
    }
    drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** How tall title bars are: as tall as Android 13's own, 32dp buttons with 5dp around them. */
val TitleBarHeight = 42.dp

/**
 * Whether apps draw no title bar of their own, so LumiDeck's goes above each window rather
 * than over Android's. That's Android's `persist.debug.caption_on_shell`, which the PC
 * client turns on, and which apps follow once the device has restarted since.
 */
val titleBarsAbove: Boolean by lazy {
    runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "persist.debug.caption_on_shell") == "true"
    }.getOrDefault(false)
}
