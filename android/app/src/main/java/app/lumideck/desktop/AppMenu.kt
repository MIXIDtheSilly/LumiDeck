package app.lumideck.desktop

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumideck.data.AppEntry
import app.lumideck.ui.LumiColors
import app.lumideck.ui.PanelShape
import app.lumideck.ui.TileShape
import app.lumideck.ui.onSecondaryClick

/** Kickoff-style launcher: type to search, Enter opens the first match, Esc closes. */
@Composable
fun AppMenu(
    apps: List<AppEntry>,
    pinnedKeys: List<String>,
    onLaunch: (AppEntry) -> Unit,
    onTogglePin: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val results = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    Column(
        modifier
            .width(560.dp)
            .heightIn(max = 600.dp)
            .shadow(24.dp, PanelShape)
            .clip(PanelShape)
            .background(LumiColors.Panel)
            .border(1.dp, LumiColors.PanelBorder, PanelShape)
            .padding(16.dp)
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                    onDismiss(); true
                } else false
            },
    ) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = LumiColors.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(LumiColors.Mist),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(onLaunch) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(TileShape)
                        .background(LumiColors.Field)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    if (query.isEmpty()) Text("Search apps…", color = LumiColors.TextDim, fontSize = 15.sp)
                    field()
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        if (results.isEmpty()) {
            Text(
                if (apps.isEmpty()) "Loading apps…" else "No apps match \"$query\"",
                color = LumiColors.TextDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(results, key = { it.key }) { app ->
                    AppTile(
                        app = app,
                        pinned = app.key in pinnedKeys,
                        onClick = { onLaunch(app) },
                        onTogglePin = { onTogglePin(app) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Right-click or long-press an app to pin it to the taskbar",
            color = LumiColors.TextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // Type-to-search needs focus, but focusing without a physical keyboard would pop the
    // on-screen keyboard over the app grid.
    val config = LocalConfiguration.current
    val hasHardwareKeyboard = config.keyboard == Configuration.KEYBOARD_QWERTY &&
        config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    LaunchedEffect(hasHardwareKeyboard) {
        if (hasHardwareKeyboard) focus.requestFocus()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppEntry, pinned: Boolean, onClick: () -> Unit, onTogglePin: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .padding(2.dp)
            .clip(TileShape)
            .background(if (hovered) LumiColors.Hover else LumiColors.Panel.copy(alpha = 0f))
            .hoverable(interaction)
            .onSecondaryClick(onTogglePin)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onTogglePin,
                onClick = onClick,
            )
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(44.dp))
            if (pinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(LumiColors.Mist),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.label,
            color = LumiColors.Text,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
