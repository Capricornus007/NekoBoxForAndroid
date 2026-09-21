package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ListListener

class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.settings)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationOnClickListener {
            (activity as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        parentFragmentManager.beginTransaction()
            .replace(R.id.settings, SettingsPreferenceFragment())
            .commitAllowingStateLoss()
    }
}
