package app.lumideck.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colours sampled from the LumiDeck logo gradient (img/Lumideck.png). */
object LumiColors {
    val Night = Color(0xFF101819)
    val Slate = Color(0xFF2E4145)
    val Teal = Color(0xFF47666B)
    val Mist = Color(0xFF82B8C3)

    val Panel = Color(0xE0131C1E)
    val PanelBorder = Color(0x1FFFFFFF)
    val Hover = Color(0x1AFFFFFF)
    val Pressed = Color(0x2EFFFFFF)
    val Active = Mist.copy(alpha = 0.28f)
    val Field = Color(0x12FFFFFF)

    val Text = Color(0xFFEAF2F3)
    val TextDim = Color(0xFF9DB3B7)
    val Warning = Color(0xFFE5786D)
}

object LumiDimens {
    val TaskbarHeight = 48.dp
    /** Gap between the floating pill and the screen edges. */
    val TaskbarMargin = 10.dp
    val TaskbarButton = 40.dp
    val TaskbarIcon = 28.dp
    val MenuGap = 8.dp
}

val PillShape = RoundedCornerShape(percent = 50)
val PanelShape = RoundedCornerShape(24.dp)
val TileShape = RoundedCornerShape(14.dp)

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
