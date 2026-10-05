package io.nekohasekai.sagernet.bg.proto

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Lock files outlive state directories: removing them would split ownership across inodes. */
internal class TailscaleStateLease private constructor(
    private val path: String,
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : Closeable {
    private var closed = false

    override fun close() = synchronized(reservations) {
        if (!closed) {
            closed = true
            try {
                lock.release()
            } finally {
                try {
                    file.close()
                } finally {
                    reservations.remove(path)
                }
            }
        }
    }

    companion object {
        // Reserve before opening: closing even an *unlocked* second descriptor can release
        // this process's POSIX lock on the inode.
        private val reservations = HashSet<String>()

        fun acquire(directory: File, ids: Collection<Long>): Closeable {
            directory.mkdirs()
            check(directory.isDirectory) { "Cannot create Tailscale lock directory" }
            val acquired = ArrayList<TailscaleStateLease>()
            try {
                for (id in ids.distinct().sorted()) {
                    val path = File(directory, "$id.lock").canonicalPath
                    synchronized(reservations) {
                        check(reservations.add(path)) { busyMessage(id) }
                        var file: RandomAccessFile? = null
                        try {
                            file = RandomAccessFile(path, "rw")
                            val lock = file.channel.tryLock() ?: error(busyMessage(id))
                            acquired += TailscaleStateLease(path, file, lock)
                        } catch (e: Throwable) {
                            try {
                                file?.close()
                            } catch (closeError: Throwable) {
                                e.addSuppressed(closeError)
                            } finally {
                                reservations.remove(path)
                            }
                            throw e
                        }
                    }
                }
            } catch (e: Throwable) {
                try {
                    closeAll(acquired)
                } catch (closeError: Throwable) {
                    e.addSuppressed(closeError)
                }
                throw e
            }
            return Closeable { closeAll(acquired) }
        }

        private fun closeAll(leases: List<TailscaleStateLease>) {
            var failure: Throwable? = null
            for (lease in leases.asReversed()) {
                try {
                    lease.close()
                } catch (e: Throwable) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
            failure?.let { throw it }
        }

        private fun busyMessage(id: Long) = "Tailscale node $id is in use. Stop the service or close the active probe and retry."
    }
}
