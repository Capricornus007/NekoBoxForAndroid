package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class TrustTunnelSettingsActivity : ProfileSettingsActivity<TrustTunnelBean>() {

    override fun createEntity() = TrustTunnelBean().applyDefaultValues()

    override fun TrustTunnelBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUserId = username
        DataStore.serverPassword = password
        DataStore.serverSNI = sni
        DataStore.serverPinnedCertChainSha256 = pinnedCertchainSha256
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverTrustTunnelQuic = quic
        DataStore.serverTrustTunnelQuicCongestion = quicCongestionControl
        DataStore.serverTrustTunnelHealthCheck = healthCheck
    }

    override fun TrustTunnelBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUserId
        password = DataStore.serverPassword
        sni = DataStore.serverSNI
        pinnedCertchainSha256 = DataStore.serverPinnedCertChainSha256
        allowInsecure = DataStore.serverAllowInsecure
        quic = DataStore.serverTrustTunnelQuic
        quicCongestionControl = DataStore.serverTrustTunnelQuicCongestion
        healthCheck = DataStore.serverTrustTunnelHealthCheck
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.trusttunnel_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}