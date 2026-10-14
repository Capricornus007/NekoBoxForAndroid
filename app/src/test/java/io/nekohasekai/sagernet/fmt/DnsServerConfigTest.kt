package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions

class DnsServerConfigTest {
    @Test fun bareLocalhostUsesAndroidSystemDNSRatherThanLoopbackUDP() {
        for (address in listOf("local", "localhost", " LocalHost ", "LOCAL")) {
            assertEquals(address, mapOf("type" to "local", "tag" to "dns-direct"),
                dnsServerConfig(address, "dns-direct", "dns-local", "direct"))
        }
    }

    @Test fun explicitLoopbackServersKeepTheirTransportAndPort() {
        for (address in listOf("127.0.0.1", "[::1]", "udp://localhost", "localhost:5353")) {
            val server = dnsServerConfig(address, "custom-dns", "dns-local", "direct")
            assertEquals("udp", server["type"])
            assertNotNull(server["server"])
        }
        assertEquals(5353, dnsServerConfig("localhost:5353", "custom-dns", "dns-local", "direct")["server_port"])
    }

    @Test fun systemDNSAliasesDoNotDisableTheProxyDetourOfFallbackDNS() {
        val remote = "https://dns.example.test/custom"
        for (direct in listOf("local", "localhost", " LocalHost ")) {
            val server = dnsServerConfig(directFallbackDnsAddress(direct, remote),
                DNS_DIRECT_FALLBACK, "dns-local", TAG_DIRECT_FALLBACK)
            assertEquals("https", server["type"])
            assertEquals("dns.example.test", server["server"])
            assertEquals(TAG_DIRECT_FALLBACK, server["detour"])
        }
        assertEquals("https://dns.google/dns-query", directFallbackDnsAddress("localhost", "LOCAL"))
        assertEquals("udp://localhost:5353", directFallbackDnsAddress("udp://localhost:5353", remote))
    }

    @Test fun blockedResponseDropsFieldsOnlySupportedByDnsRouteActions() {
        val rule = DNSRule_DefaultOptions().apply {
            server = "dns-block"
            disable_cache = true
            domain = listOf("blocked.test")
        }
        rule.useBlockedDNSResponse()
        val options = rule.asMap()
        assertFalse(options.containsKey("server"))
        assertFalse(options.containsKey("disable_cache"))
        assertEquals("predefined", options["action"])
        assertEquals("NOERROR", options["rcode"])
        assertEquals(listOf("blocked.test"), options["domain"])
    }

    @Test fun remoteHttpsUsesProxyAndKeepsQueryString() {
        val server = dnsServerConfig("https://dns.example.test:8443/custom?key=example", "dns-remote", "dns-direct", "selected-group")
        assertEquals("https", server["type"])
        assertEquals("selected-group", server["detour"])
        assertEquals("dns-direct", server["domain_resolver"])
        assertEquals("/custom?key=example", server["path"])
        assertEquals(8443, server["server_port"])
        assertFalse(server.containsKey("address"))
    }

    @Test fun supportsIpv4Ipv6AndHostnameTransports() {
        for (address in listOf("192.0.2.1", "udp://192.0.2.1", "[2001:db8::1]:5353", "2001:db8::1", "tcp://dns.example.test", "tls://dns.example.test", "quic://dns.example.test", "h3://dns.example.test")) {
            val server = dnsServerConfig(address, "dns-direct", "dns-local", "direct")
            assertFalse(server.containsKey("detour"))
            assertNotNull(server["server"])
            assertFalse((server["server"] as String).startsWith("["))
        }
        assertEquals("/dns-query", dnsServerConfig("https://dns.example.test", "dns-remote", "dns-direct", "proxy")["path"])
        assertEquals(mapOf("type" to "local", "tag" to "dns-local"), dnsServerConfig("local", "dns-local", "", "direct"))
    }

    @Test fun rejectsMalformedUrlsInsteadOfDroppingParts() {
        for (address in listOf("", "http://dns.example.test", "tls://dns.example.test:0", "udp://dns.example.test:65536", "https://user:pass@dns.example.test", "https://dns.example.test#fragment", "udp://dns.example.test/path", "tls://dns.example.test?key=example")) {
            assertThrows(IllegalArgumentException::class.java) { dnsServerConfig(address, "dns", "local", "direct") }
        }
    }
}
