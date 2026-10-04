package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class MasqueSettingsActivity : ProfileSettingsActivity<MasqueBean>() {

    override fun createEntity() = MasqueBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val transport = pbm.add(PreferenceBinding(Type.Text, "transport"))
    private val useIPv6 = pbm.add(PreferenceBinding(Type.Bool, "useIPv6"))
    private val system = pbm.add(PreferenceBinding(Type.Bool, "system"))
    private val interfaceName = pbm.add(PreferenceBinding(Type.Text, "interfaceName"))
    private val allowedIPs = pbm.add(PreferenceBinding(Type.Text, "allowedIPs"))
    private val profileId = pbm.add(PreferenceBinding(Type.Text, "profileId"))
    private val profileAuthToken = pbm.add(PreferenceBinding(Type.Text, "profileAuthToken"))
    private val profilePrivateKey = pbm.add(PreferenceBinding(Type.Text, "profilePrivateKey"))
    private val profileRecreate = pbm.add(PreferenceBinding(Type.Bool, "profileRecreate"))
    private val profileDetour = pbm.add(PreferenceBinding(Type.Text, "profileDetour"))
    private val configPrivateKey = pbm.add(PreferenceBinding(Type.Text, "configPrivateKey"))
    private val configEndpointV4 = pbm.add(PreferenceBinding(Type.Text, "configEndpointV4"))
    private val configEndpointV6 = pbm.add(PreferenceBinding(Type.Text, "configEndpointV6"))
    private val configEndpointH2V4 = pbm.add(PreferenceBinding(Type.Text, "configEndpointH2V4"))
    private val configEndpointH2V6 = pbm.add(PreferenceBinding(Type.Text, "configEndpointH2V6"))
    private val configEndpointPubKey = pbm.add(PreferenceBinding(Type.Text, "configEndpointPubKey"))
    private val configLicense = pbm.add(PreferenceBinding(Type.Text, "configLicense"))
    private val configId = pbm.add(PreferenceBinding(Type.Text, "configId"))
    private val configAccessToken = pbm.add(PreferenceBinding(Type.Text, "configAccessToken"))
    private val configIPv4 = pbm.add(PreferenceBinding(Type.Text, "configIPv4"))
    private val configIPv6 = pbm.add(PreferenceBinding(Type.Text, "configIPv6"))
    private val udpTimeout = pbm.add(PreferenceBinding(Type.Text, "udpTimeout"))
    private val udpKeepalivePeriod = pbm.add(PreferenceBinding(Type.Text, "udpKeepalivePeriod"))
    private val udpInitialPacketSize = pbm.add(PreferenceBinding(Type.TextToInt, "udpInitialPacketSize"))
    private val disablePathMTUDiscovery = pbm.add(PreferenceBinding(Type.Bool, "disablePathMTUDiscovery"))
    private val h3FallbackTimeout = pbm.add(PreferenceBinding(Type.Text, "h3FallbackTimeout"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reconnectDelay = pbm.add(PreferenceBinding(Type.Text, "reconnectDelay"))
    private val tlsSNI = pbm.add(PreferenceBinding(Type.Text, "tlsSNI"))
    private val tlsInsecure = pbm.add(PreferenceBinding(Type.Bool, "tlsInsecure"))
    private val tlsCipherSuites = pbm.add(PreferenceBinding(Type.Text, "tlsCipherSuites"))
    private val tlsCurvePreferences = pbm.add(PreferenceBinding(Type.Text, "tlsCurvePreferences"))
    private val tlsFragment = pbm.add(PreferenceBinding(Type.Bool, "tlsFragment"))
    private val tlsFragmentFallbackDelay = pbm.add(PreferenceBinding(Type.Text, "tlsFragmentFallbackDelay"))
    private val tlsRecordFragment = pbm.add(PreferenceBinding(Type.Bool, "tlsRecordFragment"))
    private val tlsKernelTx = pbm.add(PreferenceBinding(Type.Bool, "tlsKernelTx"))
    private val tlsKernelRx = pbm.add(PreferenceBinding(Type.Bool, "tlsKernelRx"))

    override fun MasqueBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun MasqueBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.masque_preferences)
        pbm.setPreferenceFragment(this)

        (profileAuthToken.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (profilePrivateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (configPrivateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (configAccessToken.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
    }
}
