package io.nekohasekai.sagernet.fmt.internal

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import java.util.regex.Pattern

enum class FastestCandidateResolutionError(val configMessage: String) {
    EMPTY_REGEX("Fastest regex profile has no regular expression"),
    INVALID_REGEX("Fastest regex profile has an invalid regular expression"),
    SOURCE_GROUP_MISSING("Fastest regex profile source subscription is missing"),
    SOURCE_GROUP_NOT_SUBSCRIPTION("Fastest regex profile source group is not a subscription"),
    DUPLICATE_MANUAL_CANDIDATES("Dynamic proxy profile contains duplicate candidates"),
    INVALID_PROTOCOL_FILTER("Fastest profile has an invalid protocol filter"),
}

class FastestCandidateResolutionException(
    val error: FastestCandidateResolutionError,
    cause: Throwable? = null,
) : IllegalArgumentException(error.configMessage, cause)

object FastestCandidateResolver {

    fun resolve(bean: ChainBean): List<ProxyEntity> {
        val candidates = if (bean.candidateMode == ChainBean.CANDIDATE_MODE_REGEX) {
            val sourceGroup = SagerDatabase.groupDao.getById(bean.sourceGroupId)
                ?: throw FastestCandidateResolutionException(
                    FastestCandidateResolutionError.SOURCE_GROUP_MISSING
                )
            if (sourceGroup.type != GroupType.SUBSCRIPTION) {
                throw FastestCandidateResolutionException(
                    FastestCandidateResolutionError.SOURCE_GROUP_NOT_SUBSCRIPTION
                )
            }
            filterRegexCandidates(
                SagerDatabase.proxyDao.getByGroup(sourceGroup.id),
                bean.nameRegex,
                bean.ignoreCase,
            )
        } else {
            if (bean.proxies.size != bean.proxies.distinct().size) {
                throw FastestCandidateResolutionException(
                    FastestCandidateResolutionError.DUPLICATE_MANUAL_CANDIDATES
                )
            }
            val profilesById = SagerDatabase.proxyDao.getEntities(bean.proxies).associateBy { it.id }
            bean.proxies.mapNotNull(profilesById::get)
        }
        val cache = candidates.associateBy { it.id }.toMutableMap()
        fun lookup(id: Long): ProxyEntity? = cache[id] ?: SagerDatabase.proxyDao.getById(id)?.also { cache[id] = it }
        return FastestProtocolFilter.filter(candidates, bean.protocolFilterMode, bean.protocols.toSet(), ::lookup) { candidate ->
            val group = SagerDatabase.groupDao.getById(candidate.groupId)
            listOfNotNull(group?.frontProxy?.let(::lookup), group?.landingProxy?.let(::lookup))
        }
    }

    fun filterRegexCandidates(
        candidates: List<ProxyEntity>,
        pattern: String,
        ignoreCase: Boolean,
    ): List<ProxyEntity> {
        if (pattern.isBlank()) {
            throw FastestCandidateResolutionException(FastestCandidateResolutionError.EMPTY_REGEX)
        }
        val regex = try {
            val flags = if (ignoreCase) {
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            } else {
                0
            }
            Pattern.compile(pattern, flags).toRegex()
        } catch (e: IllegalArgumentException) {
            throw FastestCandidateResolutionException(
                FastestCandidateResolutionError.INVALID_REGEX,
                e,
            )
        }
        return candidates.filter { regex.containsMatchIn(it.displayName()) }
    }
}
