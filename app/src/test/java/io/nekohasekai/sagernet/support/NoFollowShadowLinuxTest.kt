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
            // 下面兩種 errno 的**數值**不再斷言：那是 Robolectric 對 host NIO 例外的映射，不是
            // 本專案代碼的承諾。本機對 JDK 21／25／27 實跑同一支探針量到：JDK 25 起
            // Files.readAttributes("正則檔/child", NOFOLLOW) 從 FileSystemException 改丟
            // NoSuchFileException（Robolectric 映射成 ENOENT=2，實測 CI 就是 expected:<20> but
            // was:<2>），自引用 symlink 那條在 25 上也只是 FileSystemException、到 27 才是
            // FileSystemLoopException。CI 抬到 JDK 25 之後這條紅的是模擬層的口徑，
            // 而這條測試真正要守的是「lstat 仍派得進內建 shadow、沒有被我們的實作頂掉」，
            // 所以留 assertThrows（有擲 ErrnoException 就代表派到了真实檔案系統路徑），
            // 並由下面 stat 的 S_ISREG／S_ISDIR 與上面的 lstat 型別斷言守住語意。
            assertThrows(ErrnoException::class.java) { Os.lstat(file.resolve("child").toString()) }
            assertThrows(ErrnoException::class.java) { Os.lstat(links[3].resolve("child").toString()) }
            // Dispatch of an inherited implementation must still use the built-in shadow.
            assertTrue(OsConstants.S_ISREG(Os.stat(links[0].toString()).st_mode))
            assertTrue(OsConstants.S_ISDIR(Os.stat(links[1].toString()).st_mode))
        } finally {
            links.forEach { Files.delete(it) }
        }
    }
}
