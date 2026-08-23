package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.amneziawg.buildSingBoxEndpointAmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.RouteOptions
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderWireGuardTest {

    @Test
    fun generatedWireGuardMovesToEndpointsAndUsesDirectDetour() {
        val config = gson.toJsonTree(finalizeRootConfig(options())).asJsonObject

        val endpoint = config.getAsJsonArray("endpoints").single().asJsonObject
        assertEquals(MAIN_TAG, endpoint.get("tag").asString)
        assertEquals(TAG_DIRECT, endpoint.get("detour").asString)
        assertFalse(config.getAsJsonArray("outbounds").any { it.asJsonObject.get("type").asString == "wireguard" })
        assertEquals(MAIN_TAG, config.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun customEndpointWithSameTagReplacesGeneratedEndpoint() {
        val custom = """{"endpoints":[{"type":"wireguard","tag":"$MAIN_TAG","name":"custom"}]}"""
        val config = gson.toJsonTree(finalizeRootConfig(options(), profileCustomConfig = custom)).asJsonObject

        val matching = config.getAsJsonArray("endpoints")
            .map { it.asJsonObject }
            .filter { it.get("tag").asString == MAIN_TAG }
        assertEquals(1, matching.size)
        assertEquals("custom", matching.single().get("name").asString)
    }

    @Test
    fun endpointCanParticipateInSelector() {
        val options = options().apply {
            outbounds.add(0, buildSelectorOutbound(MAIN_TAG, listOf(MAIN_TAG)))
            route.final_ = TAG_PROXY
        }
        val config = gson.toJsonTree(finalizeRootConfig(options)).asJsonObject
        val selector = config.getAsJsonArray("outbounds")
            .map { it.asJsonObject }
            .single { it.get("tag").asString == TAG_PROXY }

        assertEquals(listOf(MAIN_TAG), selector.getAsJsonArray("outbounds").map { it.asString })
        assertTrue(config.getAsJsonArray("endpoints").any { it.asJsonObject.get("tag").asString == MAIN_TAG })
    }

    @Test
    fun listenPortKeepsListenerAndSkipsIncompatibleDetour() {
        val endpoint = wireGuardEndpoint().apply { listen_port = 51821 }
        endpoint.detourTo(TAG_DIRECT)
        assertEquals(51821, endpoint.listen_port)
        assertFalse(endpoint.asMap().containsKey("detour"))
    }

    @Test
    fun proxyChainPrefersDetourOverIncompatibleListenPort() {
        val endpoint = wireGuardEndpoint().apply { listen_port = 51821 }
        endpoint.detourTo("next-hop")
        assertEquals(null, endpoint.listen_port)
        assertEquals("next-hop", endpoint.detour)
    }

    @Test
    fun amneziaWireGuardEndpointPreservesObfuscationAndPeerFields() {
        val endpoint = buildSingBoxEndpointAmneziaWGBean(
            AmneziaWGBean().apply {
                initializeDefaultValues()
                localAddress = "10.0.0.2/32"
                privateKey = "private"
                serverAddress = "vpn.example.com"
                serverPort = 51820
                peerPublicKey = "public"
                listenPort = 51821
                persistentKeepaliveInterval = 25
                reserved = "1, 2, 3"
                jc = 4
                h1 = "123"
            },
        )
        assertEquals("awg", endpoint.type)
        assertEquals(51821, endpoint.listen_port)
        assertEquals(25, endpoint.peers.single().persistent_keepalive_interval)
        assertEquals(listOf(1, 2, 3), endpoint.peers.single().reserved)
        assertEquals(4, endpoint.jc)
        assertEquals("123", endpoint.h1)
    }

    private fun options() = MyOptions().apply {
        endpoints = mutableListOf()
        route = RouteOptions().apply { final_ = MAIN_TAG }
        outbounds = mutableListOf(
            wireGuardEndpoint(),
            Outbound().apply {
                type = "direct"
                tag = TAG_DIRECT
                _hack_config_map["network_strategy"] = "default"
            },
        )
    }

    private fun wireGuardEndpoint() = buildSingBoxEndpointWireGuardBean(
        WireGuardBean().apply {
            initializeDefaultValues()
            serverAddress = "198.51.100.10"
            serverPort = 51820
            localAddress = "10.0.0.2/32"
            privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
            peerPublicKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        },
    ).apply { tag = MAIN_TAG }

    private companion object {
        const val MAIN_TAG = "wireguard-main"
    }
}
