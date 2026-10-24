package io.nekohasekai.sagernet.fmt.internal

import java.net.URI

internal fun validateHealthUrl(value: String) {
    val uri = URI.create(value)
    require(
        value.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535),
    )
}
