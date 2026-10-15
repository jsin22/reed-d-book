package dev.reedd.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

const val MAX_RATING = 5

/**
 * A book's 1-[MAX_RATING] rating as a row of typewriters: filled up to the
 * rating, outlined after it. Tapping the Nth sets the rating to N; tapping the
 * current rating again clears it. Each typewriter is its own clickable, so on
 * a library card a tap here never also opens the book.
 */
@Composable
fun TypewriterRating(rating: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val current = rating ?: 0
    Row(
        modifier.semantics { contentDescription = if (current == 0) "Not rated" else "Rated $current of $MAX_RATING" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (value in 1..MAX_RATING) {
            val filled = value <= current
            Box(
                Modifier
                    .size(width = 30.dp, height = 28.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (value == current) "Clear rating" else "Rate $value of $MAX_RATING",
                    ) { onRate(if (value == current) null else value) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (filled) TypewriterFilled else TypewriterOutlined,
                    contentDescription = null,
                    tint = if (filled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// -- the icon ----------------------------------------------------------------
// Material Icons has no typewriter, so it is drawn here on the usual 24x24
// grid: a sheet of paper behind the platen roller, a body that widens toward
// the bottom, two rows of keys and a space bar.

private fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top); horizontalLineTo(right); verticalLineTo(bottom); horizontalLineTo(left); close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

private fun PathBuilder.body() {
    moveTo(4.5f, 10.2f); horizontalLineTo(19.5f); lineTo(21.8f, 19.6f)
    quadTo(22f, 21f, 20.6f, 21f); horizontalLineTo(3.4f)
    quadTo(2f, 21f, 2.2f, 19.6f); close()
}

private fun PathBuilder.platen() {
    rect(2.5f, 7.2f, 21.5f, 9.2f)
    rect(1f, 7.6f, 2.5f, 8.8f)
    rect(21.5f, 7.6f, 23f, 8.8f)
}

private fun PathBuilder.keys(r: Float) {
    for (x in listOf(6f, 9f, 12f, 15f, 18f)) circle(x, 13f, r)
    for (x in listOf(7.5f, 10.5f, 13.5f, 16.5f)) circle(x, 15.8f, r)
}

private val ink = SolidColor(Color.Black)

/** Solid shapes with the keys, space bar and typed lines cut out of them. */
val TypewriterFilled: ImageVector = ImageVector.Builder(
    name = "TypewriterFilled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = ink, pathFillType = PathFillType.EvenOdd) {
        rect(7f, 2f, 17f, 7f)
        rect(9f, 3.4f, 15f, 4.2f)
        rect(9f, 5f, 13f, 5.8f)
    }
    path(fill = ink) { platen() }
    path(fill = ink, pathFillType = PathFillType.EvenOdd) {
        body()
        keys(0.85f)
        rect(8f, 18f, 16f, 19.2f)
    }
}.build()

/** The same outlines, stroked, with small solid keys. */
val TypewriterOutlined: ImageVector = ImageVector.Builder(
    name = "TypewriterOutlined", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(stroke = ink, strokeLineWidth = 1.4f) {
        moveTo(7.7f, 7.2f); verticalLineTo(2.7f); horizontalLineTo(16.3f); verticalLineTo(7.2f)
    }
    path(stroke = ink, strokeLineWidth = 1.4f) { rect(2.7f, 7.4f, 21.3f, 9.2f) }
    path(stroke = ink, strokeLineWidth = 1.4f) { body() }
    path(fill = ink) {
        rect(1f, 7.6f, 2f, 9f)
        rect(22f, 7.6f, 23f, 9f)
        keys(0.7f)
        rect(8f, 18.2f, 16f, 19.1f)
    }
}.build()
