package app.lumideck.desktop

import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.lumideck.ui.DeskColors
import app.lumideck.ui.PanelShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Which taskbar button shows its tooltip, Plasma-style: the hovered one, after a pause.
 * Once a tooltip is up, moving to the next button shows its tooltip straight away.
 */
@Stable
class TooltipState {
    /** The button under the pointer. */
    var hovered by mutableStateOf<String?>(null)

    var shown by mutableStateOf<String?>(null)
        private set

    /** The button clicked since the pointer arrived on it: no tooltip there until it leaves. */
    private var dismissed: String? = null

    fun dismiss() {
        dismissed = hovered
        shown = null
    }

    suspend fun run() {
        snapshotFlow { hovered }.collectLatest { key ->
            when (key) {
                null -> {
                    dismissed = null
                    // Linger across the gap between two buttons.
                    delay(150)
                    shown = null
                }
                dismissed -> shown = null
                else -> {
                    if (shown == null) delay(600)
                    // Unless it was clicked during the pause.
                    if (key != dismissed) shown = key
                }
            }
        }
    }
}

/** Room around a tooltip for its shadow, which the popup window would otherwise clip. */
val TooltipShadowMargin = 6.dp

/** A tooltip centred above its parent, [gap] clear of it. It never takes clicks or focus. */
@Composable
fun Tooltip(title: String, detail: String?, gap: Dp) {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    Popup(
        popupPositionProvider = AboveAnchor(gapPx),
        properties = PopupProperties(flags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE),
    ) {
        Column(
            Modifier
                .padding(TooltipShadowMargin)
                .shadow(4.dp, PanelShape)
                .clip(PanelShape)
                .background(DeskColors.Panel)
                .border(1.dp, DeskColors.Border, PanelShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(title, color = DeskColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, color = DeskColors.TextDim, fontSize = 12.sp)
        }
    }
}

private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = maxOf(0, windowSize.width - popupContentSize.width)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
        return IntOffset(x, anchorBounds.top - gap - popupContentSize.height)
    }
}
