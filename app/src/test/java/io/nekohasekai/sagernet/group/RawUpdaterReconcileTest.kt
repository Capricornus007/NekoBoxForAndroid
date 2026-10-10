package io.nekohasekai.sagernet.group

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RawUpdaterReconcileTest {

    @Test
    fun unchanged_leavesContentAndOrderUnchanged() {
        val entity = entity(bean(), userOrder = 1L)

        val result = RawUpdater.reconcileExistingProfile(entity, bean(), userOrder = 1L)

        assertFalse(result.contentChanged)
        assertFalse(result.orderChanged)
        assertEquals("192.0.2.1", entity.requireBean().serverAddress)
        assertEquals(1L, entity.userOrder)
    }

    @Test
    fun contentChange_persistsContentWithoutChangingOrder() {
        val entity = entity(bean(), userOrder = 1L)
        val incoming = bean().apply { serverAddress = "192.0.2.2" }

        val result = RawUpdater.reconcileExistingProfile(entity, incoming, userOrder = 1L)

        assertTrue(result.contentChanged)
        assertFalse(result.orderChanged)
        assertEquals("192.0.2.2", entity.requireBean().serverAddress)
        assertEquals(1L, entity.userOrder)
    }

    @Test
    fun orderChange_persistsOrderWithoutChangingContent() {
        val entity = entity(bean(), userOrder = 1L)

        val result = RawUpdater.reconcileExistingProfile(entity, bean(), userOrder = 2L)

        assertFalse(result.contentChanged)
        assertTrue(result.orderChanged)
        assertEquals("192.0.2.1", entity.requireBean().serverAddress)
        assertEquals(2L, entity.userOrder)
    }

    @Test
    fun contentAndOrderChange_persistsBothOnSameEntity() {
        val entity = entity(bean(), userOrder = 1L)
        val incoming = bean().apply { serverAddress = "192.0.2.2" }

        val result = RawUpdater.reconcileExistingProfile(entity, incoming, userOrder = 2L)

        assertTrue(result.contentChanged)
        assertTrue(result.orderChanged)
        assertEquals("192.0.2.2", entity.requireBean().serverAddress)
        assertEquals(2L, entity.userOrder)
    }

    @Test
    fun contentChange_preservesStoredCustomOverrides() {
        val stored = bean().apply {
            customOutboundJson = """{"outbound":"stored"}"""
            customConfigJson = """{"config":"stored"}"""
        }
        val entity = entity(stored, userOrder = 1L)
        val incoming = bean().apply {
            serverAddress = "192.0.2.2"
            customOutboundJson = """{"outbound":"incoming"}"""
            customConfigJson = """{"config":"incoming"}"""
        }

        val result = RawUpdater.reconcileExistingProfile(entity, incoming, userOrder = 1L)
        val persisted = entity.requireBean()

        assertTrue(result.contentChanged)
        assertFalse(result.orderChanged)
        assertEquals("192.0.2.2", persisted.serverAddress)
        assertEquals("""{"outbound":"stored"}""", persisted.customOutboundJson)
        assertEquals("""{"config":"stored"}""", persisted.customConfigJson)
        assertEquals(1L, entity.userOrder)
    }

    @Test
    fun remarkChange_isPersistedAndAmbiguousRenamesAreNotGuessed() {
        val first = entity(bean(), 1L).apply { id = 1L }
        val renamed = bean().apply { name = "new remark" }
        assertEquals(first.id, RawUpdater.matchExistingProfiles(listOf(first), listOf(renamed)).getValue("new remark").id)
        assertTrue(RawUpdater.reconcileExistingProfile(first, renamed, 1L).contentChanged)
        assertEquals("new remark", first.displayName())

        val second = entity(bean().apply { name = "duplicate" }, 2L).apply { id = 2L }
        assertTrue(RawUpdater.matchExistingProfiles(listOf(first, second), listOf(bean().apply { name = "ambiguous" })).isEmpty())
    }

    @Test
    fun swappedRemarks_followTheConnectionNotTheName() {
        val a = stored(socks("192.0.2.1", "A"), 1L)
        val b = stored(socks("192.0.2.2", "B"), 2L)
        val matched = RawUpdater.matchExistingProfiles(listOf(a, b), listOf(socks("192.0.2.1", "B"), socks("192.0.2.2", "A")))
        assertEquals(mapOf("B" to 1L, "A" to 2L), matched.mapValues { it.value.id })
    }

    @Test
    fun remarkFallback_needsTheSameProtocolAndEndpointOrSettings() {
        val profile = stored(socks("192.0.2.1", "Node"), 1L)
        fun match(bean: AbstractBean) = RawUpdater.matchExistingProfiles(listOf(profile), listOf(bean))[bean.displayName()]?.id

        // Credential rotation at the same endpoint, and a server move with the same credentials.
        assertEquals(1L, match(socks("192.0.2.1", "Node", password = "rotated")))
        assertEquals(1L, match(socks("198.51.100.9", "Node")))
        // A node that shares only the remark is new, and so is another protocol at the endpoint.
        assertNull(match(socks("198.51.100.9", "Node", password = "other")))
        assertNull(
            match(
                HttpBean().apply {
                    serverAddress = "192.0.2.1"
                    serverPort = 1080
                    name = "Node"
                    initializeDefaultValues()
                },
            ),
        )
        // A rotation that also changes the remark has no stable key left to match on.
        assertNull(match(socks("192.0.2.1", "Node 4 GB", password = "rotated")))
    }

    @Test
    fun duplicateRemarksOrConnections_areNeverGuessed() {
        val first = stored(socks("192.0.2.1", "Same"), 1L)
        val second = stored(socks("192.0.2.2", "Same"), 2L)
        assertTrue(RawUpdater.matchExistingProfiles(listOf(first, second), listOf(socks("192.0.2.3", "Same"))).isEmpty())
        val incoming = listOf(socks("192.0.2.1", "Left"), socks("192.0.2.1", "Right"))
        assertTrue(RawUpdater.matchExistingProfiles(listOf(first), incoming).isEmpty())
    }

    @Test
    fun newlyImportedSettings_keepTheProfileWithTheSameRemarkAndEndpoint() {
        val old = WireGuardBean().applyDefaultValues().apply {
            name = "office"
            serverAddress = "192.0.2.7"
            serverPort = 51820
            localAddress = "10.0.0.2/32"
            privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            peerPublicKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
            customOutboundJson = """{"marker":"stored"}"""
        }
        val entity = stored(old, 7L)
        val incoming = old.clone().apply {
            mtu = 1280
            customOutboundJson = ""
        }
        assertEquals(7L, RawUpdater.matchExistingProfiles(listOf(entity), listOf(incoming)).getValue("office").id)
        assertTrue(RawUpdater.reconcileExistingProfile(entity, incoming, 1L).contentChanged)
        assertEquals("""{"marker":"stored"}""", entity.requireBean().customOutboundJson)
    }

    @Test
    fun singBoxOutboundTagChange_isARemarkChange() {
        fun outbound(tag: String) = ConfigBean().applyDefaultValues().apply {
            type = 1
            name = tag
            config = JSONObject().put("type", "socks").put("tag", tag).put("server", "192.0.2.9").put("server_port", 1080).toString()
        }
        val entity = stored(outbound("Edge | 5 GB"), 3L)
        assertEquals(3L, RawUpdater.matchExistingProfiles(listOf(entity), listOf(outbound("Edge | 4 GB"))).getValue("Edge | 4 GB").id)
    }

    @Test
    fun remarkFallback_neverCrossesAProtocolThatSharesTheClass() {
        fun vmess(vless: Boolean, uuid: String = "00000000-0000-4000-8000-000000000001") = VMessBean().apply {
            serverAddress = "192.0.2.5"
            serverPort = 443
            name = "Edge"
            this.uuid = uuid
            if (vless) alterId = -1
            initializeDefaultValues()
        }
        val storedVmess = stored(vmess(false).apply { customOutboundJson = """{"marker":"vmess"}""" }, 1L)
        assertNull(RawUpdater.matchExistingProfiles(listOf(storedVmess), listOf(vmess(true)))["Edge"])
        // The same protocol at the same endpoint still matches after a credential rotation.
        assertEquals(1L, RawUpdater.matchExistingProfiles(listOf(storedVmess), listOf(vmess(false, uuid = "00000000-0000-4000-8000-000000000002")))["Edge"]?.id)

        fun hysteria(version: Int) = HysteriaBean().apply {
            serverAddress = "192.0.2.6"
            serverPorts = "443"
            name = "Fast"
            protocolVersion = version
            authPayload = "secret"
            initializeDefaultValues()
        }
        assertNull(RawUpdater.matchExistingProfiles(listOf(stored(hysteria(1), 2L)), listOf(hysteria(2)))["Fast"])

        fun outbound(type: String) = ConfigBean().applyDefaultValues().apply {
            this.type = 1
            name = "Raw"
            config = JSONObject().put("type", type).put("tag", "Raw").put("server", "192.0.2.9").put("server_port", 443).toString()
        }
        assertNull(RawUpdater.matchExistingProfiles(listOf(stored(outbound("vmess"), 3L)), listOf(outbound("vless")))["Raw"])
        assertEquals("sing-box vless", RawUpdater.protocolKey(outbound("vless")))
        assertEquals("VLESS", RawUpdater.protocolKey(vmess(true)))
    }

    @Test
    fun userOwnedSettings_neitherDecideAMatchNorAreOverwritten() {
        fun bean(address: String, name: String, choice: String) = LocalSettingBean().apply {
            serverAddress = address
            serverPort = 51820
            this.name = name
            userChoice = choice
            initializeDefaultValues()
        }
        val stored = listOf(bean("192.0.2.1", "Node | 5 GB", "user-dns"), bean("192.0.2.2", "Other", ""))
        val incoming = listOf(bean("192.0.2.1", "Node | 4 GB", ""), bean("192.0.2.2", "Other", ""))
        // The user's choice differs from the fresh import, and the remark changed; the connection still matches.
        assertEquals(mapOf("Node | 4 GB" to 0, "Other" to 1), RawUpdater.matchBeans(stored, incoming))
        assertEquals("user-dns", stored[0].userChoice)

        // Refreshing keeps the choice; a change the provider made still counts as one.
        assertTrue(RawUpdater.refreshBean(stored[0], incoming[0]))
        assertEquals("user-dns", incoming[0].userChoice)
        val unchanged = bean("192.0.2.1", "Node | 5 GB", "")
        assertFalse(RawUpdater.refreshBean(stored[0], unchanged))
        assertEquals("user-dns", unchanged.userChoice)
        val moved = bean("192.0.2.9", "Node | 5 GB", "")
        assertTrue(RawUpdater.refreshBean(stored[0], moved))
        assertEquals("user-dns", moved.userChoice)
    }

    /** A bean with one setting the user owns, as a WireGuard profile's DNS choice is. */
    private class LocalSettingBean : AbstractBean() {
        @JvmField var userChoice: String? = null

        override fun initializeDefaultValues() {
            super.initializeDefaultValues()
            userChoice = userChoice ?: ""
        }

        override fun serialize(output: ByteBufferOutput) {
            super.serialize(output)
            output.writeString(userChoice)
        }

        override fun deserialize(input: ByteBufferInput) {
            super.deserialize(input)
            userChoice = input.readString()
        }

        override fun keepLocalSettings(from: AbstractBean) {
            if (from is LocalSettingBean) userChoice = from.userChoice
        }

        override fun clone() = KryoConverters.deserialize(LocalSettingBean(), KryoConverters.serialize(this))
    }

    private fun bean() = SOCKSBean().apply {
        serverAddress = "192.0.2.1"
        serverPort = 1080
        name = "profile"
        username = "user"
        password = "password"
        initializeDefaultValues()
    }

    private fun socks(address: String, name: String, password: String = "secret") = SOCKSBean().apply {
        serverAddress = address
        serverPort = 1080
        this.name = name
        username = "user"
        this.password = password
        initializeDefaultValues()
    }

    private fun stored(bean: AbstractBean, id: Long) = ProxyEntity(userOrder = id).apply {
        this.id = id
        putBean(bean)
    }

    private fun entity(bean: SOCKSBean, userOrder: Long) = ProxyEntity(userOrder = userOrder).apply {
        putBean(bean)
    }
}
