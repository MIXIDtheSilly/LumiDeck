package app.lumideck.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.lumideck.R
import app.lumideck.data.AppEntry
import app.lumideck.data.rememberInstalledApps
import app.lumideck.data.rememberLumiPrefs
import app.lumideck.data.rememberPinnedKeys
import app.lumideck.helper.HelperLink
import app.lumideck.ui.LumiDimens
import kotlin.math.roundToInt

/** The desktop's menus, shared by the windows it's drawn in (see [TaskbarOverlay]). */
class DesktopState {
    var menuOpen by mutableStateOf(false)

    /** The taskbar button whose right-click menu is open. */
    var taskMenu by mutableStateOf<TaskMenuAnchor?>(null)

    fun toggleMenu() {
        val open = !menuOpen
        closeMenus()
        menuOpen = open
    }

    fun closeMenus() {
        menuOpen = false
        taskMenu = null
    }
}

/** A right-click menu for the taskbar button [key], centred [x] pixels from the left of the screen. */
data class TaskMenuAnchor(val key: String, val x: Float)

/** The desktop itself: the wallpaper, plus the taskbar and menus unless [TaskbarOverlay] shows them. */
@Composable
fun DesktopScreen(showShell: Boolean, state: DesktopState, windows: Windows) {
    Image(
        painterResource(R.drawable.wallpaper),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
    if (showShell) {
        // The taskbar goes over the menus' click-outside area, so its logo still closes the menu.
        MenuLayer(state, windows)
        TaskbarLayer(state, windows)
    }
}

/** The taskbar, along the bottom of the screen. */
@Composable
fun TaskbarLayer(state: DesktopState, windows: Windows) {
    val apps by rememberInstalledApps()
    val pinnedKeys = rememberPinnedKeys(rememberLumiPrefs())
    val items = rememberTaskbarItems(apps, pinnedKeys, windows)
    BottomLayer {
        Taskbar(
            items = items,
            menuOpen = state.menuOpen,
            onLogoClick = state::toggleMenu,
            onClick = { item ->
                state.closeMenus()
                val window = item.mainWindow
                if (window != null) windows.activate(window) else item.app?.let(windows::launch)
            },
            onSecondaryClick = { item, x ->
                state.closeMenus()
                state.taskMenu = TaskMenuAnchor(item.key, x)
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The app menu and the taskbar buttons' right-click menus, above the taskbar, and a
 * click-outside area that closes them. [onShownChange] reports whether there is anything
 * to show: a menu is open, or the app menu is still animating closed.
 */
@Composable
fun MenuLayer(state: DesktopState, windows: Windows, onShownChange: (Boolean) -> Unit = {}) {
    val apps by rememberInstalledApps()
    val prefs = rememberLumiPrefs()
    val pinnedKeys = rememberPinnedKeys(prefs)
    val items = rememberTaskbarItems(apps, pinnedKeys, windows)
    val menuState = remember { MutableTransitionState(false) }.apply { targetState = state.menuOpen }
    val anchor = state.taskMenu
    val taskItem = anchor?.let { items.firstOrNull { it.key == anchor.key } }
    val shown = menuState.currentState || menuState.targetState || taskItem != null
    SideEffect { onShownChange(shown) }
    // The app closed or was unpinned while its menu was open.
    LaunchedEffect(anchor, taskItem == null) {
        if (anchor != null && taskItem == null) state.taskMenu = null
    }

    BottomLayer {
        if (state.menuOpen || taskItem != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { state.closeMenus() } },
            )
        }

        AnimatedVisibility(
            visibleState = menuState,
            enter = fadeIn() + slideInVertically { it / 12 },
            exit = fadeOut() + slideOutVertically { it / 12 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = LumiDimens.TaskbarMargin, bottom = AboveTaskbar),
        ) {
            AppMenu(
                apps = apps,
                pinnedKeys = pinnedKeys,
                onLaunch = {
                    state.closeMenus()
                    windows.launch(it)
                },
                onTogglePin = { prefs.togglePin(it.key) },
                onDismiss = state::closeMenus,
            )
        }

        if (anchor != null && taskItem != null) {
            TaskMenu(
                title = taskItem.label,
                actions = taskActions(taskItem, windows, prefs),
                onDismiss = state::closeMenus,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = AboveTaskbar)
                    .centredAt(anchor.x, LumiDimens.TaskbarMargin),
            )
        }
    }
}

/** Menus open this far above the bottom of the screen, clear of the taskbar. */
private val AboveTaskbar = LumiDimens.TaskbarMargin * 2 + LumiDimens.TaskbarHeight + LumiDimens.MenuGap

/** The taskbar's buttons: pinned apps and, while the window helper runs, the apps open on this display. */
@Composable
private fun rememberTaskbarItems(apps: List<AppEntry>, pinnedKeys: List<String>, windows: Windows): List<TaskbarItem> {
    val all by HelperLink.windows.collectAsState()
    val displayId = windows.displayId
    return remember(apps, pinnedKeys, all, displayId) {
        taskbarItems(apps, pinnedKeys, all.orEmpty().filter { it.displayId == displayId })
    }
}

/** Centres this horizontally on [x] pixels from the left, keeping it [margin] inside the edges. */
private fun Modifier.centredAt(x: Float, margin: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val edge = margin.roundToPx()
    val maxLeft = maxOf(edge, constraints.maxWidth - placeable.width - edge)
    val left = (x - placeable.width / 2f).roundToInt().coerceIn(edge, maxLeft)
    layout(constraints.maxWidth, placeable.height) { placeable.place(left, 0) }
}

/**
 * Fills the screen down to the system navigation bar. Android forces the bar back whenever
 * app windows are open, so the taskbar always sits above it instead of jumping. The PC
 * client crops the bar's strip out of the video, so on a PC it never shows at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomLayer(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
        content = content,
    )
}
