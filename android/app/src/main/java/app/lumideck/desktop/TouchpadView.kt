package app.lumideck.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.lumideck.helper.HelperLink
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The handheld's screen as a laptop touchpad, driving the helper's virtual mouse
 * ([HelperLink.mouse]), whose pointer Android shows on the desktop display:
 *
 * - one finger moves the pointer; a tap clicks
 * - two fingers scroll, the content following the fingers; a two-finger tap right-clicks
 * - tap, then touch again and move to drag; lifting ends the drag
 * - or hold a click button ([setButton]) with one hand and move with the other
 *
 * Only fingers that came down on the pad count: Compose hands it every finger on the screen,
 * the one holding a click button too.
 */
class TouchpadView(context: Context) : View(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    /** Finger travel per wheel click, in pixels. */
    private val scrollStep = SCROLL_STEP_DP * resources.displayMetrics.density

    /** Buttons held for a drag, or by [setButton] (the click bar). */
    private var dragButtons = 0
    private var barButtons = 0
    private val buttons get() = dragButtons or barButtons

    /** The fingers on the pad, by pointer id, and where each was last. */
    private val fingers = mutableMapOf<Int, Pair<Float, Float>>()
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var maxFingers = 0
    /** Farthest any finger got from where the first came down. */
    private var travel = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    /** Movement and scrolling not yet sent, under a whole pixel or click. */
    private var restX = 0f
    private var restY = 0f
    private var scrollRestX = 0f
    private var scrollRestY = 0f

    /** Holds or lets go of [button] (0 left, 1 right) from outside the pad. */
    fun setButton(button: Int, down: Boolean) {
        val bit = 1 shl button
        barButtons = if (down) barButtons or bit else barButtons and bit.inv()
        HelperLink.mouse(0, 0, buttons)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                if (x < 0 || y < 0 || x > width || y > height) return true
                if (fingers.isEmpty()) start(event.eventTime, x, y)
                fingers[event.getPointerId(i)] = x to y
                maxFingers = maxOf(maxFingers, fingers.size)
                scrollRestX = 0f
                scrollRestY = 0f
            }
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (fingers.remove(event.getPointerId(event.actionIndex)) != null && fingers.isEmpty()) {
                    end(event.eventTime, event.getX(event.actionIndex), event.getY(event.actionIndex))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                fingers.clear()
                if (dragButtons != 0) {
                    dragButtons = 0
                    HelperLink.mouse(0, 0, buttons)
                }
            }
        }
        return true
    }

    /** The first finger came down on the pad. */
    private fun start(time: Long, x: Float, y: Float) {
        downTime = time
        downX = x
        downY = y
        maxFingers = 0
        travel = 0f
        restX = 0f
        restY = 0f
        // A touch right after a tap, near it, holds the button: a drag if the finger moves,
        // the second click of a double-click if it lifts.
        if (time - lastTapTime < DOUBLE_TAP_MS && hypot(x - lastTapX, y - lastTapY) < slop * 4) {
            dragButtons = LEFT
            HelperLink.mouse(0, 0, buttons)
        }
    }

    /** The last finger left the pad. */
    private fun end(time: Long, x: Float, y: Float) {
        val tap = travel < slop && time - downTime < TAP_MS
        if (dragButtons != 0) {
            dragButtons = 0
            HelperLink.mouse(0, 0, buttons)
        } else if (tap) {
            val button = if (maxFingers >= 2) RIGHT else LEFT
            HelperLink.mouse(0, 0, buttons or button)
            HelperLink.mouse(0, 0, buttons)
            if (button == LEFT) {
                lastTapTime = time
                lastTapX = x
                lastTapY = y
            }
        }
    }

    private fun move(event: MotionEvent) {
        var dx = 0f
        var dy = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val (lastX, lastY) = fingers[id] ?: continue
            val x = event.getX(i)
            val y = event.getY(i)
            dx += x - lastX
            dy += y - lastY
            count++
            fingers[id] = x to y
            travel = maxOf(travel, hypot(x - downX, y - downY))
        }
        if (count == 0) return
        dx /= count
        dy /= count
        if (count >= 2) {
            // Natural scrolling: fingers moving up move the content up, as on a phone.
            scrollRestY += dy
            scrollRestX += dx
            val wheel = (scrollRestY / scrollStep).toInt()
            val hWheel = (-scrollRestX / scrollStep).toInt()
            if (wheel != 0 || hWheel != 0) {
                scrollRestY -= wheel * scrollStep
                scrollRestX += hWheel * scrollStep
                HelperLink.mouse(0, 0, buttons, wheel, hWheel)
            }
            return
        }
        // Two fingers lifting one at a time shouldn't jolt the pointer.
        if (maxFingers >= 2) return
        restX += dx * SPEED
        restY += dy * SPEED
        val moveX = restX.roundToInt()
        val moveY = restY.roundToInt()
        if (moveX == 0 && moveY == 0) return
        restX -= moveX
        restY -= moveY
        HelperLink.mouse(moveX, moveY, buttons)
    }

    private companion object {
        const val LEFT = 1
        const val RIGHT = 2
        const val TAP_MS = 250L
        const val DOUBLE_TAP_MS = 300L
        const val SCROLL_STEP_DP = 18f
        /** Pointer pixels per touchpad pixel, before Android's own acceleration. */
        const val SPEED = 1.1f
    }
}
