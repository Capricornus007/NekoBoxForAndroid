package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.amneziawg.buildSingBoxEndpointAmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import moe.matsuri.nb4a.SingBoxOptions.Endpoint_WireGuardOptions
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.Outbound_URLTestOptions
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

    @Test
    fun urlTestGroupCanReferenceGeneratedWireGuardEndpoint() {
        val options = options().apply {
            outbounds.add(
                0,
                Outbound_URLTestOptions().apply {
                    type = "urltest"
                    tag = TAG_PROXY
                    outbounds = listOf(MAIN_TAG)
                    url = "https://connectivitycheck.example/generate_204"
                    interval = "5m"
                    tolerance = 50
                },
            )
            route.final_ = TAG_PROXY
        }
        val config = gson.toJsonTree(finalizeRootConfig(options)).asJsonObject

        val group = node(config, "outbounds", TAG_PROXY)
        assertEquals(listOf(MAIN_TAG), group.getAsJsonArray("outbounds").map { it.asString })
        assertEquals("urltest", group.get("type").asString)
        assertTrue(
            config.getAsJsonArray("endpoints")
                .map { it.asJsonObject }
                .any { it.get("tag").asString == MAIN_TAG },
        )
        assertTopologyReferencesResolve(config)
    }

    @Test
    fun preexistingEndpointDetourIsNotOverwrittenWithDirect() {
        val options = options().apply {
            outbounds.add(
                Outbound().apply {
                    type = "direct"
                    tag = NEXT_TAG
                },
            )
            (outbounds[0] as Endpoint_WireGuardOptions).detourTo(NEXT_TAG)
        }
        val config = gson.toJsonTree(finalizeRootConfig(options)).asJsonObject

        assertEquals(NEXT_TAG, node(config, "endpoints", MAIN_TAG).get("detour").asString)
        assertTopologyReferencesResolve(config)
    }

    private fun node(config: JsonObject, section: String, tag: String) =
        config.getAsJsonArray(section).map { it.asJsonObject }.single { it.get("tag").asString == tag }

    // 拓撲完整性：群組成員、endpoint 的 detour 與 route.final 引用的 tag 必須真的存在，
    // 否則內核會在啟動階段整份配置打回。
    private fun assertTopologyReferencesResolve(config: JsonObject) {
        val available = mutableSetOf<String>()
        config.getAsJsonArray("outbounds").forEach { available.add(it.asJsonObject.get("tag").asString) }
        config.getAsJsonArray("endpoints").forEach { available.add(it.asJsonObject.get("tag").asString) }

        val referenced = mutableListOf<String>()
        config.getAsJsonArray("outbounds").forEach { element ->
            val node = element.asJsonObject
            node.getAsJsonArray("outbounds")?.forEach { referenced.add(it.asString) }
            node.get("default")?.asString?.let { referenced.add(it) }
            node.get("detour")?.asString?.let { referenced.add(it) }
        }
        config.getAsJsonArray("endpoints").forEach { element ->
            element.asJsonObject.get("detour")?.asString?.let { referenced.add(it) }
        }
        config.getAsJsonObject("route").get("final")?.asString?.let { referenced.add(it) }

        referenced.forEach { assertTrue("被引用的出口 $it 不存在", available.contains(it)) }
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
        const val NEXT_TAG = "next-hop"
    }
}
