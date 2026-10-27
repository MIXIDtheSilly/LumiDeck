package app.lumideck.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.lumideck.R
import app.lumideck.data.AppEntry
import app.lumideck.data.rememberInstalledApps
import app.lumideck.data.rememberLumiPrefs
import app.lumideck.data.rememberPinnedKeys
import app.lumideck.ui.LumiDimens

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopScreen(
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onLaunch: (AppEntry) -> Unit,
) {
    val apps by rememberInstalledApps()
    val prefs = rememberLumiPrefs()
    val pinnedKeys = rememberPinnedKeys(prefs)
    val pinned = remember(apps, pinnedKeys) {
        val byKey = apps.associateBy { it.key }
        pinnedKeys.mapNotNull { byKey[it] }
    }
    val launchAndClose: (AppEntry) -> Unit = {
        onMenuOpenChange(false)
        onLaunch(it)
    }

    Image(
        painterResource(R.drawable.wallpaper),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )

    // Android forces the system navigation bar back whenever app windows are open, so the
    // taskbar always sits above it instead of jumping. The PC client crops the bar's strip
    // out of the video, so on a PC it never shows at all.
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
    ) {

        // Clicking anywhere outside the menu closes it.
        if (menuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onMenuOpenChange(false) } },
            )
        }

        AnimatedVisibility(
            visible = menuOpen,
            enter = fadeIn() + slideInVertically { it / 12 },
            exit = fadeOut() + slideOutVertically { it / 12 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = LumiDimens.TaskbarMargin,
                    bottom = LumiDimens.TaskbarMargin * 2 + LumiDimens.TaskbarHeight + LumiDimens.MenuGap,
                ),
        ) {
            AppMenu(
                apps = apps,
                pinnedKeys = pinnedKeys,
                onLaunch = launchAndClose,
                onTogglePin = { prefs.togglePin(it.key) },
                onDismiss = { onMenuOpenChange(false) },
            )
        }

        Taskbar(
            pinned = pinned,
            menuOpen = menuOpen,
            onLogoClick = { onMenuOpenChange(!menuOpen) },
            onLaunch = launchAndClose,
            onUnpin = { prefs.togglePin(it.key) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
