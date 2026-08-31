package dev.reedd.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Both drag handles for a selection being extended, as one overlay -- see
 * `WordMenuTarget`'s own docstring for why dragging one of these, rather
 * than a long-press, is how a selection grows past a single tapped word.
 *
 * A single [Popup] whose own window is anchored **once**, at the reading
 * area's own bounds ([originXPx]/[originYPx]/[widthPx]/[heightPx] -- the
 * WebView's own on-screen rectangle, essentially never changing during
 * normal reading), and never repositioned or resized again after that --
 * not per-handle, and not per-drag-tick. Both dots are rendered inside via
 * a plain [Modifier.offset] from that fixed origin, tracking [startXPx]/
 * [startYPx]/[endXPx]/[endYPx] every recomposition exactly as before.
 *
 * This replaces a design where *each* handle owned its own small `Popup`,
 * repositioned to track that handle's own live position every tick (and,
 * during a drag, temporarily blown up to a screen-sized frozen window to
 * give the dot room to move without the earlier clipping bug -- see git
 * history for that version's own docstring). Two real, confirmed-live bugs
 * came from a *moving* popup:
 *
 * 1. Android reports each `MotionEvent` to a window in *that window's own*
 *    local coordinate space, computed at dispatch time from the window's
 *    *current* on-screen position. A window that repositions mid-gesture --
 *    which the old per-handle `Popup` did on every optimistic move and
 *    every JS-resolved snap -- reports little or no delta for the next
 *    touch event if it moved roughly the same amount the finger just did,
 *    fighting the drag to a near-standstill. Confirmed via 200 logged
 *    `extend()` calls during one continuous drag: the resolved endpoint
 *    barely left a ~10px box around the word it started at.
 * 2. Shrinking/repositioning the window back down at drag-end (even once
 *    (1) was fixed by freezing the window *during* a drag) produced a
 *    separate, still-reported visual glitch -- the handle visibly "floats"
 *    across the screen to its real position rather than landing there
 *    directly. Two different targeted fixes for *why* that transition
 *    looked wrong (platform on-screen nudging via `clippingEnabled`, then a
 *    delay to let a late `extend()` result land before unfreezing) each
 *    failed to resolve it, which is the actual reason this file no longer
 *    resizes or repositions a handle's window *at all*: rather than
 *    continuing to guess at what specifically made that transition ugly,
 *    this removes the transition itself. A window that never moves after
 *    being created has no transition to get wrong.
 *
 * [originXPx]/[originYPx]/[widthPx]/[heightPx] deliberately come from the
 * WebView's own bounds, not the full device screen: sizing this to cover
 * the whole screen would also cover the top app bar and the `WordMenuBar`
 * at the bottom (a `Popup` is a separate Android window that renders above
 * *all* of the host Activity's own content, including its Scaffold chrome),
 * silently swallowing every tap meant for those. The reading area is
 * exactly the region selection interactions are expected in.
 *
 * Because this overlay now covers the *whole* reading area rather than a
 * small per-handle target, it has to explicitly forward "tap in the empty
 * area" as [onDismiss] itself -- the underlying WebView's own tap listener,
 * which used to handle that when handles were small enough to leave most of
 * the page reachable, can no longer see those taps at all once this exists.
 * A tap that lands on a handle's own dot doesn't reach here: that dot's own
 * [Modifier.pointerInput] on [detectDragGestures] sits *inside* this
 * overlay's tree and is hit-tested first.
 *
 * `focusable = false`: a handle must never steal focus, since the rest of
 * the page still needs its own scroll/swipe gestures to keep working
 * untouched while text is being selected -- though see [onDismiss] above
 * for the one interaction (tap-elsewhere) this overlay now owns outright
 * rather than leaving to the page underneath.
 */
@Composable
fun SelectionHandlesOverlay(
    originXPx: Int,
    originYPx: Int,
    widthPx: Int,
    heightPx: Int,
    startXPx: Int,
    startYPx: Int,
    endXPx: Int,
    endYPx: Int,
    onStartDragStart: () -> Unit,
    onStartDrag: (dxPx: Float, dyPx: Float) -> Unit,
    onStartDragEnd: () -> Unit,
    onEndDragStart: () -> Unit,
    onEndDrag: (dxPx: Float, dyPx: Float) -> Unit,
    onEndDragEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val handleSizePx = with(density) { HANDLE_SIZE_DP.dp.roundToPx() }

    // A small margin around the reading area's own bounds, so a handle
    // resolved right at the very edge of the visible text still has room
    // for its full touch target without being clipped.
    val popupOriginXPx = originXPx - handleSizePx
    val popupOriginYPx = originYPx - handleSizePx
    val popupWidthPx = widthPx + handleSizePx * 2
    val popupHeightPx = heightPx + handleSizePx * 2

    Popup(
        popupPositionProvider = fixedPopupPosition(popupOriginXPx, popupOriginYPx),
        // clippingEnabled = false: the default (true) nudges a Popup's
        // whole window to keep it entirely on screen. This window is
        // deliberately oversized -- the reading area's bounds plus a
        // handleSizePx margin on every side, specifically so a handle right
        // at the very edge still has room -- which means it's *always*
        // nominally asking to extend past at least one screen edge, and the
        // platform's on-screen nudge doesn't know or care that only the
        // small dots inside actually need to be visible: it shifts the
        // *entire* window by a roughly constant amount regardless of where
        // in the text a handle actually is. That shift is exactly what a
        // real device showed as both handles landing a constant distance
        // (~5 characters, at that device's density) to the right of the
        // text they were meant to mark. fixedPopupPosition's own contract
        // already promises the exact position passed in, by construction;
        // this makes that promise actually hold, the same reasoning as the
        // earlier per-handle Popup version that had this same fix.
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .size(with(density) { popupWidthPx.toDp() }, with(density) { popupHeightPx.toDp() })
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { currentOnDismiss() })
                },
        ) {
            HandleDot(
                xPx = startXPx - popupOriginXPx,
                yPx = startYPx - popupOriginYPx,
                isStart = true,
                onDragStart = onStartDragStart,
                onDrag = onStartDrag,
                onDragEnd = onStartDragEnd,
            )
            HandleDot(
                xPx = endXPx - popupOriginXPx,
                yPx = endYPx - popupOriginYPx,
                isStart = false,
                onDragStart = onEndDragStart,
                onDrag = onEndDrag,
                onDragEnd = onEndDragEnd,
            )
        }
    }
}

/**
 * One handle's visible dot and its own drag target, positioned by a plain
 * offset within [SelectionHandlesOverlay]'s single, never-moving window.
 *
 * [onDragStart]/[onDrag]/[onDragEnd] are wrapped in [rememberUpdatedState]:
 * `pointerInput(Unit)`'s gesture-detection coroutine is long-lived (it is
 * *not* restarted just because this composable recomposes with new lambda
 * instances, since its key never changes), so without this indirection it
 * would keep calling whichever callback closures were current on the very
 * first composition -- stale data for the entire drag, rather than the
 * fresh values each recomposition actually captures.
 */
@Composable
private fun HandleDot(
    xPx: Int,
    yPx: Int,
    isStart: Boolean,
    onDragStart: () -> Unit,
    onDrag: (dxPx: Float, dyPx: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    // A visually small dot inside a much larger transparent touch target --
    // a handle exactly dot-sized would be a hard thing to reliably grab
    // with a fingertip.
    Box(
        modifier = Modifier
            .offset { IntOffset(xPx, yPx) }
            .size(HANDLE_SIZE_DP.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        currentOnDrag(amount.x, amount.y)
                    },
                    onDragEnd = { currentOnDragEnd() },
                )
            },
    ) {
        // Green/red rather than a theme color pair -- start/stop is a
        // universally understood convention, deliberately fixed rather than
        // following the theme's own primary/tertiary (which could plausibly
        // land on similar hues), so the two handles are never confused for
        // each other mid-drag.
        val color = if (isStart) HANDLE_START_COLOR else HANDLE_END_COLOR
        Box(
            modifier = Modifier
                .size(DOT_DP)
                .offset(x = (HANDLE_SIZE_DP.dp - DOT_DP) / 2, y = (HANDLE_SIZE_DP.dp - DOT_DP) / 2)
                .background(color, CircleShape),
        )
    }
}

/** In dp: each handle's touch target width and height. */
const val HANDLE_SIZE_DP = 32
private val DOT_DP = 14.dp
private val HANDLE_START_COLOR = Color(0xFF2E7D32)
private val HANDLE_END_COLOR = Color(0xFFC62828)
