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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.lumideck.R
import app.lumideck.data.AppEntry
import app.lumideck.ui.LumiColors
import app.lumideck.ui.LumiDimens
import app.lumideck.ui.PillShape
import app.lumideck.ui.onSecondaryClick

/**
 * The floating pill, Plasma-style: logo on the left, pinned apps next to it,
 * tray and clock on the right.
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
            .shadow(16.dp, PillShape)
            .clip(PillShape)
            .background(LumiColors.Panel)
            .border(1.dp, LumiColors.PanelBorder, PillShape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskbarButton(active = menuOpen, onClick = onLogoClick) {
            Icon(
                painterResource(R.drawable.ic_lumideck_glyph),
                contentDescription = "LumiDeck menu",
                tint = LumiColors.Text,
                modifier = Modifier.size(22.dp),
            )
        }
        if (pinned.isNotEmpty()) {
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .size(width = 1.dp, height = 24.dp)
                    .background(LumiColors.PanelBorder),
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
        Spacer(Modifier.width(14.dp))
    }
}

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
        active -> LumiColors.Active
        pressed -> LumiColors.Pressed
        hovered -> LumiColors.Hover
        else -> LumiColors.Panel.copy(alpha = 0f)
    }
    Box(
        Modifier
            .size(LumiDimens.TaskbarButton)
            .clip(CircleShape)
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
