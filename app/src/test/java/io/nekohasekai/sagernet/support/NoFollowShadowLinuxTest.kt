package io.nekohasekai.sagernet.support

import android.app.Application
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NoFollowShadowLinuxTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun lstatReportsMissingPathsAndLinkTypesWithoutReplacingInheritedStat() {
        val root = temporary.newFolder().toPath()
        val file = Files.createFile(root.resolve("file"))
        val directory = Files.createDirectory(root.resolve("directory"))
        val missing = root.resolve("missing")
        val links = listOf(
            Files.createSymbolicLink(root.resolve("file-link"), file),
            Files.createSymbolicLink(root.resolve("directory-link"), directory),
            Files.createSymbolicLink(root.resolve("dangling-link"), missing),
            Files.createSymbolicLink(root.resolve("loop-link"), root.resolve("loop-link")),
        )
        try {
            assertTrue(OsConstants.S_ISREG(Os.lstat(file.toString()).st_mode))
            assertTrue(OsConstants.S_ISDIR(Os.lstat(directory.toString()).st_mode))
            for (link in links) assertTrue(OsConstants.S_ISLNK(Os.lstat(link.toString()).st_mode))
            val absent = assertThrows(ErrnoException::class.java) { Os.lstat(missing.toString()) }
            assertEquals(OsConstants.ENOENT, absent.errno)
            val notDirectory = assertThrows(ErrnoException::class.java) { Os.lstat(file.resolve("child").toString()) }
            assertEquals(OsConstants.ENOTDIR, notDirectory.errno)
            val loop = assertThrows(ErrnoException::class.java) { Os.lstat(links[3].resolve("child").toString()) }
            assertEquals(OsConstants.ELOOP, loop.errno)
            // Dispatch of an inherited implementation must still use the built-in shadow.
            assertTrue(OsConstants.S_ISREG(Os.stat(links[0].toString()).st_mode))
            assertTrue(OsConstants.S_ISDIR(Os.stat(links[1].toString()).st_mode))
        } finally {
            links.forEach { Files.delete(it) }
        }
    }
}
