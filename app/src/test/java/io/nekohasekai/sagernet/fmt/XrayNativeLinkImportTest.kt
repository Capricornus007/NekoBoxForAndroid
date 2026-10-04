package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3x-ui / v2rayN / Hiddify 這一路Link 的匯入契約（移植自 hawkff #180 的非面板部分）。
 * 重點有兩條：base64 裡的 '+' 不能被當成空格吃掉；以及 sing-box 表達不了的傳輸層
 * 要明確拒絕，而不是悄悄匯入一個連不上的 TCP 設定檔。
 */
class XrayNativeLinkImportTest {

    private companion object {
        const val PRIVATE_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        const val PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val PSK_WITH_PLUS = "CCCC+DDDDCCCC+DDDDCCCC+DDDDCCCC+DDDDCCCC="
    }

    @Test
    fun wireGuardLinkKeepsKeysAddressAndReserved() {
        val bean = parseWireGuardLink(
            "wireguard://$PRIVATE_KEY@203.0.113.7:51820" +
                "?publickey=$PUBLIC_KEY&presharedkey=$PSK_WITH_PLUS" +
                "&address=10.0.0.5,fd00::2&mtu=1420&reserved=AAEC#wg-edge",
        )

        assertEquals("203.0.113.7", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals(PRIVATE_KEY, bean.privateKey)
        assertEquals(PUBLIC_KEY, bean.peerPublicKey)
        // 未編碼的 '+' 必须原样保留：標準 queryParameter 會把它解成空格，
        // 那樣 pre-shared key 直接作廢、連線握手失敗。
        assertEquals(PSK_WITH_PLUS, bean.peerPreSharedKey)
        assertEquals("10.0.0.5/32\nfd00::2/128", bean.localAddress)
        assertEquals(1420, bean.mtu)
        assertEquals("AAEC", bean.reserved)
        assertEquals("wg-edge", bean.name)
    }

    @Test
    fun wireGuardLinkKeepsCidrAndRejectsMissingPeerKey() {
        val cidr = parseWireGuardLink(
            "wg://$PRIVATE_KEY@198.51.100.9:51820?publickey=$PUBLIC_KEY&address=10.0.0.5/24#n",
        )
        assertEquals("10.0.0.5/24", cidr.localAddress)

        val missingPeerKey = runCatching {
            parseWireGuardLink("wireguard://$PRIVATE_KEY@198.51.100.9:51820?address=10.0.0.5/32#n")
        }
        assertTrue("缺少 publickey 的連結必須被拒絕", missingPeerKey.isFailure)
    }

    @Test
    fun shadowsocksWebSocketMapsToBuiltInV2rayPlugin() {
        val bean = parseShadowsocks(
            "ss://2022-blake3-aes-256-gcm:passw0rd@203.0.113.7:8443" +
                "?type=ws&path=%2Fws&host=cdn.example&security=tls#edge",
        )

        assertEquals(
            "v2ray-plugin;mode=websocket;host=cdn.example;path=/ws;tls;mux=0",
            bean.plugin,
        )
    }

    @Test
    fun shadowsocksPlainTcpKeepsPluginAndRejectsTheRest() {
        val plain = parseShadowsocks("ss://aes-256-gcm:passw0rd@203.0.113.7:8443#plain")
        assertEquals("", plain.plugin)

        for (query in listOf("type=grpc&serviceName=x", "type=xhttp", "type=kcp", "type=tcp&security=tls")) {
            val rejected = runCatching {
                parseShadowsocks("ss://aes-256-gcm:passw0rd@203.0.113.7:8443?$query#x")
            }
            assertTrue("sing-box 沒有這個 Shadowsocks 傳輸層，應該拒絕而不是退化：$query", rejected.isFailure)
        }
    }
}
