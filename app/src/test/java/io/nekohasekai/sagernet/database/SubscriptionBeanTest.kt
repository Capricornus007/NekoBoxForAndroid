package io.nekohasekai.sagernet.database

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import org.junit.Assert.*
import org.junit.Test

class SubscriptionBeanTest {
    @Test fun versionFourAndShareRoundTripPreserveNewSettings() {
        val source = SubscriptionBean().apply {
            initializeDefaultValues()
            link = "https://subscription.test/nodes"
            sendHwid = true
            customHwidParams = "hwid=fixture"
            serverDnsResolver = "https://dns.example/dns-query"
            customHeaders = "Authorization: Bearer fixture"
        }
        for (share in listOf(false, true)) {
            val output = ByteBufferOutput(1024)
            if (share) source.serializeForShare(output) else source.serializeToBuffer(output)
            val restored = SubscriptionBean().apply {
                if (share) deserializeFromShare(ByteBufferInput(output.toBytes())) else deserializeFromBuffer(ByteBufferInput(output.toBytes()))
                initializeDefaultValues()
            }
            assertEquals(source.link, restored.link)
            assertEquals(source.customHwidParams, restored.customHwidParams)
            assertEquals(source.sendHwid, restored.sendHwid)
            assertEquals(source.serverDnsResolver, restored.serverDnsResolver)
            assertEquals(source.customHeaders, restored.customHeaders)
        }
    }
    @Test fun oldVersionThreeKeepsHwidAndDefaultsNewFields() {
        val output = ByteBufferOutput(1024)
        output.writeInt(3)
        output.writeInt(0)
        output.writeString("https://old.example")
        output.writeBoolean(false)
        output.writeBoolean(true)
        output.writeBoolean(false)
        output.writeString("old-agent")
        output.writeBoolean(true)
        output.writeInt(1440)
        output.writeInt(100)
        output.writeString("")
        output.writeInt(0)
        output.writeString("")
        output.writeBoolean(true)
        output.writeString("hwid=old")
        val restored = SubscriptionBean().apply { deserializeFromBuffer(ByteBufferInput(output.toBytes())); initializeDefaultValues() }
        assertTrue(restored.sendHwid)
        assertEquals("hwid=old", restored.customHwidParams)
        assertEquals("", restored.serverDnsResolver)
        assertEquals("", restored.customHeaders)
    }
}
