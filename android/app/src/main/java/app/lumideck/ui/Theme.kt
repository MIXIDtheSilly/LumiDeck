package app.lumideck.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colours sampled from the LumiDeck logo gradient (img/Lumideck.png). */
object LumiColors {
    val Night = Color(0xFF101519)
    val Slate = Color(0xFF2B3A42)
    val Teal = Color(0xFF465D6B)
    val Mist = Color(0xFF82ADC8)

    val Text = Color(0xFFEAF2F3)
    val TextDim = Color(0xFF9DB3B7)
    val Warning = Color(0xFFE5786D)
}

/**
 * The desktop: Plasma's Breeze Light layout and contrast, tinted with the logo's light
 * blue. Everything is opaque.
 */
object DeskColors {
    val Panel = Color(0xFFF4F7FA)
    /** Kickoff's header and footer, a step darker than the panel. */
    val PanelAlt = Color(0xFFE6EDF3)
    val Border = Color(0xFFC5D3DE)
    val Field = Color(0xFFFFFFFF)

    val Accent = Color(0xFF3A87B8)
    val Hover = Accent.copy(alpha = 0.14f)
    val Pressed = Accent.copy(alpha = 0.24f)
    val Selected = Accent.copy(alpha = 0.22f)

    val Text = Color(0xFF1C2830)
    val TextDim = Color(0xFF5A6B78)
    val Warning = Color(0xFFD2503F)
    val WarningPressed = Color(0xFFB03F31)

    /** The app menu, Material 3 style: tonal surfaces from the accent, no borders. */
    val Sheet = Color(0xFFEDF3F8)
    val SearchField = Color(0xFFDAE6EF)
    val AccentContainer = Color(0xFFCCE3F3)
    val OnAccentContainer = Color(0xFF0F3A55)
}

/** The taskbar's height and icon size are settings: see [app.lumideck.data.TaskbarSize]. */
object LumiDimens {
    /** Gap between the floating taskbar and the screen edges. */
    val TaskbarMargin = 8.dp
    val MenuGap = 6.dp
}

/** Plasma's floating panel and popups: rounded rectangles with small corners. */
val PanelShape = RoundedCornerShape(8.dp)
val ItemShape = RoundedCornerShape(4.dp)

/** The app menu's Material 3 shapes: an extra-large sheet and rounded tiles. */
val SheetShape = RoundedCornerShape(28.dp)
val TileShape = RoundedCornerShape(20.dp)

@Composable
fun LumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LumiColors.Mist,
            onPrimary = LumiColors.Night,
            secondary = LumiColors.Teal,
            background = LumiColors.Night,
            onBackground = LumiColors.Text,
            surface = LumiColors.Night,
            onSurface = LumiColors.Text,
            surfaceVariant = LumiColors.Slate,
            onSurfaceVariant = LumiColors.TextDim,
        ),
        content = content,
    )
}
