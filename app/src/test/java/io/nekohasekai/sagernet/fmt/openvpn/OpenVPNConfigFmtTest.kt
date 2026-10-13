package io.nekohasekai.sagernet.fmt.openvpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `.ovpn` → sing-box endpoint 的對應表全部由這些測試釘住。
 * 重點不是「解析得到東西」，而是**欄位名與 enum 值必須是核心收得的写法**：
 * 對照 sing-box 的 `option/openvpn.go`（欄位與 enum）與 `protocol/openvpn/client.go:189-234`
 * （TLS 模式拒絕 static_key／key_direction、direction 只准 tls_auth 用）。改核心那一邊時這裡會先紅。
 *
 * 掛 Robolectric 是必要的：`org.json` 在 Android 裡是框架類，純 JVM 單元測試拿到的是 mockable
 * android.jar 那顆桩，`put()` 一律回 null，18 條測試會全紅在同一個 NPE 上（實測過）。
 * 同倉的 `VLESSXhttpFmtTest` 就是同一個理由掛 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OpenVPNConfigFmtTest {

    private val caBlock = """
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIBAAAADEADBEEF
        -----END CERTIFICATE-----
        </ca>
    """.trimIndent()

    private fun protonLike(credentialLines: String = "") = """
        client
        dev tun
        proto udp
        remote tw-tls-001.protonvpn.com 443 udp
        remote tw-tls-002.protonvpn.com 443 udp
        remote-random
        hand-window 77
        af-mtu 1500
        mssfix 1400
        cipher AES-256-GCM
        auth SHA512
        replay-window 8
        persist-key
        persist-tun
        sndbuf 524288
        rcvbuf 524288
        explicit-exit-notify 3
        keepalive 10 30
        reneg-sec 0
        remote-cert-tls server
        key-direction 1
        $caBlock
        <tls-auth>
        #
        # 2048 bit OpenVPN static key
        #
        -----BEGIN OpenVPN Static key V1-----
        00112233445566778899aabbccddeeff
        -----END OpenVPN Static key V1-----
        </tls-auth>
        $credentialLines
    """.trimIndent()

    @Test
    fun mapsRemotesToServersAndNetwork() {
        val result = parseOpenVPNConfig(protonLike())
        val endpoint = result.endpoint
        assertEquals("openvpn", endpoint.getString("type"))
        assertEquals("tls", endpoint.getString("mode"))
        assertEquals("udp", endpoint.getString("network"))
        assertTrue(endpoint.getBoolean("remote_random"))
        val servers = endpoint.getJSONArray("servers")
        assertEquals(2, servers.length())
        val first = servers.getJSONObject(0)
        assertEquals("tw-tls-001.protonvpn.com", first.getString("server"))
        assertEquals(443, first.getInt("server_port"))
        assertEquals("udp", first.getString("network"))
    }

    /** 核心規定 TLS 模式的 tls-auth 只能走 tls.control_wrap，塞 static_key 會被直接拒絕。 */
    @Test
    fun tlsAuthBecomesControlWrapNotStaticKey() {
        val endpoint = parseOpenVPNConfig(protonLike()).endpoint
        assertFalse(endpoint.has("static_key"))
        assertFalse(endpoint.has("key_direction"))
        val wrap = endpoint.getJSONObject("tls").getJSONObject("control_wrap")
        assertEquals("tls_auth", wrap.getString("type"))
        // OpenVPN 的 key-direction 1 = client，核心收的是字串不是數字。
        assertEquals("client", wrap.getString("direction"))
        val key = wrap.getJSONArray("key").getString(0)
        assertTrue(key, key.contains("BEGIN OpenVPN Static key V1"))
        assertTrue(key, key.contains("00112233445566778899aabbccddeeff"))
    }

    @Test
    fun caCertAndClientKeyGoIntoTls() {
        val endpoint = parseOpenVPNConfig(
            """
            remote example.com 1194
            $caBlock
            <cert>
            -----BEGIN CERTIFICATE-----
            CLIENTCERT
            -----END CERTIFICATE-----
            </cert>
            <key>
            -----BEGIN PRIVATE KEY-----
            CLIENTKEY
            -----END PRIVATE KEY-----
            </key>
            """.trimIndent(),
        ).endpoint
        val tls = endpoint.getJSONObject("tls")
        assertTrue(tls.getJSONArray("certificate").getString(0).contains("MIIBAAAADEADBEEF"))
        assertTrue(tls.getJSONArray("client_certificate").getString(0).contains("CLIENTCERT"))
        assertTrue(tls.getJSONArray("client_key").getString(0).contains("CLIENTKEY"))
    }

    /** tls_crypt 帶 direction 會被核心拒絕（client.go:229），所以這裡必須把 key-direction 丟掉並警告。 */
    @Test
    fun tlsCryptDropsDirectionAndWarns() {
        val result = parseOpenVPNConfig(
            """
            remote example.com 1194
            key-direction 1
            <tls-crypt>
            -----BEGIN OpenVPN Static key V1-----
            aabb
            -----END OpenVPN Static key V1-----
            </tls-crypt>
            """.trimIndent(),
        )
        val wrap = result.endpoint.getJSONObject("tls").getJSONObject("control_wrap")
        assertEquals("tls_crypt", wrap.getString("type"))
        assertFalse(wrap.has("direction"))
        assertTrue(result.warnings.any { it.contains("key-direction") })
    }

    @Test
    fun durationsAndNumbersUseCoreFormats() {
        val endpoint = parseOpenVPNConfig(protonLike()).endpoint
        assertEquals("10s", endpoint.getString("ping_interval"))
        assertEquals("30s", endpoint.getString("ping_restart"))
        assertEquals("77s", endpoint.getString("handshake_window"))
        assertEquals(1400, endpoint.getInt("mss_fix"))
        assertEquals(3, endpoint.getInt("explicit_exit_notify"))
        assertEquals("AES-256-GCM", endpoint.getString("cipher"))
        assertEquals("SHA512", endpoint.getString("auth"))
        assertEquals("server", endpoint.getJSONObject("tls").getString("remote_certificate_tls"))
    }

    /** `reneg-sec 0` 是「不重握手」，不能吐成 "0s" 讓核心以為要定時重握手。 */
    @Test
    fun zeroRenegotiationBecomesDisabledFlag() {
        val endpoint = parseOpenVPNConfig(protonLike()).endpoint
        assertFalse(endpoint.has("renegotiate_interval"))
        assertTrue(endpoint.getBoolean("renegotiate_disabled"))
    }

    /** 非數字的 duration（例如空值或註解殘留）必須整個不出現，而不是生出 "s" 這種核心解析不過的值。 */
    @Test
    fun garbageDurationIsDroppedNotEmitted() {
        val endpoint = parseOpenVPNConfig(
            """
            remote example.com 1194
            ping
            ping-restart abc
            """.trimIndent(),
        ).endpoint
        assertFalse(endpoint.has("ping_interval"))
        assertFalse(endpoint.has("ping_restart"))
    }

    @Test
    fun pullFiltersRoutesAndFlags() {
        val endpoint = parseOpenVPNConfig(
            """
            remote example.com 1194
            pull-filter ignore "dhcp-option DNS"
            pull-filter reject "route 10.0.0.0 255.0.0.0"
            route 192.168.0.0 255.255.0.0
            route 2001:db8::1
            redirect-gateway def1
            block-ipv6
            """.trimIndent(),
        ).endpoint
        val filters = endpoint.getJSONArray("pull_filters")
        assertEquals(2, filters.length())
        assertEquals("ignore", filters.getJSONObject(0).getString("action"))
        assertEquals("dhcp-option DNS", filters.getJSONObject(0).getString("text"))
        assertEquals("reject", filters.getJSONObject(1).getString("action"))
        assertEquals("route 10.0.0.0 255.0.0.0", filters.getJSONObject(1).getString("text"))
        val routes = endpoint.getJSONArray("routes")
        assertEquals("192.168.0.0/16", routes.getString(0))
        assertEquals("2001:db8::1/128", routes.getString(1))
        assertTrue(endpoint.getBoolean("redirect_gateway"))
        assertEquals("def1", endpoint.getJSONArray("redirect_gateway_flags").getString(0))
        assertTrue(endpoint.getBoolean("block_ipv6"))
    }

    @Test
    fun authUserPassAsksForCredentialsAndInjectsThem() {
        val needs = parseOpenVPNConfig(protonLike("auth-user-pass"))
        assertTrue(needs.needsCredentials)
        assertFalse(needs.endpoint.has("username"))

        val filled = parseOpenVPNConfig(
            protonLike("auth-user-pass"),
            username = "proton-user",
            password = "s3cret",
        )
        assertFalse(filled.needsCredentials)
        assertEquals("proton-user", filled.endpoint.getString("username"))
        assertEquals("s3cret", filled.endpoint.getString("password"))
    }

    /** tap 模式核心做不了，必須講清楚而不是靜默降級。 */
    @Test
    fun tapModeWarns() {
        val result = parseOpenVPNConfig(
            """
            dev tap
            remote example.com 1194
            """.trimIndent(),
        )
        assertEquals("tls", result.endpoint.getString("mode"))
        assertTrue(result.warnings.any { it.contains("tap") })
    }

    @Test
    fun unsupportedDirectivesAreReportedNotSwallowed() {
        val result = parseOpenVPNConfig(
            """
            remote example.com 1194
            script-security tde
            up /bin/true
            down /bin/true
            dhcp-option DNS 8.8.8.8
            weird-future-option 1
            """.trimIndent(),
        )
        listOf("script-security", "up", "down", "dhcp-option").forEach {
            assertTrue("$it 應該被列進警告", result.warnings.any { w -> w.contains(it) })
        }
        assertTrue(result.warnings.any { it.contains("weird-future-option") })
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val endpoint = parseOpenVPNConfig(
            """
            # comment
            ; another comment

            remote example.com 1194 udp
            """.trimIndent(),
        ).endpoint
        assertEquals(1, endpoint.getJSONArray("servers").length())
    }

    @Test
    fun noRemoteFailsLoudly() {
        val failure = runCatching { parseOpenVPNConfig("dev tun$cipherOnly") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private val cipherOnly = "\ncipher AES-256-CBC\n"

    @Test
    fun unterminatedBlockFailsLoudly() {
        val failure = runCatching {
            parseOpenVPNConfig("remote example.com 1194\n<ca>\nAAAA\n")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    /** TCP 剖面（Proton 的 tcp 那組）：proto tcp-client 要翻成核心收的 tcp。 */
    @Test
    fun tcpClientMapsToTcp() {
        val endpoint = parseOpenVPNConfig(
            """
            proto tcp-client
            remote example.com 443 tcp-client
            $caBlock
            """.trimIndent(),
        ).endpoint
        assertEquals("tcp", endpoint.getString("network"))
        assertEquals("tcp", endpoint.getJSONArray("servers").getJSONObject(0).getString("network"))
    }

    /** static-key 模式（OpenVPN 2.4 無 TLS）走另一條路：mode 與 key_direction 都在頂層。 */
    @Test
    fun staticKeyModeUsesTopLevelKeyDirection() {
        val endpoint = parseOpenVPNConfig(
            """
            mode server
            remote example.com 1194
            key-direction 0
            <static-key>
            -----BEGIN OpenVPN Static key V1-----
            ccdd
            -----END OpenVPN Static key V1-----
            </static-key>
            """.trimIndent(),
        ).endpoint
        assertEquals("static_key", endpoint.getString("mode"))
        assertEquals("server", endpoint.getString("key_direction"))
        assertTrue(endpoint.getJSONArray("static_key").getString(0).contains("ccdd"))
    }

    @Test
    fun tagIsHonoured() {
        assertEquals(
            "proton-openvpn",
            parseOpenVPNConfig("remote example.com 1194", tag = "proton-openvpn")
                .endpoint.getString("tag"),
        )
    }

    /** 產物必須是可直接塞進 `endpoints` 陣列的 JSON，不含核心不收的未知欄位。 */
    @Test
    fun outputIsValidSingleEndpointObject() {
        val endpoint = parseOpenVPNConfig(protonLike()).endpoint
        val array = JSONObject().put("endpoints", org.json.JSONArray().put(endpoint))
        assertTrue(array.toString().contains("\"type\":\"openvpn\""))
        val allowed = setOf(
            "type", "tag", "mode", "network", "servers", "remote_random", "cipher", "auth",
            "mss_fix", "explicit_exit_notify", "ping_interval", "ping_restart",
            "handshake_window", "renegotiate_disabled", "tls",
        )
        endpoint.keys().forEach {
            assertTrue("核心不收的欄位：$it", allowed.contains(it))
        }
    }
}
