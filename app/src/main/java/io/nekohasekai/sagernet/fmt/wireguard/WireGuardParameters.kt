package io.nekohasekai.sagernet.fmt.wireguard

internal fun WireGuardBean.validateAmneziaParameters() {
    val numbers = mapOf("Jc" to jc, "Jmin" to jmin, "Jmax" to jmax, "S1" to s1, "S2" to s2, "S3" to s3, "S4" to s4)
    numbers.forEach { (name, value) -> require((value ?: 0) >= 0) { "$name must be non-negative" } }
    require((jc ?: 0) == 0 || (jmin ?: 0) <= (jmax ?: 0)) { "Jmin must not exceed Jmax when junk packets are enabled" }
    mapOf("H1" to h1, "H2" to h2, "H3" to h3, "H4" to h4, "I1" to i1, "I2" to i2, "I3" to i3, "I4" to i4, "I5" to i5).forEach { (name, value) ->
        require(value?.none { it == '\r' || it == '\n' } != false) { "$name must be a single line" }
    }
}
