package io.nekohasekai.sagernet.fmt.wireguard

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

class WireGuardBean : AbstractBean() {
    @JvmField
    var localAddress: String? = null

    @JvmField
    var privateKey: String? = null

    @JvmField
    var peerPublicKey: String? = null

    @JvmField
    var peerPreSharedKey: String? = null

    @JvmField
    var mtu: Int? = null

    @JvmField
    var reserved: String? = null

    // Newline-separated prefixes routed to the peer; blank means 0.0.0.0/0 and ::/0.
    @JvmField
    var allowedIPs: String? = null

    // Seconds; 0 disables the keepalive.
    @JvmField
    var persistentKeepalive: Int? = null

    // Further peers as wg-quick [Peer] sections.
    @JvmField
    var extraPeers: String? = null

    // DNS servers and domains from the imported config; subscription updates refresh them.
    @JvmField
    var importedDnsServers: String? = null

    @JvmField
    var importedDnsDomains: String? = null

    // The user's DNS choice and overrides, which subscription updates keep. Blank overrides
    // follow the imported values.
    @JvmField
    var dnsMode: Int? = null

    @JvmField
    var customDnsServers: String? = null

    @JvmField
    var customDnsDomains: String? = null

    // How many of the extra peers come before the server peer, so the peers keep their order.
    @JvmField
    var serverPeerPosition: Int? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        localAddress = localAddress ?: ""
        privateKey = privateKey ?: ""
        peerPublicKey = peerPublicKey ?: ""
        peerPreSharedKey = peerPreSharedKey ?: ""
        mtu = mtu ?: 1420
        reserved = reserved ?: ""
        allowedIPs = allowedIPs ?: ""
        persistentKeepalive = persistentKeepalive ?: 0
        extraPeers = extraPeers ?: ""
        importedDnsServers = importedDnsServers ?: ""
        importedDnsDomains = importedDnsDomains ?: ""
        dnsMode = dnsMode ?: WireGuardDnsMode.APP
        customDnsServers = customDnsServers ?: ""
        customDnsDomains = customDnsDomains ?: ""
        serverPeerPosition = serverPeerPosition ?: 0
    }

    private fun usesVersion3Fields() = !allowedIPs.isNullOrEmpty() || (persistentKeepalive ?: 0) != 0 ||
        !extraPeers.isNullOrEmpty() || !importedDnsServers.isNullOrEmpty() || !importedDnsDomains.isNullOrEmpty() ||
        (dnsMode ?: WireGuardDnsMode.APP) != WireGuardDnsMode.APP || !customDnsServers.isNullOrEmpty() ||
        !customDnsDomains.isNullOrEmpty() || (serverPeerPosition ?: 0) != 0

    override fun keepLocalSettings(from: AbstractBean) {
        if (from !is WireGuardBean) return
        dnsMode = from.dnsMode ?: WireGuardDnsMode.APP
        customDnsServers = from.customDnsServers.orEmpty()
        customDnsDomains = from.customDnsDomains.orEmpty()
    }

    override fun serialize(output: ByteBufferOutput) {
        // Profiles without version 3 fields keep the version 2 bytes: stored blobs stay stable,
        // and so does the byte-based equality subscription updates compare profiles with.
        val version3 = usesVersion3Fields()
        output.writeInt(if (version3) 3 else 2)
        super.serialize(output)
        output.writeString(localAddress)
        output.writeString(privateKey)
        output.writeString(peerPublicKey)
        output.writeString(peerPreSharedKey)
        output.writeInt(mtu!!)
        output.writeString(reserved)
        if (version3) {
            output.writeString(allowedIPs)
            output.writeInt(persistentKeepalive ?: 0)
            output.writeString(extraPeers)
            output.writeString(importedDnsServers)
            output.writeString(importedDnsDomains)
            output.writeInt(dnsMode ?: WireGuardDnsMode.APP)
            output.writeString(customDnsServers)
            output.writeString(customDnsDomains)
            output.writeInt(serverPeerPosition ?: 0)
        }
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        localAddress = input.readString()
        privateKey = input.readString()
        peerPublicKey = input.readString()
        peerPreSharedKey = input.readString()
        mtu = input.readInt()
        reserved = input.readString()
        if (version >= 3) {
            allowedIPs = input.readString()
            persistentKeepalive = input.readInt()
            extraPeers = input.readString()
            importedDnsServers = input.readString()
            importedDnsDomains = input.readString()
            dnsMode = input.readInt()
            customDnsServers = input.readString()
            customDnsDomains = input.readString()
            serverPeerPosition = input.readInt()
        }
    }

    override fun canTCPing(): Boolean = false

    override fun clone(): WireGuardBean = KryoConverters.deserialize(WireGuardBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<WireGuardBean> = object : Serializable.CREATOR<WireGuardBean>() {
            override fun newInstance() = WireGuardBean()
            override fun newArray(size: Int): Array<WireGuardBean?> = arrayOfNulls(size)
        }
    }
}
