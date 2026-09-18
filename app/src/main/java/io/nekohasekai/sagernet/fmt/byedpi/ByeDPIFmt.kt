package io.nekohasekai.sagernet.fmt.byedpi

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions.Outbound_ByeDPIOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// byeDPI 没有服务器，链接里唯一的实体信息是 CLI 策略，整段百分号编码放进查询串：
//   byedpi://local?cli=<urlencoded>#<显示名>
// 固定 host 段 local 只为满足 URL 语法，解析端不读它。
const val BYEDPI_LINK_HOST = "local"

fun parseByeDPI(link: String): ByeDPIBean {
    val url = link.replace("byedpi://", "https://").toHttpUrlOrNull()
        ?: error("invalid byedpi link $link")
    return ByeDPIBean().apply {
        name = url.fragment ?: ""
        url.queryParameter("cli")?.let { cliStrategy = it }
    }
}

fun ByeDPIBean.toUri(): String {
    val builder = linkBuilder().host(BYEDPI_LINK_HOST)
    if (cliStrategy.isNotBlank()) {
        builder.addQueryParameter("cli", cliStrategy)
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    return builder.toLink("byedpi", appendDefaultPort = false)
}

fun buildSingBoxOutboundByeDPIBean(bean: ByeDPIBean): Outbound_ByeDPIOptions {
    return Outbound_ByeDPIOptions().apply {
        type = "byedpi"
        cli = bean.cliStrategy
    }
}
