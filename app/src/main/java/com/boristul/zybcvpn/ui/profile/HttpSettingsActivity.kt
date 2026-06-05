package com.boristul.zybcvpn.ui.profile

import com.boristul.zybcvpn.fmt.http.HttpBean

class HttpSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = HttpBean()

}
