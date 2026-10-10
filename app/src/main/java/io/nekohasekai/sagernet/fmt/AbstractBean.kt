package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.utils.JavaUtil

abstract class AbstractBean : Serializable() {
    @JvmField var serverAddress: String? = null

    @JvmField var serverPort: Int? = null

    @JvmField var name: String? = null

    @JvmField var customOutboundJson: String? = null

    @JvmField var customConfigJson: String? = null

    @JvmField @Transient
    var finalAddress: String? = null

    @JvmField @Transient
    var finalPort = 0

    open fun displayName(): String = name?.takeIf { it.isNotBlank() } ?: displayAddress()
    open fun displayAddress(): String = "${serverAddress!!.wrapIPV6Host()}:$serverPort"
    open fun network() = "tcp,udp"
    open fun canICMPing() = true
    open fun canTCPing() = true
    open fun canMapping() = true

    override fun initializeDefaultValues() {
        serverAddress = serverAddress?.takeIf { it.isNotBlank() }?.unwrapIPV6Host() ?: "127.0.0.1"
        serverPort = serverPort ?: 1080
        name = name ?: ""
        finalAddress = serverAddress
        finalPort = serverPort!!
        customOutboundJson = customOutboundJson ?: ""
        customConfigJson = customConfigJson ?: ""
    }

    @Transient
    private var serializeWithoutName = false

    override fun serializeToBuffer(output: ByteBufferOutput) {
        serialize(output)
        output.writeInt(1)
        if (!serializeWithoutName) output.writeString(name)
        output.writeString(customOutboundJson)
        output.writeString(customConfigJson)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        deserialize(input)
        input.readInt()
        name = input.readString()
        customOutboundJson = input.readString()
        customConfigJson = input.readString()
    }

    open fun serialize(output: ByteBufferOutput) {
        output.writeString(serverAddress)
        output.writeInt(serverPort!!)
    }

    open fun deserialize(input: ByteBufferInput) {
        serverAddress = input.readString()
        serverPort = input.readInt()
    }

    abstract fun clone(): AbstractBean

    /**
     * Copies the settings the user owns from [from], the stored profile this bean refreshes, so a
     * subscription update keeps them. Everything else belongs to the provider; by default nothing
     * is local.
     */
    open fun keepLocalSettings(from: AbstractBean) {}

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AbstractBean || javaClass != other.javaClass) return false
        try {
            serializeWithoutName = true
            other.serializeWithoutName = true
            return KryoConverters.serialize(this).contentEquals(KryoConverters.serialize(other))
        } finally {
            serializeWithoutName = false
            other.serializeWithoutName = false
        }
    }

    override fun hashCode(): Int {
        try {
            serializeWithoutName = true
            return KryoConverters.serialize(this).contentHashCode()
        } finally {
            serializeWithoutName = false
        }
    }

    override fun toString() = "${javaClass.simpleName} ${JavaUtil.gson.toJson(this)}"
}
