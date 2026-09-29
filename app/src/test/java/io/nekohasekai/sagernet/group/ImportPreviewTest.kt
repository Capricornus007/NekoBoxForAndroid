package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.ImportPreview.Reason
import io.nekohasekai.sagernet.group.ImportPreview.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ImportPreviewTest {

    @Before
    fun setUp() = ConfigBuilderTestEnv.reset()

    private fun socks(n: Int) = List(n) {
        SOCKSBean().apply {
            serverAddress = "192.0.2.$it"
            serverPort = 1080
            initializeDefaultValues()
        }
    }

    @Test
    fun singBoxConfigReportsBuiltinsUnparsedAndDroppedSections() {
        val text = """
            {"log": {}, "dns": {"servers": []}, "inbounds": [{"type": "tun"}],
             "outbounds": [{"type": "socks", "tag": "a"}, {"type": "socks", "tag": "b"}, {"type": "direct"}, {"type": "selector", "outbounds": ["a"]}, {"type": "mystery"}],
             "route": {"rules": []}}
        """.trimIndent()
        val preview = ImportPreview.of(text, socks(2))

        assertEquals(2, preview.acceptedCount)
        assertEquals(mapOf(Reason.BUILTIN to 2, Reason.UNPARSED to 1), preview.skipped)
        assertEquals(listOf(Section.RULES, Section.DNS, Section.INBOUNDS), preview.dropped)
        assertTrue(preview.behaviorDiffers)
    }

    @Test
    fun clashConfigCountsTopLevelNodesOnly() {
        val text = """
            port: 7890
            dns:
              enable: true
            proxies:
              - name: one
                type: socks5
                alpn:
                  - h2
              - name: two
                type: socks5
              - name: broken
                type: nope
            proxy-groups:
              - name: auto
            rules:
              - MATCH,auto
        """.trimIndent()
        val preview = ImportPreview.of(text, socks(2))

        assertEquals(mapOf(Reason.UNPARSED to 1), preview.skipped)
        assertEquals(listOf(Section.PROXY_GROUPS, Section.RULES, Section.DNS), preview.dropped)
    }

    @Test
    fun linkListReportsUnparsedLinesWithoutDroppedSections() {
        val text = "socks://a\n# comment\nsocks://b\nnotalink://c\n"
        val preview = ImportPreview.of(text, socks(2))

        assertEquals(mapOf("SOCKS5" to 2), preview.accepted)
        assertEquals(mapOf(Reason.UNPARSED to 1), preview.skipped)
        assertFalse(preview.behaviorDiffers)
    }
}
