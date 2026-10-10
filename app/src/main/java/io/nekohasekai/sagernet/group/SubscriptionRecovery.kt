package io.nekohasekai.sagernet.group

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Backup and replacement addresses of a subscription. Updates fetch only the main link and the
 * backups the user approved; an address a provider proposes is stored as an offer and takes
 * effect, credentials and device ID included, only once the user accepts it. Backups and offers
 * are HTTPS only, so falling back never sends a subscription in the clear.
 */
object SubscriptionRecovery {
    const val MAX_BACKUP_LINKS = 5
    private const val MAX_LINK_LENGTH = 2048

    /** The approved backups in [stored] (one per line) in order, without the main [link] or repeats. */
    fun backupLinks(link: String, stored: String): List<String> = stored.lineSequence()
        .map { it.trim() }
        .filter { it != link && isHttpsLink(it) }
        .distinct()
        .take(MAX_BACKUP_LINKS)
        .toList()

    fun isHttpsLink(link: String): Boolean = link.length <= MAX_LINK_LENGTH &&
        link.none(Char::isISOControl) &&
        link.startsWith("https://", ignoreCase = true) &&
        link.toHttpUrlOrNull() != null

    /**
     * The replacement a provider proposes for [link]: `new-url` as given, or else `new-domain` in
     * place of the host of [link]. Null when absent, not HTTPS or the same as [link].
     */
    fun proposedLink(link: String, newUrl: String?, newDomain: String?): String? {
        newUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { return offer(link, it) }
        val domain = newDomain?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = link.toHttpUrlOrNull() ?: return null
        val moved = runCatching { url.newBuilder().scheme("https").host(domain).build().toString() }.getOrNull()
        return offer(link, moved)
    }

    /** [candidate] as an offer for the subscription at [link], an HTTP(S) address: HTTPS only, never [link] itself. */
    fun offer(link: String, candidate: String?): String? {
        if (link.toHttpUrlOrNull() == null) return null
        return candidate?.trim()?.takeIf { isHttpsLink(it) && it != link }
    }
}
