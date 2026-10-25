package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import com.simplecityapps.recyclerview_fastscroll.views.FastScroller

/** Reuses the existing thumb, animations and section popup, with variable-height row geometry. */
class StableFastScrollRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FastScrollRecyclerView(context, attrs, defStyleAttr) {
    private val scrollbar: FastScroller
    private val bounds = Rect()
    private var downX = 0
    private var downY = 0
    private var lastY = 0
    private var thumbX = -1
    private var thumbY = -1
    private var thumbGesture = false
    private var pendingSeek: Pair<Int, Float>? = null

    init {
        // 2.0.1 keeps its renderer private; replace its scroll listener before adding our renderer.
        super.setFastScrollEnabled(false)
        super.setAutoHideEnabled(false)
        clearOnScrollListeners()
        scrollbar = FastScroller(context, this, attrs)
    }

    private fun rowOf(position: Int): Int {
        val grid = layoutManager as? GridLayoutManager ?: return position
        return grid.spanSizeLookup.getSpanGroupIndex(position, grid.spanCount)
    }

    private fun rowPosition(row: Int): Int {
        val grid = layoutManager as? GridLayoutManager ?: return row
        // All current grids have uniform spans; also keep this correct for a future header span.
        var low = 0
        var high = (adapter?.itemCount ?: 1) - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (grid.spanSizeLookup.getSpanGroupIndex(mid, grid.spanCount) < row) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    private fun rowBounds(row: Int): Pair<Int, Int>? {
        val manager = layoutManager ?: return null
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION || rowOf(position) != row) continue
            manager.getDecoratedBoundsWithMargins(child, bounds)
            top = minOf(top, bounds.top)
            bottom = maxOf(bottom, bounds.bottom)
        }
        return if (bottom > top) top to bottom else null
    }

    override fun onUpdateScrollbar() {
        val manager = layoutManager as? LinearLayoutManager ?: return
        val count = adapter?.itemCount ?: 0
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (count == 0 || first == NO_POSITION || last == NO_POSITION ||
            height - paddingTop - paddingBottom <= scrollbar.thumbHeight ||
            (!canScrollVertically(-1) && !canScrollVertically(1))
        ) {
            thumbX = -1
            thumbY = -1
            scrollbar.setThumbPosition(-1, -1)
            return
        }
        val firstRow = rowOf(first)
        val lastRow = rowOf(last)
        val start = rowBounds(firstRow) ?: return
        val end = rowBounds(lastRow) ?: return
        val before = firstRow + ((paddingTop - start.first).toFloat() / (start.second - start.first)).coerceIn(0f, 1f)
        val after = rowOf(count - 1) - lastRow +
            ((end.second - (height - paddingBottom)).toFloat() / (end.second - end.first)).coerceIn(0f, 1f)
        val progress = when {
            !canScrollVertically(-1) -> 0f
            !canScrollVertically(1) -> 1f
            else -> FastScrollGeometry.progress(before, after)
        }
        thumbX = if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL) 0 else width - scrollbar.width
        thumbY = FastScrollGeometry.thumbTop(progress, height, scrollbar.thumbHeight, paddingTop, paddingBottom)
        scrollbar.setThumbPosition(thumbX, thumbY)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        onUpdateScrollbar()
        scrollbar.draw(canvas)
    }

    override fun showScrollbar() = scrollbar.show()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val (row, progress) = pendingSeek ?: return
        pendingSeek = null
        val rowBounds = rowBounds(row) ?: return
        val rows = rowOf((adapter?.itemCount ?: 1) - 1) + 1
        val (_, offset) = FastScrollGeometry.target(
            progress,
            rows,
            height - paddingTop - paddingBottom,
            rowBounds.second - rowBounds.first,
        )
        // Align using the actual laid-out height, including margins, before the frame is drawn.
        scrollBy(0, rowBounds.first - paddingTop - offset)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean = handleThumbTouch(event)

    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        handleThumbTouch(event)
    }

    private fun handleThumbTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onUpdateScrollbar()
            downX = event.x.toInt()
            downY = event.y.toInt()
            lastY = downY
            val inset = 24 * resources.displayMetrics.density
            val visibleX = thumbX + scrollbar.offsetX
            thumbGesture = thumbX >= 0 && thumbY >= 0 &&
                event.x >= visibleX - inset && event.x <= visibleX + scrollbar.width + inset &&
                event.y >= thumbY - inset && event.y <= thumbY + scrollbar.thumbHeight + inset
            // Claim a thumb gesture before ViewPager2 can turn it into a group swipe.
            if (thumbGesture) parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            lastY = event.y.toInt()
        }
        if (!thumbGesture) return false
        scrollbar.handleTouchEvent(event, downX, downY, lastY, null)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            thumbGesture = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return scrollbar.isDragging
    }

    override fun scrollToPositionAtProgress(touchFraction: Float): String {
        val manager = layoutManager as? LinearLayoutManager ?: return ""
        val count = adapter?.itemCount ?: 0
        if (count == 0 || height <= paddingTop + paddingBottom) return ""
        val progress = FastScrollGeometry.dragProgress(touchFraction, height, scrollbar.thumbHeight, paddingTop, paddingBottom)
        val rows = rowOf(count - 1) + 1
        val targetRow = (progress * rows).toInt().coerceIn(0, rows - 1)
        val viewport = height - paddingTop - paddingBottom
        val position = rowPosition(targetRow)
        stopScroll()
        pendingSeek = targetRow to progress
        // Keep the requested row visible so onLayout can use its real height, even for a tall rule.
        manager.scrollToPositionWithOffset(position, (progress * (viewport - 1)).toInt())
        return (adapter as? SectionedAdapter)?.getSectionName(position).orEmpty()
    }
}
