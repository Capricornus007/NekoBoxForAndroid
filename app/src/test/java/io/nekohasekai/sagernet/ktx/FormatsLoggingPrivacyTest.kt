package io.nekohasekai.sagernet.ktx

import android.content.Context
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FormatsLoggingPrivacyTest {

    private lateinit var originalLogSink: (String) -> Unit
    private var originalLogLevel: Int = 0
    private val capturedLogs = mutableListOf<String>()

    @Before
    fun setUp() {
        // Logs.d/i 會觸碰 DataStore（clinit 走 PublicDatabase -> SagerNet.application）。
        // 測試的 Application 是 plain android.app.Application，SagerNet.application 這個
        // lateinit 不會被框架賦值——照 ConfigBuilderTestEnv 的手法補上（不跑 onCreate，
        // 避免載入 JVM 環境不可用的原生庫）。
        val alreadyAttached = try {
            SagerNet.application; true
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
        if (!alreadyAttached) {
            val application = SagerNet()
            SagerNet::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
                invoke(application, RuntimeEnvironment.getApplication() as android.app.Application)
            }
        }
        // PR #140 觀察的是 Info 級解析日誌；fork 後來給 Logs.i 加了 logLevel 門檻（2..4），
        // 預設 0 會把待驗證的日誌整個濾掉——把等級拉到 info 再驗證。
        originalLogLevel = DataStore.logLevel
        DataStore.logLevel = 3
        originalLogSink = Logs.sink
        Logs.sink = { capturedLogs.add(it) }
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
        DataStore.logLevel = originalLogLevel
    }

    @Test
    fun unrecognizedScheme_doesNotLogInput() = runTest {
        val markers = listOf(
            "unknown-userinfo-marker",
            "unknown-host-marker",
            "unknown-path-marker",
            "unknown-query-marker",
            "unknown-fragment-marker",
        )
        val input =
            "unknown://${markers[0]}@${markers[1]}.invalid/${markers[2]}?value=${markers[3]}#${markers[4]}"

        assertTrue(parseProxies(input).isEmpty())
        assertTrue(capturedLogs.isEmpty())
        assertLogsOmit(input, markers)
    }

    @Test
    fun malformedSupportedUri_doesNotLogInputOrExceptionText() = runTest {
        val markers = listOf(
            "direct-userinfo-marker",
            "direct-host-marker",
            "direct-path-marker",
            "direct-query-marker",
            "direct-fragment-marker",
            "bad-port",
        )
        val input =
            "socks://${markers[0]}@${markers[1]}.invalid:bad-port/${markers[2]}" +
                "?value=${markers[3]}#${markers[4]}"

        assertTrue(parseProxies(input).isEmpty())
        assertTrue(capturedLogs.any { it.contains("SOCKS parser rejected input") })
        assertLogsOmit(input, markers)
    }

    @Test
    fun malformedV2RayFallbacks_doNotLogEncodedPayload() = runTest {
        val decodedMarker = "encoded-payload-marker"
        val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"ps":"$decodedMarker"}""".toByteArray(),
        )
        val input = "vmess://$encodedPayload@:bad-port"

        assertTrue(parseProxies(input).isEmpty())
        assertTrue(capturedLogs.any { it.contains("V2RayN parser rejected input") })
        assertTrue(capturedLogs.any { it.contains("Kitsunebi parser rejected input") })
        assertLogsOmit(input, listOf(decodedMarker, encodedPayload, "bad-port"))
    }

    @Test
    fun multilineInput_parsesValidEntryWithoutLoggingMalformedEntry() = runTest {
        val marker = "multiline-malformed-marker"
        val validInput = "socks://reader:password@192.0.2.8:1080#valid-entry"
        val malformedInput = "socks://user@$marker.invalid:bad-port/path"
        val input = "$validInput\n$malformedInput"

        val bean = parseProxies(input).single() as SOCKSBean

        assertEquals("192.0.2.8", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("reader", bean.username)
        assertEquals("password", bean.password)
        assertLogsOmit(input, listOf(marker, validInput, malformedInput))
    }

    private fun assertLogsOmit(input: String, markers: List<String>) {
        val output = capturedLogs.joinToString("\n")
        assertFalse("complete input must not be logged", output.contains(input))
        markers.forEach { marker ->
            assertFalse("sensitive marker must not be logged", output.contains(marker))
        }
    }
}
