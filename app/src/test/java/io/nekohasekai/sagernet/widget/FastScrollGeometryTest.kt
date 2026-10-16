package io.nekohasekai.sagernet.widget

import org.junit.Assert.*
import org.junit.Test

class FastScrollGeometryTest {
    @Test fun variableHeightRowsDoNotMoveThumbBackwards() {
        // Includes a short header, margins, multiline cards and a very tall rule.
        val heights = listOf(52, 168, 112, 96, 320, 104, 76, 176, 96, 400, 76)
        val starts = heights.runningFold(0) { sum, height -> sum + height }
        val viewport = 460
        var previous = 0f
        for (scroll in 0..(starts.last() - viewport)) {
            val first = heights.indices.first { starts[it + 1] > scroll }
            val last = heights.indices.last { starts[it] < scroll + viewport }
            val before = first + (scroll - starts[first]).toFloat() / heights[first]
            val after = heights.lastIndex - last +
                (starts[last + 1] - scroll - viewport).toFloat() / heights[last]
            val progress = FastScrollGeometry.progress(before, after)
            assertTrue("backwards at $scroll: $previous -> $progress", progress + 0.00001f >= previous)
            previous = progress
        }
        assertEquals(1f, previous, 0.00001f)
    }

    @Test fun draggingUsesSamePaddedTrackAsDrawing() {
        // The profile list reserves 148dp at the bottom for connection controls.
        for (height in listOf(800, 1600, 2400)) {
            for (p in listOf(0f, .1f, .5f, .9f, 1f)) {
                val y = FastScrollGeometry.thumbTop(p, height, 52, 8, 148)
                val raw = y.toFloat() / (height - 52)
                assertEquals(p, FastScrollGeometry.dragProgress(raw, height, 52, 8, 148), .002f)
            }
        }
    }

    @Test fun uniformRowsAndTwoColumnGridSeekRoundTrip() {
        // Grid metrics use row count, including an incomplete final row.
        for (rows in listOf(13, 61, 100)) for (p in listOf(0f, .1f, .5f, .9f, 1f)) {
            val (row, offset) = FastScrollGeometry.target(p, rows, 400, 80)
            val before = row - offset / 80f
            val after = rows - before - 400 / 80f
            assertEquals(p, FastScrollGeometry.progress(before, after), .001f)
            if (p == 1f) {
                assertEquals(rows - 1, row)
                assertEquals(320, offset)
            }
        }
    }

    @Test fun emptyAndSmallViewportsAreBounded() {
        assertEquals(0f, FastScrollGeometry.progress(0f, 0f), 0f)
        assertEquals(0f, FastScrollGeometry.dragProgress(.5f, 100, 52, 8, 148), 0f)
        assertEquals(0f, FastScrollGeometry.dragProgress(Float.NaN, 800, 52, 8, 148), 0f)
        assertEquals(1f, FastScrollGeometry.dragProgress(2f, 800, 52, 8, 148), 0f)
        assertEquals(0, FastScrollGeometry.target(0f, 0, 400, 80).first)
    }
}
