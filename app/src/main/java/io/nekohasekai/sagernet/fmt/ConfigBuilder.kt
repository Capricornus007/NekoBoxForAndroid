package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.amneziawg.buildSingBoxEndpointAmneziaWGBean
import io.nekohasekai.sagernet.fmt.byedpi.ByeDPIBean
import io.nekohasekai.sagernet.fmt.byedpi.buildSingBoxOutboundByeDPIBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.FastestCandidateResolver
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.shadowquic.ShadowQUICBean
import io.nekohasekai.sagernet.fmt.shadowquic.buildSingBoxOutboundShadowQUICBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.trusttunnel.buildSingBoxOutboundTrustTunnelBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.SingBoxOptionsUtil
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_DNS_HOSTS = "dns-hosts"

const val LOCALHOST = "127.0.0.1"

// selector/urltest 分组出站不支持 domain_strategy 字段（sing-box 1.14 解码
// 直接拒绝），只有真实出站才能带。来自 isai 分支的修复
// （1d9328380 Fix domain strategy on group outbounds）。
private val GROUP_OUTBOUND_TYPES = setOf("selector", "urltest")

internal fun SingBoxOption.applyDomainStrategyIfSupported(domainStrategy: String) {
    val outboundType = asMap()["type"] as? String
    if (outboundType !in GROUP_OUTBOUND_TYPES) {
        _hack_config_map["domain_strategy"] = domainStrategy
    }
}

private val ENDPOINT_TYPES = setOf("wireguard", "awg")

private fun SingBoxOption.isGeneratedEndpoint(): Boolean = this is Endpoint && type in ENDPOINT_TYPES

internal fun SingBoxOption.detourTo(nextTag: String) {
    if (this is Endpoint_WireGuardOptions || this is Endpoint_AwgOptions) {
        val listenPort = asMap()["listen_port"]?.toString()?.toDoubleOrNull()?.toInt() ?: 0
        if (listenPort > 0) {
            // sing-box rejects listen_port together with detour. Direct is already the
            // endpoint default, so keep the listener in that case. For an actual proxy
            // chain, preserve the requested chain and disable only the incompatible
            // listener instead of aborting config creation.
            if (nextTag == TAG_DIRECT) return
            when (this) {
                is Endpoint_WireGuardOptions -> listen_port = null
                is Endpoint_AwgOptions -> listen_port = null
            }
        }
        when (this) {
            is Endpoint_WireGuardOptions -> detour = nextTag
            is Endpoint_AwgOptions -> detour = nextTag
        }
        return
    }
    _hack_config_map["detour"] = nextTag
}

internal data class ChainHopTag(
    val tag: String,
    val reused: Boolean,
)

internal fun resolveChainHopTag(
    profileId: Long,
    proposedTag: String,
    needGlobal: Boolean,
    globalOutbounds: MutableMap<Long, String>,
): ChainHopTag {
    if (!needGlobal) return ChainHopTag(proposedTag, reused = false)

    val existingTag = globalOutbounds[profileId]
    if (existingTag != null) return ChainHopTag(existingTag, reused = true)

    globalOutbounds[profileId] = proposedTag
    return ChainHopTag(proposedTag, reused = false)
}

internal fun RouteOptions.ensureMainRouteFinal(mainProxyTag: String) {
    if (final_.isNullOrBlank()) final_ = mainProxyTag
}

internal fun buildSelectorOutbound(defaultTag: String?, memberTags: List<String>) = Outbound_SelectorOptions().apply {
    type = "selector"
    tag = TAG_PROXY
    default_ = defaultTag
    outbounds = memberTags
}

// 自动优选最低延迟（OwnBox F01）：开启后本组出站从 selector 换成 urltest，
// 内核自动健康检查并毫秒级切换到最低延迟节点。
internal fun buildUrlTestOutbound(memberTags: List<String>) = Outbound_URLTestOptions().apply {
    type = "urltest"
    tag = TAG_PROXY
    outbounds = memberTags
    url = DataStore.connectionTestURL.takeIf { it.isNotBlank() }
        ?: "https://www.gstatic.com/generate_204"
    interval = "5m"
    tolerance = 50
}

// 負載平衡（OwnBox 移植）：round-robin 分發到所有成員
internal fun buildLoadBalanceOutbound(memberTags: List<String>) = Outbound_SelectorOptions().apply {
    type = "loadbalance"
    tag = TAG_PROXY
    outbounds = memberTags
}

private fun endpointTag(value: Any?): String? =
    (value as? Map<*, *>)?.get("tag")?.toString()?.takeIf { it.isNotBlank() }

private fun mergeEndpointList(existing: List<*>, incoming: List<*>, prependNew: Boolean = false): MutableList<Any?> {
    val result = existing.toMutableList()
    val additions = mutableListOf<Any?>()
    incoming.forEach { endpoint ->
        val tag = endpointTag(endpoint)
        val existingIndex = tag?.let { candidate -> result.indexOfFirst { endpointTag(it) == candidate } } ?: -1
        val additionIndex = tag?.let { candidate -> additions.indexOfFirst { endpointTag(it) == candidate } } ?: -1
        when {
            existingIndex >= 0 -> result[existingIndex] = endpoint
            additionIndex >= 0 -> additions[additionIndex] = endpoint
            else -> additions.add(endpoint)
        }
    }
    if (prependNew) result.addAll(0, additions) else result.addAll(additions)
    return result
}

@Suppress("UNCHECKED_CAST")
private fun mergeRootConfig(dst: MutableMap<String, Any?>, json: String) {
    if (json.isBlank()) return
    val source = gson.fromJson(json, dst.javaClass) as? Map<String, Any?> ?: return
    val remaining = source.toMutableMap()
    val replacement = remaining.remove("endpoints")
    val prepended = remaining.remove("+endpoints")
    val appended = remaining.remove("endpoints+")
    Util.mergeMap(dst, remaining)

    fun merge(value: Any?, prependNew: Boolean = false) {
        if (value !is List<*>) {
            if (value != null) dst["endpoints"] = value
            return
        }
        val current = dst["endpoints"] as? List<*> ?: emptyList<Any?>()
        dst["endpoints"] = mergeEndpointList(current, value, prependNew)
    }
    merge(replacement)
    merge(prepended, prependNew = true)
    merge(appended)
}

internal fun finalizeRootConfig(
    options: MyOptions,
    globalCustomConfig: String = "",
    profileCustomConfig: String = "",
): MutableMap<String, Any?> {
    val generatedEndpoints = options.outbounds.orEmpty().filter { it.isGeneratedEndpoint() }
    generatedEndpoints
        .filterIsInstance<Endpoint>()
        .filter { it.asMap()["detour"]?.toString().isNullOrBlank() }
        .forEach { it.detourTo(TAG_DIRECT) }
    options.endpoints = options.endpoints.orEmpty() + generatedEndpoints.map { it as Endpoint }
    options.outbounds = options.outbounds.orEmpty().filterNot { it.isGeneratedEndpoint() }

    val configMap = options.asMap()
    mergeRootConfig(configMap, globalCustomConfig)
    mergeRootConfig(configMap, profileCustomConfig)
    return configMap
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    val localProxyCredentials: Map<Int, Pair<String, String>> = emptyMap(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

// Validate a hosts address token strictly enough for sing-box's netip-based
// parser: the app-wide isIpAddress() regex is looser (it allows IPv4 leading
// zeros and malformed IPv6 forms) and one bad value would fail the whole config
// load. Embedded-IPv4 IPv6 forms (::ffff:1.2.3.4) are not supported; write the
// plain IPv4 address instead. Returns the address, or null when not usable.
private fun parseHostsAddress(token: String): String? {
    val value = token.unwrapIPV6Host()
    if (!value.isIpAddress()) return null
    if (value.contains(':')) {
        // Pure-hex IPv6 (the regex rejects embedded IPv4 forms). Reject the empty
        // groups Go's netip parser refuses but the app regex tolerates: more than
        // one "::" (a ":::" run counts twice via overlap), a bare leading or
        // trailing ":", and more than 7 explicit groups alongside "::" (without
        // "::" the regex already enforces exactly 8).
        if (value.indexOf("::") != value.lastIndexOf("::")) return null
        if (value.startsWith(":") && !value.startsWith("::")) return null
        if (value.endsWith(":") && !value.endsWith("::")) return null
        if (value.contains("::") && value.split(':').count { it.isNotEmpty() } > 7) return null
    } else {
        // IPv4: reject leading zeros, which Go's netip parser refuses.
        if (value.split('.').any { it.length > 1 && it.startsWith('0') }) return null
    }
    return value
}

private fun parseHostsDomain(token: String): String? {
    var domain = sanitizeDnsEntry(token).removeSuffix(".")
    if (domain.isEmpty()) return null
    // Internationalized domains: convert to punycode, which is what arrives in
    // actual DNS queries and what sing-box matches against.
    if (domain.any { it.code >= 0x80 }) {
        domain = try {
            IDN.toASCII(domain)
        } catch (_: IllegalArgumentException) {
            return null
        }
    }
    domain = domain.lowercase()
    if (domain.isEmpty() || domain.isIpAddress() || domain.length > 253) return null
    val labels = domain.split('.')
    if (labels.any { it.isEmpty() || it.length > 63 }) return null
    // Underscore is permitted: it is common in DNS names (service labels) even
    // though it is invalid in strict hostnames.
    if (labels.any { label ->
            label.startsWith('-') ||
                label.endsWith('-') ||
                label.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }
        }
    ) {
        return null
    }
    return domain
}

// Token separator for hosts entries: ASCII whitespace plus NBSP, which pasted web
// content often contains and which neither Java's \s (ASCII-only) nor the
// ISO-control sanitization covers.
private val hostsSeparator = "[\\s\u00A0]+".toRegex()

// Parse the user DNS hosts rewrite list: one "domain ip [ip ...]" entry per line,
// separated by any whitespace. Blank lines, comments (#) and malformed lines are
// ignored instead of failing the config build. Non-IP tokens after the domain are
// dropped, and IPv6 addresses may be written with or without brackets.
internal fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val tokens = line.split(hostsSeparator)
            .map { sanitizeDnsEntry(it) }
            .filter { it.isNotEmpty() }
        if (tokens.size < 2 || tokens.first().startsWith("#")) return@forEach
        val domain = parseHostsDomain(tokens.first()) ?: return@forEach
        val addresses = tokens.drop(1)
            .takeWhile { !it.startsWith("#") }
            .mapNotNull { parseHostsAddress(it) }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
}

private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        return try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return fallback
}

fun buildConfig(proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false): ConfigBuildResult {
    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id,
                mapOf(tagProxy to listOf(proxy)),
                mapOf(proxy.id to tagProxy),
                -1L,
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    // Per-port credentials for authenticated external-plugin SOCKS loopbacks (#1166).
    val localProxyCredentials = HashMap<Int, Pair<String, String>>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_FRAGMENT, TAG_MIXED, TAG_PROXY)
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        if (bean is BalancerBean) {
            val beans = if (bean.type == BalancerBean.TYPE_LIST) {
                SagerDatabase.proxyDao.getEntities(bean.proxies)
            } else {
                SagerDatabase.proxyDao.getByGroup(bean.groupId)
                    .filter {
                        if (bean.nameFilter.isEmpty()) {
                            true
                        } else {
                            !Regex(bean.nameFilter).containsMatchIn(
                                it.requireBean().name,
                            )
                        }
                    }
                    .filter {
                        if (bean.nameFilter1.isEmpty()) {
                            true
                        } else {
                            Regex(bean.nameFilter1).containsMatchIn(
                                it.requireBean().name,
                            )
                        }
                    }
            }
            val beanList = ArrayList<ProxyEntity>()
            for (item in beans) {
                if (item.id == id) continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList
        }
        return mutableListOf(this)
    }

    fun readableTag(name_: String): String {
        var name = name_
        var count = 0
        while (!readableNames.add(name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    /** Members of a balancer, same filter rules as the main proxy urltest path. */
    fun balancerMembers(balancerBean: BalancerBean, selfId: Long): List<ProxyEntity> {
        val beans = if (balancerBean.type == BalancerBean.TYPE_LIST) {
            SagerDatabase.proxyDao.getEntities(balancerBean.proxies)
        } else {
            SagerDatabase.proxyDao.getByGroup(balancerBean.groupId)
                .filter {
                    if (balancerBean.nameFilter.isEmpty()) {
                        true
                    } else {
                        !Regex(balancerBean.nameFilter).containsMatchIn(it.requireBean().name)
                    }
                }
                .filter {
                    if (balancerBean.nameFilter1.isEmpty()) {
                        true
                    } else {
                        Regex(balancerBean.nameFilter1).containsMatchIn(it.requireBean().name)
                    }
                }
        }
        return beans.filter { it.id != selfId }
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) {
            mapOf()
        } else {
            SagerDatabase.proxyDao.getEntities(
                extraRules.mapNotNull { rule ->
                    rule.outbound.takeIf { it > 0 && it != proxy.id }
                }.toHashSet().toList(),
            ).associateBy { it.id }
        }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val perGroupResolver = HashMap<Long, String>()
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>()
    val hostResolvers = HashMap<String, MutableSet<String>>()
    val nonCustomFinalHosts = hashSetOf<String>()
    val groupCache = HashMap<Long, ProxyGroup?>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    // hev 模式下没有 tun inbound，设备流量从 loopback 的 mixed 入站进来，
    // 路由/DNS 规则里的入站匹配要跟着改，否则规则挂在根本不存在的入站上。
    val deviceInboundTag = if (isVPN && DataStore.enableHevTun) TAG_MIXED else "tun-in"
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val dnsHosts by lazy { parseDnsHosts(DataStore.dnsHosts) }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    // 测速配置必须与正式连接一致（对齐 husi）：沿用用户的 IPv6 模式。
    // 曾强制 ENABLE——测速拨号的协议族选择与真实路径不同，
    // v6 不通的网络里测速假 err（节点实际可用），反之假成功。
    val ipv6Mode = DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        if (!forTest) {
            experimental = ExperimentalOptions().apply {
                // cache-file 壞掉時 sing-box 會直接把整個啟動丟出來，使用者唯一能做的是「清除
                // 資料」——連同全部節點一起清掉。寫配置前先做個 16 字節魔數體檢：不是合法
                // SQLite 頭就改名留證（改名失敗才直刪），讓核心自己重建一個。
                // 刻意不去比對異常文字判損毀：關鍵字比對容易把別的故障誤判成這個。
                quarantineCorruptCacheDb()

                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    store_fakeip = true
                }

                // clash_api 常开：实时流量图（TrafficChartActivity）依赖 /traffic 与
                // /connections 端点；enableClashAPI 只控制 yacd 面板文件的下载。
                clash_api = ClashAPIOptions().apply {
                    external_controller = "127.0.0.1:9090"
                    if (DataStore.enableClashAPI) {
                        external_ui = "../files/yacd"
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN && !DataStore.enableHevTun) {
                inbounds.add(
                    Inbound_TunOptions().apply {
                        type = "tun"
                        tag = "tun-in"
                        interface_name = "tun0"
                        stack = when (DataStore.tunImplementation) {
                            TunImplementation.GVISOR -> "gvisor"
                            TunImplementation.SYSTEM -> "system"
                            else -> "mixed"
                        }
                        endpoint_independent_nat = true
                        mtu = DataStore.mtu
                        auto_route = true
                        strict_route = DataStore.strictRoute
                        when (ipv6Mode) {
                            IPv6Mode.DISABLE -> {
                                // 前綴以 VpnService 實際下發的 /30 為準（兩邊不一致時內核路由表說的是 VpnService 那個）
                                address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/30")
                            }

                            IPv6Mode.ONLY -> {
                                address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                            }

                            else -> {
                                address = listOf(
                                    VpnService.PRIVATE_VLAN4_CLIENT + "/30",
                                    VpnService.PRIVATE_VLAN6_CLIENT + "/126",
                                )
                            }
                        }
                    },
                )
            }
            inbounds.add(
                Inbound_MixedOptions().apply {
                    type = "mixed"
                    tag = TAG_MIXED
                    listen = bind
                    listen_port = DataStore.mixedPort
                    if (DataStore.mixedInboundNeedsAuth) {
                        users = listOf(
                            User().also { u ->
                                u.username = Key.MIXED_USERNAME
                                u.password = DataStore.mixedSecret
                            },
                        )
                    }
                },
            )
        }

        outbounds = mutableListOf()
        endpoints = mutableListOf()

        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()

            // 「停用 IPv6」的語意是不要用 IPv6，不是讓 IPv6 裸奔出實體網卡。VpnService 現在
            // 一律下發 v6 位址與路由（否則 v6 流量根本不進 VPN），所以改由這裡明確拒掉。
            // 必須是第一條：路由規則為首匹配，排後面會被別的規則先接走。
            if (ipv6Mode == IPv6Mode.DISABLE) {
                rules.add(
                    Rule_DefaultOptions().apply {
                        ip_version = 6
                        action = "reject"
                    },
                )
            }

            // sing-box 1.14 removed route.concurrent_dial; the feature moved to
            // default_network_strategy. "fallback" = Happy Eyeballs 并发容灾拨号
            // （对多个地址/接口并发发起，先完成者胜出）——等价旧 concurrent_dial。
            if (DataStore.concurrentDial) {
                default_network_strategy = "fallback"
            }

            // DNS 劫持：把 53 端口的查询显式交给 sing-box 的 DNS 模块。
            // 没有这条规则时，TUN 模式依赖 TUN 栈对网关地址的自动拦截，
            // hev 模式下发往 172.19.0.2:53 的 UDP 会被当成普通流量转出去
            // （目标是 VPN 内部地址，远程节点不可达）→ DNS 全灭。
            // 三种模式（gVisor/system/hev-mixed）都走这条确定性路径；
            // 用户设置的 DNS（如本机 AdGuardHome）照常生效。
            //
            // hev 模式必须把劫持目标收窄到 VPN DNS（172.19.0.2）本身：hev 的
            // socks5 转发保留了原始目的地址，本机解析器（AdGuardHome 等）的
            // UDP:53 上游查询会从 mixed 入站进来，广域 port-53 劫持会把它们也
            // 攥进 sing-box DNS → 127.0.0.1:5591 → 又回到本机解析器 → 无限
            // 迴圈；迴圈中 fake-ip 服务器抢答（dns.google → fc00::2）还会毒死
            // DoH 上游自举，外站域名随机超时（真机 tcpdump 实证）。收窄后：
            // 应用查询（dst 172.19.0.2:53）照旧劫持进用户 DNS；解析器上游
            // （dst 8.8.8.8:53 等）走正常出站，与其 TCP DoH 同路。
            if (isVPN) {
                rules.add(
                    Rule_DefaultOptions().apply {
                        inbound = listOf(deviceInboundTag)
                        port = listOf(53)
                        if (DataStore.enableHevTun) {
                            ip_cidr = listOf(VpnService.PRIVATE_VLAN4_ROUTER + "/32")
                        }
                        action = "hijack-dns"
                    },
                )
            }

            if (needSniff) {
                rules.add(
                    Rule_DefaultOptions().apply {
                        action = "sniff"
                    },
                )
            }
            val resolveStrategy = genDomainStrategy(DataStore.resolveDestination)
            if (resolveStrategy.isNotEmpty()) {
                rules.add(
                    Rule_DefaultOptions().apply {
                        action = "resolve"
                        strategy = resolveStrategy
                    },
                )
            }
        }

        // 前向引用：buildChain 与 buildDynamicGroup 互相递归——buildChain 遇到动态
        // 分组（最快/瀑布）时转交 buildDynamicGroup，而 buildDynamicGroup 又对静态候选
        // 回调 buildChain。Kotlin 局部函数仅在自身声明点之后可见，先声明的 buildChain
        // 无法直接引用后声明的 buildDynamicGroup，故用一个函数类型的 lateinit 引用桥接
        // 这条“先→后”的边；引用在 buildDynamicGroup 声明完毕、首次调用发生前赋值。
        lateinit var buildDynamicGroupImpl: (ProxyEntity, Boolean) -> String

        @Suppress("UNCHECKED_CAST")
        fun buildChain(chainId: Long, entity: ProxyEntity): String {
            // 动态分组（最快 / 瀑布）不做链路展平，改为构建 urltest 分组。
            // 静态候选仍交回 buildChain 构建，与 main 现有链路建置架构对齐。
            if (entity.type == ProxyEntity.TYPE_FASTEST ||
                entity.type == ProxyEntity.TYPE_WATERFALL
            ) {
                return buildDynamicGroupImpl(entity, false)
            }

            // Nested balancer (route / chain extra): same naming as main proxy group —
            // group tag = readable displayName, each member built alone so nodes keep displayName.
            // Main selected balancer still uses fixed TAG_PROXY (libcore binds on "proxy").
            if (entity.requireBean() is BalancerBean && chainId != 0L) {
                val balancerBean = entity.requireBean() as BalancerBean
                val members = balancerMembers(balancerBean, entity.id)
                val memberTags = ArrayList<String>()
                members.forEach { member ->
                    val tag = buildChain(member.id, member)
                    memberTags.add(tag)
                    tagMap[member.id] = tag
                    trafficMap[tag] = listOf(member)
                }
                val balancerTag = readableTag(balancerBean.displayName())
                outbounds.add(
                    Outbound_URLTestOptions().apply {
                        type = "urltest"
                        tag = balancerTag
                        outbounds = memberTags
                        url = balancerBean.probeUrl.ifEmpty { DataStore.connectionTestURL }
                        if (balancerBean.probeInterval > 0) {
                            _hack_config_map["interval"] = "${balancerBean.probeInterval}s"
                        }
                        tolerance = balancerBean.toleranceMs()
                    },
                )
                trafficMap[balancerTag] = listOf(entity)
                return balancerTag
            }
            val profileList = entity.resolveChain()
            // profileList 的顺序即应用流量经过各 outbound 的顺序：前一跳通过
            // detour 交给后一跳拨号，最后一项直接连接物理网络。
            Logs.d(
                "Outbound chain id=$chainId forTest=$forTest appToEgress=" +
                    profileList.joinToString(" -> ") { hop ->
                        val hopBean = hop.requireBean()
                        val host = serverHostOf(hopBean) ?: "<unknown>"
                        val endpoint = hopBean.displayAddress().takeIf { it.isNotBlank() }
                            ?: "$host:${hopBean.serverPort}"
                        "${hop.id}:${hop.displayType()}@$endpoint"
                    },
            )
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            // 節點域名也得跟著 IPv6 開關走：只讀使用者設定的 "auto" 時，選「僅 IPv6」會拿到
            // prefer_ipv4、選「停用 IPv6」也可能解出 v6 位址，鏈上第一跳就在 tun 裡不可達。
            val defaultServerDomainStrategy = when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> SingBoxOptionsUtil.domainStrategy("server")
            }

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                var tagOut = "$chainTag-${proxyEntity.id}"
                var needGlobal = false

                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()

                    if (!forTest) {
                        val ownerGid = entity.groupId
                        val ownerGroup = groupCache.getOrPut(ownerGid) {
                            SagerDatabase.groupDao.getById(ownerGid)
                        }
                        val resolver = ownerGroup
                            ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                            ?.subscription?.serverDnsResolver
                            ?.let { sanitizeDnsEntry(it) }
                            ?.takeIf { it.isNotBlank() }

                        if (resolver != null) {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    if (hop.groupId == ownerGid) {
                                        perGroupResolver[ownerGid] = resolver
                                        perGroupServerHosts.getOrPut(ownerGid) { mutableSetOf() }
                                            .add(host)
                                        hostResolvers.getOrPut(host) { mutableSetOf() }.add(resolver)
                                    } else {
                                        nonCustomFinalHosts.add(host)
                                    }
                                }
                            }
                        } else {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    nonCustomFinalHosts.add(host)
                                }
                            }
                        }
                    }
                }

                if (index == 0) {
                    tagOut = readableTag(bean.displayName())
                }

                // Resolve a globally shared hop before writing the edge that references it.
                // A hop built by an earlier rule may use a readable or endpoint tag instead of
                // this chain's proposed g-<id> tag; the global map is the source of truth.
                val resolvedTag = resolveChainHopTag(
                    proxyEntity.id,
                    tagOut,
                    needGlobal,
                    globalOutbounds,
                )
                tagOut = resolvedTag.tag

                if (index > 0) {
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(
                            Rule_DefaultOptions().apply {
                                inbound = listOf(pastInboundTag)
                                outbound = tagOut
                            },
                        )
                    } else {
                        pastOutbound.detourTo(tagOut)
                    }
                } else {
                    chainTagOut = tagOut
                }

                // The edge now points at the previously generated final tag, so the duplicate
                // object can be skipped without leaving a dangling detour.
                if (resolvedTag.reused) return@forEachIndexed

                if (proxyEntity.needExternal()) {
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                        if (!forExport) {
                            val user = "neko"
                            val pass = java.util.UUID.randomUUID().toString().replace("-", "")
                            localProxyCredentials[localPort] = user to pass
                            username = user
                            password = pass
                        }
                    }
                } else {
                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption

                        is ShadowTLSBean ->
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean ->
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is ShadowQUICBean ->
                            buildSingBoxOutboundShadowQUICBean(bean)

                        is TrustTunnelBean ->
                            buildSingBoxOutboundTrustTunnelBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean ->
                            buildSingBoxEndpointWireGuardBean(bean)

                        is AmneziaWGBean ->
                            buildSingBoxEndpointAmneziaWGBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        is ByeDPIBean ->
                            buildSingBoxOutboundByeDPIBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && DataStore.enableTLSFragment) {
                        val outboundMap = currentOutbound.asMap()
                        val tlsOptions = outboundMap["tls"] as? Map<*, *>
                        if (tlsOptions?.get("enabled") == true) {
                            currentOutbound._hack_config_map["detour"] = TAG_FRAGMENT
                        }
                    }
                }

                currentOutbound.apply {
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    pastEntity?.requireBean()?.apply {
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    // 测速配置必须与正式连接一致（对齐 husi）：沿用统一的服务器
                    // 域名解析策略。曾强制空——测速解析出的 IP/协议族与真实路径不同。
                    _hack_custom_config = bean.customOutboundJson
                    applyDomainStrategyIfSupported(defaultServerDomainStrategy)

                    _hack_config_map["tag"] = tagOut
                }

                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> {
                                if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            }
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception(
                                "You are using an unsupported $pluginId, please download the correct plugin.",
                            )
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(
                            Inbound_DirectOptions().apply {
                                type = "direct"
                                listen = LOCALHOST
                                listen_port = mappingPort
                                tag = "$chainTag-mapping-${proxyEntity.id}"

                                override_address = bean.serverAddress
                                override_port = bean.serverPort

                                pastInboundTag = tag

                                if (index == profileList.lastIndex) {
                                    if (DataStore.enableTLSFragment) {
                                        route.rules.add(
                                            Rule_DefaultOptions().apply {
                                                network = listOf("tcp")
                                                inbound = listOf(tag)
                                                outbound = TAG_FRAGMENT
                                            },
                                        )
                                    }

                                    route.rules.add(
                                        Rule_DefaultOptions().apply {
                                            inbound = listOf(tag)
                                            outbound = TAG_DIRECT
                                        },
                                    )
                                }
                            },
                        )
                    }
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // 构建动态分组（最快 / 瀑布）的 urltest outbound。
        // 由 sf 分支的 buildProfile 移植而来，并对齐 main 重构后的架构：
        // 静态候选改由 buildChain 构建（原 buildStaticChain 已在 main 合并进 buildChain）。
        fun buildDynamicGroup(entity: ProxyEntity, managedByParent: Boolean = false): String {
            val bean = entity.chainBean ?: error("Missing dynamic profile data")
            val candidates = if (entity.type == ProxyEntity.TYPE_FASTEST) {
                FastestCandidateResolver.resolve(bean)
            } else {
                if (bean.proxies.size != bean.proxies.distinct().size) {
                    error("Dynamic proxy profile contains duplicate candidates")
                }
                val profilesById = SagerDatabase.proxyDao.getEntities(bean.proxies).associateBy { it.id }
                bean.proxies.mapNotNull(profilesById::get)
            }
            if (candidates.isEmpty()) {
                error("Dynamic proxy profile has no available candidates")
            }

            val candidateTags = candidates.map { candidate ->
                when {
                    entity.type == ProxyEntity.TYPE_WATERFALL &&
                        candidate.type == ProxyEntity.TYPE_FASTEST ->
                        buildDynamicGroup(candidate, managedByParent = true)

                    candidate.type == ProxyEntity.TYPE_WATERFALL ||
                        candidate.type == ProxyEntity.TYPE_FASTEST ->
                        error("Unsupported nested dynamic proxy profile")

                    else -> buildChain(candidate.id, candidate)
                }
            }

            val groupTag = readableTag(entity.displayName())
            outbounds.add(
                Outbound_URLTestOptions().apply {
                    type = "urltest"
                    tag = groupTag
                    outbounds = candidateTags
                    url = DataStore.connectionTestURL
                    interval = "10m"
                    idle_timeout = "30m"
                    timeout = "${DataStore.connectionTestTimeout}ms"
                    tolerance = if (entity.type == ProxyEntity.TYPE_FASTEST) 50 else 0
                    strategy = if (entity.type == ProxyEntity.TYPE_WATERFALL) {
                        "priority"
                    } else {
                        "fastest"
                    }
                    managed_by_parent = managedByParent
                    wait_for_initial = true
                },
            )

            trafficMap[groupTag] = buildList {
                add(entity)
                candidateTags.forEach { tag ->
                    addAll(trafficMap[tag].orEmpty())
                }
            }.distinctBy { it.id }
            return groupTag
        }
        buildDynamicGroupImpl = ::buildDynamicGroup

        val useAutoSelect = !forTest && !forExport && DataStore.autoSelectLowestLatency
        val useLoadBalance = !forTest && !forExport && group?.let { DataStore.isGroupLoadBalance(it.id) } == true
        if (buildSelector || useAutoSelect || useLoadBalance) {
            val list = group?.id?.let { SagerDatabase.proxyDao.getByGroup(it) } ?: listOf(proxy)
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(
                0,
                if (useLoadBalance && tagMap.isNotEmpty()) {
                    buildLoadBalanceOutbound(tagMap.values.toList())
                } else if (useAutoSelect && tagMap.isNotEmpty()) {
                    buildUrlTestOutbound(tagMap.values.toList())
                } else {
                    buildSelectorOutbound(tagMap[proxy.id], tagMap.values.toList())
                },
            )
        } else if (proxy.requireBean() is BalancerBean) {
            val balancerBean = proxy.requireBean() as BalancerBean
            val beans = balancerMembers(balancerBean, proxy.id)
            beans.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(
                0,
                Outbound_URLTestOptions().apply {
                    type = "urltest"
                    tag = TAG_PROXY
                    outbounds = tagMap.values.toList()
                    url = balancerBean.probeUrl.ifEmpty { DataStore.connectionTestURL }
                    if (balancerBean.probeInterval > 0) {
                        _hack_config_map["interval"] = "${balancerBean.probeInterval}s"
                    }
                    tolerance = balancerBean.toleranceMs()
                },
            )
            trafficMap[TAG_PROXY] = listOf(proxy)
            // Also track child nodes individually for their own traffic
            beans.forEach { child ->
                val childTag = tagMap[child.id]
                if (childTag != null) {
                    trafficMap[childTag] = listOf(child)
                }
            }
        } else {
            val mainTag = buildChain(0, proxy)
            tagMap[proxy.id] = mainTag
        }

        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        val mainProxyTag = (if (buildSelector || useAutoSelect || useLoadBalance) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY

        if (!forTest && DataStore.globalMode) {
            if (DataStore.bypassLan) {
                route.rules.add(
                    Rule_DefaultOptions().apply {
                        ip_cidr = listOf(
                            "224.0.0.0/3",
                            "172.16.0.0/12",
                            "127.0.0.0/8",
                            "10.0.0.0/8",
                            "192.168.0.0/16",
                            "169.254.0.0/16",
                            "::1/128",
                            "fc00::/7",
                            "fe80::/10",
                        )
                        outbound = TAG_DIRECT
                    },
                )
            }

            route.rules.add(
                Rule_DefaultOptions().apply {
                    inbound = listOf(deviceInboundTag)
                    outbound = mainProxyTag
                },
            )

            route.rules.add(
                Rule_DefaultOptions().apply {
                    inbound = listOf(TAG_MIXED)
                    outbound = mainProxyTag
                },
            )

            route.final_ = mainProxyTag
        } else {
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.map {
                    if (!isVPN) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    PackageCache[it]?.takeIf { uid -> uid >= 1000 }
                }.toHashSet().filterNotNull()
                val ruleSets = mutableListOf<RuleSet>()

                val ruleObj = Rule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) {
                        PackageCache.awaitLoadSync()
                        user_id = uidList
                    }
                    var domainList: List<String>? = null
                    if (rule.domains.isNotBlank()) {
                        domainList = rule.domains.listByLineOrComma()
                        makeSingBoxRule(domainList, false)
                    }
                    if (rule.ip.isNotBlank()) {
                        makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                    }

                    if (rule_set != null) generateRuleSet(rule_set, ruleSets)

                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()

                    if (rule.ruleset.isNotBlank()) {
                        val rulesetUrls = rule.ruleset.listByLineOrComma()
                        rulesetUrls.forEach { origUrl ->
                            val (url, isIPRuleset) = processRulesetUrl(origUrl)

                            val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval)

                            rulesetTags.add(Pair(tag, isIPRuleset))

                            rule_set = (rule_set ?: mutableListOf()).apply {
                                add(tag)
                            }
                        }
                    }

                    if (rule.port.isNotBlank()) {
                        port = mutableListOf<Int>()
                        port_range = mutableListOf<String>()
                        rule.port.listByLineOrComma().map {
                            if (it.contains(":")) {
                                port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { port.add(this) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        source_port = mutableListOf<Int>()
                        source_port_range = mutableListOf<String>()
                        rule.sourcePort.listByLineOrComma().map {
                            if (it.contains(":")) {
                                source_port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { source_port.add(this) }
                            }
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        network = listOf(rule.network)
                    }
                    if (rule.source.isNotBlank()) {
                        source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        protocol = rule.protocol.listByLineOrComma()
                    }

                    fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                        return DNSRule_DefaultOptions().apply {
                            if (uidList.isNotEmpty()) user_id = uidList
                            domainList?.let { makeSingBoxRule(it) }

                            val nonIpRulesets = mutableListOf<String>()
                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        nonIpRulesets.add(tag)
                                    }
                                }
                            }
                            if (nonIpRulesets.isNotEmpty()) {
                                rule_set = nonIpRulesets
                            }
                        }
                    }

                    // 移植 hawkff #160 (0f4f63a5e)「Fix DNS routing criteria」的守衛概念，適配我方
                    // makeDnsRuleObj 結構：DNS 看不到後續連線的 port/network/protocol/source，
                    // custom JSON 也可能反轉或取代準則。若路由規則帶了這些「連線準則」，就
                    // 不為其 domain 生成 DNS 規則，否則該 domain 的解析會被過度放寬路由。
                    val dnsProbe = makeDnsRuleObj()
                    val hasDomainCriteria = !dnsProbe.checkEmpty()
                    val hasConnectionCriteria = rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
                        rule.network.isNotBlank() || rule.source.isNotBlank() ||
                        rule.protocol.isNotBlank() || rule.config.isNotBlank()
                    val isAppOnlyDns = uidList.isNotEmpty() &&
                        rule.domains.isBlank() && rule.ip.isBlank() && rule.ruleset.isBlank()
                    if ((hasDomainCriteria || isAppOnlyDns) && !hasConnectionCriteria &&
                        (rule.packages.isEmpty() || uidList.isNotEmpty())
                    ) {
                        when (rule.outbound) {
                            -1L -> {
                                userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                            }

                            -2L -> {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-block"
                                    disable_cache = true
                                }
                            }

                            else -> {
                                if (useFakeDns) {
                                    userDNSRuleList += makeDnsRuleObj().apply {
                                        server = "dns-fake"
                                        inbound = listOf(deviceInboundTag)
                                        query_type = listOf("A", "AAAA")
                                    }
                                } else {
                                    userDNSRuleList += makeDnsRuleObj().apply {
                                        server = "dns-remote"
                                    }
                                }
                            }
                        }
                    }
                    outbound = when (val outId = rule.outbound) {
                        0L -> mainProxyTag
                        -1L -> TAG_BYPASS
                        -2L -> TAG_BLOCK
                        else -> if (outId == proxy.id) mainProxyTag else tagMap[outId] ?: ""
                    }

                    _hack_custom_config = rule.config
                }

                if (!ruleObj.checkEmpty()) {
                    if (ruleObj.outbound.isNullOrBlank()) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.warning_nonexistent_outbound, rule.displayName()),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        if (ruleObj.outbound == TAG_BLOCK) {
                            ruleObj.outbound = null
                            ruleObj.action = "reject"
                        } else {
                            // Ported from miku/UwU a97f23892: if the rule's custom JSON config
                            // carries an "action" (e.g. sniff, resolve), the generated rule must
                            // not keep an "outbound" field at the same time.
                            var hasCustomAction = false
                            if (!rule.config.isNullOrBlank()) {
                                try {
                                    // Only parsed as a generic Map to check for the "action" key.
                                    @Suppress("UNCHECKED_CAST")
                                    val customMap = gson.fromJson(rule.config, Map::class.java) as? Map<String, Any>
                                    if (customMap?.containsKey("action") == true) {
                                        hasCustomAction = true
                                    }
                                } catch (e: Exception) {
                                    // JSON parse failed or shape mismatch: ignore.
                                }
                            }

                            if (hasCustomAction) {
                                ruleObj.outbound = null
                            }
                        }
                        route.rules.add(ruleObj)
                        route.rule_set.addAll(ruleSets)
                    }
                }
            }
        }

        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) {
            outbounds.add(
                Outbound().apply {
                    tag = freedom
                    type = "direct"
                    if (freedom == TAG_DIRECT) {
                        _hack_config_map["network_strategy"] = "default"
                    }
                },
            )
        }

        if (DataStore.enableTLSFragment) {
            val fragmentOutbound = Outbound().apply {
                tag = TAG_FRAGMENT
                type = "direct"
                _hack_config_map["fragment"] = Fragment().apply {
                    length = DataStore.fragmentLength
                    interval = DataStore.fragmentInterval
                }.asMap()
            }
            outbounds.add(fragmentOutbound)
        }

        fun isExclusiveCustomHost(host: String): Boolean {
            return hostResolvers[host]?.size == 1 && !nonCustomFinalHosts.contains(host)
        }

        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                if (!isExclusiveCustomHost(serverAddr)) {
                    domainListDNSDirectForce.add("full:$serverAddr")
                }
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(
            DNSServerOptions().apply {
                address = "rcode://success"
                tag = "dns-block"
            },
        )

        dns.servers.add(
            DNSServerOptions().apply {
                address = "local"
                tag = "dns-local"
                detour = TAG_DIRECT
            },
        )

        directDNS.firstOrNull().let {
            dns.servers.add(
                DNSServerOptions().apply {
                    address = it ?: throw Exception("No direct DNS, check your settings!")
                    tag = "dns-direct"
                    detour = TAG_DIRECT
                    address_resolver = "dns-local"
                    strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
                },
            )
        }

        remoteDns.firstOrNull().let {
            if (!forTest) {
                dns.servers.add(
                    DNSServerOptions().apply {
                        address = it ?: throw Exception("No remote DNS, check your settings!")
                        tag = "dns-remote"
                        address_resolver = "dns-direct"
                        strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
                    },
                )
            }
        }

        if (dnsHosts.isNotEmpty()) {
            dns.servers.add(
                DNSServerOptions().apply {
                    tag = TAG_DNS_HOSTS
                    _hack_config_map["type"] = "hosts"
                    _hack_config_map["predefined"] = dnsHosts
                },
            )
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            route.rules.add(
                0,
                Rule_DefaultOptions().apply {
                    protocol = listOf("dns")
                    action = "hijack-dns"
                },
            )
            route.rules.add(
                0,
                Rule_DefaultOptions().apply {
                    port = listOf(53)
                    action = "hijack-dns"
                },
            )
            if (DataStore.bypassLanInCore) {
                route.rules.add(
                    Rule_DefaultOptions().apply {
                        outbound = TAG_BYPASS
                        ip_is_private = true
                    },
                )
            }
            route.rules.add(
                Rule_DefaultOptions().apply {
                    ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                    source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                    action = "reject"
                },
            )
            if (useFakeDns) {
                dns.servers.add(
                    DNSServerOptions().apply {
                        _hack_config_map["type"] = "fakeip"
                        tag = "dns-fake"
                        _hack_config_map["inet4_range"] = "198.18.0.0/15"
                        if (ipv6Mode != IPv6Mode.DISABLE) {
                            _hack_config_map["inet6_range"] = "fc00::/18"
                        }
                    },
                )
                dns.rules.add(
                    DNSRule_DefaultOptions().apply {
                        inbound = listOf(deviceInboundTag)
                        server = "dns-fake"
                        disable_cache = true
                        // 停用 IPv6 時不發假 v6 位址、也不代答 AAAA：讓應用自己走 IPv4，
                        // 比給它一個必然被拒的 fake v6 更接近「沒有 IPv6」的實感。
                        query_type = if (ipv6Mode == IPv6Mode.DISABLE) listOf("A") else listOf("A", "AAAA")
                    },
                )
            }
            if (dnsHosts.isNotEmpty()) {
                dns.rules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        domain = dnsHosts.keys.map { it.lowercase() }
                        query_type = listOf("A", "AAAA")
                        server = TAG_DNS_HOSTS
                    },
                )
            }
            // 這一條只管「解析代理伺服器自己的域名」時用哪台：Outbound 欄位是在建立出口時塞進
            // context 的（box.go:384），App 發起的查詢不帶它。用 dns-direct＝節點域名在隧道外解析，
            // 改成 dns-remote 會變成「繞著自己的隧道去解析这台隧道要連的位址」＝啟動死循環隱患。
            dns.rules.add(
                0,
                DNSRule_DefaultOptions().apply {
                    outbound = mutableListOf("any")
                    server = "dns-direct"
                },
            )
            // 境內域名排他地交給「直連 DNS」（用戶自己設的那台，通常是本机 AdGuardHome：去廣告 +
            // 境內就近 CDN + ECS 全留）。sing-box 的 DNS 規則是「第一條命中即用」，這才是排他；
            // AdGuardHome 自己的 `#域名` 釘選不排他（v0.108.0-b.90 沒有 use_most_specific_servers，
            // 且 load_balance 是所有適用上游並行賽跑、誰先回用誰），所以這一刀只能切在 sing-box 這層。
            // 複用已在的 route 規則集，絕不新增第二份：sing-box 對重複 rule-set tag 是直接
            // 拒絕整份配置（route/router.go "duplicate rule-set tag"），訂閱那條 geosite:cn 路由
            // 規則早就聲明過這個 tag，再加一條就整機沒網。
            val cnRuleSetTag = "geosite:cn"
            val cnDeclared = route.rule_set?.any { it.tag == cnRuleSetTag } == true
            if (cnDeclared ||
                java.io.File(io.nekohasekai.sagernet.SagerNet.application.externalAssets, "geosite.db")
                    .exists()
            ) {
                if (!cnDeclared) {
                    val cnRuleSets = mutableListOf<RuleSet>()
                    generateRuleSet(listOf(cnRuleSetTag), cnRuleSets)
                    route.rule_set.addAll(cnRuleSets)
                }
                dns.rules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        rule_set = mutableListOf(cnRuleSetTag)
                        server = "dns-direct"
                    },
                )
                dns.rules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        domain_suffix = mutableListOf("cn")
                        server = "dns-direct"
                    },
                )
            }
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                        server = "dns-direct"
                    },
                )
            }
            perGroupResolver.forEach { (gid, resolver) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach

                val serverTag = "dns-sub-$gid"
                dns.servers.add(
                    DNSServerOptions().apply {
                        address = resolver
                        tag = serverTag
                        detour = TAG_DIRECT
                        if (!resolver.isIpAddress()) {
                            address_resolver = "dns-direct"
                        }
                        strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("server"))
                    },
                )
                dns.rules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(hosts)
                        server = serverTag
                    },
                )
            }
        }

        // Moving WireGuard from outbounds to endpoints removes the old implicit first-outbound
        // fallback. Keep the selected main tag explicit so endpoint-only profiles do not fall
        // through to a direct connection.
        route.ensureMainRouteFinal(mainProxyTag)

        // OwnBox 移植（5efd05bcd）：IPv6 關閉時在最前 reject AAAA 查詢。
        // genDomainStrategy 的 ipv4_only 只管出站/DNS 解析策略，hijack 或自訂
        // server 的 AAAA 響應仍會透傳給應用；直接拒絕讓應用立即走 v4。
        if (!forTest && ipv6Mode == IPv6Mode.DISABLE) {
            dns.rules.add(
                0,
                DNSRule_DefaultOptions().apply {
                    query_type = listOf("AAAA")
                    action = "reject"
                },
            )
        }
    }.let { options ->
        val configMap = finalizeRootConfig(
            options,
            globalCustomConfig = if (forTest) "" else DataStore.globalCustomConfig,
            profileCustomConfig = proxy.requireBean().customConfigJson,
        )
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L,
            localProxyCredentials,
        )
    }
}

/**
 * 啟動前體檢 sing-box 的 cache-file：損毀就隔離掉，讓核心重建。
 * 判據只看 SQLite 的 16 字節文件頭魔數，不依賴任何異常訊息。
 */
private fun quarantineCorruptCacheDb() {
    try {
        val db = java.io.File(SagerNet.application.cacheDir, "cache.db")
        if (!db.isFile) return
        val magic = ByteArray(16)
        if (db.length() >= 16) {
            java.io.RandomAccessFile(db, "r").use { it.readFully(magic) }
            if (String(magic, Charsets.ISO_8859_1) == "SQLite format 3\u0000") return
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        // 留一份樣本便於事後判斷是真損毀還是被截斷；留不下來就直接刪，別擋著啟動。
        if (!db.renameTo(java.io.File(db.parentFile, "cache.db.corrupt-$stamp"))) {
            db.delete()
        }
    } catch (ignored: Throwable) {
    }
}
