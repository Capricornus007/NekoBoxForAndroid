package io.nekohasekai.sagernet.fmt.wireguard

internal fun WireGuardBean.validateAmneziaParameters() {
    val numbers = mapOf("Jc" to jc, "Jmin" to jmin, "Jmax" to jmax, "S1" to s1, "S2" to s2, "S3" to s3, "S4" to s4)
    numbers.forEach { (name, value) -> require((value ?: 0) >= 0) { "$name must be non-negative" } }
    require((jc ?: 0) == 0 || (jmin ?: 0) <= (jmax ?: 0)) { "Jmin must not exceed Jmax when junk packets are enabled" }
    mapOf("H1" to h1, "H2" to h2, "H3" to h3, "H4" to h4, "I1" to i1, "I2" to i2, "I3" to i3, "I4" to i4, "I5" to i5).forEach { (name, value) ->
        require(value?.none { it == '\r' || it == '\n' } != false) { "$name must be a single line" }
    }
    mapOf("PersistentKeepalive" to persistentKeepalive, "ContentPaddingAddition" to contentPaddingAddition,
        "RekeyAfterTime" to rekeyAfterTime, "RekeyTimeout" to rekeyTimeout, "RejectAfterTime" to rejectAfterTime,
        "KeepaliveTimeout" to keepaliveTimeout, "MaxHandshakeAttempts" to maxHandshakeAttempts,
        "H1" to h1, "H2" to h2, "H3" to h3, "H4" to h4).forEach { (name, value) -> validateAmneziaRange(name, value) }
    if (!headerProtectionKey.isNullOrBlank() && decodeHeaderProtectionKey(headerProtectionKey).any { it.toInt() != 0 }) {
        require(listOf(s1, s2, s3, s4).all { (it ?: 0) >= 12 }) { "HeaderProtectionKey requires S1-S4 >= 12" }
    }

}

internal fun validateAmneziaRange(name: String, value: String?) {
    val text = value.orEmpty().trim()
    if (text.isEmpty()) return
    require(text.matches(Regex("[0-9]+(?:-[0-9]+)?"))) { "Invalid $name range" }
    val parts = text.split('-')
    val low = parts[0].toLongOrNull()
    val high = parts.last().toLongOrNull()
    require(low != null && high != null && low in 0..4294967295L && high in low..4294967295L && !(low == 0L && high == 4294967295L)) { "Invalid $name range" }
}

private fun decodeHeaderProtectionKey(value: String): ByteArray {
    val text = value.trim()
    val bytes = try {
        if (text.length == 64 && text.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            ByteArray(32) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else java.util.Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid HeaderProtectionKey") }
    require(bytes.size == 32) { "HeaderProtectionKey must contain 32 bytes" }
    return bytes
}
