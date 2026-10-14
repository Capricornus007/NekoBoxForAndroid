package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.io.InputStream

const val MAX_SUBSCRIPTION_BYTES = 16 * 1024 * 1024
const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

fun InputStream.readBytesLimited(limit: Int): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val remaining = limit - total
        val read = read(buffer, 0, if (remaining >= buffer.size) buffer.size else remaining + 1)
        if (read == -1) return output.toByteArray()
        require(read > 0) { "Input stream made no progress" }
        require(read <= limit - total) { "Input exceeds $limit bytes" }
        output.write(buffer, 0, read)
        total += read
    }
}
