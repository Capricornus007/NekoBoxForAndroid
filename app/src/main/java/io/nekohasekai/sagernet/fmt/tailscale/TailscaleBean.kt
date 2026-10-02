package io.nekohasekai.sagernet.fmt.tailscale

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

// A node in a tailnet. There is no server to dial: the control plane and DERP
// relays come from the control URL, so serverAddress/serverPort keep their
// defaults and only exist to satisfy AbstractBean.
class TailscaleBean : AbstractBean() {
    @JvmField
    var authKey: String? = null

    @JvmField
    var controlUrl: String? = null

    @JvmField
    var hostname: String? = null

    @JvmField
    var exitNode: String? = null

    @JvmField
    var exitNodeAllowLanAccess: Boolean? = null

    @JvmField
    var acceptRoutes: Boolean? = null

    @JvmField
    var onlyTcp443: Boolean? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        authKey = authKey ?: ""
        controlUrl = controlUrl ?: ""
        hostname = hostname ?: ""
        exitNode = exitNode ?: ""
        exitNodeAllowLanAccess = exitNodeAllowLanAccess ?: false
        acceptRoutes = acceptRoutes ?: false
        onlyTcp443 = onlyTcp443 ?: false
    }

    override fun displayAddress(): String {
        val exit = exitNode?.takeIf { it.isNotBlank() } ?: return "Tailscale"
        return "Tailscale via $exit"
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(authKey)
        output.writeString(controlUrl)
        output.writeString(hostname)
        output.writeString(exitNode)
        output.writeBoolean(exitNodeAllowLanAccess!!)
        output.writeBoolean(acceptRoutes!!)
        output.writeBoolean(onlyTcp443!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        authKey = input.readString()
        controlUrl = input.readString()
        hostname = input.readString()
        exitNode = input.readString()
        exitNodeAllowLanAccess = input.readBoolean()
        acceptRoutes = input.readBoolean()
        onlyTcp443 = input.readBoolean()
    }

    override fun canICMPing(): Boolean = false

    override fun canTCPing(): Boolean = false

    override fun canMapping(): Boolean = false

    override fun clone(): TailscaleBean = KryoConverters.deserialize(TailscaleBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TailscaleBean> = object : Serializable.CREATOR<TailscaleBean>() {
            override fun newInstance() = TailscaleBean()
            override fun newArray(size: Int): Array<TailscaleBean?> = arrayOfNulls(size)
        }
    }
}
