package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.Theme

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ViewBinding intentionally not used here: this is a base class whose concrete view is
        // supplied by each subclass's own layout (layout_route/layout_group/layout_about/...).
        // The toolbar id is shared across those layouts, so findViewById on the subclass view is
        // the correct generic lookup.
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationOnClickListener {
            (activity as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // 霧化時工具列要改成半透明，才露得出背後的模糊；非霧化維持主題原色。
        if (DataStore.blurEffectMode) {
            val hex = if (Theme.usingNightMode()) "#CC121212" else "#E6FFFFFF"
            val bar = view.findViewById<AppBarLayout?>(R.id.appbar)
            bar?.setBackgroundColor(Color.parseColor(hex))
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
