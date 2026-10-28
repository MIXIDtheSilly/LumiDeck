package app.lumideck.desktop

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.graphics.RegionIterator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.lumideck.helper.HelperLink
import app.lumideck.helper.TaskWindow
import app.lumideck.ui.DeskColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Title bars and resizable edges for app windows.
 *
 * Android 13 gives every window a dark grey title bar of its own, but can't move windows by
 * it on LumiDeck's displays, nor resize them by their edges: it fails to take over the
 * pointer there ("Unable to transfer touch focus"). So LumiDeck covers that title bar with
 * its own and moves and resizes windows through the window helper.
 *
 * They're drawn in windows above every app (see [TaskbarOverlay]), so each app window's
 * title bar and edges only take up the parts no window in front of it covers, cut into
 * rectangles with a window each.
 *
 * A window dragged to the left or right edge of the screen snaps to that half, and to the
 * top edge, fills the screen, with an outline showing where while it's there.
 */
class WindowDecor(
    private val activity: ComponentActivity,
    private val overlay: TaskbarOverlay,
    private val state: DesktopState,
    private val windows: Windows,
) {
    private val density get() = activity.resources.displayMetrics.density

    private fun Dp.px() = (value * density).roundToInt()
    private val handler = Handler(Looper.getMainLooper())
    private val sender = BoundsSender()
    private val preview = SnapPreview()
    private var started = false
    private var job: Job? = null

    /** The windows showing the pieces, kept for reuse so the menus stay in front of them. */
    private val pool = mutableListOf<PieceWindow>()

    /** The window being moved or resized. */
    private var gesture: Gesture? = null

    /** A window LumiDeck brought to the front, and maybe moved, that the helper hasn't reported yet. */
    private var expected: Expected? = null

    fun start() {
        if (started) return
        // Before collecting, which starts right away with the current windows.
        started = true
        job = activity.lifecycleScope.launch { HelperLink.windows.collect { layout() } }
    }

    fun stop() {
        started = false
        job?.cancel()
        job = null
        pool.forEach { it.remove() }
        pool.clear()
        preview.remove()
        gesture = null
        expected = null
    }

    /** Fits the title bars and edges to where the windows are now. */
    fun layout() {
        if (!started) return
        val shown = HelperLink.windows.value.orEmpty()
            .filter { it.displayId == windows.displayId && it.visible }
            .sortedBy { it.layer }
        val expected = expected?.takeUnless { it.settled(shown) }
        this.expected = expected
        val raised = expected?.let { e -> shown.firstOrNull { it.taskId == e.taskId } }
        val frames = (if (raised != null) listOf(raised) + (shown - raised) else shown).map { window ->
            Frame(
                window,
                bounds = expected?.bounds?.takeIf { window === raised } ?: window.bounds,
                focused = if (raised != null) window === raised else window.focused,
            )
        }
        place(framePieces(frames))
    }

    /** What's left of each window's title bar and edges once the windows in front of it, and the taskbar, are taken out. */
    private fun framePieces(frames: List<Frame>): List<Piece> {
        val caption = CaptionHeight.px()
        val grip = GripWidth.px()
        val desktop = activity.window.decorView
        val screen = Rect(0, 0, desktop.width, desktop.height)
        val covered = Region(overlay.taskbarArea())
        val titles = mutableListOf<Piece>()
        val edges = mutableListOf<Pair<Frame, Region>>()
        val edgesInFront = Region()
        for (frame in frames) {
            val bounds = frame.bounds
            if (frame.window.freeform) {
                val title = Region(bounds.left, bounds.top, bounds.right, bounds.top + caption)
                title.op(screen, Region.Op.INTERSECT)
                title.op(covered, Region.Op.DIFFERENCE)
                title.rects().mapTo(titles) { Piece(frame, title = true, it) }

                val ring = Region(bounds.left - grip, bounds.top - grip, bounds.right + grip, bounds.bottom + grip)
                ring.op(bounds, Region.Op.DIFFERENCE)
                ring.op(screen, Region.Op.INTERSECT)
                ring.op(covered, Region.Op.DIFFERENCE)
                ring.op(edgesInFront, Region.Op.DIFFERENCE)
                edgesInFront.op(ring, Region.Op.UNION)
                edges += frame to ring
            }
            covered.op(bounds, Region.Op.UNION)
        }
        // Title bars go over the edges of windows in front of them, which are only a shadow.
        val allTitles = Region().apply { titles.forEach { op(it.rect, Region.Op.UNION) } }
        return titles + edges.flatMap { (frame, ring) ->
            ring.op(allTitles, Region.Op.DIFFERENCE)
            ring.rects().map { Piece(frame, title = false, it) }
        }
    }

    /** Shows [pieces], each in the window that showed the most of it before. */
    private fun place(pieces: List<Piece>) {
        val placed = LinkedHashMap<Piece, PieceWindow>()
        // One being pressed sticks to its piece, or Android would end the press.
        for (window in pool.filter { it.busy }) {
            val old = window.piece ?: continue
            pieces.filter { it !in placed && it.title == window.forTitle && it.taskId == old.taskId }
                .maxByOrNull { overlap(it.rect, old.rect) }
                ?.let { placed[it] = window }
        }
        val free = (pool - placed.values.toSet()).toMutableList()
        for (piece in pieces) {
            if (piece in placed) continue
            val window = free.filter { it.forTitle == piece.title }.maxByOrNull { it.likeness(piece) }
                ?: PieceWindow(piece.title).also { pool += it }
            free -= window
            placed[piece] = window
        }
        placed.forEach { (piece, window) -> window.show(piece) }
        free.forEach { it.hide() }
    }

    private fun press(frame: Frame) {
        state.closeMenus()
        if (!frame.focused) HelperLink.focus(frame.window.taskId)
        expected = Expected(frame.window.taskId, expected?.bounds?.takeIf { expected?.taskId == frame.window.taskId })
        layout()
    }

    /** The press on [frame] has ended without a drag. */
    private fun release() {
        if (gesture == null) settle()
    }

    /** Starts moving the window, or resizing it by [edges], from where the pointer is ([x], [y]) on screen. */
    private fun beginDrag(frame: Frame, x: Float, y: Float, edges: Int) {
        var start = Rect(frame.bounds)
        if (edges == 0 && windows.isArranged(frame.window)) {
            // Dragging a maximised or snapped window restores its size, keeping it under the pointer, as on Plasma.
            val restored = windows.unmaximized(frame.window)
            val left = (x - (x - start.left) / start.width() * restored.width()).roundToInt()
            start = Rect(left, start.top, left + restored.width(), start.top + restored.height())
        }
        gesture = Gesture(frame.window.taskId, x, y, start, edges)
        dragTo(x, y)
    }

    private fun dragTo(x: Float, y: Float) {
        val gesture = gesture ?: return
        val bounds = if (gesture.edges == 0) gesture.moved(x, y) else gesture.resized(x, y)
        if (gesture.edges == 0) {
            gesture.snap = snapTarget(x, y)
            preview.show(gesture.snap)
        }
        expected = Expected(gesture.taskId, bounds)
        layout()
        // Moving is cheap, but a resize redraws the app, so it waits between steps.
        sender.send(gesture.taskId, bounds, if (gesture.edges == 0) 0L else RESIZE_INTERVAL_MS)
    }

    private fun endDrag() {
        val gesture = gesture ?: return
        this.gesture = null
        sender.flush()
        preview.show(null)
        gesture.snap?.let { bounds ->
            // It goes back to the size it had, where it was picked up, when it's restored.
            windows.place(gesture.taskId, bounds, restore = gesture.start)
            expected = Expected(gesture.taskId, bounds)
            layout()
        }
        settle()
    }

    /**
     * Where a window being moved snaps to with the pointer at ([x], [y]): half the screen at
     * its left or right edge, all of it at the top.
     */
    private fun snapTarget(x: Float, y: Float): Rect? {
        val edge = SNAP_EDGE * density
        val width = activity.window.decorView.width
        return when {
            y <= edge -> windows.maximizedArea()
            x <= edge -> windows.snapArea(Snap.Left)
            x >= width - 1 - edge -> windows.snapArea(Snap.Right)
            else -> null
        }
    }

    /** Gives the helper a moment to report what LumiDeck did before trusting its reports again. */
    private fun settle() {
        val expected = expected ?: return
        expected.until = SystemClock.uptimeMillis() + SETTLE_MS
        handler.postDelayed(::layout, SETTLE_MS + 10)
    }

    private fun Gesture.moved(x: Float, y: Float): Rect {
        val bounds = Rect(start)
        bounds.offset((x - this.x).roundToInt(), (y - this.y).roundToInt())
        // Keep enough of the title bar on screen, above the taskbar, to drag it back.
        val desktop = activity.window.decorView
        val keep = (KEEP_VISIBLE * density).roundToInt()
        val left = bounds.left.coerceIn(keep - bounds.width(), maxOf(keep - bounds.width(), desktop.width - keep))
        val bottom = overlay.taskbarArea().top - CaptionHeight.px()
        val top = bounds.top.coerceIn(0, maxOf(0, bottom))
        bounds.offsetTo(left, top)
        return bounds
    }

    private fun Gesture.resized(x: Float, y: Float): Rect {
        val dx = (x - this.x).roundToInt()
        val dy = (y - this.y).roundToInt()
        val minWidth = (MIN_WIDTH * density).roundToInt()
        val minHeight = (MIN_HEIGHT * density).roundToInt()
        val screenBottom = activity.window.decorView.height
        return Rect(start).apply {
            if (edges and LEFT != 0) left = minOf(start.left + dx, start.right - minWidth)
            if (edges and RIGHT != 0) right = maxOf(start.right + dx, start.left + minWidth)
            if (edges and TOP != 0) top = minOf(maxOf(start.top + dy, 0), start.bottom - minHeight)
            if (edges and BOTTOM != 0) bottom = maxOf(minOf(start.bottom + dy, screenBottom), start.top + minHeight)
        }
    }

    /** Which edges of [bounds] a point just outside it resizes. Near a corner, it's both. */
    private fun edgesAt(bounds: Rect, x: Float, y: Float): Int {
        val corner = CORNER * density
        var edges = 0
        if (x < bounds.left) edges = edges or LEFT
        if (x >= bounds.right) edges = edges or RIGHT
        if (y < bounds.top) edges = edges or TOP
        if (y >= bounds.bottom) edges = edges or BOTTOM
        if (edges == TOP || edges == BOTTOM) {
            if (x < bounds.left + corner) edges = edges or LEFT
            if (x > bounds.right - corner) edges = edges or RIGHT
        }
        if (edges == LEFT || edges == RIGHT) {
            if (y < bounds.top + corner) edges = edges or TOP
            if (y > bounds.bottom - corner) edges = edges or BOTTOM
        }
        return edges
    }

    /** One of the windows the pieces are shown in. Hidden ones wait to be reused. */
    private inner class PieceWindow(val forTitle: Boolean) {
        var piece by mutableStateOf<Piece?>(null)
            private set

        /** Being pressed or dragged. */
        var busy = false

        private var added = false
        private val params = LayoutParams(
            0,
            0,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS or LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            title = if (forTitle) "LumiDeck title bar" else "LumiDeck window edge"
        }
        private val view: View = if (forTitle) overlay.withContent(TitleRoot(this)) { TitleContent(this) } else EdgeView(this)

        fun likeness(piece: Piece): Int {
            val old = this.piece ?: return 0
            return if (old.taskId == piece.taskId) overlap(old.rect, piece.rect) + 1 else 0
        }

        fun show(piece: Piece) {
            val r = piece.rect
            val moved = params.x != r.left || params.y != r.top || params.width != r.width() || params.height != r.height()
            this.piece = piece
            params.x = r.left
            params.y = r.top
            params.width = r.width()
            params.height = r.height()
            if (!added) {
                overlay.addUnderMenus(view, params)
                added = true
            } else if (moved) {
                overlay.updateWindow(view, params)
            }
            view.visibility = View.VISIBLE
            if (!forTitle) view.invalidate()
        }

        fun hide() {
            piece = null
            busy = false
            view.visibility = View.GONE
        }

        fun remove() {
            if (added) overlay.removeWindow(view)
            added = false
        }
    }

    /** A title bar, or the part of one that's in view. */
    @Composable
    private fun TitleContent(window: PieceWindow) {
        val piece = window.piece ?: return
        val frame = piece.frame
        val task = frame.window
        val item = state.taskbarItems.firstOrNull { item -> item.windows.any { it.taskId == task.taskId } }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            TitleBar(
                title = item?.label ?: task.component.packageName,
                icon = item?.icon,
                focused = frame.focused,
                maximized = windows.isMaximized(task),
                onMinimize = { windows.minimize(task) },
                onToggleMaximized = { windows.toggleMaximized(task) },
                onClose = { windows.close(task) },
                // The whole bar, placed so this window shows its part.
                modifier = Modifier.layout { measurable, constraints ->
                    val bar = measurable.measure(Constraints.fixed(frame.bounds.width(), CaptionHeight.px()))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        bar.place(frame.bounds.left - piece.rect.left, frame.bounds.top - piece.rect.top)
                    }
                },
            )
        }
    }

    /** Holds a title bar, and moves its window once a press on it is dragged. */
    private inner class TitleRoot(private val window: PieceWindow) : FrameLayout(activity) {
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var canDrag = false
        private var dragging = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                window.busy = true
                window.piece?.frame?.let(::press)
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                window.busy = false
                release()
            }
            return handled
        }

        override fun onInterceptTouchEvent(event: MotionEvent) = track(event)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            track(event)
            return true
        }

        /** Turns the press into a drag once it moves far enough, taking it from the buttons. */
        private fun track(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    canDrag = event.isPrimary()
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && canDrag && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                        val frame = window.piece?.frame
                        if (frame != null) {
                            dragging = true
                            beginDrag(frame, downX, downY, edges = 0)
                        }
                    }
                    if (dragging) dragTo(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    endDrag()
                }
            }
            return dragging
        }
    }

    /** A strip along a window's edges that resizes it, drawn as the window's shadow. */
    private inner class EdgeView(private val window: PieceWindow) : View(activity) {
        private val paint = Paint().apply { style = Paint.Style.STROKE }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val frame = window.piece?.frame ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!event.isPrimary()) return false
                    window.busy = true
                    press(frame)
                    beginDrag(frame, event.rawX, event.rawY, edgesAt(frame.bounds, event.rawX, event.rawY))
                }
                MotionEvent.ACTION_MOVE -> dragTo(event.rawX, event.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    window.busy = false
                    endDrag()
                }
            }
            return true
        }

        override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
            val frame = window.piece?.frame ?: return null
            val type = when (edgesAt(frame.bounds, event.getRawX(pointerIndex), event.getRawY(pointerIndex))) {
                LEFT, RIGHT -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
                TOP, BOTTOM -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
                LEFT or TOP, RIGHT or BOTTOM -> PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
                else -> PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
            }
            return PointerIcon.getSystemIcon(context, type)
        }

        override fun onDraw(canvas: Canvas) {
            val piece = window.piece ?: return
            val b = piece.frame.bounds
            val grip = GripWidth.px()
            canvas.translate(-piece.rect.left.toFloat(), -piece.rect.top.toFloat())
            // One-pixel rings fading out from the window's edge.
            for (i in 1..grip) {
                val fade = 1f - (i - 1f) / grip
                paint.color = android.graphics.Color.argb((SHADOW_ALPHA * fade * fade).roundToInt(), 0, 0, 0)
                canvas.drawRect(b.left - i + 0.5f, b.top - i + 0.5f, b.right + i - 0.5f, b.bottom + i - 0.5f, paint)
            }
        }
    }

    /**
     * Sends a window's new bounds to the helper: right away with no [interval], or else at
     * most every [interval] ms.
     */
    private inner class BoundsSender {
        private var pending: Pair<Int, Rect>? = null
        private var scheduled = false
        private var lastSent = 0L

        fun send(taskId: Int, bounds: Rect, interval: Long) {
            pending = taskId to bounds
            // Android hands over the pointer's moves once a frame, so this is once a frame
            // too. Waiting for the next one would hold the window back a frame behind its bar.
            if (interval == 0L && !scheduled) return flush()
            if (scheduled) return
            scheduled = true
            val wait = (lastSent + interval - SystemClock.uptimeMillis()).coerceAtLeast(0)
            Choreographer.getInstance().postFrameCallbackDelayed({
                scheduled = false
                flush()
            }, wait)
        }

        fun flush() {
            val (taskId, bounds) = pending ?: return
            pending = null
            lastSent = SystemClock.uptimeMillis()
            HelperLink.resize(taskId, bounds)
        }
    }

    /** An outline of where a window being dragged will snap to. */
    private inner class SnapPreview {
        private var view: View? = null
        private var shown: Rect? = null
        private val params = LayoutParams(
            0,
            0,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS or LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            title = "LumiDeck snap outline"
        }

        /** Shows the outline around [area], or hides it if that's null. */
        fun show(area: Rect?) {
            if (area == shown) return
            shown = area
            val view = view
            if (area == null) {
                view?.visibility = View.GONE
                return
            }
            val inset = SnapInset.px()
            params.x = area.left + inset
            params.y = area.top + inset
            params.width = area.width() - 2 * inset
            params.height = area.height() - 2 * inset
            if (view == null) {
                val accent = DeskColors.Accent.toArgb()
                this.view = View(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(accent and 0x00FFFFFF or (0x38 shl 24))
                        setStroke(2.dp.px(), accent)
                        cornerRadius = 8.dp.px().toFloat()
                    }
                }.also { overlay.addUnderMenus(it, params) }
            } else {
                overlay.updateWindow(view, params)
                view.visibility = View.VISIBLE
            }
        }

        fun remove() {
            view?.let(overlay::removeWindow)
            view = null
            shown = null
        }
    }

    /** A window as its frame is drawn: where it is, perhaps ahead of Android. */
    private data class Frame(val window: TaskWindow, val bounds: Rect, val focused: Boolean)

    /** Part of a window's title bar, or of the strip around its edges. */
    private data class Piece(val frame: Frame, val title: Boolean, val rect: Rect) {
        val taskId get() = frame.window.taskId
    }

    private class Gesture(val taskId: Int, val x: Float, val y: Float, val start: Rect, val edges: Int) {
        /** Where the window snaps to if it's let go now. */
        var snap: Rect? = null
    }

    /** [taskId] is in front, at [bounds] if they're given, until the helper says so or [until]. */
    private inner class Expected(val taskId: Int, val bounds: Rect?) {
        var until = Long.MAX_VALUE

        fun settled(shown: List<TaskWindow>): Boolean {
            if (gesture?.taskId == taskId) return false
            if (SystemClock.uptimeMillis() > until) return true
            val front = shown.firstOrNull() ?: return true
            return front.taskId == taskId && (bounds == null || front.bounds == bounds)
        }
    }

    private companion object {
        /** Android 13's own title bar: 32dp buttons with 5dp around them. LumiDeck's covers it exactly. */
        val CaptionHeight = 42.dp

        /** How far out from a window's edges it can be grabbed to resize it. */
        val GripWidth = 6.dp

        const val CORNER = 16f
        const val KEEP_VISIBLE = 64f
        /** How close to a screen edge the pointer snaps a window being moved. */
        const val SNAP_EDGE = 4f
        val SnapInset = 6.dp
        const val MIN_WIDTH = 320f
        const val MIN_HEIGHT = 200f
        const val SHADOW_ALPHA = 60f
        const val RESIZE_INTERVAL_MS = 50L
        const val SETTLE_MS = 1000L

        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8

        fun overlap(a: Rect, b: Rect): Int {
            val r = Rect()
            return if (r.setIntersect(a, b)) r.width() * r.height() else 0
        }

        fun Region.rects(): List<Rect> = buildList {
            val iterator = RegionIterator(this@rects)
            val r = Rect()
            while (iterator.next(r)) add(Rect(r))
        }

        /** A touch, or a mouse's left button. */
        fun MotionEvent.isPrimary() = getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE || isButtonPressed(MotionEvent.BUTTON_PRIMARY)
    }
}
