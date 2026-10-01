package io.nekohasekai.sagernet.fmt.shadowsocks

import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable

class ShadowsocksBean : AbstractBean() {
    @JvmField
    var method: String? = null

    @JvmField
    var password: String? = null

    @JvmField
    var plugin: String? = null

    @JvmField
    var sUoT: Boolean? = null

    @JvmField
    var enableMux: Boolean? = null

    @JvmField
    var muxPadding: Boolean? = null

    @JvmField
    var muxType: Int? = null

    @JvmField
    var muxConcurrency: Int? = null

    @JvmField
    var muxMode: Int? = null

    @JvmField
    var muxMaxConnections: Int? = null

    @JvmField
    var muxMinStreams: Int? = null

    @JvmField
    var muxBrutal: Boolean? = null

    @JvmField
    var muxBrutalUpMbps: Int? = null

    @JvmField
    var muxBrutalDownMbps: Int? = null

    override fun initializeDefaultValues() {
        super.initializeDefaultValues()
        if (method.isNullOrBlank()) {
            method = "aes-256-gcm"
        }
        method = method ?: ""
        password = password ?: ""
        plugin = plugin ?: ""
        sUoT = sUoT ?: false
        enableMux = enableMux ?: false
        muxPadding = muxPadding ?: false
        muxType = muxType ?: 0
        muxConcurrency = muxConcurrency ?: 8
        muxMode = muxMode ?: 0
        muxMaxConnections = muxMaxConnections ?: 4
        muxMinStreams = muxMinStreams ?: 4
        muxBrutal = muxBrutal ?: false
        muxBrutalUpMbps = muxBrutalUpMbps ?: 100
        muxBrutalDownMbps = muxBrutalDownMbps ?: 100
    }

    override fun serialize(output: ByteBufferOutput) {
        output.writeInt(5)
        super.serialize(output)
        output.writeString(method)
        output.writeString(password)
        output.writeString(plugin)
        output.writeBoolean(sUoT!!)
        output.writeBoolean(enableMux!!)
        output.writeBoolean(muxPadding!!)
        output.writeInt(muxType!!)
        output.writeInt(muxConcurrency!!)
        output.writeInt(muxMode!!)
        output.writeInt(muxMaxConnections!!)
        output.writeInt(muxMinStreams!!)
        output.writeBoolean(muxBrutal!!)
        output.writeInt(muxBrutalUpMbps!!)
        output.writeInt(muxBrutalDownMbps!!)
    }

    override fun deserialize(input: ByteBufferInput) {
        val version = input.readInt()
        super.deserialize(input)
        method = input.readString()
        password = input.readString()
        plugin = input.readString()
        sUoT = input.readBoolean()
        if (version >= 3) {
            enableMux = input.readBoolean()
            muxPadding = input.readBoolean()
            muxType = input.readInt()
            muxConcurrency = input.readInt()
        }
        if (version >= 4) {
            muxMode = input.readInt()
            muxMaxConnections = input.readInt()
            muxMinStreams = input.readInt()
        }
        if (version >= 5) {
            muxBrutal = input.readBoolean()
            muxBrutalUpMbps = input.readInt()
            muxBrutalDownMbps = input.readInt()
        }
    }

    override fun clone(): ShadowsocksBean = KryoConverters.deserialize(ShadowsocksBean(), KryoConverters.serialize(this))

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ShadowsocksBean> = object : Serializable.CREATOR<ShadowsocksBean>() {
            override fun newInstance() = ShadowsocksBean()
            override fun newArray(size: Int): Array<ShadowsocksBean?> = arrayOfNulls(size)
        }
    }
}
