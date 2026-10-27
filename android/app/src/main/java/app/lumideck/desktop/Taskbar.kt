package app.lumideck.desktop

import android.os.UserHandle
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumideck.R
import app.lumideck.data.AppEntry
import app.lumideck.helper.TaskWindow
import app.lumideck.ui.DeskColors
import app.lumideck.ui.ItemShape
import app.lumideck.ui.LumiColors
import app.lumideck.ui.LumiDimens
import app.lumideck.ui.PanelShape
import app.lumideck.ui.onSecondaryClick

/** A taskbar button: a pinned app, a running one, or both. */
class TaskbarItem(
    val key: String,
    val label: String,
    /** Null when the app isn't in the app list; the button then shows its initial. */
    val icon: ImageBitmap?,
    /** The app, if it's in the app list. Only those can be pinned or started. */
    val app: AppEntry?,
    val pinned: Boolean,
    /** Its open windows, oldest first. */
    val windows: List<TaskWindow>,
) {
    val focused get() = windows.any { it.focused && it.visible }
    val minimized get() = windows.isNotEmpty() && windows.none { it.visible }

    /** The window a click acts on: the focused one, or else the one used last. */
    val mainWindow get() = windows.firstOrNull { it.focused && it.visible } ?: windows.maxByOrNull { it.lastActive }
}

/** Pinned apps in pin order, then the other running apps in the order they opened. */
fun taskbarItems(apps: List<AppEntry>, pinnedKeys: List<String>, windows: List<TaskWindow>): List<TaskbarItem> {
    val running = LinkedHashMap<String, MutableList<TaskWindow>>()
    val appFor = mutableMapOf<String, AppEntry?>()
    for (window in windows) {
        // Find the window's app by the activity that started it, or failing that its package.
        val user = UserHandle.getUserHandleForUid(window.userId * PER_USER_UID_RANGE)
        val app = apps.firstOrNull { it.user == user && it.component == window.component }
            ?: apps.firstOrNull { it.user == user && it.component.packageName == window.component.packageName }
        val key = app?.key ?: "${window.component.packageName}#${window.userId}"
        running.getOrPut(key) { mutableListOf() } += window
        appFor[key] = app
    }
    val byKey = apps.associateBy { it.key }
    val pinned = pinnedKeys.mapNotNull { byKey[it] }.map { app ->
        TaskbarItem(app.key, app.label, app.icon, app, pinned = true, windows = running.remove(app.key).orEmpty())
    }
    return pinned + running.map { (key, windows) ->
        val app = appFor[key]
        TaskbarItem(key, app?.label ?: windows.first().component.packageName, app?.icon, app, pinned = false, windows)
    }
}

/** Android gives each user a block of this many uids. */
private const val PER_USER_UID_RANGE = 100_000

/**
 * The floating panel, Plasma-style: logo on the left, then pinned and running apps, tray
 * and clock on the right. [onSecondaryClick] gets the button's centre, in pixels from the
 * left of the window.
 */
@Composable
fun Taskbar(
    items: List<TaskbarItem>,
    menuOpen: Boolean,
    onLogoClick: () -> Unit,
    onClick: (TaskbarItem) -> Unit,
    onSecondaryClick: (TaskbarItem, centerX: Float) -> Unit,
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
        items.forEach { item ->
            key(item.key) {
                var centerX by remember { mutableFloatStateOf(0f) }
                TaskbarButton(
                    onClick = { onClick(item) },
                    active = item.focused,
                    running = item.windows.isNotEmpty(),
                    dimmed = item.minimized,
                    onSecondaryClick = { onSecondaryClick(item, centerX) },
                    modifier = Modifier.onGloballyPositioned { centerX = it.boundsInWindow().center.x },
                ) {
                    AppIcon(item)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        BatteryIndicator()
        Spacer(Modifier.width(16.dp))
        Clock()
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun AppIcon(item: TaskbarItem) {
    val size = Modifier.size(LumiDimens.TaskbarIcon)
    if (item.icon != null) {
        Image(item.icon, contentDescription = item.label, modifier = size)
    } else {
        Box(size.clip(ItemShape).background(LumiColors.Teal), contentAlignment = Alignment.Center) {
            Text(item.label.take(1).uppercase(), color = LumiColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * A full-height panel button, highlighted while [active]. A running app gets a line along
 * the bottom: long while it's in front, faded while it's minimised.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskbarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    running: Boolean = false,
    dimmed: Boolean = false,
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
        modifier
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
        if (running) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(width = if (active) 18.dp else 6.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(if (dimmed) DeskColors.Accent.copy(alpha = 0.4f) else DeskColors.Accent),
            )
        }
    }
}
