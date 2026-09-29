package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ListListener

/**
 * Hosts the settings hub and its pages in the child fragment manager. Tapping a nested
 * PreferenceScreen in global_preferences.xml pushes that screen as a page; back pops it.
 */
class SettingsFragment :
    ToolbarFragment(R.layout.layout_config_settings),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)

        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings, SettingsPreferenceFragment())
                .commit()
        }
        childFragmentManager.addOnBackStackChangedListener(backStackListener)
        updateToolbar()
    }

    private val backStackListener = FragmentManager.OnBackStackChangedListener { updateToolbar() }

    override fun onDestroyView() {
        childFragmentManager.removeOnBackStackChangedListener(backStackListener)
        super.onDestroyView()
    }

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        val page = SettingsPreferenceFragment().apply {
            arguments = bundleOf(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT to pref.key)
        }
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                androidx.fragment.R.animator.fragment_open_enter,
                androidx.fragment.R.animator.fragment_open_exit,
                androidx.fragment.R.animator.fragment_close_enter,
                androidx.fragment.R.animator.fragment_close_exit,
            )
            .replace(R.id.settings, page)
            .addToBackStack(pref.title?.toString())
            .commit()
        return true
    }

    private fun updateToolbar() {
        val depth = childFragmentManager.backStackEntryCount
        if (depth == 0) {
            toolbar.setTitle(R.string.settings)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
            toolbar.setNavigationOnClickListener {
                (activity as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        } else {
            toolbar.title = childFragmentManager.getBackStackEntryAt(depth - 1).name
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener { childFragmentManager.popBackStack() }
        }
    }

    override fun onBackPressed(): Boolean {
        if (childFragmentManager.backStackEntryCount == 0) return false
        childFragmentManager.popBackStack()
        return true
    }
}
