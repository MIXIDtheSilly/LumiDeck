package app.lumideck.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumideck.data.LumiPrefs
import app.lumideck.helper.TaskWindow
import app.lumideck.ui.DeskColors
import app.lumideck.ui.ItemShape
import app.lumideck.ui.PanelShape

/** One entry in a taskbar button's right-click menu. */
class MenuAction(val label: String, val onClick: () -> Unit)

/** What a taskbar button's right-click menu offers, depending on whether the app is running. */
fun taskActions(item: TaskbarItem, windows: Windows, prefs: LumiPrefs): List<MenuAction> = buildList {
    val window = item.mainWindow
    val app = item.app
    if (window == null && app != null) add(MenuAction("Open") { windows.launch(app) })
    if (window != null) {
        if (window.freeform) {
            add(MenuAction(if (windows.isMaximized(window)) "Restore size" else "Maximise") { windows.toggleMaximized(window) })
        }
        add(if (window.visible) MenuAction("Minimise") { windows.minimize(window) } else MenuAction("Show") { windows.activate(window) })
    }
    if (app != null) {
        add(MenuAction(if (item.pinned) "Unpin from taskbar" else "Pin to taskbar") { prefs.togglePin(app.key) })
    }
    if (window != null) {
        val count = item.windows.size
        add(MenuAction(if (count > 1) "Close $count windows" else "Close") { item.windows.forEach(windows::close) })
    }
}

/** What the right-click menu on an empty part of the taskbar offers. [open] is every window on the desktop. */
fun panelActions(open: List<TaskWindow>, windows: Windows): List<MenuAction> = buildList {
    if (open.any { it.visible }) add(MenuAction("Show desktop") { windows.showDesktop(open) })
    add(MenuAction("Desktop settings") { windows.openSettings() })
}

/** A taskbar button's right-click menu, Plasma-style: the app's name, then a short list. */
@Composable
fun TaskMenu(title: String, actions: List<MenuAction>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(220.dp)
            .shadow(8.dp, PanelShape)
            .clip(PanelShape)
            .background(DeskColors.Panel)
            .border(1.dp, DeskColors.Border, PanelShape)
            .padding(4.dp),
    ) {
        Text(
            title,
            color = DeskColors.TextDim,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
        actions.forEach { action ->
            MenuRow(action.label) {
                onDismiss()
                action.onClick()
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        label,
        color = DeskColors.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ItemShape)
            .background(if (hovered) DeskColors.Hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
