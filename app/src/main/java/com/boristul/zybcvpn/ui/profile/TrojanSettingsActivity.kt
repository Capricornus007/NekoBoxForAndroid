package com.boristul.zybcvpn.ui.profile

import com.boristul.zybcvpn.fmt.trojan.TrojanBean

class TrojanSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = TrojanBean()

}
