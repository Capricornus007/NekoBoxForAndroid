package com.boristul.zybcvpn.ui

import android.os.Bundle
import com.boristul.zybcvpn.R
import com.boristul.zybcvpn.SagerNet
import com.boristul.zybcvpn.database.DataStore
import com.boristul.zybcvpn.database.ProfileManager
import com.boristul.zybcvpn.ktx.runOnMainDispatcher

class SwitchActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    override val isDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(true, null, R.string.action_switch)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        runOnMainDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(profileId, true)
        }
        SagerNet.reloadService()
        finish()
    }
}