package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.shadowquic.ShadowQUICBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class ShadowQUICSettingsActivity : ProfileSettingsActivity<ShadowQUICBean>() {

    override fun createEntity() = ShadowQUICBean().applyDefaultValues()

    override fun ShadowQUICBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUserId = username
        DataStore.serverPassword = password
        DataStore.serverSNI = sni
        DataStore.serverShadowQuicCongestion = congestionControl
        DataStore.serverShadowQuicAlpn = alpn
        DataStore.serverShadowQuicUdpOverStream = udpOverStream
        DataStore.serverShadowQuicZeroRtt = zeroRTT
        DataStore.serverShadowQuicSunny = sunnyQUIC
    }

    override fun ShadowQUICBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUserId
        password = DataStore.serverPassword
        sni = DataStore.serverSNI
        congestionControl = DataStore.serverShadowQuicCongestion
        alpn = DataStore.serverShadowQuicAlpn
        udpOverStream = DataStore.serverShadowQuicUdpOverStream
        zeroRTT = DataStore.serverShadowQuicZeroRtt
        sunnyQUIC = DataStore.serverShadowQuicSunny
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.shadowquic_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}