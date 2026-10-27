package app.lumideck.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.lumideck.R
import app.lumideck.data.AppEntry
import app.lumideck.data.PanelFloat
import app.lumideck.data.Wallpaper
import app.lumideck.data.rememberDesktopSettings
import app.lumideck.data.rememberInstalledApps
import app.lumideck.data.rememberLumiPrefs
import app.lumideck.data.rememberPinnedKeys
import app.lumideck.helper.HelperLink
import app.lumideck.ui.LumiDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The desktop's menus, shared by the windows it's drawn in (see [TaskbarOverlay]). */
class DesktopState {
    var menuOpen by mutableStateOf(false)

    /** The open right-click menu on the taskbar. */
    var taskMenu by mutableStateOf<TaskMenuAnchor?>(null)

    /** The taskbar is attached to the screen edge rather than floating. */
    var panelAttached by mutableStateOf(false)

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

/**
 * A right-click menu for the taskbar button [key], or for the taskbar itself if [key] is
 * null, centred [x] pixels from the left of the screen.
 */
data class TaskMenuAnchor(val key: String?, val x: Float)

/** The desktop itself: the wallpaper, plus the taskbar and menus unless [TaskbarOverlay] shows them. */
@Composable
fun DesktopScreen(showShell: Boolean, state: DesktopState, windows: Windows) {
    WallpaperImage(rememberDesktopSettings(rememberLumiPrefs()).wallpaper)
    if (showShell) {
        // The taskbar goes over the menus' click-outside area, so its logo still closes the menu.
        MenuLayer(state, windows)
        TaskbarLayer(state, windows)
    }
}

@Composable
private fun WallpaperImage(wallpaper: Wallpaper) {
    val fill = Modifier.fillMaxSize()
    when (wallpaper) {
        Wallpaper.Builtin -> Image(painterResource(R.drawable.wallpaper), null, fill, contentScale = ContentScale.Crop)
        is Wallpaper.Plain -> Box(fill.background(Color(wallpaper.argb)))
        is Wallpaper.Picture -> {
            // Null while it loads, which shows the window's background colour.
            val picture by produceState<Result<ImageBitmap>?>(null, wallpaper.file) {
                value = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(wallpaper.file.path)!!.asImageBitmap() }
                }
            }
            picture?.let { result ->
                val bitmap = result.getOrNull()
                if (bitmap != null) Image(bitmap, null, fill, contentScale = ContentScale.Crop) else WallpaperImage(Wallpaper.Builtin)
            }
        }
    }
}

/**
 * The taskbar, along the bottom of the screen. [onHeightChange] gets how much of the screen
 * it takes up, which changes with its size setting and when it attaches to the edge.
 */
@Composable
fun TaskbarLayer(state: DesktopState, windows: Windows, onHeightChange: (Dp) -> Unit = {}) {
    val apps by rememberInstalledApps()
    val prefs = rememberLumiPrefs()
    val pinnedKeys = rememberPinnedKeys(prefs)
    val settings = rememberDesktopSettings(prefs)
    val items = rememberTaskbarItems(apps, pinnedKeys, windows)
    val attached = when (settings.panelFloat) {
        PanelFloat.Always -> false
        PanelFloat.Never -> true
        PanelFloat.Adaptive -> items.any { item -> item.windows.any(windows::reachesPanel) }
    }
    SideEffect { state.panelAttached = attached }
    // Floating height while it floats and while it slides between the two, so it's never cut off.
    var settled by remember { mutableStateOf(attached) }
    LaunchedEffect(attached) {
        if (attached) delay(PanelAnimationMs.toLong())
        settled = attached
    }
    val height = panelFootprint(settings.taskbarSize, attached = attached && settled)
    SideEffect { onHeightChange(height) }

    BottomLayer {
        Taskbar(
            items = items,
            size = settings.taskbarSize,
            clock = settings.clock,
            attached = attached,
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
            onPanelSecondaryClick = { x ->
                state.closeMenus()
                state.taskMenu = TaskMenuAnchor(null, x)
            },
            onReorder = prefs::reorderPins,
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
    val settings = rememberDesktopSettings(prefs)
    val items = rememberTaskbarItems(apps, pinnedKeys, windows)
    val aboveTaskbar = panelFootprint(settings.taskbarSize, state.panelAttached) + LumiDimens.MenuGap
    val menuState = remember { MutableTransitionState(false) }.apply { targetState = state.menuOpen }
    val anchor = state.taskMenu
    val taskItem = anchor?.key?.let { key -> items.firstOrNull { it.key == key } }
    val panelMenu = anchor != null && anchor.key == null
    val shown = menuState.currentState || menuState.targetState || taskItem != null || panelMenu
    SideEffect { onShownChange(shown) }
    // The app closed or was unpinned while its menu was open.
    LaunchedEffect(anchor, taskItem == null) {
        if (anchor?.key != null && taskItem == null) state.taskMenu = null
    }

    BottomLayer {
        if (state.menuOpen || taskItem != null || panelMenu) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { state.closeMenus() } },
            )
        }

        AnimatedVisibility(
            visibleState = menuState,
            // Quick, like Plasma's popups: the default springs take about 400ms to settle.
            enter = fadeIn(tween(MenuAnimationMs)) + slideInVertically(tween(MenuAnimationMs)) { it / 12 },
            exit = fadeOut(tween(MenuAnimationMs)) + slideOutVertically(tween(MenuAnimationMs)) { it / 12 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = LumiDimens.TaskbarMargin, bottom = aboveTaskbar),
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

        if (anchor != null && (taskItem != null || panelMenu)) {
            TaskMenu(
                title = taskItem?.label ?: "Taskbar",
                actions = if (taskItem != null) {
                    taskActions(taskItem, windows, prefs)
                } else {
                    panelActions(items.flatMap { it.windows }, windows)
                },
                onDismiss = state::closeMenus,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = aboveTaskbar)
                    .centredAt(anchor.x, LumiDimens.TaskbarMargin),
            )
        }
    }
}

private const val MenuAnimationMs = 110

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
