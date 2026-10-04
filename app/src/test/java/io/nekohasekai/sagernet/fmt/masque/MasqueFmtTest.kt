package io.nekohasekai.sagernet.fmt.masque

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasqueFmtTest {

    @Test
    fun defaultsMatchCoreSemantics() {
        val bean = MasqueBean().apply { initializeDefaultValues() }
        assertEquals("auto", bean.transport)
        assertFalse(bean.useIPv6)
        assertFalse(bean.system)
        assertEquals("consumer-masque.cloudflareclient.com", bean.tlsSNI)
        assertEquals("5m0s", bean.udpTimeout)
        assertEquals("5s", bean.reconnectDelay)
        assertEquals("MASQUE", bean.displayAddress())
    }

    @Test
    fun useHttp2IsDerivedFromTransport() {
        assertFalse(masqueUseHTTP2("auto"))
        assertFalse(masqueUseHTTP2("h3"))
        assertTrue(masqueUseHTTP2("h2"))
    }

    @Test
    fun linkRoundTripPreservesEveryField() {
        val bean = MasqueBean().apply {
            initializeDefaultValues()
            name = "home-warp"
            transport = "h2"
            useIPv6 = true
            system = true
            interfaceName = "warp0"
            allowedIPs = "0.0.0.0/0, ::/0"
            profileId = "cf-profile-1"
            profileAuthToken = "tok abc"
            profilePrivateKey = "pk/with+slash"
            profileRecreate = true
            profileDetour = "42"
            configPrivateKey = "cfgpk"
            configEndpointV4 = "162.159.193.10"
            configEndpointV6 = "2603:2e0::1"
            configEndpointH2V4 = "188.114.96.0"
            configEndpointH2V6 = "2a06:98c0:3000::"
            configEndpointPubKey = "-----BEGIN PUBLIC KEY-----"
            configLicense = "license-key"
            configId = "device-id"
            configAccessToken = "access-token"
            configIPv4 = "10.0.0.2"
            configIPv6 = "fd00::2"
            udpTimeout = "3m0s"
            udpKeepalivePeriod = "25s"
            udpInitialPacketSize = 1252
            disablePathMTUDiscovery = true
            h3FallbackTimeout = "7s"
            mtu = 1420
            reconnectDelay = "8s"
            tlsSNI = "custom.example"
            tlsInsecure = true
            tlsCipherSuites = "TLS_AES_128_GCM_SHA256,TLS_CHACHA20_POLY1305_SHA256"
            tlsCurvePreferences = "X25519,P256"
            tlsFragment = true
            tlsFragmentFallbackDelay = "2s"
            tlsRecordFragment = true
            tlsKernelTx = true
            tlsKernelRx = true
        }

        val link = bean.toUri()
        assertTrue(link.startsWith("masque://"))

        val parsed = parseMasque(link)
        assertEquals(bean.name, parsed.name)
        assertEquals("h2", parsed.transport)
        assertTrue(parsed.useIPv6)
        assertTrue(parsed.system)
        assertEquals("warp0", parsed.interfaceName)
        assertEquals("0.0.0.0/0, ::/0", parsed.allowedIPs)
        assertEquals("cf-profile-1", parsed.profileId)
        assertEquals("tok abc", parsed.profileAuthToken)
        assertEquals("pk/with+slash", parsed.profilePrivateKey)
        assertTrue(parsed.profileRecreate)
        assertEquals("42", parsed.profileDetour)
        assertEquals("cfgpk", parsed.configPrivateKey)
        assertEquals("162.159.193.10", parsed.configEndpointV4)
        assertEquals("2603:2e0::1", parsed.configEndpointV6)
        assertEquals("188.114.96.0", parsed.configEndpointH2V4)
        assertEquals("2a06:98c0:3000::", parsed.configEndpointH2V6)
        assertEquals("-----BEGIN PUBLIC KEY-----", parsed.configEndpointPubKey)
        assertEquals("license-key", parsed.configLicense)
        assertEquals("device-id", parsed.configId)
        assertEquals("access-token", parsed.configAccessToken)
        assertEquals("10.0.0.2", parsed.configIPv4)
        assertEquals("fd00::2", parsed.configIPv6)
        assertEquals("3m0s", parsed.udpTimeout)
        assertEquals("25s", parsed.udpKeepalivePeriod)
        assertEquals(1252, parsed.udpInitialPacketSize)
        assertTrue(parsed.disablePathMTUDiscovery)
        assertEquals("7s", parsed.h3FallbackTimeout)
        assertEquals(1420, parsed.mtu)
        assertEquals("8s", parsed.reconnectDelay)
        assertEquals("custom.example", parsed.tlsSNI)
        assertTrue(parsed.tlsInsecure)
        assertEquals("TLS_AES_128_GCM_SHA256,TLS_CHACHA20_POLY1305_SHA256", parsed.tlsCipherSuites)
        assertEquals("X25519,P256", parsed.tlsCurvePreferences)
        assertTrue(parsed.tlsFragment)
        assertEquals("2s", parsed.tlsFragmentFallbackDelay)
        assertTrue(parsed.tlsRecordFragment)
        assertTrue(parsed.tlsKernelTx)
        assertTrue(parsed.tlsKernelRx)
    }

    @Test
    fun exportIsStableAcrossRounds() {
        val link = MasqueBean().apply {
            initializeDefaultValues()
            transport = "h3"
            allowedIPs = "0.0.0.0/0"
            profileId = "p1"
            profileAuthToken = "t1"
        }.toUri()

        val first = parseMasque(link).toUri()
        val second = parseMasque(first).toUri()
        assertEquals(first, second)
    }

    @Test
    fun kryoCloneRoundTripPreservesFields() {
        val bean = MasqueBean().apply {
            initializeDefaultValues()
            transport = "h2"
            useIPv6 = true
            configEndpointV4 = "1.2.3.4"
            tlsSNI = "sni.example"
        }
        val clone = bean.clone()
        assertEquals("h2", clone.transport)
        assertTrue(clone.useIPv6)
        assertEquals("1.2.3.4", clone.configEndpointV4)
        assertEquals("sni.example", clone.tlsSNI)
    }

    @Test
    fun hasConfigDetectsEndpointMaterial() {
        val empty = MasqueBean().apply { initializeDefaultValues() }
        assertFalse(empty.hasConfig())
        empty.configEndpointV4 = "162.159.193.10"
        assertTrue(empty.hasConfig())
        assertNotNull(empty.buildMasqueConfig())
    }
}
