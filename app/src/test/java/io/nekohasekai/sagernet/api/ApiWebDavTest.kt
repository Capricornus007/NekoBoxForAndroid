package io.nekohasekai.sagernet.api

import android.app.Application
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class ApiWebDavTest {
    @Test
    fun listingAcceptsOnlyBackupFilesDirectlyInTheConfiguredDirectory() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/NekoBox/nekobox_backup_20261001_120000.json</D:href></D:response>
              <D:response><D:href>nekobox_backup_20261002_120000.zip</D:href></D:response>
              <D:response><D:href>https://other.example/dav/NekoBox/nekobox_backup_other.json</D:href></D:response>
              <D:response><D:href>/dav/other/nekobox_backup_other.json</D:href></D:response>
              <D:response><D:href>/dav/NekoBox/sub/nekobox_backup_other.json</D:href></D:response>
              <D:response><D:href>/dav/NekoBox/nekobox_backup_other.json?secret=x</D:href></D:response>
              <D:response><D:href>/dav/NekoBox/not-a-backup.txt</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        val files = ApiWebDav.parseListing(xml, "https://dav.example/dav/NekoBox".toHttpUrl())
        assertEquals(2, files.length())
        assertEquals("nekobox_backup_20261002_120000.zip", files.getString(0))
        assertEquals("nekobox_backup_20261001_120000.json", files.getString(1))
    }

    @Test
    fun xmlEntitiesAndFileTraversalAreRejected() {
        val xml = """<!DOCTYPE root [<!ENTITY file SYSTEM "file:///data/private">]><root>&file;</root>"""
        assertTrue(runCatching { ApiWebDav.parseListing(xml, "https://dav.example/backups".toHttpUrl()) }.exceptionOrNull() is ApiFailure)
        for (name in listOf("../nekobox_backup_a.json", "nekobox_backup_a/other.json", "nekobox_backup_a\\other.zip", "nekobox_backup_a\u0000.zip", "nekobox_backup_a.txt")) {
            assertFalse(name, ApiWebDav.validName(name))
        }
        assertTrue(ApiWebDav.validName("nekobox_backup_20261001_120000.zip"))
    }
}
