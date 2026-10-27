package app.lumideck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.lumideck.R

/** The full-colour logo (img/Lumideck.png): the glyph on the gradient tile. */
@Composable
fun LumiLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.14f))
            .background(
                Brush.linearGradient(
                    0f to LumiColors.Night,
                    0.5f to LumiColors.Teal,
                    1f to LumiColors.Mist,
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .padding(size * 0.134f),
    ) {
        Icon(
            painterResource(R.drawable.ic_lumideck_glyph),
            contentDescription = null,
            tint = LumiColors.Text,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
