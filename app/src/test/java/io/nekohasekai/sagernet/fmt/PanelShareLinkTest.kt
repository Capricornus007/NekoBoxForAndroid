package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundTLS
import io.nekohasekai.sagernet.fmt.v2ray.parseV2RayN
import io.nekohasekai.sagernet.fmt.v2ray.toUriVMessVLESSTrojan
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder
import java.util.Base64

/** Share links and subscription metadata in the shapes 3x-ui generates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PanelShareLinkTest {

    private lateinit var originalLogSink: (String) -> Unit

    @Before
    fun setUp() {
        originalLogSink = Logs.sink
        Logs.sink = {}
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    @Test
    fun wireguardLink_importsKeysAndAddresses() = runTest {
        val link = "wireguard://${encode(PRIVATE_KEY)}@198.51.100.7:51820?publickey=${encode(PUBLIC_KEY)}" +
            "&presharedkey=${encode(PRESHARED_KEY)}&address=${encode("10.0.0.2/32,fd00::2")}&mtu=1280&dns=1.1.1.1&keepalive=25#wg-node"
        val bean = parseProxies(link).single() as WireGuardBean

        assertEquals("wg-node", bean.name)
        assertEquals("198.51.100.7", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals(PRIVATE_KEY, bean.privateKey)
        assertEquals(PUBLIC_KEY, bean.peerPublicKey)
        assertEquals(PRESHARED_KEY, bean.peerPreSharedKey)
        assertEquals("10.0.0.2/32\nfd00::2/128", bean.localAddress)
        assertEquals(1280, bean.mtu)
        // A raw '+' in a key is a base64 character, not a space.
        val rawPlus = parseProxies("wg://${encode(PRIVATE_KEY)}@198.51.100.7:51820?publickey=$PRESHARED_KEY&address=10.0.0.2").single() as WireGuardBean
        assertEquals(PRESHARED_KEY, rawPlus.peerPublicKey)
        assertEquals("10.0.0.2/32", rawPlus.localAddress)
    }

    @Test
    fun amneziaVpnLink_importsConfAndRemark() = runTest {
        val conf = """
            [Interface]
            PrivateKey = $PRIVATE_KEY
            Address = 10.0.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 15
            S2 = 18
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4

            # office|📊∞
            [Peer]
            PublicKey = $PUBLIC_KEY
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 198.51.100.7:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val link = "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(conf.toByteArray())
        val bean = parseProxies(link).single() as AmneziaWGBean

        assertEquals("office|📊∞", bean.name)
        assertEquals("198.51.100.7", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals(PUBLIC_KEY, bean.peerPublicKey)
        assertEquals(4, bean.jc)
        assertEquals("1", bean.h1)
    }

    @Test
    fun hysteria2Link_acceptsGeckoBoundsAliases() {
        val bean = parseHysteria2("hysteria2://pw@203.0.113.5:443?security=tls&sni=h.example&obfs=gecko&obfs-password=g&minPacketSize=512&maxPacketSize=1200#hy")

        assertEquals(HysteriaBean.OBFS_GECKO, bean.hysteria2ObfsType)
        assertEquals("g", bean.obfuscation)
        assertEquals(512, bean.geckoMinPacketSize)
        assertEquals(1200, bean.geckoMaxPacketSize)
    }

    @Test
    fun shadowsocksLink_mapsWebSocketTransportToV2rayPlugin() {
        val userinfo = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pw".toByteArray())
        val bean = parseShadowsocks("ss://$userinfo@203.0.113.5:443?type=ws&path=%2Fss&host=cdn.example&security=tls&sni=cdn.example#ss-ws")

        assertEquals("aes-256-gcm", bean.method)
        assertEquals("v2ray-plugin;mode=websocket;host=cdn.example;path=/ss;tls;mux=0", bean.plugin)
        assertEquals("", parseShadowsocks("ss://$userinfo@203.0.113.5:443?type=tcp#plain").plugin)
        assertThrows(IllegalStateException::class.java) {
            parseShadowsocks("ss://$userinfo@203.0.113.5:443?type=grpc&serviceName=ss#ss-grpc")
        }
    }

    @Test
    fun vlessLink_importsEchAndRoundTripsIt() = runTest {
        ConfigBuilderTestEnv.reset()
        val link = "vless://$UUID@node.example:443?type=tcp&security=tls&sni=node.example&fp=chrome&flow=xtls-rprx-vision&ech=${encode(ECH)}#ech"
        val bean = parseProxies(link).single() as VMessBean
        assertTrue(bean.enableECH!!)
        assertEquals(ECH, bean.echConfig)

        val reimported = parseProxies(bean.toUriVMessVLESSTrojan(false)).single() as VMessBean
        assertEquals(ECH, reimported.echConfig)

        // A DoH URL means "query the HTTPS record"; it is kept for export and sing-box runs ECH without a config.
        val dynamic = parseProxies("vless://$UUID@node.example:443?type=tcp&security=tls&ech=${encode("https://1.1.1.1/dns-query")}").single() as VMessBean
        assertTrue(dynamic.enableECH!!)
        assertEquals("https://1.1.1.1/dns-query", dynamic.echConfig)
        assertTrue(dynamic.toUriVMessVLESSTrojan(false).contains("ech="))
        val dynamicEch = buildSingBoxOutboundTLS(dynamic.applyDefaultValues())!!.ech!!
        assertEquals(true, dynamicEch.enabled)
        assertNull(dynamicEch.config)
        val inlineEch = buildSingBoxOutboundTLS(bean.applyDefaultValues())!!.ech!!
        assertEquals(listOf("-----BEGIN ECH CONFIGS-----", ECH, "-----END ECH CONFIGS-----"), inlineEch.config)
    }

    @Test
    fun vmessLink_collectsFlattenedXhttpExtra() {
        val json = JSONObject()
            .put("v", "2").put("ps", "xhttp").put("add", "node.example").put("port", 443).put("id", UUID)
            .put("net", "xhttp").put("type", "none").put("host", "cdn.example").put("path", "/x").put("mode", "packet-up")
            .put("tls", "tls").put("sni", "cdn.example").put("ech", ECH)
            .put("x_padding_bytes", "100-1000").put("xPaddingBytes", "100-1000").put("scMaxEachPostBytes", "500000")
            .put("noGRPCHeader", true).put("xmux", JSONObject().put("maxConcurrency", "16-32"))
        val bean = parseV2RayN("vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray())).applyDefaultValues()

        assertEquals("xhttp", bean.type)
        assertEquals("packet-up", bean.xhttpMode)
        assertEquals(ECH, bean.echConfig)
        val extra = JSONObject(bean.xhttpExtra!!)
        assertEquals("100-1000", extra.getString("x_padding_bytes"))
        assertEquals("500000", extra.getString("sc_max_each_post_bytes"))
        assertTrue(extra.getBoolean("no_grpc_header"))
        assertEquals("16-32", extra.getJSONObject("xmux").getString("max_concurrency"))
        assertFalse(extra.has("ps"))
        assertFalse(extra.has("add"))
    }

    @Test
    fun subscriptionUserinfo_zeroExpiryMeansUnlimited() {
        val bean = SubscriptionBean().applyDefaultValues()
        bean.subscriptionUserinfo = "upload=1; download=2; total=0; expire=0"
        assertNull(bean.expiry())
        bean.subscriptionUserinfo = "upload=1; download=2; total=0; expire=1900000000"
        assertEquals(1900000000L, bean.expiry())
    }

    @Test
    fun subscriptionBean_storesSendDeviceId() {
        val bean = SubscriptionBean().applyDefaultValues().apply { sendDeviceId = true }
        val copy = KryoConverters.deserialize(SubscriptionBean(), KryoConverters.serialize(bean))
        assertTrue(copy.sendDeviceId!!)
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val UUID = "00000000-0000-4000-8000-000000000001"
        const val PRIVATE_KEY = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
        const val PUBLIC_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
        const val PRESHARED_KEY = "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE="
        const val ECH = "AEX+DQBBDQAgACCKB1Q4ZLb1MWv9S0XyWK8nZ9A8d0x2Yy5lBr0E3S9hCgAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="
    }
}
