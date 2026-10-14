package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

const val MAX_SUBSCRIPTION_BYTES = 16 * 1024 * 1024
const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    copyToLimited(output, limit.toLong())
    return output.toByteArray()
}

fun InputStream.copyToLimited(output: OutputStream, limit: Long): Long {
    require(limit >= 0)
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val remaining = limit - total
        val read = read(buffer, 0, if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt())
        if (read == -1) return total
        require(read > 0) { "Input stream made no progress" }
        require(read <= limit - total) { "Input exceeds $limit bytes" }
        output.write(buffer, 0, read)
        total += read
    }
}
