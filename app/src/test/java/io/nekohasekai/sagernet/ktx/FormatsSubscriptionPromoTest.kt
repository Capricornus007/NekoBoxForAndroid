package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 面板常把自家官網／客服連結寫在訂閱末尾。那種網址路徑剛好是 "/"，parseHttp 原本會
 * 把它收成一顆沒有帳號密碼、永遠測速超時的假節點（實測名稱形如「客服👉 - https://…」）。
 * 這裡釘住三件事：訂閱路徑擋掉它、手動貼連結不受影響、有證據的真代理照收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FormatsSubscriptionPromoTest {

    // 預設的 Logs.sink 會走到需要 libgojni 的那條路，JVM 單測裡沒有這顆庫
    // （go.Seq 靜態初始化 → UnsatisfiedLinkError）。本倉既有測試（FormatsLoggingPrivacyTest）
    // 同樣是換掉 sink，這裡沿用同一個做法。
    private val capturedLogs = mutableListOf<String>()

    @Before
    fun redirectLogs() {
        originalSink = Logs.sink
        Logs.sink = { capturedLogs.add(it) }
    }

    @After
    fun restoreLogs() {
        Logs.sink = originalSink
        capturedLogs.clear()
    }

    private var originalSink: (String) -> Unit = {}

    private val promo = "https://panel.example/"
    private val promoWithLabel = "https://panel.example/#客服-signup-link"
    private val socks = "socks://reader:password@192.0.2.8:1080#real-node"

    @Test
    fun subscriptionPromoUrl_doesNotBecomeANode() = runTest {
        val beans = parseProxies("$socks\n$promo", subscription = true)

        assertEquals(1, beans.size)
        assertTrue("唯一那顆應該是真 SOCKS 節點，實際 ${beans.single()::class.java.simpleName}", beans.single() is SOCKSBean)
    }

    @Test
    fun subscriptionPromoUrlWithFragment_doesNotBecomeANode() = runTest {
        // 帶 #fragment 的 promo 最陰：fragment 會變成節點名稱。
        val beans = parseProxies("$socks\n$promoWithLabel", subscription = true)

        assertEquals(1, beans.size)
        assertTrue(beans.single() is SOCKSBean)
    }

    @Test
    fun manualPastePath_stillAcceptsBareHttpUrl() = runTest {
        // 回歸鎖：閘門只准裝在訂閱解析路徑上，手動貼連結的行為不該跟著變。
        val beans = parseProxies("$socks\n$promo")

        assertEquals(2, beans.size)
        assertTrue(beans.any { it is HttpBean })
    }

    @Test
    fun promoAlone_becomesSubscriptionCandidateInsteadOfANode() = runTest {
        try {
            val beans = parseProxies(promo, subscription = true)
            fail("該被當成訂閱網址，結果回傳了節點：$beans")
        } catch (e: SubscriptionFoundException) {
            assertTrue(e.link.startsWith("clash://install-config"))
        }
    }

    @Test
    fun httpProxyWithCredentials_survivesTheGate() = runTest {
        val beans = parseProxies("http://user:pass@192.0.2.9:8080#proxy", subscription = true)

        val bean = beans.single() as HttpBean
        assertEquals("192.0.2.9", bean.serverAddress)
        assertEquals(8080, bean.serverPort)
    }

    @Test
    fun httpProxyOnNonDefaultPortWithoutAuth_survivesTheGate() = runTest {
        val beans = parseProxies("http://192.0.2.9:3128/", subscription = true)

        assertTrue(beans.single() is HttpBean)
    }

    @Test
    fun evidencePredicateCoversBothDirections() {
        assertTrue(hasHttpProxyEvidence("http://192.0.2.9:8080/"))
        assertTrue(hasHttpProxyEvidence("http://u:p@192.0.2.9/"))
        assertTrue(hasHttpProxyEvidence("https://192.0.2.9/?sni=proxy.example"))
        assertFalse(hasHttpProxyEvidence("https://panel.example/"))
        // 寫出來但等於協定預設值的端口不算證據：它跟裸網址一樣沒有區辨力。
        assertFalse(hasHttpProxyEvidence("https://panel.example:443/"))
        assertFalse(hasHttpProxyEvidence("https://t.me/some_channel"))
        assertFalse(hasHttpProxyEvidence("not a url"))
    }

    @Test
    fun promoLinkIsNeverWrittenToTheLog() = runTest {
        parseProxies("$socks\n$promoWithLabel", subscription = true)

        val output = capturedLogs.joinToString("\n")
        assertFalse("訂閱內容不得進日誌", output.contains("panel.example"))
        assertFalse("節點名稱不得進日誌", output.contains("客服"))
        // 這行是「neko.log 裡看不到 promo guard 就等于沒命中」這個推論的前提：
        // 攔截必須留得下痕跡，否則觀測口一旦靜默，實測的 0 筆就毫無意義。
        assertTrue("攔截要留下可計數的痕跡", output.contains("no proxy evidence"))
        assertTrue(output.contains("promo guard"))
    }

    // 另一種假節點來源：面板把官網／客服文字塞進節點 remark。那種不是 http 連結，
    // 證據閘門擋不到，只能先觀測。這裡釘住兩頭：要命中該命中的，且不得把
    // `xhttp` 這種協定字誤判成 promo（實測 DB 裡就有 VLESS-xhttp-Reality 這種真節點）。
    @Test
    fun promoNamePatternMatchesPanelFooterNotProtocolNames() {
        assertEquals("support-site", promoNamePattern("客服👉 官网"))
        assertEquals("quota-info", promoNamePattern("剩余流量 100GB"))
        assertEquals("quota-info", promoNamePattern("到期时间"))
        assertEquals("subscription-info", promoNamePattern("续费订阅"))
        assertEquals("telegram-link", promoNamePattern("加 T.ME 频道"))
        assertEquals("url-in-name", promoNamePattern("a https://x.invalid b"))

        assertNull("協定字 xhttp 不得被當成 promo", promoNamePattern("VLESS-xhttp-Reality-Vision"))
        assertNull(promoNamePattern("马来西亚03|x8.5|Stream|AI"))
        assertNull(promoNamePattern("日本05|x2.0|Stream"))
        assertNull(promoNamePattern(""))
    }

    @Test
    fun promoNamedNodeIsObservedButNotDropped() = runTest {
        // remark 帶 promo 文字的連結仍是合法節點：只能記一筆觀測，不能刪。
        val link = "socks://reader:password@192.0.2.8:1080#客服👉 官网入口"
        val beans = parseProxies(link, subscription = true)

        assertEquals(1, beans.size)
        assertTrue(capturedLogs.joinToString("\n").contains("look like panel info text"))
    }
}
