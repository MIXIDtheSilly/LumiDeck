package app.lumideck.desktop

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumideck.data.AppEntry
import app.lumideck.ui.CloseIcon
import app.lumideck.ui.DeskColors
import app.lumideck.ui.SearchIcon
import app.lumideck.ui.SheetShape
import app.lumideck.ui.TileShape
import app.lumideck.ui.onSecondaryClick

/**
 * The app drawer, styled like Android 16's: a search pill over a grid of apps on a tonal
 * sheet. Type to search, Enter opens the highlighted first match, Esc closes.
 */
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
            .width(600.dp)
            // A fixed height, like Android's drawer, so it doesn't jump while searching.
            // Smaller screens clamp it.
            .height(620.dp)
            .shadow(16.dp, SheetShape)
            .clip(SheetShape)
            .background(DeskColors.Sheet)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                    onDismiss(); true
                } else false
            },
    ) {
        SearchBar(
            query = query,
            onQueryChange = { query = it },
            onGo = { results.firstOrNull()?.let(onLaunch) },
            focus = focus,
        )
        if (results.isEmpty()) {
            Text(
                if (apps.isEmpty()) "Loading apps…" else "No apps match \"${query.trim()}\"",
                color = DeskColors.TextDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 40.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f).fadeBottom(24.dp),
            ) {
                itemsIndexed(results, key = { _, app -> app.key }) { index, app ->
                    AppTile(
                        app = app,
                        pinned = app.key in pinnedKeys,
                        // What Enter opens.
                        highlighted = index == 0 && query.isNotBlank(),
                        onClick = { onLaunch(app) },
                        onTogglePin = { onTogglePin(app) },
                    )
                }
            }
        }
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

/** Fades the last [height] of the content out, so rows cut off by scrolling don't end hard. */
private fun Modifier.fadeBottom(height: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
            topLeft = Offset(0f, size.height - fade),
            blendMode = BlendMode.DstIn,
        )
    }

/** A pill-shaped field with a magnifier, and a button that clears it while it has text. */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onGo: () -> Unit, focus: FocusRequester) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = DeskColors.Text, fontSize = 16.sp),
        cursorBrush = SolidColor(DeskColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(DeskColors.SearchField)
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchIcon(DeskColors.TextDim, size = 22.dp)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", color = DeskColors.TextDim, fontSize = 16.sp)
                    field()
                }
                if (query.isNotEmpty()) {
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (hovered) DeskColors.Hover else Color.Transparent)
                            .hoverable(interaction)
                            .clickable(interactionSource = interaction, indication = null) {
                                onQueryChange("")
                                focus.requestFocus()
                            },
                        contentAlignment = Alignment.Center,
                    ) { CloseIcon(DeskColors.TextDim, size = 20.dp) }
                }
            }
        },
    )
}

/**
 * An app's icon and name. Hovering lays a tint over its rounded tile, and pressing
 * squeezes the icon with a spring, as Material 3 Expressive does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppEntry,
    pinned: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        when {
            highlighted && hovered -> lerp(DeskColors.AccentContainer, DeskColors.Accent, 0.12f)
            highlighted -> DeskColors.AccentContainer
            pressed -> DeskColors.Pressed
            hovered -> DeskColors.Hover
            else -> Color.Transparent
        },
        tween(90),
        label = "tile",
    )
    val iconScale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    Column(
        Modifier
            .padding(2.dp)
            .clip(TileShape)
            .background(background)
            .hoverable(interaction)
            .onSecondaryClick(onTogglePin)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onTogglePin,
                onClick = onClick,
            )
            .padding(top = 12.dp, bottom = 10.dp, start = 6.dp, end = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(52.dp).scale(iconScale))
            if (pinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(DeskColors.Sheet)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(DeskColors.Accent),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.label,
            color = if (highlighted) DeskColors.OnAccentContainer else DeskColors.Text,
            fontSize = 13.sp,
            fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
