package app.lumideck.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** Runs [onClick] on a mouse right-click. Touch users get the same action via long-press. */
fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
    }
}

/**
 * Runs [onClick] with the pointer's position on a right-click that no child took, such as
 * one on an empty part of a panel.
 */
fun Modifier.onSecondaryClickAt(onClick: (Offset) -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
                change.consume()
                onClick(change.position)
            }
        }
    }
}
