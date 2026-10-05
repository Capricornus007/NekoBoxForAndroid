package io.nekohasekai.sagernet.support

import android.system.ErrnoException
import android.system.OsConstants
import android.system.StructStat
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLinux
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

// Robolectric 4.17 delegates lstat to its following stat implementation and returns mode 0
// for missing files. Override only lstat; retain all other installed ShadowLinux behavior.
@Implements(className = "libcore.io.Linux", minSdk = 26, isInAndroidSdk = false)
class NoFollowShadowLinux : ShadowLinux() {
    @Implementation
    @Throws(ErrnoException::class)
    protected override fun lstat(path: String): StructStat = try {
        val attributes = Files.readAttributes(Paths.get(path), "unix:*", NOFOLLOW_LINKS)
        fun number(name: String) = (attributes.getValue(name) as Number).toLong()
        fun seconds(name: String) = (attributes.getValue(name) as FileTime).to(TimeUnit.SECONDS)
        StructStat(
            number("dev"),
            number("ino"),
            number("mode").toInt(),
            number("nlink"),
            number("uid").toInt(),
            number("gid").toInt(),
            number("rdev"),
            number("size"),
            seconds("lastAccessTime"),
            seconds("lastModifiedTime"),
            seconds("ctime"),
            0,
            0,
        )
    } catch (e: Exception) {
        val errno = when (e) {
            is NoSuchFileException -> OsConstants.ENOENT

            is AccessDeniedException, is SecurityException -> OsConstants.EACCES

            is NotDirectoryException -> OsConstants.ENOTDIR

            is FileSystemLoopException -> OsConstants.ELOOP

            is InvalidPathException -> OsConstants.EINVAL

            is FileSystemException -> when {
                e.reason == "Not a directory" -> OsConstants.ENOTDIR
                e.reason?.startsWith("Too many levels of symbolic links") == true -> OsConstants.ELOOP
                e.reason == "File name too long" -> OsConstants.ENAMETOOLONG
                else -> OsConstants.EIO
            }

            is IOException -> OsConstants.EIO

            is UnsupportedOperationException -> OsConstants.ENOSYS

            else -> throw e
        }
        throw ErrnoException("lstat", errno, e)
    }
}
