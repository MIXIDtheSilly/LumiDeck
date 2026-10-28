package app.lumideck.desktop

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumideck.helper.TaskWindow
import app.lumideck.ui.DeskColors
import app.lumideck.ui.ItemShape
import app.lumideck.ui.LumiColors
import app.lumideck.ui.PanelShape

/**
 * The desktop's keyboard shortcuts, which the stream server takes out of the PC's keys
 * (server/DesktopKeys.kt) and passes on through [app.lumideck.helper.HelperProvider], to
 * the desktop on the display they're for.
 */
object Shortcuts {
    private val handler = Handler(Looper.getMainLooper())

    /** On the main thread. */
    private val desktops = mutableMapOf<Int, (String) -> Unit>()

    fun register(displayId: Int, onShortcut: (String) -> Unit) {
        desktops[displayId] = onShortcut
    }

    fun unregister(displayId: Int, onShortcut: (String) -> Unit) {
        if (desktops[displayId] === onShortcut) desktops.remove(displayId)
    }

    /** From any thread. */
    fun dispatch(displayId: Int, name: String) {
        handler.post { desktops[displayId]?.invoke(name) }
    }
}

/** Alt+Tab's list: the desktop's windows, most recently used first, and the one picked. */
data class SwitcherState(val windows: List<TaskWindow>, val index: Int) {
    val picked get() = windows[index]

    /** The next window along, or the one before. */
    fun step(forward: Boolean) = copy(index = (index + if (forward) 1 else windows.size - 1) % windows.size)

    companion object {
        /**
         * Starts at the window used before the one in front, as on Plasma and Windows, or
         * the last if [forward] is false. Null if there are no windows.
         */
        fun start(windows: List<TaskWindow>, forward: Boolean): SwitcherState? {
            val list = windows.sortedByDescending { it.lastActive }
            if (list.isEmpty()) return null
            val front = list.first().let { it.focused && it.visible }
            val index = when {
                !forward -> list.lastIndex
                front && list.size > 1 -> 1
                else -> 0
            }
            return SwitcherState(list, index)
        }
    }
}

/** The window switcher: each window's app, in a row, with the picked one highlighted. */
@Composable
fun Switcher(state: SwitcherState, items: List<TaskbarItem>, onPick: (TaskWindow) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .shadow(8.dp, PanelShape)
            .clip(PanelShape)
            .background(DeskColors.Panel)
            .border(1.dp, DeskColors.Border, PanelShape)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.windows.forEachIndexed { i, window ->
                val item = items.firstOrNull { item -> item.windows.any { it.taskId == window.taskId } }
                SwitcherEntry(item, window, picked = i == state.index, onClick = { onPick(window) })
            }
        }
    }
}

@Composable
private fun SwitcherEntry(item: TaskbarItem?, window: TaskWindow, picked: Boolean, onClick: () -> Unit) {
    val label = item?.label ?: window.component.packageName
    Column(
        Modifier
            .width(104.dp)
            .clip(ItemShape)
            .background(if (picked) DeskColors.Selected else Color.Transparent)
            .border(1.dp, if (picked) DeskColors.Accent else Color.Transparent, ItemShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = item?.icon
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(48.dp))
        } else {
            Box(Modifier.size(48.dp).clip(ItemShape).background(LumiColors.Teal), contentAlignment = Alignment.Center) {
                Text(label.take(1).uppercase(), color = LumiColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = if (window.visible) DeskColors.Text else DeskColors.TextDim,
            fontSize = 12.sp,
            fontWeight = if (picked) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
