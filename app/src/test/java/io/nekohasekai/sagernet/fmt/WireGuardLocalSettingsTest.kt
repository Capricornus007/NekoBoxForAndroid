package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardDnsMode
import io.nekohasekai.sagernet.group.RawUpdater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The subscription refresh contract: an incoming bean takes the user's DNS choice and overrides
 * from the stored profile through [AbstractBean.keepLocalSettings], and comparisons neutralize
 * those settings with a freshly parsed bean.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WireGuardLocalSettingsTest {
    private fun conf(dns: String, amnezia: Boolean = false) = """
        [Interface]
        PrivateKey = QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=
        Address = 10.8.0.2/32
        DNS = $dns
        ${if (amnezia) "Jc = 4" else ""}

        [Peer]
        PublicKey = QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 10.0.0.0/8
    """.trimIndent()

    private fun parse(dns: String, name: String = "office") = (RawUpdater.parseWireGuardConf(conf(dns)).single() as WireGuardBean).apply { this.name = name }

    private fun stored(dns: String) = parse(dns).apply {
        dnsMode = WireGuardDnsMode.DOMAINS
        customDnsServers = "10.0.0.53"
        customDnsDomains = "lab.example"
    }

    private fun neutral(bean: AbstractBean, reference: AbstractBean) = bean.clone().apply { keepLocalSettings(reference) }

    @Test
    fun refreshKeepsTheUserChoiceAndOverridesWhileImportedDnsFollowsTheProvider() {
        val stored = stored("10.0.0.1, corp.example")
        val incoming = parse("10.0.0.2, corp.example")

        incoming.keepLocalSettings(stored)

        assertEquals(WireGuardDnsMode.DOMAINS, incoming.dnsMode)
        assertEquals("10.0.0.53", incoming.customDnsServers)
        assertEquals("lab.example", incoming.customDnsDomains)
        assertEquals("10.0.0.2", incoming.importedDnsServers)
        // The provider changed its DNS, so the refresh is a content change.
        assertNotEquals(stored, incoming)
    }

    @Test
    fun aRemarkOnlyRenameIsNoContentChange() {
        val stored = stored("10.0.0.1")
        val incoming = parse("10.0.0.1", name = "renamed")

        incoming.keepLocalSettings(stored)

        assertEquals(stored, incoming)
        assertEquals("renamed", incoming.name)
    }

    @Test
    fun identityIgnoresLocalSettingsAndLeavesTheStoredProfileAlone() {
        val stored = stored("10.0.0.1")
        val storedBytes = KryoConverters.serialize(stored)
        val incoming = parse("10.0.0.1")

        // The reference is a freshly parsed bean, whose local settings are the defaults.
        assertEquals(neutral(incoming, incoming), neutral(stored, incoming))
        assertArrayEquals(storedBytes, KryoConverters.serialize(stored))
        assertEquals(WireGuardDnsMode.DOMAINS, stored.dnsMode)
        // Neutral local settings restore the version 2 layout of a profile without new fields.
        val plain = WireGuardBean().apply {
            serverAddress = "192.0.2.1"
            serverPort = 51820
            initializeDefaultValues()
        }
        val withChoice = plain.clone().apply { dnsMode = WireGuardDnsMode.ALL }
        assertEquals(3, KryoConverters.serialize(withChoice)[0].toInt())
        assertEquals(2, KryoConverters.serialize(plain)[0].toInt())
        assertArrayEquals(KryoConverters.serialize(plain), KryoConverters.serialize(neutral(withChoice, plain)))
    }

    @Test
    fun amneziaWgKeepsTheSameFieldsAndOtherClassesAreIgnored() {
        val stored = (RawUpdater.parseWireGuardConf(conf("10.0.0.1", amnezia = true)).single() as AmneziaWGBean).apply {
            dnsMode = WireGuardDnsMode.ALL
            customDnsServers = "10.0.0.53"
        }
        val incoming = RawUpdater.parseWireGuardConf(conf("10.0.0.2", amnezia = true)).single() as AmneziaWGBean

        incoming.keepLocalSettings(stored)
        assertEquals(WireGuardDnsMode.ALL, incoming.dnsMode)
        assertEquals("10.0.0.53", incoming.customDnsServers)

        val plain = parse("10.0.0.1")
        plain.keepLocalSettings(SOCKSBean().apply { initializeDefaultValues() })
        assertEquals(WireGuardDnsMode.APP, plain.dnsMode)
        plain.keepLocalSettings(stored)
        assertEquals(WireGuardDnsMode.APP, plain.dnsMode)
    }
}
