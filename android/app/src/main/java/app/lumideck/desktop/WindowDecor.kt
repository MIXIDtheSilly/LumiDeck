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
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
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
 * pointer there ("Unable to transfer touch focus"). So LumiDeck draws its own and moves and
 * resizes windows through the window helper. With Android's turned off ([titleBarsAbove]),
 * LumiDeck's sits just above each window; otherwise it covers Android's.
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
    private val dragBar = DragBar { hidden ->
        // Its title bar's own window is hidden, and left where it is, once the drag bar shows.
        val moving = gesture?.takeIf { it.edges == 0 }?.taskId
        val concealed = pool.filter { moving != null && it.piece?.taskId == moving }
        concealed.forEach { it.conceal(true) }
        layout()
        concealed.firstOrNull()?.afterDraw(hidden) ?: hidden()
    }
    private var started = false
    private var job: Job? = null

    /**
     * The thread the windows along the windows' edges run on. Showing, hiding or moving any
     * of LumiDeck's windows is a relayout of ~12 ms on the RG556, and the many it takes as a
     * window is picked up held the drag up on the main thread for a quarter of a second.
     */
    private var edges: Handler? = null

    /** The windows showing the pieces, kept for reuse so the menus stay in front of them. */
    private val pool = mutableListOf<PieceWindow>()

    /** The window being moved or resized. */
    private var gesture: Gesture? = null

    /**
     * The window pressed on, laid out from the press as if it were already being moved (see
     * [heldArea]), so the relayouts that takes are done by the time the press turns into a drag.
     */
    private var pressed: Int? = null

    /** A window LumiDeck brought to the front, and maybe moved, that the helper hasn't reported yet. */
    private var expected: Expected? = null

    /** The desktop's size goes into where title bars fit, and it has none yet when a new display starts. */
    private val resized = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) refresh()
    }

    fun start() {
        if (started) return
        // Before collecting, which starts right away with the current windows.
        started = true
        edges = Handler(HandlerThread("LumiDeck window edges").apply { start() }.looper)
        activity.window.decorView.addOnLayoutChangeListener(resized)
        job = activity.lifecycleScope.launch { HelperLink.windows.collect { refresh() } }
        dragBar.prepare()
    }

    private fun refresh() {
        windows.adopt(HelperLink.windows.value.orEmpty())
        layout()
    }

    fun stop() {
        started = false
        activity.window.decorView.removeOnLayoutChangeListener(resized)
        job?.cancel()
        job = null
        pool.forEach { it.remove() }
        pool.clear()
        edges?.let { it.post { it.looper.quitSafely() } }
        edges = null
        preview.remove()
        dragBar.remove()
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
        place(framePieces(frames), heldArea(frames))
    }

    /**
     * What the window being moved or resized covers, with its edges when they show. The
     * other windows' pieces aren't cut to it but drawn around it (see [framePieces]).
     */
    private fun heldArea(frames: List<Frame>): Rect? {
        val taskId = gesture?.taskId ?: pressed ?: return null
        val frame = frames.firstOrNull { it.window.taskId == taskId } ?: return null
        return outline(frame.bounds).apply { if ((gesture?.edges ?: 0) != 0) inset(-GripWidth.px(), -GripWidth.px()) }
    }

    /** What's left of each window's title bar and edges once the windows in front of it, and the taskbar, are taken out. */
    private fun framePieces(frames: List<Frame>): List<Piece> {
        val grip = GripWidth.px()
        val desktop = activity.window.decorView
        val screen = Rect(0, 0, desktop.width, desktop.height)
        val covered = Region(overlay.taskbarArea())
        val titles = mutableListOf<Piece>()
        val edges = mutableListOf<Pair<Frame, Region>>()
        val edgesInFront = Region()
        // Every piece that moves costs Android a relayout, which takes this device ~12 ms,
        // so a window being moved goes without its edges until it's let go, and its title
        // bar slides along in the drag bar (see [DragBar]). The other windows' pieces aren't
        // cut to the window being moved or resized, which would move them at every step, but
        // stay as they are and leave out what it covers as they're drawn ([heldArea]).
        val held = gesture?.taskId ?: pressed
        val moving = held?.takeIf { gesture?.edges == 0 }
        val dragged = moving?.takeIf { dragBar.showing }
        for (frame in frames) {
            val bounds = if (frame.window.freeform) outline(frame.bounds) else frame.bounds
            if (frame.window.freeform && frame.window.taskId != dragged) {
                val title = Region(titleArea(frame.bounds))
                title.op(screen, Region.Op.INTERSECT)
                title.op(covered, Region.Op.DIFFERENCE)
                title.rects().mapTo(titles) { Piece(frame, title = true, it) }
            }
            if (frame.window.freeform && frame.window.taskId != moving) {
                val ring = Region(bounds.left - grip, bounds.top - grip, bounds.right + grip, bounds.bottom + grip)
                ring.op(bounds, Region.Op.DIFFERENCE)
                ring.op(screen, Region.Op.INTERSECT)
                ring.op(covered, Region.Op.DIFFERENCE)
                ring.op(edgesInFront, Region.Op.DIFFERENCE)
                if (frame.window.taskId != held) edgesInFront.op(ring, Region.Op.UNION)
                edges += frame to ring
            }
            if (frame.window.taskId != held) covered.op(bounds, Region.Op.UNION)
        }
        // Title bars go over the edges of windows in front of them, which are only a shadow.
        val allTitles = Region().apply { titles.forEach { if (it.taskId != held) op(it.rect, Region.Op.UNION) } }
        val heldTitles = Region().apply { titles.forEach { if (it.taskId == held) op(it.rect, Region.Op.UNION) } }
        return titles + edges.flatMap { (frame, ring) ->
            ring.op(allTitles, Region.Op.DIFFERENCE)
            if (frame.window.taskId == held) ring.op(heldTitles, Region.Op.DIFFERENCE)
            ring.rects().map { Piece(frame, title = false, it) }
        }
    }

    /** The title bar of a window at [bounds]: above them, or over Android's own at their top. */
    private fun titleArea(bounds: Rect): Rect {
        val caption = TitleBarHeight.px()
        return if (titleBarsAbove) Rect(bounds.left, bounds.top - caption, bounds.right, bounds.top)
        else Rect(bounds.left, bounds.top, bounds.right, bounds.top + caption)
    }

    /** All of a window at [bounds], its title bar included. */
    private fun outline(bounds: Rect) = Rect(bounds).apply { top = minOf(top, titleArea(bounds).top) }

    /** Shows [pieces], each in the window that showed the most of it before, leaving [held] out of other windows' pieces. */
    private fun place(pieces: List<Piece>, held: Rect?) {
        val placed = LinkedHashMap<Piece, PieceWindow>()
        // One being pressed sticks to its piece, or Android would end the press, and stays
        // put while it has none (its window is being moved).
        val kept = mutableSetOf<PieceWindow>()
        for (window in pool.filter { it.busy }) {
            val old = window.piece ?: continue
            pieces.filter { it !in placed && it.title == window.forTitle && it.taskId == old.taskId }
                .maxByOrNull { overlap(it.rect, old.rect) }
                ?.let { placed[it] = window } ?: kept.add(window)
        }
        val free = (pool - placed.values.toSet() - kept).toMutableList()
        for (piece in pieces) {
            if (piece in placed) continue
            val window = free.filter { it.forTitle == piece.title }.maxByOrNull { it.likeness(piece) }
                ?: PieceWindow(piece.title).also { pool += it }
            free -= window
            placed[piece] = window
        }
        placed.forEach { (piece, window) -> window.show(piece, held?.takeIf { piece.taskId != (gesture?.taskId ?: pressed) }) }
        free.forEach { it.hide() }
    }

    /** A press on [frame]'s title bar, or on its edges ([byTitle] false). */
    private fun press(frame: Frame, byTitle: Boolean = true) {
        state.closeMenus()
        if (!frame.focused) HelperLink.focus(frame.window.taskId)
        expected = Expected(frame.window.taskId, expected?.bounds?.takeIf { expected?.taskId == frame.window.taskId })
        pressed = frame.window.taskId
        if (byTitle) dragBar.warm(frame.window, frame.bounds.width())
        layout()
    }

    /** The press on a title bar has ended, after a drag ([endDrag]) or none. */
    private fun release() {
        if (pressed != null) {
            pressed = null
            layout()
        }
        if (gesture == null) settle()
        if (!dragBar.showing) dragBar.hide()
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
        if (edges == 0) dragBar.show(frame.window, start.width(), titleArea(gesture!!.moved(x, y)))
        dragTo(x, y)
    }

    private fun dragTo(x: Float, y: Float) {
        val gesture = gesture ?: return
        val bounds = if (gesture.edges == 0) gesture.moved(x, y) else gesture.resized(x, y)
        expected = Expected(gesture.taskId, bounds)
        // First, so the window gets there with the drag bar. A resize redraws the app, so it
        // waits between steps; the frame follows as the app is told, so the two stay together.
        sender.send(gesture.taskId, bounds, if (gesture.edges == 0) 0L else RESIZE_INTERVAL_MS)
        if (gesture.edges == 0) {
            dragBar.moveTo(titleArea(bounds))
            gesture.snap = snapTarget(x, y)
            preview.show(gesture.snap)
        }
    }

    private fun endDrag() {
        val gesture = gesture ?: return
        this.gesture = null
        pressed = null
        sender.flush()
        preview.show(null)
        gesture.snap?.let { bounds ->
            // It goes back to the size it had, where it was picked up, when it's restored.
            windows.place(gesture.taskId, bounds, restore = gesture.start)
            expected = Expected(gesture.taskId, bounds)
        }
        // With its edges back.
        layout()
        settle()
        if (gesture.edges == 0) {
            // Once its title bar's own window has had two frames to get there.
            handler.postDelayed({
                pool.forEach { it.conceal(false) }
                handler.postDelayed({ if (this.gesture == null) dragBar.hide() }, HANDOVER_MS)
            }, HANDOVER_MS)
        }
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
        val highest = bounds.top - titleArea(bounds).top
        val lowest = overlay.taskbarArea().top - titleArea(bounds).bottom + bounds.top
        val top = bounds.top.coerceIn(highest, maxOf(highest, lowest))
        bounds.offsetTo(left, top)
        return bounds
    }

    private fun Gesture.resized(x: Float, y: Float): Rect {
        val dx = (x - this.x).roundToInt()
        val dy = (y - this.y).roundToInt()
        val minWidth = (MIN_WIDTH * density).roundToInt()
        val minHeight = (MIN_HEIGHT * density).roundToInt()
        val screenBottom = activity.window.decorView.height
        // The title bar stays on screen.
        val highest = start.top - titleArea(start).top
        return Rect(start).apply {
            if (edges and LEFT != 0) left = minOf(start.left + dx, start.right - minWidth)
            if (edges and RIGHT != 0) right = maxOf(start.right + dx, start.left + minWidth)
            if (edges and TOP != 0) top = minOf(maxOf(start.top + dy, highest), start.bottom - minHeight)
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
        var piece: Piece? = null
            private set

        /** What its title bar shows, which a window only moving doesn't change, so it isn't drawn again. */
        var look by mutableStateOf<TitleLook?>(null)
            private set

        /** Being pressed or dragged. */
        var busy = false

        /** The part of it left out as it's drawn, in its own coordinates. */
        var cut: Rect? = null
            private set

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
            preventMoveAnimation()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            title = if (forTitle) "LumiDeck title bar" else "LumiDeck window edge"
        }
        private val view: View = if (forTitle) overlay.withContent(TitleRoot(this)) { TitleContent(this) } else EdgeView(this)

        fun likeness(piece: Piece): Int {
            val old = this.piece ?: return 0
            return if (old.taskId == piece.taskId) overlap(old.rect, piece.rect) + 1 else 0
        }

        /** Shows [piece], but not where it's under [over], given on screen. */
        fun show(piece: Piece, over: Rect?) {
            val r = piece.rect
            val moved = params.x != r.left || params.y != r.top || params.width != r.width() || params.height != r.height()
            this.piece = piece
            val cut = over?.let { Rect(it).apply { offset(-r.left, -r.top) } }?.takeIf { it.intersect(0, 0, r.width(), r.height()) }
            val cutChanged = cut != this.cut
            this.cut = cut
            if (forTitle) {
                val frame = piece.frame
                look = TitleLook(
                    frame.window.taskId,
                    frame.window.component.packageName,
                    frame.focused,
                    windows.isMaximized(frame.window),
                    frame.bounds.width(),
                    frame.bounds.left - r.left,
                    titleArea(frame.bounds).top - r.top,
                )
            }
            params.x = r.left
            params.y = r.top
            params.width = r.width()
            params.height = r.height()
            // An edge's own thread gets a copy, as these change again meanwhile.
            val params = if (forTitle) params else LayoutParams().apply { copyFrom(params) }
            val first = !added
            added = true
            val edge = if (forTitle) null else EdgeLook(Rect(r), Rect(piece.frame.bounds), cut)
            onViewThread {
                if (first) overlay.addUnderMenus(view, params) else if (moved) overlay.updateWindow(view, params)
                view.visibility = View.VISIBLE
                // An edge is drawn from its window's bounds, so only once they change.
                if (view is EdgeView) {
                    if (view.look != edge) view.invalidate()
                    view.look = edge
                } else if (cutChanged) {
                    view.invalidate()
                }
            }
        }

        fun hide() {
            piece = null
            look = null
            busy = false
            cut = null
            onViewThread {
                view.visibility = View.GONE
                view.alpha = 1f
                if (view is EdgeView) view.look = null
            }
        }

        /** Runs [block] on the thread the window runs on: the main one, or for an edge, [edges]. */
        private fun onViewThread(block: () -> Unit) {
            if (forTitle) block() else edges?.post(block)
        }

        /** Hides what it shows while [DragBar] shows its title bar elsewhere, but still takes the pointer. */
        fun conceal(hidden: Boolean) {
            view.alpha = if (hidden) 0f else 1f
        }

        /** Runs [block] once what it shows now is on screen, or after [DRAWN_MS] at the latest. */
        fun afterDraw(block: () -> Unit) {
            var done = false
            val once = {
                if (!done) {
                    done = true
                    block()
                }
            }
            view.viewTreeObserver.registerFrameCommitCallback(once)
            view.invalidate()
            handler.postDelayed(once, DRAWN_MS)
        }

        fun remove() {
            if (added) onViewThread { overlay.removeWindow(view) }
            added = false
        }
    }

    /** A title bar, or the part of one that's in view. */
    @Composable
    private fun TitleContent(window: PieceWindow) {
        val look = window.look ?: return
        val item = state.taskbarItems.firstOrNull { item -> item.windows.any { it.taskId == look.taskId } }
        // The window as it is when a button's clicked, since moving it doesn't come through here.
        val task = { window.piece?.frame?.window }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            TitleBar(
                title = item?.label ?: look.packageName,
                icon = item?.icon,
                focused = look.focused,
                maximized = look.maximized,
                onMinimize = { task()?.let(windows::minimize) },
                onToggleMaximized = { task()?.let(windows::toggleMaximized) },
                onClose = { task()?.let(windows::close) },
                // The whole bar, placed so this window shows its part.
                modifier = Modifier.layout { measurable, constraints ->
                    val bar = measurable.measure(Constraints.fixed(look.width, TitleBarHeight.px()))
                    layout(constraints.maxWidth, constraints.maxHeight) { bar.place(look.dx, look.dy) }
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

        override fun dispatchDraw(canvas: Canvas) {
            window.cut?.let(canvas::clipOutRect)
            super.dispatchDraw(canvas)
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

        /** What it shows, as its own thread last heard. */
        var look: EdgeLook? = null

        /** On the edges' thread, so what it does goes over to the main one. */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (look == null) return false
            val x = event.rawX
            val y = event.rawY
            val main = this@WindowDecor.handler
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!event.isPrimary()) return false
                    main.post {
                        val frame = window.piece?.frame ?: return@post
                        window.busy = true
                        press(frame, byTitle = false)
                        beginDrag(frame, x, y, edgesAt(outline(frame.bounds), x, y))
                    }
                }
                MotionEvent.ACTION_MOVE -> main.post { dragTo(x, y) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.post {
                    window.busy = false
                    endDrag()
                }
            }
            return true
        }

        override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
            val look = look ?: return null
            val type = when (edgesAt(outline(look.bounds), event.getRawX(pointerIndex), event.getRawY(pointerIndex))) {
                LEFT, RIGHT -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
                TOP, BOTTOM -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
                LEFT or TOP, RIGHT or BOTTOM -> PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
                else -> PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
            }
            return PointerIcon.getSystemIcon(context, type)
        }

        override fun onDraw(canvas: Canvas) {
            val look = look ?: return
            val b = outline(look.bounds)
            val grip = GripWidth.px()
            look.cut?.let(canvas::clipOutRect)
            canvas.translate(-look.rect.left.toFloat(), -look.rect.top.toFloat())
            // One-pixel rings fading out from the window's edge.
            for (i in 1..grip) {
                val fade = 1f - (i - 1f) / grip
                paint.color = android.graphics.Color.argb((SHADOW_ALPHA * fade * fade).roundToInt(), 0, 0, 0)
                canvas.drawRect(b.left - i + 0.5f, b.top - i + 0.5f, b.right + i - 0.5f, b.bottom + i - 0.5f, paint)
            }
        }
    }

    /**
     * Sends a window's new bounds to the helper, and fits its frame to them: right away with
     * no [interval], or else at most every [interval] ms.
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
            layout()
        }
    }

    /**
     * The title bar of the window being moved. It's drawn in a surface of its own that's
     * moved straight in SurfaceFlinger, as Android moves the window itself, so the two move
     * together, in the same frame, and moving costs nothing on LumiDeck's side. Moving the
     * title bar's own window instead costs a relayout each step (see [framePieces]). It
     * doesn't take the pointer: the press stays with the title bar's own window, hidden
     * once this shows ([onShown]).
     */
    private inner class DragBar(private val onShown: (hidden: () -> Unit) -> Unit) {
        /** A window over the screen that's only there during a drag, for the surface to show in. */
        private var host: FrameLayout? = null
        /** The surface's holder, as wide as the screen and as tall as a title bar. Never laid out again, so Android leaves its surface where it's moved to. */
        private var holder: SurfaceView? = null
        private var viewHost: SurfaceControlViewHost? = null
        /**
         * What the surface shows, see-through until a drag puts the surface over the window:
         * Android shows it at the top left of the screen from the press, when the window it's
         * in is put up, as it may become a drag.
         */
        private var bar: View? = null
        private var ready = false
        private var width = 0
        private var at: Rect? = null
        private var look by mutableStateOf<TitleLook?>(null)
        /**
         * Not [LayoutParams.FLAG_NOT_TOUCHABLE], as Android draws apps' overlay windows that
         * are at no more than 80% opacity, so others can be seen through them. It's only up
         * from a press to its end, while the press stays with the window it started in.
         */
        private val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            windowAnimations = 0
            preventMoveAnimation()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            title = "LumiDeck dragged title bar"
        }

        /** Showing a title bar, from once it's drawn after [show] until [hide]. */
        var showing = false
            private set

        /** Shown, and drawing the bar for the first time. */
        private var revealing = false

        /** Moving with the drag, once the window's own title bar is hidden. */
        private var following = false

        /** Where the drag started: over the window's own title bar. */
        private var from: Rect? = null

        /** Adds the window, hidden, ahead of the first drag. */
        fun prepare() {
            if (host != null) return
            val holder = SurfaceView(activity).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                layoutParams = FrameLayout.LayoutParams(activity.resources.displayMetrics.widthPixels, TitleBarHeight.px())
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        ready = true
                        place()
                    }

                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        ready = false
                        showing = false
                        revealing = false
                        following = false
                    }
                })
            }
            this.holder = holder
            host = FrameLayout(activity).apply {
                addView(holder)
                visibility = View.GONE
                // A press that lands on it all the same, when it was somehow left up, takes it down.
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && gesture == null) handler.post { hide() }
                    false
                }
            }.also { overlay.addUnderMenus(it, params) }
        }

        /**
         * Gets ready to show [window]'s title bar, [width] wide, as a press on it may turn
         * into a drag: putting up the window and drawing the bar take a relayout each, which
         * would hold up the drag's first steps.
         */
        fun warm(window: TaskWindow, width: Int) {
            prepare()
            val holder = holder ?: return
            if (viewHost == null) {
                val token = holder.hostToken ?: return
                val viewHost = SurfaceControlViewHost(activity, activity.display!!, token)
                val bar = overlay.withContent(FrameLayout(activity)) { DragTitle() }.apply { alpha = 0f }
                viewHost.setView(bar, 1, TitleBarHeight.px())
                holder.setChildSurfacePackage(viewHost.surfacePackage ?: return)
                this.viewHost = viewHost
                this.bar = bar
            }
            look = TitleLook(window.taskId, window.component.packageName, focused = true, maximized = false, width, 0, 0)
            if (width != this.width) {
                this.width = width
                viewHost?.relayout(width, TitleBarHeight.px())
            }
            host?.visibility = View.VISIBLE
        }

        /** Shows [window]'s title bar, [width] wide, at [title], once the surface is there. */
        fun show(window: TaskWindow, width: Int, title: Rect) {
            warm(window, width)
            at = title
            from = title
            if (ready) place()
        }

        fun moveTo(title: Rect) {
            at = title
            if (following) place()
        }

        /**
         * Puts the surface where the title bar is. At first it's over the window's own title
         * bar, which looks the same, until that's hidden ([onShown]) once the bar's been
         * drawn, so there's only ever one, and then it moves with the drag.
         */
        private fun place() {
            val title = (if (following) at else from) ?: return
            val surface = holder?.surfaceControl ?: return
            SurfaceControl.Transaction()
                .setPosition(surface, title.left.toFloat(), title.top.toFloat())
                .setVisibility(surface, true)
                .apply()
            if (showing || revealing) return
            val bar = bar ?: return
            revealing = true
            bar.alpha = 1f
            bar.viewTreeObserver.registerFrameCommitCallback {
                if (!revealing) return@registerFrameCommitCallback
                revealing = false
                showing = true
                onShown {
                    if (showing && !following) {
                        following = true
                        place()
                    }
                }
            }
        }

        fun hide() {
            at = null
            from = null
            look = null
            showing = false
            revealing = false
            following = false
            bar?.alpha = 0f
            host?.visibility = View.GONE
        }

        fun remove() {
            viewHost?.release()
            viewHost = null
            bar = null
            revealing = false
            following = false
            from = null
            host?.let(overlay::removeWindow)
            host = null
            holder = null
            ready = false
            showing = false
            width = 0
            at = null
            look = null
        }

        @Composable
        private fun DragTitle() {
            val look = look ?: return
            val item = state.taskbarItems.firstOrNull { item -> item.windows.any { it.taskId == look.taskId } }
            TitleBar(
                title = item?.label ?: look.packageName,
                icon = item?.icon,
                focused = true,
                maximized = false,
                onMinimize = {},
                onToggleMaximized = {},
                onClose = {},
                modifier = Modifier.fillMaxSize(),
            )
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
            preventMoveAnimation()
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

    /** A title bar as drawn: [width] wide, placed at [dx], [dy] in the window showing part of it. */
    private data class TitleLook(
        val taskId: Int,
        val packageName: String,
        val focused: Boolean,
        val maximized: Boolean,
        val width: Int,
        val dx: Int,
        val dy: Int,
    )

    /** A strip along a window's edges as drawn: where it is, around a window at [bounds], less [cut]. */
    private data class EdgeLook(val rect: Rect, val bounds: Rect, val cut: Rect?)

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
        /** How long a window's title bar takes to go from the drag bar back to its own window, in two steps. */
        const val HANDOVER_MS = 34L
        /** How long a window can take to draw a change before it's taken as done. */
        const val DRAWN_MS = 100L

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

/**
 * Has Android put the window where it's moved to straight away. Otherwise it slides an app's
 * overlay window there from where it was, over ~200 ms, when it moves and resizes at once,
 * as the windows title bars and edges are shown in do when they're given other pieces.
 * There's no API for it before Android 14, but the flag is written out with the params,
 * after their width, height, x, y, type and flags, so it's set there and they're read back.
 */
private fun LayoutParams.preventMoveAnimation() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return setCanPlayMoveAnimation(false)
    val noMoveAnimation = 0x40
    val parcel = Parcel.obtain()
    try {
        writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        if (List(6) { parcel.readInt() } != listOf(width, height, x, y, type, flags)) return
        val at = parcel.dataPosition()
        val privateFlags = parcel.readInt()
        parcel.setDataPosition(at)
        parcel.writeInt(privateFlags or noMoveAnimation)
        parcel.setDataPosition(0)
        copyFrom(LayoutParams(parcel))
    } finally {
        parcel.recycle()
    }
}
