package io.nekohasekai.sagernet.fmt.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 清單快取的契約：退出頁面再進來要看得到東西，但壞掉的快取必須当成「沒有」，
 * 否則使用者會一直對著一份永遠空著的清單。org.json 是 Android 框架類別，
 * 所以跟 ProtonSidecarParseTest 一樣走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProtonNodeCacheTest {

    private fun dir(): File = Files.createTempDirectory("proton-cache").toFile()

    private val payload = """
        {"ok":true,"dropped":2,"servers":[
          {"id":"1","name":"US-FREE#2","penalty":0.1,"tier":1,"ipv6":true,"country":"US","city":"Los Angeles","endpoint":"1.2.3.4","wgPublicKey":"k1","port":51820},
          {"id":"2","name":"JP-PLUS#1","penalty":0.4,"tier":2,"country":"jp","city":"Tokyo","domain":"jp.example","wgPublicKey":"k2"}
        ]}
    """.trimIndent()

    @Test
    fun `round-trips through the same parser the network path uses`() {
        val cache = dir()
        assertTrue(ProtonNodeCache.save(cache, payload))
        val loaded = ProtonNodeCache.load(cache)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.nodes.size)
        assertEquals(2, loaded.dropped)
        assertEquals("US-FREE#2", loaded.nodes.first().name)
        // 第二條沒帶 endpoint 時會退回 domain，兩邊解析結果必須一致。
        assertEquals("jp.example", loaded.nodes[1].endpoint)
    }

    @Test
    fun `missing, blank and non-object payloads all read as no cache`() {
        val empty = dir()
        assertNull(ProtonNodeCache.load(empty))
        assertEquals(0L, ProtonNodeCache.savedAt(empty))

        ProtonNodeCache.save(empty, "   ")
        assertNull(ProtonNodeCache.load(empty))

        ProtonNodeCache.save(empty, "not json at all")
        assertNull(ProtonNodeCache.load(empty))
    }

    @Test
    fun `a payload with no usable server is not kept as a cache`() {
        val cache = dir()
        assertTrue(ProtonNodeCache.save(cache, """{"ok":true,"dropped":1576,"servers":[]}"""))
        assertNull(ProtonNodeCache.load(cache))
    }

    @Test
    fun `an explicit failure payload is not kept either`() {
        val cache = dir()
        ProtonNodeCache.save(cache, """{"ok":false,"error":"session rejected, please log in again"}""")
        assertNull(ProtonNodeCache.load(cache))
    }

    @Test
    fun `savedAt moves forward after a save and clear removes the file`() {
        val cache = dir()
        assertTrue(ProtonNodeCache.save(cache, payload))
        assertTrue(ProtonNodeCache.savedAt(cache) > 0L)
        assertTrue(ProtonNodeCache.file(cache).isFile)
        ProtonNodeCache.clear(cache)
        assertFalse(ProtonNodeCache.file(cache).exists())
        assertNull(ProtonNodeCache.load(cache))
    }

    @Test
    fun `payload sniffing rejects arrays and junk`() {
        assertTrue(ProtonNodeCache.looksLikePayload(payload))
        assertFalse(ProtonNodeCache.looksLikePayload("""["a"]"""))
        assertFalse(ProtonNodeCache.looksLikePayload(""))
    }
}
