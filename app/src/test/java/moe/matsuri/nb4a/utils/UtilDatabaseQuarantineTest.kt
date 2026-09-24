package moe.matsuri.nb4a.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * sing-box 的 cache.db 是 bbolt，Room 的庫是 SQLite。早先的體檢只認 SQLite 魔數，結果每次
 * 啟動都把健康的 fakeip 快取當損毀隔離掉，所以兩種格式都得放行。
 */
class UtilDatabaseQuarantineTest {

    private val dir: File = Files.createTempDirectory("quarantine").toFile().apply { deleteOnExit() }

    private fun write(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun sqliteHeader(): ByteArray {
        val header = ByteArray(4096)
        "SQLite format 3".toByteArray(Charsets.ISO_8859_1).copyInto(header, 0)
        header[15] = 0
        return header
    }

    private fun bboltHeader(pageSize: Int): ByteArray {
        val header = ByteArray(4096)
        byteArrayOf(0xED.toByte(), 0xDA.toByte(), 0x0C.toByte(), 0xED.toByte()).copyInto(header, 16)
        header[20] = 2
        header[24] = (pageSize and 0xFF).toByte()
        header[25] = ((pageSize shr 8) and 0xFF).toByte()
        return header
    }

    private fun corruptSiblings(name: String) =
        dir.listFiles { f -> f.name.startsWith("$name.corrupt-") }?.toList().orEmpty()

    @Test
    fun healthySqliteIsLeftAlone() {
        val db = write("profile.db", sqliteHeader())

        assertFalse(Util.quarantineIfNotDatabase(db))
        assertTrue(db.isFile)
        assertTrue(corruptSiblings("profile.db").isEmpty())
    }

    @Test
    fun healthyBboltCacheIsLeftAlone() {
        val db = write("cache.db", bboltHeader(4096))

        assertFalse(Util.quarantineIfNotDatabase(db))
        assertTrue(db.isFile)
        assertTrue(corruptSiblings("cache.db").isEmpty())
    }

    @Test
    fun bboltWithBogusPageSizeIsQuarantined() {
        val db = write("boguspage.db", bboltHeader(1234))

        assertTrue(Util.quarantineIfNotDatabase(db))
        assertFalse(db.isFile)
        assertEquals(1, corruptSiblings("boguspage.db").size)
    }

    @Test
    fun garbageIsQuarantined() {
        val db = write("garbage.db", ByteArray(64) { 0xFF.toByte() })

        assertTrue(Util.quarantineIfNotDatabase(db))
        assertFalse(db.isFile)
        assertEquals(1, corruptSiblings("garbage.db").size)
    }

    @Test
    fun truncatedFileIsQuarantined() {
        val db = write("short.db", ByteArray(20))

        assertTrue(Util.quarantineIfNotDatabase(db))
        assertFalse(db.isFile)
    }

    @Test
    fun walAndShmGoWithTheQuarantinedDatabase() {
        val db = write("journal.db", ByteArray(64) { 0xFF.toByte() })
        val wal = write("journal.db-wal", ByteArray(8))
        val shm = write("journal.db-shm", ByteArray(8))

        assertTrue(Util.quarantineIfNotDatabase(db))
        assertFalse(wal.exists())
        assertFalse(shm.exists())
    }

    @Test
    fun missingFileIsNotAnError() {
        assertFalse(Util.quarantineIfNotDatabase(File(dir, "never-existed.db")))
    }
}
