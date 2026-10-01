package io.nekohasekai.sagernet.fmt.shadowsocksr

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

class ShadowsocksRBean : AbstractBean() {
    @JvmField
    var method: String? = null

    @JvmField
    var password: String? = null

    @JvmField
    var protocol: String? = null

    @JvmField
    var protocolParam: String? = null

    @JvmField
    var obfs: String? = null

    @JvmField
    var obfsParam: String? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (method.isNullOrBlank()) {
            method = "none"
        }
        password = password ?: ""
        if (protocol.isNullOrBlank()) {
            protocol = "origin"
        }
        protocolParam = protocolParam ?: ""
        if (obfs.isNullOrBlank()) {
            obfs = "plain"
        }
        obfsParam = obfsParam ?: ""
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(0)
        super.serialize(output)
        output.writeString(method)
        output.writeString(password)
        output.writeString(protocol)
        output.writeString(protocolParam)
        output.writeString(obfs)
        output.writeString(obfsParam)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        method = input.readString()
        password = input.readString()
        protocol = input.readString()
        protocolParam = input.readString()
        obfs = input.readString()
        obfsParam = input.readString()
    }

    override fun clone(): ShadowsocksRBean = KryoConverters.deserialize(ShadowsocksRBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ShadowsocksRBean> = object : Serializable.CREATOR<ShadowsocksRBean>() {
            override fun newInstance() = ShadowsocksRBean()
            override fun newArray(size: Int): Array<ShadowsocksRBean?> = arrayOfNulls(size)
        }
    }
}
