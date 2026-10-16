package io.nekohasekai.sagernet.widget

import kotlin.math.roundToInt

/** Row units keep scroll progress continuous when adjacent cards have different heights. */
internal object FastScrollGeometry {
    fun progress(rowsBefore: Float, rowsAfter: Float): Float {
        val before = rowsBefore.coerceAtLeast(0f)
        val after = rowsAfter.coerceAtLeast(0f)
        return if (before + after == 0f) 0f else before / (before + after)
    }

    fun dragProgress(raw: Float, height: Int, thumb: Int, top: Int, bottom: Int): Float {
        val track = height - top - bottom - thumb
        return if (track <= 0 || !raw.isFinite()) 0f
        else ((raw * (height - thumb) - top) / track).coerceIn(0f, 1f)
    }

    fun thumbTop(progress: Float, height: Int, thumb: Int, top: Int, bottom: Int): Int =
        top + ((height - top - bottom - thumb).coerceAtLeast(0) * progress.coerceIn(0f, 1f)).roundToInt()

    // Seek by row, aligning the point within that row with the same point in the viewport.
    fun target(progress: Float, rowCount: Int, viewport: Int, rowHeight: Int): Pair<Int, Int> {
        val p = progress.coerceIn(0f, 1f)
        val row = (p * rowCount).toInt().coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        val fraction = p * rowCount - row
        return row to (p * viewport - fraction * rowHeight).roundToInt()
    }
}
