package dev.reedd.ui.reader

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/**
 * A [Popup] positioned at an exact, caller-computed point relative to the
 * window -- bypassing [androidx.compose.ui.window.Popup]'s own convenience
 * `alignment`/`offset` parameters entirely (default `Alignment.TopStart`,
 * `offset` added to whatever that alignment resolves to against an implicit
 * anchor). Several rounds of a real, hard-to-diagnose positioning bug in
 * [SelectionHandle] came from assuming exactly how that resolved without a
 * device to check it against. [PopupPositionProvider]'s own contract has no
 * such ambiguity: whatever [calculatePosition] returns *is* the popup
 * content's top-left corner, relative to the window, full stop. The caller
 * still has to supply a genuinely window-relative point, though -- see the
 * `webViewWindowOffset` this is called with in `ReaderScreen.kt`'s own
 * `EpubNavigator`, and its own comment, for the other half of that: a rect
 * from `TapTextResolver`/`SelectionTextResolver` is relative to the
 * WebView's own viewport, not the window.
 */
private class FixedPopupPosition(private val x: Int, private val y: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(x, y)
}

fun fixedPopupPosition(x: Int, y: Int): PopupPositionProvider = FixedPopupPosition(x, y)
