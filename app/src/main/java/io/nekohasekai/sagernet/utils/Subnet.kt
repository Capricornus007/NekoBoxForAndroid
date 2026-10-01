package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.ktx.parseNumericAddress
import java.net.InetAddress
import java.util.*

class Subnet(val address: InetAddress, val prefixSize: Int) : Comparable<Subnet> {
    companion object {
        fun fromString(value: String, lengthCheck: Int = -1): Subnet? {
            val parts = value.split('/', limit = 2)
            val addr = parts[0].parseNumericAddress() ?: return null
            check(lengthCheck < 0 || addr.address.size == lengthCheck)
            return if (parts.size == 2) {
                try {
                    val prefixSize = parts[1].toInt()
                    if (prefixSize < 0 || prefixSize > addr.address.size shl 3) {
                        null
                    } else {
                        Subnet(
                            addr,
                            prefixSize,
                        )
                    }
                } catch (_: NumberFormatException) {
                    null
                }
            } else {
                Subnet(addr, addr.address.size shl 3)
            }
        }
    }

    private val addressLength get() = address.address.size shl 3

    init {
        require(prefixSize in 0..addressLength) { "prefixSize $prefixSize not in 0..$addressLength" }
    }

    override fun toString(): String = if (prefixSize == addressLength) address.hostAddress else address.hostAddress + '/' + prefixSize

    private fun Byte.unsigned() = toInt() and 0xFF
    override fun compareTo(other: Subnet): Int {
        val addrThis = address.address
        val addrThat = other.address.address
        var result =
            addrThis.size.compareTo(addrThat.size) // IPv4 address goes first
        if (result != 0) return result
        for (i in addrThis.indices) {
            result = addrThis[i].unsigned()
                .compareTo(addrThat[i].unsigned()) // undo sign extension of signed byte
            if (result != 0) return result
        }
        return prefixSize.compareTo(other.prefixSize)
    }

    override fun equals(other: Any?): Boolean {
        val that = other as? Subnet
        return address == that?.address && prefixSize == that.prefixSize
    }

    override fun hashCode(): Int = Objects.hash(address, prefixSize)
}
