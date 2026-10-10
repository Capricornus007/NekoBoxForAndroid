package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.bg.NetworkAutomation
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2Json
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.snell.parseClashSnell
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.checkWireGuardLimits
import io.nekohasekai.sagernet.fmt.wireguard.dnsServerHost
import io.nekohasekai.sagernet.fmt.wireguard.normalizeReserved
import io.nekohasekai.sagernet.fmt.wireguard.orderedPeers
import io.nekohasekai.sagernet.fmt.wireguard.parseAllowedIPs
import io.nekohasekai.sagernet.fmt.wireguard.parseKeepalive
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardEndpoint
import io.nekohasekai.sagernet.fmt.wireguard.splitWireGuardDns
import io.nekohasekai.sagernet.fmt.wireguard.wireGuardPeerSection
import io.nekohasekai.sagernet.fmt.wireguard.wireGuardSettings
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.BackupFormatV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.nio.ByteBuffer

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    // Deletion circuit breaker: only guards groups of at least this many nodes, and trips as
    // soon as the fetched unique node count falls below this share of what is stored.
    private const val CIRCUIT_BREAK_MIN_PROFILES = 10
    private const val CIRCUIT_BREAK_MAX_DROP_RATIO = 0.7

    internal fun subscriptionFilterActive(filterMode: Int, filterRegex: String): Boolean =
        filterMode != SubscriptionFilterMode.DISABLED && filterRegex.isNotBlank()

    /**
     * The predicate a subscription name filter selects with, shared by the profile list below and
     * the keep/replace decisions. Matching ignores case - people type "hk" for a subscription that
     * labels its nodes "Hong Kong". A pattern the regex engine rejects matches everything, so one
     * malformed subscription cannot abort the update; [applySubscriptionFilter] logs it.
     */
    internal fun subscriptionMatcher(filterMode: Int, filterRegex: String): (String) -> Boolean {
        if (!subscriptionFilterActive(filterMode, filterRegex)) return { _: String -> true }
        val regex = try {
            filterRegex.trim().toRegex(RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            Logs.w("Invalid subscription filter regex '$filterRegex': ${e.readableMessage}")
            return { _: String -> true }
        }
        return when (filterMode) {
            SubscriptionFilterMode.INCLUDE -> { name: String -> regex.containsMatchIn(name) }
            SubscriptionFilterMode.EXCLUDE -> { name: String -> !regex.containsMatchIn(name) }
            else -> { _: String -> true }
        }
    }

    /**
     * Apply the subscription name filter. Matching ignores case and a pattern the regex engine
     * rejects is logged and skipped instead of aborting the whole update.
     */
    internal fun applySubscriptionFilter(
        proxies: List<AbstractBean>,
        filterMode: Int,
        filterRegex: String,
    ): List<AbstractBean> {
        val selected = subscriptionMatcher(filterMode, filterRegex)
        val filtered = proxies.filter { selected(it.displayName()) }
        if (subscriptionFilterActive(filterMode, filterRegex)) {
            Logs.d("After filter (mode=$filterMode): ${filtered.size}")
        }
        return filtered
    }

    /**
     * An include/exclude filter is the user asking for a smaller group, so its result must
     * never be read as a truncated subscription response.
     */
    internal fun deletionCircuitBreak(existsSize: Int, fetchedSize: Int, filterActive: Boolean): Boolean =
        !filterActive && existsSize >= CIRCUIT_BREAK_MIN_PROFILES &&
            fetchedSize < existsSize * CIRCUIT_BREAK_MAX_DROP_RATIO

    internal data class ReconciliationResult(
        val contentChanged: Boolean,
        val orderChanged: Boolean,
    )

    internal fun reconcileExistingProfile(
        entity: ProxyEntity,
        bean: AbstractBean,
        userOrder: Long,
    ): ReconciliationResult {
        val contentChanged = refreshBean(entity.requireBean(), bean)
        val orderChanged = entity.userOrder != userOrder
        if (contentChanged || orderChanged) {
            entity.putBean(bean)
        }
        if (orderChanged) {
            entity.userOrder = userOrder
        }

        return ReconciliationResult(contentChanged, orderChanged)
    }

    /**
     * Gives [bean], the provider's new version of [stored], what the user owns in [stored]: the
     * local settings and the custom outbound and config overrides. Returns whether anything else
     * changed, the remark included.
     */
    internal fun refreshBean(stored: AbstractBean, bean: AbstractBean): Boolean {
        bean.keepLocalSettings(stored)
        bean.customOutboundJson = stored.customOutboundJson
        bean.customConfigJson = stored.customConfigJson
        return stored != bean || stored.name != bean.name
    }

    /**
     * Pairs stored profiles with incoming nodes, keyed by the incoming display name. A matched
     * profile keeps its ID, traffic history, references and local overrides.
     *
     * First, a node whose connection settings are unchanged apart from the remark takes the one
     * stored profile with those settings; panels such as 3x-ui put quota and days left into
     * remarks. Identical settings on several stored or incoming entries are ambiguous and left to
     * the remark. Then the remaining nodes match by remark, one stored profile to one node of the
     * same protocol, and only when the endpoint or the rest of the settings is unchanged: a
     * credential rotation or a server move. A node that shares nothing but the remark is new.
     * Settings the user owns ([AbstractBean.keepLocalSettings]) never decide a match.
     */
    internal fun matchExistingProfiles(existing: List<ProxyEntity>, incoming: List<AbstractBean>): Map<String, ProxyEntity> = matchBeans(existing.map { it.requireBean() }, incoming).mapValues { existing[it.value] }

    /** [matchExistingProfiles] on the beans: incoming display names to indexes into [stored]. */
    internal fun matchBeans(stored: List<AbstractBean>, incoming: List<AbstractBean>): Map<String, Int> {
        // Copies of both sides take their local settings from one incoming bean of the class, so
        // only provider settings are compared. Stored beans are not modified.
        val references = incoming.associateBy { it.javaClass }
        fun key(bean: AbstractBean, withEndpoint: Boolean = true) = connectionKey(bean, references[bean.javaClass], withEndpoint)
        val matched = HashMap<String, Int>()
        val used = HashSet<Int>()
        val storedByKey = stored.indices.groupBy { key(stored[it]) }
        for ((key, beans) in incoming.groupBy { key(it) }) {
            val index = storedByKey[key]?.singleOrNull() ?: continue
            val bean = beans.singleOrNull() ?: continue
            matched[bean.displayName()] = index
            used += index
        }
        val storedByName = stored.indices.filterNot { it in used }.groupBy { stored[it].displayName() }
        for ((name, beans) in incoming.filterNot { it.displayName() in matched }.groupBy { it.displayName() }) {
            val bean = beans.singleOrNull() ?: continue
            val index = storedByName[name]?.singleOrNull() ?: continue
            val storedBean = stored[index]
            if (storedBean.javaClass != bean.javaClass || protocolKey(storedBean) != protocolKey(bean)) continue
            if (endpoint(storedBean) == endpoint(bean) || key(storedBean, withEndpoint = false) == key(bean, withEndpoint = false)) {
                matched[name] = index
            }
        }
        return matched
    }

    /**
     * The protocol a profile speaks, finer than its class: VMess and VLESS share one bean, as do
     * Hysteria 1 and 2, and a sing-box outbound is told apart by its type.
     */
    internal fun protocolKey(bean: AbstractBean): String = when {
        bean is ConfigBean -> {
            val type = if (bean.type == 1) runCatching { JSONObject(bean.config!!).optString("type") }.getOrDefault("") else ""
            "sing-box " + type.ifEmpty { "config" }
        }

        ProtocolRegistry.forBean(bean) == null -> bean.javaClass.name

        else -> ProxyEntity().putBean(bean).displayType()
    }

    /**
     * Connection settings without the remark and the local overrides, optionally without the
     * endpoint. User-owned settings come from [reference] so they compare equal on both sides.
     */
    private fun connectionKey(bean: AbstractBean, reference: AbstractBean?, withEndpoint: Boolean = true) = Protocols.Deduplication(
        bean.clone().apply {
            if (reference != null) keepLocalSettings(reference)
            customOutboundJson = ""
            customConfigJson = ""
            // Provider DNS can change with a remark without changing the WireGuard connection's identity.
            when (this) {
                is WireGuardBean -> {
                    importedDnsServers = ""
                    importedDnsDomains = ""
                }

                is AmneziaWGBean -> {
                    importedDnsServers = ""
                    importedDnsDomains = ""
                }
            }
            if (this is ConfigBean) {
                // sing-box outbounds carry the remark as their tag.
                config = runCatching {
                    JSONObject(config!!).apply {
                        remove("tag")
                        if (!withEndpoint) {
                            remove("server")
                            remove("server_port")
                        }
                    }.toString()
                }.getOrDefault(config)
            } else if (!withEndpoint) {
                serverAddress = ""
                serverPort = 0
                if (this is HysteriaBean) serverPorts = ""
            }
        },
    )

    private fun endpoint(bean: AbstractBean): String = if (bean is ConfigBean) {
        runCatching { JSONObject(bean.config!!).let { it.optString("server") + ":" + it.optString("server_port") } }.getOrDefault("")
    } else {
        bean.displayAddress()
    }

    /**
     * Profiles in use: the selected and the running profile, rule outbounds, chain hops, group
     * front and landing proxies, and the rules of stored routing profiles. An update keeps such a
     * profile when the provider drops it, so no selection or reference is left dangling.
     */
    internal fun referencedProfileIds(): Set<Long> {
        val ids = hashSetOf(DataStore.selectedProxy, DataStore.currentProfile)
        NetworkAutomation.rules().mapTo(ids) { it.profileId }
        SagerDatabase.rulesDao.allRules().mapTo(ids) { it.outbound }
        for (profile in RoutingProfiles.list()) {
            runCatching { BackupFormatV2.decodeRules(profile.content.getJSONArray("rules")) }.getOrNull()?.mapTo(ids) { it.outbound }
        }
        SagerDatabase.proxyDao.getIdsByType(ProxyEntity.TYPE_CHAIN).takeIf { it.isNotEmpty() }?.let { chains ->
            SagerDatabase.proxyDao.getEntities(chains).forEach { ids += it.chainBean?.proxies.orEmpty() }
        }
        SagerDatabase.groupDao.allGroups().forEach {
            ids += it.frontProxy
            ids += it.landingProxy
        }
        return ids.filterTo(HashSet()) { it > 0 }
    }

    internal fun requireUpdatableProfiles(profiles: List<ProxyEntity>) {
        require(profiles.all { it.canBuild() }) { app.getString(R.string.profile_unsupported) }
    }

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean,
    ) {
        val link = subscription.link!!
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.use { it.readTextBounded() }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
            updateFromContent(proxyGroup, subscription, contentText, userInterface, byUser, requestFormat = null)
            return
        }
        updateFromLink(proxyGroup, subscription, userInterface, byUser) { url, userAgent, timeoutMillis ->
            fetch(subscription, url, userAgent, timeoutMillis)
        }
    }

    /** Fetches the subscription's link, or its approved backups, through [fetch] and applies the response. */
    internal suspend fun updateFromLink(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean,
        reconfigureUpdater: suspend () -> Unit = { SubscriptionUpdater.reconfigureUpdater() },
        fetch: suspend (url: String, userAgent: String, timeoutMillis: Long?) -> FetchedResponse,
    ) {
        val link = subscription.link!!
        // A response that would turn stored profiles into another representation is negotiated
        // away when another format avoids it; otherwise updateFromContent asks first.
        val storedRepresentations = SagerDatabase.proxyDao.getByGroup(proxyGroup.id).mapTo(HashSet()) { it.requireBean() is ConfigBean }
        val customUserAgent = subscription.customUserAgent.orEmpty()
        val plan = SubscriptionFormat.plan(
            subscription.outputFormat ?: SubscriptionFormat.DEFAULT,
            subscription.negotiatedFormat ?: SubscriptionFormat.DEFAULT,
            updated = (subscription.lastUpdated ?: 0) > 0,
            custom = customUserAgent,
        )
        // The main link first, then the backups the user approved. A provider's proposed URL is
        // never fetched here; it waits for approval in the group settings.
        val download = negotiate(
            listOf(link) + SubscriptionRecovery.backupLinks(link, subscription.backupLinks.orEmpty()),
            plan,
            compatible = { proxies -> storedRepresentations.isEmpty() || proxies.all { (it is ConfigBean) in storedRepresentations } },
            fetch = fetch,
        )
        updateFromContent(
            proxyGroup,
            subscription,
            download.text,
            userInterface,
            byUser,
            download.title,
            download.userinfo,
            download.disposition,
            download.meta,
            servedByBackup = download.url != link,
            requestFormat = download.format.takeIf { customUserAgent.isBlank() },
            reconfigureUpdater = reconfigureUpdater,
        )
    }

    private const val FETCH_BUDGET_MILLIS = 90_000L
    private const val ATTEMPT_MILLIS = 20_000L
    private const val MIN_ATTEMPT_MILLIS = 3_000L

    /** A response body with its header lookup. */
    internal class FetchedResponse(val text: String, val header: (String) -> String)

    internal data class Download(
        val url: String,
        val format: Int,
        val text: String,
        val title: String,
        val userinfo: String,
        val disposition: String,
        val meta: Map<String, String>,
        val preview: ImportPreview,
        val compatible: Boolean,
    )

    /** A response that arrived but holds no profiles this app can import. */
    private class UnusableResponseException : IllegalStateException(app.getString(R.string.subscription_no_compatible_format))

    /**
     * Fetches [urls] in order until one yields importable profiles. Each URL gets the first
     * requests of [plan], and its fallback requests only when none of them yields profiles that
     * are [compatible] with the stored ones. Among the responses of a URL, compatible ones come
     * first, then [SubscriptionFormat.better]; ties keep the earlier request. A request that
     * fails quickly without an HTTP response ends its URL, since other formats would fail the same
     * way; one that ran out of time does not, as another format's response may be smaller.
     *
     * A lone request keeps the HTTP client's own limits, as single fetches always had. Several
     * requests share [budgetMillis], measured on a monotonic [clock]. Every URL still to come keeps
     * a share for one attempt. The request a URL is expected to answer, the only first one of
     * [plan], may use the rest, minus one attempt for the fallback formats when no URL is left;
     * comparisons and fallbacks get at most [ATTEMPT_MILLIS] each. Throws the first failure when
     * no URL yields profiles. The fetch blocks and cannot be interrupted, so cancellation is
     * checked before and after each one.
     */
    internal suspend fun negotiate(
        urls: List<String>,
        plan: SubscriptionFormat.Plan,
        compatible: (List<AbstractBean>) -> Boolean = { true },
        budgetMillis: Long = FETCH_BUDGET_MILLIS,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        fetch: suspend (url: String, userAgent: String, timeoutMillis: Long?) -> FetchedResponse,
    ): Download {
        val bounded = urls.size * plan.size > 1
        val deadline = clock() + budgetMillis
        val urlShare = minOf(ATTEMPT_MILLIS, budgetMillis / (urls.size + 1))
        var failure: Exception? = null
        for ((index, url) in urls.withIndex()) {
            val reserved = urlShare * (urls.size - 1 - index)
            var best: Download? = null
            var reachable = true
            for (requests in listOf(plan.first, plan.fallback)) {
                if (!reachable || best?.compatible == true) break
                val expected = requests === plan.first && requests.size == 1
                for (request in requests) {
                    currentCoroutineContext().ensureActive()
                    var timeoutMillis: Long? = null
                    if (bounded) {
                        val available = deadline - clock() - reserved
                        timeoutMillis = when {
                            !expected -> minOf(available, ATTEMPT_MILLIS)
                            reserved == 0L && plan.fallback.isNotEmpty() && available >= 2 * ATTEMPT_MILLIS -> available - ATTEMPT_MILLIS
                            else -> available
                        }
                        if (timeoutMillis < MIN_ATTEMPT_MILLIS) break
                    }
                    val started = clock()
                    val response = try {
                        fetch(url, request.userAgent, timeoutMillis)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w("Subscription fetch attempt failed")
                        if (failure == null) failure = e
                        val message = e.message.orEmpty()
                        // An HTTP status may differ for another format, and so may running out of time.
                        val timedOut = (timeoutMillis != null && clock() - started >= timeoutMillis) ||
                            message.contains("timeout", ignoreCase = true) || message.contains("deadline exceeded", ignoreCase = true)
                        if (message.contains("HTTP ") || timedOut) continue
                        reachable = false
                        break
                    }
                    // The update may have been cancelled while the fetch blocked.
                    currentCoroutineContext().ensureActive()
                    val parsed = try {
                        parseContent(readSubscriptionContent(response.text).body)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (parsed == null) {
                        if (failure == null) failure = UnusableResponseException()
                        continue
                    }
                    val candidate = Download(
                        url,
                        request.format,
                        response.text,
                        response.header("Profile-Title"),
                        response.header("Subscription-Userinfo"),
                        response.header("Content-Disposition"),
                        META_KEYS.associateWith { response.header(it).trim() }.filterValues { it.isNotEmpty() },
                        ImportPreview.of(parsed),
                        compatible(parsed.proxies),
                    )
                    val previous = best
                    if (previous == null || (candidate.compatible && !previous.compatible) ||
                        (candidate.compatible == previous.compatible && SubscriptionFormat.better(candidate.preview, previous.preview))
                    ) {
                        best = candidate
                    }
                }
            }
            if (best != null) return best
        }
        throw failure ?: UnusableResponseException()
    }

    /**
     * Fetches [url] through the local mixed inbound. Subscription traffic goes through the proxy,
     * so when it is down or broken an "update subscription" fails wholesale and the user reads it
     * as "this subscription is broken" rather than "the proxy is down". With the fallback switch
     * on, a proxied failure is retried once direct. Off by default: the subscription link carries
     * a token and going direct sends it out over the local network.
     */
    private fun fetch(subscription: SubscriptionBean, url: String, userAgent: String, timeoutMillis: Long?): FetchedResponse {
        return try {
            fetch(subscription, url, userAgent, timeoutMillis, useProxy = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!DataStore.subscriptionDirectFallback) throw e
            Logs.w("subscription via local proxy failed, retrying direct", e)
            fetch(subscription, url, userAgent, timeoutMillis, useProxy = false)
        }
    }

    private fun fetch(subscription: SubscriptionBean, url: String, userAgent: String, timeoutMillis: Long?, useProxy: Boolean): FetchedResponse {
        val client = Libcore.newHttpClient()
        try {
            timeoutMillis?.let { client.setTimeoutMs(it.toInt()) }
            if (useProxy) {
                client.trySocks5(DataStore.mixedPort, DataStore.mixedInboundUser, DataStore.mixedInboundPass)
                client.tryH3Direct()
            }
            if (DataStore.appTLSVersion == "1.3") client.restrictedTLS()
            val response = client.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) allowInsecure()
                setURL(url)
                setUserAgent(userAgent)
                // 用戶 2026-10-10 裁定：訂閱更新一律不帶裝置識別碼。
                // hawkff 那組 X-HWID／X-Device-OS／X-Ver-OS／X-Device-Model 是給 3x-ui、
                // Marzban、Remnawave 數裝置用的，等於把一台設備綁到機場的日誌上；
                // 我方早在任務 #82 就把 hwid 這條線拆掉，這裡不能再被合併帶回來。
            }.execute()
            val text = response.getContentStringLimited(MAX_IMPORT_BYTES)
            return FetchedResponse(text) { response.getHeader(it) }
        } finally {
            client.close()
        }
    }

    /** Provider metadata keys read from response headers and `#key: value` preamble lines. */
    internal val META_KEYS = listOf(
        "support-url",
        "profile-web-page-url",
        "announce",
        "profile-update-interval",
        "routing",
        "new-url",
        "new-domain",
        "fallback-url",
    )

    private fun httpUrl(value: String?): String = value?.trim()?.takeIf {
        it.startsWith("https://") || it.startsWith("http://")
    } ?: ""

    internal suspend fun updateFromContent(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        text: String,
        userInterface: GroupManager.Interface? = null,
        byUser: Boolean = false,
        httpTitle: String = "",
        httpUserinfo: String? = null,
        contentDisposition: String = "",
        httpMeta: Map<String, String> = emptyMap(),
        servedByBackup: Boolean = false,
        requestFormat: Int? = SubscriptionFormat.DEFAULT,
        reconfigureUpdater: suspend () -> Unit = { SubscriptionUpdater.reconfigureUpdater() },
    ) {
        val previousWarning = subscription.importWarning.orEmpty()
        val content = readSubscriptionContent(text)
        // Header values win over preamble lines, and a fetch that omits a key clears it.
        val meta = content.meta + httpMeta
        val updateIntervalMinutes = meta["profile-update-interval"]?.toIntOrNull()?.takeIf { it > 0 }
            ?.coerceAtMost(MAX_UPDATE_INTERVAL_HOURS)?.times(60)
        val parsed = parseContent(content.body) ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        var proxies = parsed.proxies
        val preview = ImportPreview.of(parsed)
        // Entries that could not be imported may be the nodes some stored profiles came from.
        val partial = preview.unparsedCount > 0

        // A provider routing profile arrives as a header, a preamble line or a body line. It is
        // translated now so a rejected one is reported, and stored after the profiles commit.
        val routingValue = meta["routing"]
            ?: content.body.lineSequence().map { it.trim() }.firstOrNull { RoutingProfiles.isRoutingLink(it) }
        val routing = routingValue?.let {
            runCatching { RoutingProfiles.parse(it, RoutingProfiles.subscriptionSource(proxyGroup.id)) }.getOrNull()
        }
        val routingNote = routingValue?.let { value ->
            when {
                routing != null -> routing.notes.takeIf { it.isNotEmpty() }?.let {
                    app.getString(R.string.routing_profile_not_applied, routing.name, it.joinToString(", "))
                }

                RoutingProfiles.isDisableRequest(value) -> app.getString(R.string.subscription_routing_off_ignored)

                else -> app.getString(R.string.subscription_routing_rejected)
            }
        }

        // Replacement and backup URLs a provider proposes wait for the user's approval.
        val link = subscription.link.orEmpty()
        val backups = SubscriptionRecovery.backupLinks(link, subscription.backupLinks.orEmpty())
        val offeredLink = SubscriptionRecovery.proposedLink(link, meta["new-url"], meta["new-domain"])
            ?.takeIf { it !in backups }.orEmpty()
        val offeredBackupLink = SubscriptionRecovery.offer(link, meta["fallback-url"])
            ?.takeIf { it !in backups && it != offeredLink }.orEmpty()

        // Nonblank HTTP metadata wins. Titles fall back to Content-Disposition, then
        // the body preamble. Missing usage clears on HTTP success, stays unchanged for files.
        val remoteName = decodeProfileTitle(httpTitle)
            ?: runCatching { Util.decodeFilename(contentDisposition).trim().takeIf { it.isNotEmpty() } }.getOrNull()
            ?: content.title
        val userinfo = httpUserinfo?.trim()?.takeIf { it.isNotEmpty() }
            ?: content.userinfo ?: httpUserinfo ?: subscription.subscriptionUserinfo

        ensureUniqueNames(proxies)

        if (subscription.forceResolve!!) forceResolve(proxies, proxyGroup.id)

        val filterMode = subscription.filterMode ?: SubscriptionFilterMode.DISABLED
        val filterRegex = subscription.filterRegex ?: ""
        val filterActive = subscriptionFilterActive(filterMode, filterRegex)
        val selected: (String) -> Boolean = subscriptionMatcher(filterMode, filterRegex)
        if (filterActive) {
            proxies = applySubscriptionFilter(proxies, filterMode, filterRegex)
            check(proxies.isNotEmpty()) { app.getString(R.string.subscription_filter_empty) }
        }

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        requireUpdatableProfiles(exists)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication!!) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            val indexOf = HashMap<Protocols.Deduplication, Int>()
            val uniqueNames = HashMap<Protocols.Deduplication, String>()
            for (_proxy in proxies) {
                val proxy = Protocols.Deduplication(_proxy)
                if (!uniqueProxies.add(proxy)) {
                    // O(1) lookup of the first-seen insertion index instead of O(n)
                    // LinkedHashSet.indexOf (which made dedup O(n^2) over the subscription).
                    // The map stores `uniqueProxies.size - 1` at first insertion, which equals
                    // the old indexOf result, so duplicate labels are byte-identical.
                    val index = indexOf.getValue(proxy)
                    if (uniqueNames.containsKey(proxy)) {
                        val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                        if (name.isNotBlank()) {
                            duplicate.add("$name ($index)")
                            uniqueNames[proxy] = ""
                        }
                    }
                    duplicate.add(_proxy.displayName() + " ($index)")
                } else {
                    indexOf[proxy] = uniqueProxies.size - 1
                    uniqueNames[proxy] = _proxy.displayName()
                }
            }
            uniqueProxies.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.toList().map { it.bean }
        }

        check(proxies.isNotEmpty()) { app.getString(R.string.no_proxies_found_in_subscription) }
        Logs.d("New profiles: ${proxies.size}")

        val nameMap = proxies.associateBy { bean ->
            bean.displayName()
        }

        Logs.d("Unique profiles: ${nameMap.size}")

        val toReplace = matchExistingProfiles(exists, proxies)
        val matchedIds = toReplace.values.mapTo(HashSet()) { it.id }
        val unmatched = exists.filterNot { it.id in matchedIds }
        val referenced = if (unmatched.isEmpty()) emptySet() else referencedProfileIds()

        // Native profiles and raw sing-box outbounds never match each other, and another format's
        // parser may describe the same nodes with other settings, so stored profiles whose nodes
        // arrive in another representation, or from another request than the stored profiles came
        // from, would be replaced by new profiles without their history and overrides. That happens
        // only when the user agrees to it; otherwise every stored profile stays as it is. Profiles in
        // use stay either way, and those the user's filter leaves out go as the filter decides.
        val requestChanged = SubscriptionFormat.requestChanged(
            subscription.negotiatedFormat ?: SubscriptionFormat.DEFAULT,
            requestFormat ?: SubscriptionFormat.UNKNOWN,
        )
        val insertedRaw = proxies.filterNot { it.displayName() in toReplace }.mapTo(HashSet()) { it is ConfigBean }
        // Older imports stored an extra peer as its own profile; consolidation must not erase its history silently.
        val consolidatedPeers = proxies.flatMap { bean ->
            val settings = bean.wireGuardSettings() ?: return@flatMap emptyList()
            if (settings.extraPeers.isBlank()) return@flatMap emptyList()
            settings.orderedPeers().mapIndexedNotNull { index, peer ->
                if (index == settings.serverPeerPosition) {
                    null
                } else {
                    peer.server?.let { server ->
                        Triple(bean.javaClass, peer.public_key, server.unwrapIPV6Host() to peer.server_port)
                    }
                }
            }
        }.toSet()
        val consolidatedIds = unmatched.filter { stored ->
            val bean = stored.requireBean()
            val settings = bean.wireGuardSettings() ?: return@filter false
            settings.allowedIPs.isBlank() && settings.extraPeers.isBlank() &&
                Triple(bean.javaClass, settings.publicKey, settings.server.unwrapIPV6Host() to settings.port) in consolidatedPeers
        }.mapTo(HashSet()) { it.id }
        val converted = unmatched.filter { stored ->
            stored.id !in referenced && selected(stored.displayName()) &&
                (requestChanged || stored.id in consolidatedIds || insertedRaw.any { it != (stored.requireBean() is ConfigBean) })
        }
        val peerLayoutChanged = converted.any { it.id in consolidatedIds }
        if (converted.isNotEmpty()) {
            val representation = app.getString(
                if (true in insertedRaw) R.string.subscription_representation_raw else R.string.subscription_representation_native,
            )
            val question = when {
                requestChanged -> app.getString(R.string.subscription_request_changed_question, converted.size)
                peerLayoutChanged -> app.getString(R.string.subscription_peer_layout_question, converted.size)
                else -> app.getString(R.string.subscription_representation_question, converted.size, representation)
            }
            if (!byUser || userInterface?.confirm(question) != true) {
                val notice = when {
                    requestChanged -> app.getString(R.string.subscription_request_changed_kept, converted.size)
                    peerLayoutChanged -> app.getString(R.string.subscription_peer_layout_kept, converted.size)
                    else -> app.getString(R.string.subscription_representation_kept, converted.size, representation)
                }
                currentCoroutineContext().ensureActive()
                if (notice != previousWarning) {
                    val noted = KryoConverters.deserialize(SubscriptionBean(), KryoConverters.serialize(subscription))
                    noted.importWarning = notice
                    SagerDatabase.groupDao.updateGroup(proxyGroup.copy(subscription = noted))
                    subscription.importWarning = notice
                }
                error(notice)
            }
        }
        val convertedIds = converted.mapTo(HashSet()) { it.id }

        // A profile the provider no longer lists stays while something uses it, and while the
        // response has entries that did not import and may have been its node, unless the user
        // agreed to replace it. Kept profiles follow the listed ones in their previous order.
        // Circuit breaker (kept from our fork): when an endpoint that used to serve a full list
        // suddenly returns a heavily truncated one (partial page, server hiccup, geo-blocked
        // response), the profiles it dropped were not really retired - deleting them would wipe a
        // working group. Past the minimum size and drop ratio we keep every unmatched profile
        // (except those the user agreed to convert) and only add/update what came back. The count
        // check below stays consistent because a kept profile still lives in the database.
        val circuitBreak = deletionCircuitBreak(exists.size, nameMap.size, filterActive)
        if (circuitBreak) {
            Logs.w(
                "Subscription diff circuit breaker tripped: exists=${exists.size}, " +
                    "fetched=${nameMap.size}, skipping deletion of ${unmatched.count { it.id !in convertedIds }} profiles",
            )
        }
        val kept = unmatched.filter {
            it.id in referenced || (partial && it.id !in convertedIds && selected(it.displayName())) ||
                (circuitBreak && it.id !in convertedIds)
        }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val toDelete = unmatched.filterNot { it.id in keptIds }
        val keptReferenced = kept.count { it.id in referenced }

        val failedEntries = preview.failed.groupingBy { it ?: app.getString(R.string.subscription_import_unknown_entry) }
            .eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }
        val importWarning = listOfNotNull(
            app.getString(R.string.subscription_import_failed, preview.acceptedCount, preview.unparsedCount, failedEntries)
                .takeIf { partial },
            app.getString(R.string.subscription_import_dropped, preview.dropped.joinToString(", ") { app.getString(it.title) })
                .takeIf { preview.behaviorDiffers },
            app.getString(R.string.subscription_kept_referenced, keptReferenced).takeIf { keptReferenced > 0 },
            app.getString(R.string.subscription_kept_partial, kept.size - keptReferenced).takeIf { kept.size > keptReferenced },
            app.getString(R.string.subscription_served_by_backup).takeIf { servedByBackup },
            routingNote,
        ).joinToString("\n")
        // Sections the app never imports are listed in the settings only; anything that may have
        // cost a node, kept a stale one or skipped provider data is shown right away.
        val alertUser = partial || kept.isNotEmpty() || servedByBackup || routingNote != null

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        val toUpdate = ArrayList<ProxyEntity>()
        val toInsert = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
                val oldName = entity.displayName()
                val reconciliation = reconcileExistingProfile(entity, bean, userOrder)
                if (reconciliation.contentChanged || reconciliation.orderChanged) {
                    toUpdate.add(entity)
                }
                if (reconciliation.contentChanged) {
                    changed++
                    updated[oldName] = name
                    Logs.d("Updated profile")
                }
                if (reconciliation.orderChanged) {
                    Logs.d("Reordered profile")
                }
                if (!reconciliation.contentChanged && !reconciliation.orderChanged) {
                    Logs.d("Ignored profile")
                }
            } else {
                changed++
                // Accumulate for a single batch insert after the loop (one transaction)
                // instead of one insert per row. userOrder is set here, same as before, so
                // the persisted ordering is unchanged.
                toInsert.add(
                    ProxyEntity(
                        groupId = proxyGroup.id,
                        userOrder = userOrder,
                    ).apply {
                        putBean(bean)
                    },
                )
                added.add(name)
                Logs.d("Inserted profile")
            }
            userOrder++
        }
        for (entity in kept) {
            if (entity.userOrder != userOrder) {
                entity.userOrder = userOrder
                toUpdate.add(entity)
            }
            userOrder++
        }

        val updatedSubscription = KryoConverters.deserialize(SubscriptionBean(), KryoConverters.serialize(subscription)).apply {
            subscriptionUserinfo = userinfo
            this.importWarning = importWarning
            this.offeredLink = offeredLink
            this.offeredBackupLink = offeredBackupLink
            // The request the stored profiles now come from; files and custom User-Agents are no format's.
            negotiatedFormat = requestFormat ?: SubscriptionFormat.UNKNOWN
            lastUpdated = (System.currentTimeMillis() / 1000).toInt()
            supportUrl = httpUrl(meta["support-url"])
            webPageUrl = httpUrl(meta["profile-web-page-url"])
            announce = meta["announce"]?.let { decodeProfileTitle(it) }?.take(MAX_ANNOUNCE_CHARS) ?: ""
            // A provider interval applies when it changes; between changes the user's settings win.
            if (updateIntervalMinutes != null && updateIntervalMinutes != providerUpdateInterval) {
                autoUpdate = true
                autoUpdateDelay = updateIntervalMinutes
            }
            providerUpdateInterval = updateIntervalMinutes ?: 0
        }
        // The worker also runs for expiry reminders, so an expiry appearing or vanishing reschedules.
        val scheduleChanged = updatedSubscription.autoUpdate != subscription.autoUpdate ||
            updatedSubscription.autoUpdateDelay != subscription.autoUpdateDelay ||
            (updatedSubscription.expiry() != null) != (subscription.expiry() != null)
        val updatedGroup = proxyGroup.copy(subscription = updatedSubscription)
        if (updatedGroup.name?.startsWith("Subscription #") == true && remoteName != null) {
            updatedGroup.name = remoteName
        }

        var updatedCount = 0
        var deletedCount = 0
        // Nothing is stored for an update that was cancelled, for example while it asked the user.
        currentCoroutineContext().ensureActive()
        SagerDatabase.instance.runInTransaction {
            if (toInsert.isNotEmpty()) {
                SagerDatabase.proxyDao.insert(toInsert)
            }
            if (toUpdate.isNotEmpty()) {
                updatedCount = SagerDatabase.proxyDao.updateProxy(toUpdate)
            }
            if (toDelete.isNotEmpty()) {
                deletedCount = SagerDatabase.proxyDao.deleteProxy(toDelete)
            }

            val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()
            if (existCount != proxies.size + kept.size) {
                val message = "Exist profiles: $existCount, new profiles: ${proxies.size}, kept profiles: ${kept.size}"
                Logs.e(message)
                error(message)
            }

            SagerDatabase.groupDao.updateGroup(updatedGroup)
        }
        // Publish metadata only after the profile transaction commits.
        proxyGroup.name = updatedGroup.name
        subscription.subscriptionUserinfo = updatedSubscription.subscriptionUserinfo
        subscription.lastUpdated = updatedSubscription.lastUpdated
        subscription.importWarning = updatedSubscription.importWarning
        subscription.offeredLink = updatedSubscription.offeredLink
        subscription.offeredBackupLink = updatedSubscription.offeredBackupLink
        subscription.negotiatedFormat = updatedSubscription.negotiatedFormat
        subscription.supportUrl = updatedSubscription.supportUrl
        subscription.webPageUrl = updatedSubscription.webPageUrl
        subscription.announce = updatedSubscription.announce
        subscription.autoUpdate = updatedSubscription.autoUpdate
        subscription.autoUpdateDelay = updatedSubscription.autoUpdateDelay
        subscription.providerUpdateInterval = updatedSubscription.providerUpdateInterval
        if (scheduleChanged) reconfigureUpdater()
        // A provider-supplied routing profile is stored, or refreshed when this subscription
        // delivered it before; it never replaces the user's own profiles and is never activated.
        routing?.let { profile -> runCatching { RoutingProfiles.store(profile) }.onFailure { Logs.w(it) } }

        Logs.d("Inserted profiles: ${toInsert.size}")
        Logs.d("Updated profiles: $updatedCount")
        Logs.d("Deleted profiles: $deletedCount")
        finishUpdate(proxyGroup)

        userInterface?.onUpdateSuccess(
            proxyGroup,
            changed,
            added,
            updated,
            deleted,
            duplicate,
            byUser,
        )
        // The update is complete before the warning shows; an unchanged one stays in the settings.
        if (byUser && alertUser && importWarning != previousWarning) userInterface?.alert(importWarning)
    }

    internal fun ensureUniqueNames(profiles: List<AbstractBean>) {
        val used = HashSet<String>()
        val suffixes = HashMap<String, Pair<Int, String>>()
        for (profile in profiles) {
            val original = profile.displayName()
            if (used.add(original)) continue
            var (index, name) = suffixes[original] ?: (0 to original)
            do {
                index++
                name = name.replace(" (${index - 1})", "") + " ($index)"
            } while (!used.add(name))
            profile.name = name
            suffixes[original] = index to name
        }
    }

    internal data class SubscriptionContent(
        val body: String,
        val title: String?,
        val userinfo: String?,
        val meta: Map<String, String> = emptyMap(),
    )

    private const val MAX_ANNOUNCE_CHARS = 200
    private const val MAX_UPDATE_INTERVAL_HOURS = 24 * 365

    private fun decodeSubscriptionBase64(value: String): String? = try {
        val encoded = value.filterNot { it.isWhitespace() }
        if (encoded.isEmpty() || !encoded.matches("[A-Za-z0-9+/_-]+={0,2}".toRegex())) {
            null
        } else {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Util.b64Decode(encoded))).toString()
        }
    } catch (_: Exception) {
        null
    }

    internal fun decodeProfileTitle(value: String): String? {
        val title = value.trim().let {
            if (it.startsWith("base64:", ignoreCase = true)) {
                decodeSubscriptionBase64(it.substringAfter(':')) ?: return null
            } else {
                it
            }
        }.trim()
        return title.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }
    }

    private fun readPreamble(text: String): SubscriptionContent {
        val lines = text.removePrefix("\uFEFF").lines()
        var title: String? = null
        var userinfo: String? = null
        val meta = mutableMapOf<String, String>()
        val preamble = lines.takeWhile { it.isBlank() || it.trimStart().startsWith('#') }
        for (line in preamble) {
            val header = line.trim().removePrefix("#").trim()
            if (!header.contains(':')) continue
            val value = header.substringAfter(':').trim()
            when (val key = header.substringBefore(':').trim().lowercase()) {
                "profile-title" -> if (title == null) title = decodeProfileTitle(value)
                "subscription-userinfo" -> if (userinfo == null) userinfo = value.takeIf { it.isNotEmpty() }
                in META_KEYS -> if (value.isNotEmpty()) meta.putIfAbsent(key, value)
            }
        }
        return SubscriptionContent(lines.drop(preamble.size).joinToString("\n"), title, userinfo, meta)
    }

    internal fun readSubscriptionContent(text: String): SubscriptionContent {
        if (text.length > MAX_IMPORT_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            throw ImportTooLargeException(MAX_IMPORT_BYTES)
        }
        val outer = readPreamble(text)
        val decoded = decodeSubscriptionBase64(outer.body.linesNoComments().joinToString("\n"))
            ?: return outer
        val inner = readPreamble(decoded)
        // Decode one envelope only. Outer metadata takes precedence over encoded metadata.
        return inner.copy(
            title = outer.title ?: inner.title,
            userinfo = outer.userinfo ?: inner.userinfo,
            meta = inner.meta + outer.meta,
        )
    }

    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? = parseRawContent(readSubscriptionContent(text).body, fileName)

    /** [parseRaw] with what the parse could not use; null when the source holds no importable node. */
    suspend fun parseImport(text: String, fileName: String = ""): ParsedContent? = parseContent(readSubscriptionContent(text).body, fileName)

    internal suspend fun parseContent(body: String, fileName: String = ""): ParsedContent? {
        val report = ImportReport()
        val proxies = parseRawContent(body, fileName, report)?.takeIf { it.isNotEmpty() } ?: return null
        return ParsedContent(proxies, report)
    }

    private val CLASH_SECTIONS = mapOf(
        "proxy-groups" to ImportPreview.Section.PROXY_GROUPS,
        "rules" to ImportPreview.Section.RULES,
        "rule-providers" to ImportPreview.Section.RULE_PROVIDERS,
        "dns" to ImportPreview.Section.DNS,
    )
    private val SING_BOX_SECTIONS = mapOf(
        "route" to ImportPreview.Section.RULES,
        "dns" to ImportPreview.Section.DNS,
        "inbounds" to ImportPreview.Section.INBOUNDS,
    )

    // A Clash/Mihomo document in YAML, or in JSON with a quoted key.
    private val CLASH_JSON_PROXIES = Regex("\"proxies\"\\s*:")

    @Suppress("UNCHECKED_CAST")
    private suspend fun parseRawContent(text: String, fileName: String = "", report: ImportReport = ImportReport()): List<AbstractBean>? {
        val proxies = mutableListOf<AbstractBean>()

        if (text.contains("proxies:") || CLASH_JSON_PROXIES.containsMatchIn(text)) {
            // clash & meta

            try {
                // Parse untrusted subscription YAML safely.
                // SafeConstructor only produces standard types (Map/List/String/
                // Number/Boolean/null) and rejects custom/global tags such as
                // !!javax..., preventing arbitrary class instantiation
                // (CVE-2022-1471 style gadget chains). A code-point limit bounds
                // input size to mitigate decompression/billion-laughs style DoS.
                // Note: load() is used instead of loadAs(..., Map::class.java)
                // because SafeConstructor cannot construct an explicit root type
                // tag; a YAML mapping is returned as a LinkedHashMap natively.
                val loaderOptions = LoaderOptions().apply {
                    codePointLimit = 10 * 1024 * 1024 // 10 MiB
                    // SnakeYAML 2.x defaults maxAliasesForCollections to 50 as a
                    // billion-laughs guard. Legitimate large Clash/Mihomo configs
                    // reuse anchors heavily and exceed 50 (issue #1042). Raise to a
                    // finite cap (not Int.MAX_VALUE). 200 covers known real-world
                    // configs while keeping alias-expansion amplification bounded
                    // (codePointLimit bounds input size, not the expanded object graph).
                    maxAliasesForCollections = 200
                }
                // In SnakeYAML 2.x, Yaml(BaseConstructor) adopts the constructor's
                // LoaderOptions (getLoadingConfig()), so codePointLimit set above is
                // enforced during parsing (verified: input over the limit throws).
                val yaml = Yaml(SafeConstructor(loaderOptions)).load<Any?>(text) as? Map<String, Any?>
                    ?: error(app.getString(R.string.no_proxies_found_in_file))
                for ((key, section) in CLASH_SECTIONS) if (yaml.containsKey(key)) report.dropped += section

                val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

                for (rawProxy in (
                    yaml["proxies"] as? List<*> ?: error(
                        app.getString(R.string.no_proxies_found_in_file),
                    )
                    )) {
                    // Note: YAML numbers parsed as "Long"

                    // Per-entry resilience: a single malformed node (type-confused field,
                    // missing required key, or a non-mapping list item) must be skipped, not
                    // abort the whole subscription. The list is iterated as List<*> and each
                    // item is cast inside the per-entry guard; the required `type` is resolved
                    // safely; any remaining unchecked cast inside a branch is contained by the
                    // per-proxy try/catch, so the bad node is skipped and the rest still import.
                    val proxy = rawProxy as? Map<String, Any?>
                    val type = proxy?.get("type")?.toString()
                    val imported = proxies.size
                    try {
                        if (proxy == null || type == null) error("Malformed Clash node")
                        checkClashNode(type, proxy)
                        when (type) {
                            "socks5" -> {
                                // SOCKS here has no TLS; without it the credentials would travel in the clear.
                                if (clashBoolean(proxy["tls"] ?: false)) error("Unsupported SOCKS over TLS")
                                proxies.add(
                                    SOCKSBean().apply {
                                        serverAddress = proxy["server"] as String
                                        serverPort = proxy["port"].toString().toInt()
                                        username = proxy["username"]?.toString()
                                        password = proxy["password"]?.toString()
                                        name = proxy["name"]?.toString()
                                    },
                                )
                            }

                            "http" -> {
                                proxies.add(
                                    HttpBean().apply {
                                        require((proxy["headers"] as? Map<*, *>).isNullOrEmpty()) { "Unsupported HTTP proxy headers" }
                                        serverAddress = proxy["server"] as String
                                        serverPort = proxy["port"].toString().toInt()
                                        username = proxy["username"]?.toString()
                                        password = proxy["password"]?.toString()
                                        setTLS(clashBoolean(proxy["tls"] ?: false))
                                        sni = proxy["sni"]?.toString()
                                        name = proxy["name"]?.toString()
                                        allowInsecure = clashBoolean(proxy["skip-cert-verify"] ?: false)
                                    },
                                )
                            }

                            "ss" -> {
                                val ssPlugin = mutableListOf<String>()
                                if (!proxy["plugin"]?.toString().isNullOrEmpty()) {
                                    val opts = proxy["plugin-opts"] as Map<String, Any?>
                                    require(opts["fingerprint"]?.toString().isNullOrBlank()) { "Unsupported certificate pin" }
                                    when (proxy["plugin"]) {
                                        "obfs" -> {
                                            ssPlugin.apply {
                                                add("obfs-local")
                                                add("obfs=" + (opts["mode"]?.toString() ?: ""))
                                                add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                                            }
                                        }

                                        "v2ray-plugin" -> {
                                            ssPlugin.apply {
                                                add("v2ray-plugin")
                                                add("mode=" + (opts["mode"]?.toString() ?: ""))
                                                if (clashBoolean(opts["tls"] ?: false)) add("tls")
                                                add("host=" + (opts["host"]?.toString() ?: ""))
                                                add("path=" + (opts["path"]?.toString() ?: ""))
                                                if (clashBoolean(opts["mux"] ?: false)) add("mux=8")
                                            }
                                        }

                                        // shadow-tls, restls and the rest would be dropped, leaving a node that is not the provider's.
                                        else -> error("Unsupported Shadowsocks plugin")
                                    }
                                }
                                val mux = (proxy["smux"] as? Map<*, *>)?.let { parseClashMux(it) }
                                proxies.add(
                                    ShadowsocksBean().apply {
                                        serverAddress = proxy["server"] as String
                                        serverPort = proxy["port"].toString().toInt()
                                        password = proxy["password"]?.toString()
                                        method = clashCipher(proxy["cipher"] as String)
                                        plugin = ssPlugin.joinToString(";")
                                        name = proxy["name"]?.toString()
                                        mux?.let {
                                            enableMux = true
                                            muxType = it.protocol
                                            muxPadding = it.padding
                                            muxMode = if (it.limitsConnections) 1 else 0
                                            it.maxStreams?.let { value -> muxConcurrency = value }
                                            it.maxConnections?.let { value -> muxMaxConnections = value }
                                            it.minStreams?.let { value -> muxMinStreams = value }
                                            muxBrutal = it.brutalUpMbps != null
                                            it.brutalUpMbps?.let { value -> muxBrutalUpMbps = value }
                                            it.brutalDownMbps?.let { value -> muxBrutalDownMbps = value }
                                        }
                                    },
                                )
                            }

                            "ssr" -> {
                                proxies.add(
                                    ShadowsocksRBean().apply {
                                        for (opt in proxy) {
                                            if (opt.value == null) continue
                                            when (opt.key) {
                                                "name" -> name = opt.value.toString()
                                                "server" -> serverAddress = opt.value as String
                                                "port" -> serverPort = opt.value.toString().toInt()
                                                "cipher" -> method = clashCipher(opt.value as String)
                                                "password" -> password = opt.value.toString()
                                                "obfs" -> obfs = opt.value as String
                                                "protocol" -> protocol = opt.value as String
                                                "obfs-param" -> obfsParam = opt.value.toString()
                                                "protocol-param" -> protocolParam = opt.value.toString()
                                            }
                                        }
                                    },
                                )
                            }

                            "vmess", "vless", "trojan" -> {
                                val bean = when (type) {
                                    "vmess" -> VMessBean()

                                    "vless" -> VMessBean().apply {
                                        alterId = -1 // make it VLESS
                                        packetEncoding = 2 // clash meta default XUDP
                                    }

                                    "trojan" -> TrojanBean().apply {
                                        security = "tls"
                                    }

                                    else -> error("impossible")
                                }

                                bean.serverAddress = proxy["server"]?.toString() ?: error("Missing server")
                                bean.serverPort = proxy["port"]?.toString()?.toIntOrNull() ?: error("Missing port")
                                bean.type = when (val network = proxy["network"]?.toString()) {
                                    null, "", "tcp" -> "tcp"

                                    "h2", "http" -> "http"

                                    "ws", "grpc", "httpupgrade", "xhttp" -> network

                                    "splithttp" -> "xhttp"

                                    // Built as plain TCP, an unknown transport would not be the provider's node.
                                    else -> error("Unsupported subscription transport")
                                }

                                for (opt in proxy) {
                                    when (opt.key) {
                                        "name" -> bean.name = opt.value?.toString()

                                        "password" -> if (bean is TrojanBean) {
                                            bean.password =
                                                opt.value?.toString()
                                        }

                                        "uuid" -> if (bean is VMessBean) {
                                            bean.uuid =
                                                opt.value?.toString()
                                        }

                                        "alterId" -> if (bean is VMessBean && !bean.isVLESS) {
                                            bean.alterId =
                                                opt.value?.toString()?.toIntOrNull()
                                        }

                                        "cipher" -> if (bean is VMessBean && !bean.isVLESS) {
                                            bean.encryption =
                                                (opt.value as? String)
                                        }

                                        "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                            (opt.value as? String)?.let {
                                                if (it.contains("xtls-rprx-vision")) {
                                                    bean.encryption = "xtls-rprx-vision"
                                                }
                                            }
                                        }

                                        "encryption" -> if (bean is VMessBean && bean.isVLESS) {
                                            bean.vlessEncryption = opt.value?.toString() ?: ""
                                        }

                                        "packet-encoding" -> if (bean is VMessBean) {
                                            bean.packetEncoding = when ((opt.value as? String)) {
                                                "packetaddr" -> 1
                                                "xudp" -> 2
                                                else -> 0
                                            }
                                        }

                                        "tls" -> if (bean is VMessBean) {
                                            bean.security = if (clashBoolean(opt.value)) "tls" else ""
                                        }

                                        "servername", "sni" -> bean.sni = opt.value?.toString()

                                        "alpn" ->
                                            bean.alpn =
                                                (opt.value as? List<Any>)?.joinToString("\n")

                                        "skip-cert-verify" -> bean.allowInsecure = clashBoolean(opt.value)

                                        "client-fingerprint" ->
                                            bean.utlsFingerprint =
                                                opt.value as String

                                        "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (realityOpt in it) {
                                                bean.security = "tls"

                                                when (realityOpt.key) {
                                                    "public-key" ->
                                                        bean.realityPubKey =
                                                            realityOpt.value?.toString()

                                                    "short-id" ->
                                                        bean.realityShortId =
                                                            realityOpt.value?.toString()

                                                    // Dropping the post-quantum key exchange would weaken the handshake.
                                                    "support-x25519mlkem768" -> if (clashBoolean(realityOpt.value)) error("Unsupported REALITY key exchange")

                                                    else -> error("Unsupported REALITY option")
                                                }
                                            }
                                        }

                                        "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (wsOpt in it) {
                                                when (wsOpt.key) {
                                                    "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                                        when (key.toString().lowercase()) {
                                                            "host" -> {
                                                                bean.host = value?.toString()
                                                            }

                                                            // Only Host has a field; another header would be sent without.
                                                            else -> error("Unsupported WebSocket header")
                                                        }
                                                    }

                                                    "path" -> {
                                                        bean.path = wsOpt.value?.toString()
                                                    }

                                                    "max-early-data" -> {
                                                        bean.wsMaxEarlyData =
                                                            wsOpt.value?.toString()?.toIntOrNull()
                                                    }

                                                    "early-data-header-name" -> {
                                                        bean.earlyDataHeaderName =
                                                            wsOpt.value?.toString()
                                                    }

                                                    "v2ray-http-upgrade" -> {
                                                        if (clashBoolean(wsOpt.value)) {
                                                            bean.type = "httpupgrade"
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (h2Opt in it) {
                                                when (h2Opt.key) {
                                                    "host" ->
                                                        bean.host =
                                                            (h2Opt.value as? List<Any>)?.joinToString("\n")

                                                    "path" -> bean.path = h2Opt.value?.toString()
                                                }
                                            }
                                        }

                                        "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (httpOpt in it) {
                                                when (httpOpt.key) {
                                                    "path" ->
                                                        bean.path =
                                                            (httpOpt.value as? List<Any>)?.joinToString("\n")

                                                    "headers" -> {
                                                        (httpOpt.value as? Map<Any, List<Any>>)?.forEach { (key, value) ->
                                                            when (key.toString().lowercase()) {
                                                                "host" -> {
                                                                    bean.host = value.joinToString("\n")
                                                                }

                                                                else -> error("Unsupported HTTP header")
                                                            }
                                                        }
                                                    }

                                                    "method" -> require(httpOpt.value?.toString().equals("GET", ignoreCase = true)) {
                                                        "Unsupported HTTP method"
                                                    }
                                                }
                                            }
                                        }

                                        "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (grpcOpt in it) {
                                                when (grpcOpt.key) {
                                                    "grpc-service-name" ->
                                                        bean.path =
                                                            grpcOpt.value?.toString()
                                                }
                                            }
                                        }

                                        "xhttp-opts" -> if (bean.type == "xhttp") {
                                            (opt.value as? Map<String, Any?>)?.also { parseClashXhttpOptions(bean, it) }
                                        }

                                        "smux" -> (opt.value as? Map<*, *>)?.let { parseClashMux(it) }?.let {
                                            bean.enableMux = true
                                            bean.muxType = it.protocol
                                            bean.muxPadding = it.padding
                                            bean.muxMode = if (it.limitsConnections) 1 else 0
                                            it.maxStreams?.let { value -> bean.muxConcurrency = value }
                                            it.maxConnections?.let { value -> bean.muxMaxConnections = value }
                                            it.minStreams?.let { value -> bean.muxMinStreams = value }
                                            bean.muxBrutal = it.brutalUpMbps != null
                                            it.brutalUpMbps?.let { value -> bean.muxBrutalUpMbps = value }
                                            it.brutalDownMbps?.let { value -> bean.muxBrutalDownMbps = value }
                                        }

                                        "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (echOpt in it) {
                                                when (echOpt.key) {
                                                    "enable" -> bean.enableECH = clashBoolean(echOpt.value)

                                                    "config" ->
                                                        bean.echConfig =
                                                            echOpt.value?.toString()

                                                    else -> error("Unsupported ECH option")
                                                }
                                            }
                                        }
                                    }
                                }
                                proxies.add(bean)
                            }

                            "anytls" -> {
                                val bean = AnyTLSBean()
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()

                                        "server" -> bean.serverAddress = opt.value as String

                                        "port" -> bean.serverPort = opt.value.toString().toInt()

                                        "password" -> bean.password = opt.value.toString()

                                        "client-fingerprint" ->
                                            bean.utlsFingerprint =
                                                opt.value as String

                                        "sni" -> bean.sni = opt.value.toString()

                                        "skip-cert-verify" -> bean.allowInsecure = clashBoolean(opt.value)

                                        "alpn" -> {
                                            val alpn = (opt.value as? (List<String>))
                                            bean.alpn = alpn?.joinToString("\n")
                                        }

                                        "reality-pub-key", "public-key" ->
                                            bean.realityPubKey =
                                                opt.value.toString()

                                        "reality-short-id", "short-id" ->
                                            bean.realityShortId =
                                                opt.value.toString()
                                    }
                                }
                                proxies.add(bean)
                            }

                            "wireguard" -> {
                                // Mihomo semantics: in a peers list every peer needs server, port,
                                // public-key and allowed-ips, and the top-level reserved and
                                // persistent-keepalive apply to each peer. A node without a list has
                                // one peer, which carries all traffic unless allowed-ips narrows it.
                                require(proxy.clashOption("amnezia-wg-option") == null) { "unsupported AmneziaWG options" }
                                val keepalive = proxy.clashOption("persistent-keepalive")?.let {
                                    parseKeepalive(it.toString()) ?: error("invalid persistent-keepalive")
                                } ?: 0
                                val defaultReserved = proxy.clashOption("reserved")?.let { clashReserved(it) }
                                val peers = (proxy.clashOption("peers")?.let { it as? List<*> ?: error("invalid peers") }).orEmpty()
                                    .mapIndexed { index, peer ->
                                        clashWireGuardPeer(peer as? Map<*, *> ?: error("invalid peer"), index + 1, defaultReserved)
                                    }

                                val bean = WireGuardBean().apply {
                                    name = proxy["name"].toString()
                                    privateKey = proxy.clashOption("private-key")?.toString()
                                    proxy.clashOption("mtu")?.let { mtu = it.toString().toIntOrNull() ?: 0 }
                                    localAddress = listOfNotNull(
                                        proxy.clashOption("ip")?.toString()?.let { if (it.contains("/")) it else "$it/32" },
                                        proxy.clashOption("ipv6")?.toString()?.let { if (it.contains("/")) it else "$it/128" },
                                    ).joinToString("\n")
                                    proxy.clashOption("dns")?.let { dns ->
                                        // Mihomo reads every entry as a name server.
                                        val servers = clashList(dns)
                                        require(servers.all { dnsServerHost(it) != null }) { "unsupported DNS server" }
                                        importedDnsServers = servers.joinToString("\n")
                                    }
                                    persistentKeepalive = keepalive
                                    val serverPeer = peers.firstOrNull()
                                    if (serverPeer == null) {
                                        serverAddress = proxy.clashOption("server")?.toString()
                                        proxy.clashOption("port")?.let { serverPort = it.toString().toIntOrNull() ?: 0 }
                                        peerPublicKey = proxy.clashOption("public-key")?.toString()
                                        peerPreSharedKey = (proxy.clashOption("pre-shared-key") ?: proxy.clashOption("preshared-key"))?.toString()
                                        reserved = defaultReserved
                                        proxy.clashOption("allowed-ips")?.let {
                                            allowedIPs = parseAllowedIPs(clashList(it))?.takeIf { prefixes -> prefixes.isNotEmpty() }
                                                ?.joinToString("\n") ?: error("invalid allowed-ips")
                                        }
                                    } else {
                                        serverAddress = serverPeer.server
                                        serverPort = serverPeer.port
                                        peerPublicKey = serverPeer.publicKey
                                        peerPreSharedKey = serverPeer.preSharedKey
                                        reserved = serverPeer.reserved
                                        allowedIPs = serverPeer.allowedIPs.joinToString("\n")
                                        extraPeers = peers.drop(1).joinToString("\n") {
                                            wireGuardPeerSection(
                                                it.publicKey,
                                                it.preSharedKey,
                                                it.server to it.port,
                                                it.allowedIPs,
                                                keepalive,
                                                it.reserved?.let { bytes -> normalizeReserved(bytes) },
                                            )
                                        }
                                    }
                                    initializeDefaultValues()
                                    checkWireGuardLimits()
                                }
                                proxies.add(bean)
                            }

                            "hysteria" -> {
                                val bean = HysteriaBean()
                                bean.protocolVersion = 1
                                var hopPorts = ""
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()

                                        "server" -> bean.serverAddress = opt.value as String

                                        "port" -> bean.serverPorts = opt.value.toString()

                                        "ports" -> hopPorts = opt.value.toString()

                                        "obfs" -> bean.obfuscation = opt.value.toString()

                                        "auth-str" -> {
                                            bean.authPayloadType = HysteriaBean.TYPE_STRING
                                            bean.authPayload = opt.value.toString()
                                        }

                                        "sni" -> bean.sni = opt.value.toString()

                                        "skip-cert-verify" -> bean.allowInsecure = clashBoolean(opt.value)

                                        "up" ->
                                            bean.uploadMbps =
                                                opt.value.toString().substringBefore(" ").toIntOrNull()
                                                    ?: 100

                                        "down" ->
                                            bean.downloadMbps =
                                                opt.value.toString().substringBefore(" ").toIntOrNull()
                                                    ?: 100

                                        "recv-window-conn" ->
                                            bean.connectionReceiveWindow =
                                                opt.value.toString().toIntOrNull() ?: 0

                                        "recv-window" ->
                                            bean.streamReceiveWindow =
                                                opt.value.toString().toIntOrNull() ?: 0

                                        "disable-mtu-discovery" -> bean.disableMtuDiscovery = clashBoolean(opt.value)

                                        "alpn" -> {
                                            val alpn = (opt.value as? (List<String>))
                                            bean.alpn = alpn?.joinToString("\n") ?: "h3"
                                        }
                                    }
                                }
                                if (hopPorts.isNotBlank()) {
                                    bean.serverPorts = hopPorts
                                }
                                proxies.add(bean)
                            }

                            "hysteria2" -> {
                                val bean = HysteriaBean()
                                bean.protocolVersion = 2
                                var hopPorts = ""
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()

                                        "server" -> bean.serverAddress = opt.value as String

                                        "port" -> bean.serverPorts = opt.value.toString()

                                        "ports" -> hopPorts = opt.value.toString()

                                        "obfs-password" -> bean.obfuscation = opt.value.toString()

                                        "password" -> bean.authPayload = opt.value.toString()

                                        "sni" -> bean.sni = opt.value.toString()

                                        "skip-cert-verify" -> bean.allowInsecure = clashBoolean(opt.value)

                                        "up" ->
                                            bean.uploadMbps =
                                                opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                                        "down" ->
                                            bean.downloadMbps =
                                                opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0
                                    }
                                }
                                if (hopPorts.isNotBlank()) {
                                    bean.serverPorts = hopPorts
                                }
                                proxies.add(bean)
                            }

                            "tuic" -> {
                                val bean = TuicBean()
                                var ip = ""
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()

                                        "server" -> bean.serverAddress = opt.value.toString()

                                        "ip" -> ip = opt.value.toString()

                                        "port" -> bean.serverPort = opt.value.toString().toInt()

                                        "token" -> {
                                            bean.protocolVersion = 4
                                            bean.token = opt.value.toString()
                                        }

                                        "uuid" -> bean.uuid = opt.value.toString()

                                        "password" -> bean.token = opt.value.toString()

                                        "skip-cert-verify" -> bean.allowInsecure = clashBoolean(opt.value)

                                        "disable-sni" -> bean.disableSNI = clashBoolean(opt.value)

                                        "reduce-rtt" -> bean.reduceRTT = clashBoolean(opt.value)

                                        "sni" -> bean.sni = opt.value.toString()

                                        "alpn" -> {
                                            val alpn = (opt.value as? (List<String>))
                                            bean.alpn = alpn?.joinToString("\n")
                                        }

                                        "congestion-controller" ->
                                            bean.congestionController =
                                                opt.value.toString()

                                        "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()
                                    }
                                }
                                if (ip.isNotBlank()) {
                                    bean.serverAddress = ip
                                    if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress!!.isIpAddress()) {
                                        bean.sni = bean.serverAddress
                                    }
                                }
                                proxies.add(bean)
                            }

                            "snell" -> {
                                val bean = parseClashSnell(proxy)
                                proxies.add(bean)
                            }
                        }
                    } catch (e: Exception) {
                        // Malformed node (e.g. a type-confused field): skip it and keep the
                        // rest of the subscription instead of failing the whole update.
                        Logs.w("Skipping malformed Clash node")
                    }
                    // Unknown types, refused options and malformed nodes are all reported by type.
                    if (proxies.size == imported) report.failed += type
                }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI
                        if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host!!.isIpAddress()) {
                            it.sni = it.host
                        }
                        // 2. globalClientFingerprint
                        if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                            it.utlsFingerprint = globalClientFingerprint
                            if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
                        }
                    }
                }
                return proxies.takeIf { it.isNotEmpty() }
            } catch (_: YAMLException) {
                Logs.w("Subscription YAML rejected")
                return null
            } catch (_: Exception) {
                Logs.w("Malformed subscription YAML")
                // Entries after the failure were not read; the result may lack some nodes.
                if (proxies.isNotEmpty()) report.failed += null
                return proxies.takeIf { it.isNotEmpty() }
            }
        } else if (text.contains("[Interface]", ignoreCase = true)) {
            // A WireGuard config that cannot be imported as written fails with the reason.
            proxies.addAll(
                parseWireGuardConf(text).onEach {
                    if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                },
            )
            return proxies
        }

        if (text.trimStart().startsWith('{') || text.trimStart().startsWith('[')) {
            return try {
                val tokener = JSONTokener(text)
                val json = tokener.nextValue()
                if (tokener.nextClean() != '\u0000') return null
                parseJSON(json, report).takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                Logs.w("Subscription JSON rejected")
                null
            }
        }

        try {
            return parseProxies(text, report).takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (ignored: Exception) {
        }

        return null
    }

    private fun Map<*, *>.clashOption(key: String) = entries.firstOrNull { it.key.toString().replace("_", "-") == key }?.value

    private fun clashList(value: Any?): List<String> {
        val items = when (value) {
            null -> emptyList()
            is List<*> -> value.mapNotNull { it?.toString() }
            else -> listOf(value.toString())
        }
        // A line break inside one item would read as a second item once stored line by line.
        require(items.none { item -> item.any { it.isISOControl() } }) { "invalid list value" }
        return items
    }

    private fun clashReserved(value: Any): String {
        val reserved = when (value) {
            is List<*> -> if (value.size == 1) {
                value[0].toString().replace("[\\[\\] ]".toRegex(), "")
            } else {
                value.joinToString("\n") { it.toString() }
            }

            else -> value.toString().replace("[\\[\\] ]".toRegex(), "")
        }
        require(normalizeReserved(reserved) != null) { "invalid reserved" }
        return reserved
    }

    private class ClashWireGuardPeer(
        val server: String,
        val port: Int,
        val publicKey: String,
        val preSharedKey: String?,
        val allowedIPs: List<String>,
        val reserved: String?,
    )

    private fun clashWireGuardPeer(peer: Map<*, *>, number: Int, defaultReserved: String?): ClashWireGuardPeer {
        val allowedIPs = parseAllowedIPs(clashList(peer.clashOption("allowed-ips"))) ?: error("Peer $number has an invalid allowed-ips entry")
        if (allowedIPs.isEmpty()) error("Peer $number has no allowed-ips")
        return ClashWireGuardPeer(
            peer.clashOption("server")?.toString()?.takeIf { it.isNotBlank() } ?: error("Peer $number has no server"),
            peer.clashOption("port")?.toString()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Peer $number has no port"),
            peer.clashOption("public-key")?.toString()?.takeIf { it.isNotBlank() } ?: error("Peer $number has no public-key"),
            (peer.clashOption("pre-shared-key") ?: peer.clashOption("preshared-key"))?.toString(),
            allowedIPs,
            peer.clashOption("reserved")?.let { clashReserved(it) } ?: defaultReserved,
        )
    }

    fun clashCipher(cipher: String): String = when (cipher) {
        "dummy" -> "none"
        else -> cipher
    }

    /**
     * A WireGuard `.conf`, or an AmneziaWG one when the `[Interface]` section carries obfuscation
     * keys, as one profile: the first peer with an endpoint is its server, the other peers keep
     * their sections, and every peer keeps its place in the order. With a single peer, a
     * `# comment` line directly above `[Peer]` names the profile, as in 3x-ui's AmneziaWG exports.
     */
    fun parseWireGuardConf(conf: String): List<AbstractBean> {
        val ini = IniConfig.parse(conf)
        val iface = ini["Interface"] ?: error("Missing 'Interface' section")
        val parsed = WireGuardConf(iface, ini.getAll("Peer") ?: error("Missing 'Peer' sections"))
        val bean = if (isAmneziaWGConf(iface)) parseAmneziaWG(iface, parsed) else parseWireGuard(parsed)
        val remark = conf.lines().zipWithNext().singleOrNull { (_, section) -> section.trim().equals("[Peer]", ignoreCase = true) }
            ?.first?.takeIf { it.trimStart().startsWith("#") }?.trimStart('#', ' ')?.trim()
        if (!remark.isNullOrEmpty()) bean.name = remark
        bean.checkWireGuardLimits()
        return listOf(bean)
    }

    private class WireGuardConf(iface: IniConfig.Section, peers: List<IniConfig.Section>) {
        val localAddress = iface.getAll("Address")?.flatMap { it.split(",") }?.joinToString("\n")
            ?: error("Empty address in 'Interface' section")
        val privateKey = iface["PrivateKey"]
        val mtu = iface["MTU"]?.toIntOrNull()
        val dns = splitWireGuardDns(iface.getAll("DNS").orEmpty())

        val server: String
        val port: Int
        val publicKey: String
        val preSharedKey: String?
        val allowedIPs: String
        val keepalive: Int
        val extraPeers: String
        val serverPeerPosition: Int

        init {
            // wg-quick routes nothing to a peer without AllowedIPs. A full tunnel in its place would
            // send traffic to a peer the config never routed there, so such a config is rejected.
            val parsed = peers.mapIndexed { index, peer ->
                val number = index + 1
                val endpoint = peer["Endpoint"]?.takeIf { it.isNotBlank() }?.let {
                    parseWireGuardEndpoint(it) ?: error("Peer $number has an invalid Endpoint")
                }
                val allowed = parseAllowedIPs(peer.getAll("AllowedIPs").orEmpty())
                    ?: error("Peer $number has an invalid AllowedIPs entry")
                if (allowed.isEmpty()) error("Peer $number has no AllowedIPs")
                WireGuardConfPeer(
                    peer["PublicKey"]?.takeIf { it.isNotBlank() } ?: error("Peer $number has no PublicKey"),
                    peer["PresharedKey"],
                    endpoint,
                    allowed,
                    peer["PersistentKeepalive"]?.let { parseKeepalive(it) ?: error("Peer $number has an invalid PersistentKeepalive") } ?: 0,
                )
            }
            serverPeerPosition = parsed.indexOfFirst { it.endpoint != null }
            if (serverPeerPosition < 0) error("Empty available peer list")
            val primary = parsed[serverPeerPosition]
            server = primary.endpoint!!.first
            port = primary.endpoint.second
            publicKey = primary.publicKey
            preSharedKey = primary.preSharedKey
            allowedIPs = primary.allowedIPs.joinToString("\n")
            keepalive = primary.keepalive
            extraPeers = parsed.filterIndexed { index, _ -> index != serverPeerPosition }.joinToString("\n") {
                wireGuardPeerSection(it.publicKey, it.preSharedKey, it.endpoint, it.allowedIPs, it.keepalive)
            }
        }
    }

    private class WireGuardConfPeer(
        val publicKey: String,
        val preSharedKey: String?,
        val endpoint: Pair<String, Int>?,
        val allowedIPs: List<String>,
        val keepalive: Int,
    )

    private fun parseWireGuard(conf: WireGuardConf) = WireGuardBean().applyDefaultValues().apply {
        localAddress = conf.localAddress
        privateKey = conf.privateKey
        conf.mtu?.let { mtu = it }
        serverAddress = conf.server
        serverPort = conf.port
        peerPublicKey = conf.publicKey
        peerPreSharedKey = conf.preSharedKey
        allowedIPs = conf.allowedIPs
        persistentKeepalive = conf.keepalive
        extraPeers = conf.extraPeers
        serverPeerPosition = conf.serverPeerPosition
        importedDnsServers = conf.dns.first.joinToString("\n")
        importedDnsDomains = conf.dns.second.joinToString("\n")
    }.applyDefaultValues()

    // AmneziaWG configs are WireGuard configs that also carry obfuscation keys in
    // the [Interface] section (Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5).
    private fun isAmneziaWGConf(iface: IniConfig.Section): Boolean = listOf(
        "Jc", "Jmin", "Jmax",
        "S1", "S2", "S3", "S4",
        "H1", "H2", "H3", "H4",
        "I1", "I2", "I3", "I4", "I5",
    ).any { !iface[it].isNullOrBlank() }

    private fun parseAmneziaWG(iface: IniConfig.Section, conf: WireGuardConf) = AmneziaWGBean().applyDefaultValues().apply {
        localAddress = conf.localAddress
        privateKey = conf.privateKey
        conf.mtu?.let { mtu = it }
        serverAddress = conf.server
        serverPort = conf.port
        peerPublicKey = conf.publicKey
        peerPreSharedKey = conf.preSharedKey
        allowedIPs = conf.allowedIPs
        persistentKeepalive = conf.keepalive
        extraPeers = conf.extraPeers
        serverPeerPosition = conf.serverPeerPosition
        importedDnsServers = conf.dns.first.joinToString("\n")
        importedDnsDomains = conf.dns.second.joinToString("\n")
        // AmneziaWG obfuscation parameters.
        jc = iface["Jc"]?.toIntOrNull() ?: 0
        jmin = iface["Jmin"]?.toIntOrNull() ?: 0
        jmax = iface["Jmax"]?.toIntOrNull() ?: 0
        s1 = iface["S1"]?.toIntOrNull() ?: 0
        s2 = iface["S2"]?.toIntOrNull() ?: 0
        s3 = iface["S3"]?.toIntOrNull() ?: 0
        s4 = iface["S4"]?.toIntOrNull() ?: 0
        h1 = iface["H1"] ?: ""
        h2 = iface["H2"] ?: ""
        h3 = iface["H3"] ?: ""
        h4 = iface["H4"] ?: ""
        i1 = iface["I1"] ?: ""
        i2 = iface["I2"] ?: ""
        i3 = iface["I3"] ?: ""
        i4 = iface["I4"] ?: ""
        i5 = iface["I5"] ?: ""
    }.applyDefaultValues()

    /** Mihomo xhttp-opts string options, kept as sing-box XHTTP extra fields. */
    private val CLASH_XHTTP_STRING_KEYS = listOf(
        "x-padding-key",
        "x-padding-header",
        "x-padding-placement",
        "x-padding-method",
        "uplink-http-method",
        "session-placement",
        "session-key",
        "seq-placement",
        "seq-key",
        "uplink-data-placement",
        "uplink-data-key",
    )
    private val CLASH_XHTTP_BOOLEAN_KEYS = listOf("no-grpc-header", "x-padding-obfs-mode")

    // Ranges: the core reads a number, "from-to" or an object.
    private val CLASH_XHTTP_RANGE_KEYS = listOf("x-padding-bytes", "sc-max-each-post-bytes", "sc-min-posts-interval-ms", "uplink-chunk-size")
    private val CLASH_XMUX_RANGE_KEYS = listOf("max-connections", "max-concurrency", "c-max-reuse-times", "h-max-request-times", "h-max-reusable-secs")

    // A whole number of seconds in the core's schema.
    private const val CLASH_XMUX_KEEP_ALIVE = "h-keep-alive-period"

    /**
     * Reads Mihomo xhttp-opts into sing-box XHTTP extra fields of the types the core expects. An
     * option without a counterpart, such as download-settings or a server-side setting, a value
     * of the wrong type or an unknown mode rejects the node, which would otherwise connect
     * differently or not build.
     */
    internal fun parseClashXhttpOptions(bean: StandardV2RayBean, options: Map<String, Any?>) {
        val known = setOf("host", "path", "mode", "headers", "reuse-settings") + CLASH_XHTTP_STRING_KEYS + CLASH_XHTTP_BOOLEAN_KEYS + CLASH_XHTTP_RANGE_KEYS
        require(options.keys.all { it in known }) { "Unsupported XHTTP option" }
        options["host"]?.let { bean.host = it.toString() }
        options["path"]?.let { bean.path = it.toString() }
        options["mode"]?.let {
            bean.xhttpMode = when (val mode = it.toString()) {
                "" -> "auto"
                "auto", "packet-up", "stream-up", "stream-one" -> mode
                else -> error("Unsupported XHTTP mode")
            }
        }
        val extra = JSONObject()
        options["headers"]?.let { headers ->
            require(headers is Map<*, *>) { "Malformed XHTTP headers" }
            extra.put("headers", JSONObject(headers.entries.filter { it.value != null }.associate { it.key.toString() to it.value.toString() }))
        }
        for (key in CLASH_XHTTP_BOOLEAN_KEYS) options[key]?.let { extra.put(key.replace('-', '_'), clashBoolean(it)) }
        for (key in CLASH_XHTTP_RANGE_KEYS) options[key]?.let { extra.put(key.replace('-', '_'), clashRange(it)) }
        for (key in CLASH_XHTTP_STRING_KEYS) options[key]?.let { extra.put(key.replace('-', '_'), it.toString()) }
        options["reuse-settings"]?.let { reuse ->
            require(reuse is Map<*, *> && reuse.keys.all { it.toString() in CLASH_XMUX_RANGE_KEYS || it == CLASH_XMUX_KEEP_ALIVE }) {
                "Unsupported XHTTP reuse setting"
            }
            val xmux = JSONObject()
            for (key in CLASH_XMUX_RANGE_KEYS) reuse[key]?.let { xmux.put(key.replace('-', '_'), clashRange(it)) }
            reuse[CLASH_XMUX_KEEP_ALIVE]?.let {
                val seconds = (it as? Number)?.toLong() ?: it.toString().trim().toLongOrNull() ?: error("Malformed XHTTP keep-alive period")
                xmux.put(CLASH_XMUX_KEEP_ALIVE.replace('-', '_'), seconds)
            }
            if (xmux.length() > 0) extra.put("xmux", xmux)
        }
        if (extra.length() > 0) bean.xhttpExtra = extra.toString(2)
    }

    /** An XHTTP range: a whole number stays a number, "from-to" a string. */
    private fun clashRange(value: Any): Any = when (value) {
        is Int, is Long -> value
        is String -> value.trim().also { require(it.matches(Regex("-?\\d+(\\s*-\\s*-?\\d+)?"))) { "Malformed XHTTP range" } }
        else -> error("Malformed XHTTP range")
    }

    /**
     * A Mihomo boolean: true or false, or what Mihomo reads as one (a number, or "true", "1",
     * "t" and their opposites in any case); an empty value is false. Anything else rejects the
     * node instead of falling back to a default such as no TLS.
     */
    internal fun clashBoolean(value: Any?): Boolean = when (value) {
        null -> false

        is Boolean -> value

        is Number -> value.toDouble() != 0.0

        is String -> when (value.trim().lowercase()) {
            "true", "t", "1" -> true
            "false", "f", "0", "" -> false
            else -> error("Malformed boolean")
        }

        else -> error("Malformed boolean")
    }

    /** Clash types this app can multiplex: VMess, VLESS and Trojan share a bean, Shadowsocks has its own. */
    private val CLASH_MUX_TYPES = setOf("vmess", "vless", "trojan", "ss")
    private val CLASH_MUX_KEYS = setOf("enabled", "protocol", "max-connections", "min-streams", "max-streams", "padding", "statistic", "only-tcp", "brutal-opts")

    /**
     * Refuses a node whose options would be lost on import and change how, or through what, it
     * connects: a certificate pin (without it skip-cert-verify would trust any certificate), a relay
     * through another proxy, multiplexing a protocol here cannot multiplex, or Trojan's extra
     * Shadowsocks layer.
     */
    private fun checkClashNode(type: String, proxy: Map<String, Any?>) {
        require(proxy["fingerprint"]?.toString().isNullOrBlank()) { "Unsupported certificate pin" }
        require(proxy["dialer-proxy"]?.toString().isNullOrBlank()) { "Unsupported relay" }
        proxy["smux"]?.let { smux ->
            require(smux is Map<*, *>) { "Malformed multiplex options" }
            require(type in CLASH_MUX_TYPES || !clashBoolean(smux["enabled"] ?: false)) { "Unsupported multiplexing" }
        }
        proxy["ss-opts"]?.let { layer ->
            require(layer is Map<*, *> && !clashBoolean(layer["enabled"] ?: false)) { "Unsupported Trojan Shadowsocks layer" }
        }
    }

    /** Mihomo multiplex options in the bean's terms; [limitsConnections] selects the connection-count mode. */
    internal class ClashMux(
        val protocol: Int,
        val padding: Boolean,
        val limitsConnections: Boolean,
        val maxStreams: Int?,
        val maxConnections: Int?,
        val minStreams: Int?,
        val brutalUpMbps: Int?,
        val brutalDownMbps: Int?,
    )

    /**
     * Mihomo smux options, or null when multiplexing is off. Options sing-box cannot honor, such as
     * only-tcp or both stream and connection limits, reject the node.
     */
    internal fun parseClashMux(options: Map<*, *>): ClashMux? {
        require(options.keys.all { it.toString() in CLASH_MUX_KEYS }) { "Unsupported multiplex option" }
        if (!clashBoolean(options["enabled"] ?: false)) return null
        require(!clashBoolean(options["only-tcp"] ?: false)) { "Unsupported multiplex option" }
        val protocol = when (options["protocol"]?.toString()?.lowercase() ?: "h2mux") {
            "h2mux" -> 0
            "smux" -> 1
            "yamux" -> 2
            else -> error("Unsupported multiplex protocol")
        }
        fun count(key: String) = options[key]?.let { it.toString().trim().toIntOrNull()?.takeIf { value -> value >= 0 } ?: error("Malformed multiplex option") }
        val maxStreams = count("max-streams")
        val maxConnections = count("max-connections")
        val minStreams = count("min-streams")
        require(maxStreams == null || (maxConnections == null && minStreams == null)) { "Conflicting multiplex limits" }
        var up: Int? = null
        var down: Int? = null
        options["brutal-opts"]?.let { brutal ->
            require(brutal is Map<*, *> && brutal.keys.all { it.toString() in setOf("enabled", "up", "down") }) { "Unsupported brutal option" }
            if (clashBoolean(brutal["enabled"] ?: false)) {
                up = clashMbps(brutal["up"])
                down = clashMbps(brutal["down"])
            }
        }
        return ClashMux(protocol, clashBoolean(options["padding"] ?: false), maxStreams == null && (maxConnections != null || minStreams != null), maxStreams, maxConnections, minStreams, up, down)
    }

    /** A bandwidth in Mbps: a number, or "N", "N Mbps" or "N Gbps". */
    private fun clashMbps(value: Any?): Int {
        val match = Regex("(\\d+)\\s*([mg]bps)?", RegexOption.IGNORE_CASE).matchEntire(value?.toString()?.trim().orEmpty())
            ?: error("Malformed bandwidth")
        val amount = match.groupValues[1].toInt() * if (match.groupValues[2].equals("gbps", ignoreCase = true)) 1000 else 1
        require(amount > 0) { "Malformed bandwidth" }
        return amount
    }

    /**
     * sing-box outbound types the app's core registers (libcore box_include.go) and a provider may
     * set up. An outbound of another type could only fail once connected, so it is not imported and
     * does not count towards a format's coverage. Tor is left out: its outbound can start a
     * program named in the configuration, which is the user's decision, not a provider's.
     */
    private val SING_BOX_PROXY_TYPES = setOf(
        "socks",
        "http",
        "shadowsocks",
        "shadowsocksr",
        "vmess",
        "trojan",
        "ssh",
        "shadowtls",
        "vless",
        "anytls",
        "snell",
        "hysteria",
        "tuic",
        "hysteria2",
        "juicity",
        "wireguard",
        "amneziawg",
    )

    /** Outbounds that are not nodes: sing-box's and, in Xray configurations, Xray's. */
    private val SING_BOX_BUILTIN_TYPES = setOf("direct", "block", "dns", "selector", "urltest", "freedom", "blackhole", "loopback")

    fun parseJSON(json: Any): List<AbstractBean> = parseJSON(json, ImportReport())

    private fun parseJSON(json: Any, report: ImportReport): List<AbstractBean> = parseJSONValue(json, report).filter { bean ->
        // Check before applying defaults: missing endpoints must not become localhost profiles.
        val hasPort = if (bean is HysteriaBean) !bean.serverPorts.isNullOrBlank() else (bean.serverPort ?: 0) in 1..65535
        val valid = bean is ConfigBean || (
            !bean.serverAddress.isNullOrBlank() && hasPort &&
                (bean !is ShadowsocksBean || !bean.method.isNullOrBlank())
            )
        if (!valid) report.failed += protocolKey(bean)
        valid
    }.onEach { it.initializeDefaultValues() }

    private fun parseJSONValue(json: Any, report: ImportReport): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                // HY2 client config: server + a string auth. Distinguished from HY1 by the
                // absence of HY1's up/up_mbps (tls/obfs/bandwidth are optional in HY2).
                json.has("server") && json.getStr("auth") != null &&
                    !json.has("up") && !json.has("up_mbps") -> {
                    return listOf(json.parseHysteria2Json())
                }

                json.has("method") && json.has("obfs") && json.has("protocol") -> {
                    return listOf(json.parseShadowsocksR())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("outbounds") -> {
                    for ((key, section) in SING_BOX_SECTIONS) if (json.has(key)) report.dropped += section
                    val outbounds = json.optJSONArray("outbounds") ?: JSONArray()
                    for (index in 0 until outbounds.length()) {
                        val outbound = outbounds.opt(index) as? JSONObject
                        val type = outbound?.getStr("type") ?: outbound?.getStr("protocol")
                        when {
                            outbound == null -> report.failed += null

                            type in SING_BOX_BUILTIN_TYPES -> report.builtin++

                            outbound.getStr("type") in SING_BOX_PROXY_TYPES -> proxies += ConfigBean().apply {
                                applyDefaultValues()
                                this.type = 1
                                config = outbound.toStringPretty()
                                name = outbound.getStr("tag")
                            }

                            else -> report.failed += type
                        }
                    }
                    return proxies
                }

                json.has("server") && json.has("server_port") && json.getStr("type") in SING_BOX_PROXY_TYPES -> {
                    return listOf(
                        ConfigBean().applyDefaultValues().apply {
                            type = 1
                            config = json.toStringPretty()
                        },
                    )
                }

                else -> report.failed += json.getStr("type")
            }
        } else {
            json as JSONArray
            json.forEach { _, it ->
                try {
                    if (isJsonObjectValid(it)) {
                        proxies.addAll(parseJSON(if (it is String) JSONTokener(it).nextValue() else it, report))
                    } else {
                        report.failed += null
                    }
                } catch (_: Exception) {
                    Logs.w("Skipping malformed JSON subscription entry")
                    report.failed += null
                }
            }
        }

        return proxies
    }
}
