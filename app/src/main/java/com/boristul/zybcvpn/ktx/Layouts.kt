package com.boristul.zybcvpn.ktx

import android.graphics.Rect
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boristul.zybcvpn.ui.MainActivity

class FixedLinearLayoutManager(val recyclerView: RecyclerView) :
    LinearLayoutManager(recyclerView.context, RecyclerView.VERTICAL, false) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    private var listenerDisabled = false

    override fun scrollVerticallyBy(
        dx: Int, recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        return super.scrollVerticallyBy(dx, recycler, state)

    }

}
