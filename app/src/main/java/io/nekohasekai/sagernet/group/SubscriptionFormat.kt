package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.ktx.USER_AGENT

/**
 * The response format a subscription asks for. Providers choose a format from the User-Agent,
 * so a request differs only in that header: the URL, credentials and device headers stay the same.
 */
object SubscriptionFormat {
    /** One request with the app's own User-Agent, which asks for Clash/Mihomo. */
    const val DEFAULT = 0

    /** Finds a format that works, then keeps requesting it while it does. */
    const val AUTO = 1
    const val LINKS = 2
    const val SING_BOX = 3

    /** Auto's own name for the default request, so a remembered choice differs from none (0). */
    const val CLASH = 4

    /** Profiles that did not come from one of the formats' requests: a custom User-Agent or a file. */
    const val UNKNOWN = -1

    /**
     * Profiles whose request the user changed since they were stored, to another User-Agent or
     * between a link and a file. Until an update is accepted, it is unknown what they came from.
     */
    const val CHANGED = -2

    /** The formats Auto chooses between, in order of preference. */
    private val AUTO_FORMATS = listOf(LINKS, SING_BOX, CLASH)

    private val LINKS_USER_AGENT = "NekoBox-Links/" + BuildConfig.VERSION_NAME
    private const val SING_BOX_USER_AGENT = "sing-box"

    /** One request: the format it asks for and the User-Agent that asks for it. */
    data class Request(val format: Int, val userAgent: String)

    /** The requests for a link: [first] on every update, [fallback] only when the first yield nothing usable. */
    class Plan(val first: List<Request>, val fallback: List<Request>) {
        val size get() = first.size + fallback.size
    }

    /**
     * What to request. A custom User-Agent is always the only request. Auto requests the format
     * the stored profiles came from, [stored], and tries the others only when that stops working;
     * the default request counts as Clash's. Before an update made with one of the formats
     * ([updated] false, or profiles from a custom User-Agent or a file) it requests all of them.
     */
    fun plan(format: Int, stored: Int, updated: Boolean, custom: String): Plan {
        if (custom.isNotBlank()) return Plan(listOf(Request(format, custom)), emptyList())
        if (format != AUTO) return Plan(listOf(request(format)), emptyList())
        val all = AUTO_FORMATS.map(::request)
        val remembered = if (stored == DEFAULT && updated) CLASH else stored
        val known = all.firstOrNull { it.format == remembered } ?: return Plan(all, emptyList())
        return Plan(listOf(known), all - known)
    }

    private fun request(format: Int) = Request(
        format,
        when (format) {
            LINKS -> LINKS_USER_AGENT
            SING_BOX -> SING_BOX_USER_AGENT
            else -> USER_AGENT
        },
    )

    /**
     * Whether an update from the [current] request may describe nodes differently from the
     * profiles stored from the [stored] one: the requests differ, the default request and Clash's
     * being the same, one of them is not a format's request, or the user changed the request
     * since ([CHANGED]). Two requests that are both not formats' requests cannot be told apart.
     */
    fun requestChanged(stored: Int, current: Int): Boolean = when {
        stored == CHANGED -> true
        stored == UNKNOWN || current == UNKNOWN -> stored != current
        else -> request(stored).userAgent != request(current).userAgent
    }

    /**
     * Whether [candidate] should replace [previous]: only for more importable profiles. Parsers
     * refuse nodes they would import with weaker settings, so the count includes no downgraded
     * nodes. Skipped entries do not count against a response, since one that leaves unsupported
     * nodes out entirely is no better. On a tie the earlier, preferred format stays.
     */
    fun better(candidate: ImportPreview, previous: ImportPreview): Boolean = candidate.acceptedCount > previous.acceptedCount
}
