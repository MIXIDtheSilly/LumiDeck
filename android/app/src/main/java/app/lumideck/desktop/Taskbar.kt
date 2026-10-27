package app.lumideck.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.hoverable
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.lumideck.R
import app.lumideck.data.AppEntry
import app.lumideck.ui.DeskColors
import app.lumideck.ui.ItemShape
import app.lumideck.ui.LumiColors
import app.lumideck.ui.LumiDimens
import app.lumideck.ui.PanelShape
import app.lumideck.ui.onSecondaryClick

/**
 * The floating panel, Plasma-style: logo on the left, pinned apps next to it, tray and
 * clock on the right.
 */
@Composable
fun Taskbar(
    pinned: List<AppEntry>,
    menuOpen: Boolean,
    onLogoClick: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onUnpin: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(LumiDimens.TaskbarMargin)
            .height(LumiDimens.TaskbarHeight)
            .shadow(6.dp, PanelShape)
            .clip(PanelShape)
            .background(DeskColors.Panel)
            .border(1.dp, DeskColors.Border, PanelShape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskbarButton(active = menuOpen, onClick = onLogoClick) {
            Icon(
                painterResource(R.drawable.ic_lumideck_glyph),
                contentDescription = "LumiDeck menu",
                tint = LumiColors.Mist,
                modifier = Modifier.size(24.dp),
            )
        }
        pinned.forEach { app ->
            TaskbarButton(onClick = { onLaunch(app) }, onSecondaryClick = { onUnpin(app) }) {
                Image(app.icon, contentDescription = app.label, modifier = Modifier.size(LumiDimens.TaskbarIcon))
            }
        }
        Spacer(Modifier.weight(1f))
        BatteryIndicator()
        Spacer(Modifier.width(16.dp))
        Clock()
        Spacer(Modifier.width(10.dp))
    }
}

/** A full-height panel button, highlighted while [active]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskbarButton(
    onClick: () -> Unit,
    active: Boolean = false,
    onSecondaryClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        pressed -> DeskColors.Pressed
        active -> DeskColors.Selected
        hovered -> DeskColors.Hover
        else -> Color.Transparent
    }
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .fillMaxHeight()
            .width(LumiDimens.TaskbarButtonWidth)
            .clip(ItemShape)
            .background(background)
            .hoverable(interaction)
            .then(if (onSecondaryClick != null) Modifier.onSecondaryClick(onSecondaryClick) else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onSecondaryClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
