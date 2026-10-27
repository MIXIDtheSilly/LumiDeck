package app.lumideck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumideck.data.ClockFormat
import app.lumideck.data.LumiPrefs
import app.lumideck.data.PanelFloat
import app.lumideck.data.TaskbarSize
import app.lumideck.data.Wallpaper
import app.lumideck.data.rememberDesktopSettings
import app.lumideck.data.rememberInstalledApps
import app.lumideck.data.rememberPinnedKeys
import app.lumideck.ui.DeskColors
import app.lumideck.ui.ItemShape
import app.lumideck.ui.LumiColors
import app.lumideck.ui.LumiTheme
import app.lumideck.ui.PanelShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** The desktop's settings, opened as a window from the app menu or the taskbar's right-click menu. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = LumiPrefs(this)
        setContent { LumiTheme { SettingsScreen(prefs) } }
    }
}

@Composable
private fun SettingsScreen(prefs: LumiPrefs) {
    val settings = rememberDesktopSettings(prefs)
    Box(
        Modifier
            .fillMaxSize()
            .background(DeskColors.PanelAlt)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Desktop Settings", color = DeskColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

            Section("Wallpaper") {
                WallpaperChoices(prefs, settings.wallpaper)
            }

            Section("Taskbar") {
                Label("Size")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskbarSize.entries.forEach { size ->
                        Chip(size.label, selected = settings.taskbarSize == size) { prefs.taskbarSize = size }
                    }
                }
                Spacer(Modifier.size(12.dp))
                Label("Position")
                PanelFloat.entries.forEach { mode ->
                    Choice(mode.label, selected = settings.panelFloat == mode) { prefs.panelFloat = mode }
                }
            }

            Section("Clock") {
                ClockFormat.entries.forEach { format ->
                    Choice(format.label, selected = settings.clock == format) { prefs.clock = format }
                }
            }

            Section("Pinned apps") {
                PinnedApps(prefs)
            }
        }
    }
}

@Composable
private fun WallpaperChoices(prefs: LumiPrefs, current: Wallpaper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { copyWallpaper(context, uri, prefs.newWallpaperFile()) }.getOrNull() }
            failed = file == null
            if (file != null) prefs.wallpaper = Wallpaper.Picture(file)
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatch("LumiDeck", selected = current == Wallpaper.Builtin, onClick = { prefs.wallpaper = Wallpaper.Builtin }) {
            Image(painterResource(R.drawable.wallpaper), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        PlainColors.forEach { (name, color) ->
            val wallpaper = Wallpaper.Plain(color.toArgb())
            Swatch(name, selected = current == wallpaper, onClick = { prefs.wallpaper = wallpaper }) {
                Box(Modifier.fillMaxSize().background(color))
            }
        }
        Swatch("Picture…", selected = current is Wallpaper.Picture, onClick = {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            val picture = (current as? Wallpaper.Picture)?.let { thumbnail(it.file) }
            if (picture != null) {
                Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(DeskColors.Field), contentAlignment = Alignment.Center) {
                    Text("+", color = DeskColors.TextDim, fontSize = 28.sp)
                }
            }
        }
    }
    if (failed) Text("That picture couldn't be opened.", color = DeskColors.Warning, fontSize = 13.sp)
}

/** Flat wallpapers in the logo's colours. */
private val PlainColors = listOf(
    "Night" to LumiColors.Night,
    "Slate" to LumiColors.Slate,
    "Teal" to LumiColors.Teal,
    "Mist" to LumiColors.Mist,
    "Light" to Color(0xFFCFE0EC),
)

/**
 * Copies a picked picture into [file], scaled down to at most 2560 pixels on its longer
 * side: plenty for a 1440p monitor, and quick for the desktop to load.
 */
private fun copyWallpaper(context: Context, uri: Uri, file: File): File {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val longest = max(info.size.width, info.size.height)
        if (longest > MaxWallpaperSide) {
            val scale = MaxWallpaperSide.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
    try {
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        return file
    } catch (error: Exception) {
        file.delete()
        throw error
    } finally {
        bitmap.recycle()
    }
}

private const val MaxWallpaperSide = 2560

@Composable
private fun thumbnail(file: File): ImageBitmap? = produceState<ImageBitmap?>(null, file) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = 240f / max(info.size.width, info.size.height)
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }.asImageBitmap()
        }.getOrNull()
    }
}.value

@Composable
private fun PinnedApps(prefs: LumiPrefs) {
    val apps by rememberInstalledApps()
    val pinnedKeys = rememberPinnedKeys(prefs)
    val byKey = apps.associateBy { it.key }
    val pinned = pinnedKeys.mapNotNull { byKey[it] }
    if (pinned.isEmpty()) {
        Text(
            "No pinned apps. Right-click an app in the app menu to pin it.",
            color = DeskColors.TextDim,
            fontSize = 13.sp,
        )
        return
    }
    Text(
        "In taskbar order. You can also drag them along the taskbar.",
        color = DeskColors.TextDim,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    pinned.forEachIndexed { i, app ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(app.icon, null, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                app.label,
                color = DeskColors.Text,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val keys = pinned.map { it.key }
            SmallButton("↑", enabled = i > 0) { prefs.reorderPins(keys.moved(i, i - 1)) }
            SmallButton("↓", enabled = i < pinned.lastIndex) { prefs.reorderPins(keys.moved(i, i + 1)) }
            SmallButton("Unpin") { prefs.togglePin(app.key) }
        }
    }
}

private fun List<String>.moved(from: Int, to: Int) = toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(DeskColors.Panel)
            .border(1.dp, DeskColors.Border, PanelShape)
            .padding(16.dp),
    ) {
        Text(title, color = DeskColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(10.dp))
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = DeskColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Swatch(name: String, selected: Boolean, onClick: () -> Unit, preview: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 112.dp, height = 70.dp)
                .clip(ItemShape)
                .border(
                    if (selected) BorderStroke(3.dp, DeskColors.Accent) else BorderStroke(1.dp, DeskColors.Border),
                    ItemShape,
                )
                .clickable(onClick = onClick)
                .padding(if (selected) 3.dp else 1.dp)
                .clip(ItemShape),
        ) {
            preview()
        }
        Text(name, color = DeskColors.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else DeskColors.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(ItemShape)
            .background(if (selected) DeskColors.Accent else DeskColors.Field)
            .border(1.dp, if (selected) DeskColors.Accent else DeskColors.Border, ItemShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ItemShape)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = DeskColors.Accent, unselectedColor = DeskColors.TextDim),
            modifier = Modifier.padding(8.dp),
        )
        Text(label, color = DeskColors.Text, fontSize = 14.sp)
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        label,
        color = if (enabled) DeskColors.Text else DeskColors.TextDim.copy(alpha = 0.4f),
        fontSize = 13.sp,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(ItemShape)
            .background(if (hovered && enabled) DeskColors.Hover else Color.Transparent)
            .border(1.dp, DeskColors.Border, ItemShape)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
