package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

/**
 * OpenVPN 剖面編輯頁。欄位與核心 option/openvpn.go 的 client endpoint 一一對應，
 * 由 PreferenceBindingManager 用欄位名反射對應到 [OpenVPNBean]，所以新增欄位只要同時改
 * bean、XML 與這個清單三處（少了這裡，值就不會進資料庫）。
 *
 * 憑證／金鑰那幾格直接收 PEM 原文（含換行），跟 `.ovpn` 內嵌區塊同一個形狀，匯入後可以直接在這裡改。
 */
class OpenVPNSettingsActivity : ProfileSettingsActivity<OpenVPNBean>() {

    override fun createEntity() = OpenVPNBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val servers = pbm.add(PreferenceBinding(Type.Text, "servers"))
    private val transport = pbm.add(PreferenceBinding(Type.Text, "transport"))
    private val remoteRandom = pbm.add(PreferenceBinding(Type.Bool, "remoteRandom"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val ca = pbm.add(PreferenceBinding(Type.Text, "ca"))
    private val clientCert = pbm.add(PreferenceBinding(Type.Text, "clientCert"))
    private val clientKey = pbm.add(PreferenceBinding(Type.Text, "clientKey"))
    private val controlWrapType = pbm.add(PreferenceBinding(Type.Text, "controlWrapType"))
    private val controlWrapKey = pbm.add(PreferenceBinding(Type.Text, "controlWrapKey"))
    private val controlWrapDirection = pbm.add(PreferenceBinding(Type.Text, "controlWrapDirection"))
    private val staticKey = pbm.add(PreferenceBinding(Type.Text, "staticKey"))
    private val keyDirection = pbm.add(PreferenceBinding(Type.Text, "keyDirection"))
    private val cipher = pbm.add(PreferenceBinding(Type.Text, "cipher"))
    private val dataCiphers = pbm.add(PreferenceBinding(Type.Text, "dataCiphers"))
    private val dataCiphersFallback = pbm.add(PreferenceBinding(Type.Text, "dataCiphersFallback"))
    private val auth = pbm.add(PreferenceBinding(Type.Text, "auth"))
    private val compression = pbm.add(PreferenceBinding(Type.Text, "compression"))
    private val compressionLzo = pbm.add(PreferenceBinding(Type.Text, "compressionLzo"))
    private val allowCompression = pbm.add(PreferenceBinding(Type.Text, "allowCompression"))
    private val replayWindow = pbm.add(PreferenceBinding(Type.TextToInt, "replayWindow"))
    private val replayWindowTime = pbm.add(PreferenceBinding(Type.Text, "replayWindowTime"))
    private val mssFix = pbm.add(PreferenceBinding(Type.TextToInt, "mssFix"))
    private val fragment = pbm.add(PreferenceBinding(Type.TextToInt, "fragment"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val topology = pbm.add(PreferenceBinding(Type.Text, "topology"))
    private val routes = pbm.add(PreferenceBinding(Type.Text, "routes"))
    private val redirectGateway = pbm.add(PreferenceBinding(Type.Bool, "redirectGateway"))
    private val redirectGatewayFlags = pbm.add(PreferenceBinding(Type.Text, "redirectGatewayFlags"))
    private val redirectPrivate = pbm.add(PreferenceBinding(Type.Bool, "redirectPrivate"))
    private val blockIPv6 = pbm.add(PreferenceBinding(Type.Bool, "blockIPv6"))
    private val routeNoPull = pbm.add(PreferenceBinding(Type.Bool, "routeNoPull"))
    private val pullFilters = pbm.add(PreferenceBinding(Type.Text, "pullFilters"))
    private val pingInterval = pbm.add(PreferenceBinding(Type.Text, "pingInterval"))
    private val pingRestart = pbm.add(PreferenceBinding(Type.Text, "pingRestart"))
    private val handshakeWindow = pbm.add(PreferenceBinding(Type.Text, "handshakeWindow"))
    private val tlsTimeout = pbm.add(PreferenceBinding(Type.Text, "tlsTimeout"))
    private val renegotiateInterval = pbm.add(PreferenceBinding(Type.Text, "renegotiateInterval"))
    private val renegotiateDisabled = pbm.add(PreferenceBinding(Type.Bool, "renegotiateDisabled"))
    private val explicitExitNotify = pbm.add(PreferenceBinding(Type.TextToInt, "explicitExitNotify"))
    private val authRetry = pbm.add(PreferenceBinding(Type.Text, "authRetry"))
    private val staticChallenge = pbm.add(PreferenceBinding(Type.Text, "staticChallenge"))
    private val staticChallengeEcho = pbm.add(PreferenceBinding(Type.Bool, "staticChallengeEcho"))
    private val tlsServerName = pbm.add(PreferenceBinding(Type.Text, "tlsServerName"))
    private val tlsServerNameType = pbm.add(PreferenceBinding(Type.Text, "tlsServerNameType"))
    private val remoteCertificateTls = pbm.add(PreferenceBinding(Type.Text, "remoteCertificateTls"))
    private val nsCertificateType = pbm.add(PreferenceBinding(Type.Text, "nsCertificateType"))
    private val peerFingerprint = pbm.add(PreferenceBinding(Type.Text, "peerFingerprint"))
    private val remoteCertificateKu = pbm.add(PreferenceBinding(Type.Text, "remoteCertificateKu"))
    private val remoteCertificateEku = pbm.add(PreferenceBinding(Type.Text, "remoteCertificateEku"))
    private val tlsCipher = pbm.add(PreferenceBinding(Type.Text, "tlsCipher"))
    private val tlsGroups = pbm.add(PreferenceBinding(Type.Text, "tlsGroups"))
    private val tlsVersionMin = pbm.add(PreferenceBinding(Type.Text, "tlsVersionMin"))
    private val tlsVersionMax = pbm.add(PreferenceBinding(Type.Text, "tlsVersionMax"))
    private val customOutboundJson = pbm.add(PreferenceBinding(Type.Text, "customOutboundJson"))

    override fun OpenVPNBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun OpenVPNBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.openvpn_preferences)
        pbm.setPreferenceFragment(this)

        (password.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (clientKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (controlWrapKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (staticKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
    }
}
