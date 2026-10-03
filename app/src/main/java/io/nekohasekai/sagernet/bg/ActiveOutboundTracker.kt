package io.nekohasekai.sagernet.bg

import android.content.Context
import android.text.format.Formatter
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

object ActiveOutboundTracker {

    @Volatile
    var activeLeafProfileId: Long = 0L
        private set

    @Volatile
    var activeLeafProfileName: String = ""
        private set

    fun reset() {
        activeLeafProfileId = 0L
        activeLeafProfileName = ""
    }

    fun getStrategyDisplayName(profile: ProxyEntity): String {
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.requireBean() as? BalancerBean
            return when (bean?.strategy) {
                BalancerBean.STRATEGY_LEAST_PING -> "最低延迟"
                BalancerBean.STRATEGY_LEAST_LOAD -> "最低负载"
                BalancerBean.STRATEGY_RANDOM -> "随机"
                BalancerBean.STRATEGY_ROUND_ROBIN, BalancerBean.STRATEGY_ROUND_ROBIN_LEGACY -> "轮询"
                BalancerBean.STRATEGY_FAILOVER -> "故障转移"
                BalancerBean.STRATEGY_STABLE -> "最稳定"
                BalancerBean.STRATEGY_CONSISTENT_HASH, BalancerBean.STRATEGY_CONSISTENT_HASH_CAMEL -> "一致性哈希"
                else -> "策略组"
            }
        }
        val group = runCatching { SagerDatabase.groupDao.getById(profile.groupId) }.getOrNull()
        if (group != null) {
            if (runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false)) return "自动测速"
            if (runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)) return "负载均衡"
        }
        val isGlobal = runCatching { DataStore.globalMode }.getOrDefault(false)
        return if (isGlobal) "全局模式" else "规则分流"
    }

    fun onProfileSwitched(newProfile: ProxyEntity) {
        val oldId = activeLeafProfileId
        reset()
        if (oldId > 0L) {
            runOnDefaultDispatcher {
                ProfileManager.postUpdate(oldId, true)
                ProfileManager.postUpdate(newProfile.id, true)
            }
        }
    }

    fun updateActiveLeaf(candidateId: Long, candidateName: String) {
        activeLeafProfileId = candidateId
        activeLeafProfileName = candidateName
    }

    fun getActiveLeafNodeDisplay(profile: ProxyEntity): String? {
        val isBalancer = profile.type == ProxyEntity.TYPE_BALANCER
        val group = runCatching { SagerDatabase.groupDao.getById(profile.groupId) }.getOrNull()
        val isGroupStrategy = group != null && (
            runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false) ||
            runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)
        )
        if (!isBalancer && !isGroupStrategy) return null

        val leafId = activeLeafProfileId
        if (leafId > 0L && leafId != profile.id) {
            val name = activeLeafProfileName.takeIf { it.isNotBlank() }
                ?: runCatching { SagerDatabase.proxyDao.getById(leafId)?.displayName() }.getOrNull()
            return name?.takeIf { it.isNotBlank() }
        }
        return null
    }

    fun formatNotificationTitle(
        profile: ProxyEntity,
        isGlobalMode: Boolean? = null
    ): String {
        val baseTitle = runCatching { ServiceNotification.genTitle(profile) }.getOrDefault(profile.displayName())
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val strat = getStrategyDisplayName(profile)
            return if (baseTitle.contains(strat) || baseTitle.contains("策略")) {
                baseTitle
            } else {
                "$baseTitle（策略组：$strat）"
            }
        }
        val group = runCatching { SagerDatabase.groupDao.getById(profile.groupId) }.getOrNull()
        val isGroupStrategy = group != null && (
            runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false) ||
            runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)
        )
        if (isGroupStrategy) {
            val groupTitle = group?.displayName() ?: profile.displayName()
            val groupPrefix = if (DataStore.showGroupInNotification && !groupTitle.startsWith("[")) {
                "[${group.displayName()}] "
            } else ""
            val strat = getStrategyDisplayName(profile)
            return if (groupTitle.contains("策略") || groupTitle.contains(strat)) {
                "$groupPrefix$groupTitle"
            } else {
                "$groupPrefix$groupTitle（策略组：$strat）"
            }
        }
        return baseTitle
    }

    fun formatNotificationSubText(
        context: Context,
        stats: SpeedDisplayData,
        profile: ProxyEntity,
    ): String {
        val trafficStr = context.getString(
            R.string.traffic,
            Formatter.formatFileSize(context, stats.txTotal),
            Formatter.formatFileSize(context, stats.rxTotal)
        )
        val strategyStr = getStrategyDisplayName(profile)
        return "$trafficStr · $strategyStr"
    }

    private fun queryClashNowTag(groupTag: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://127.0.0.1:9090/proxies/$groupTag")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 300
                readTimeout = 300
                requestMethod = "GET"
                val secret = DataStore.clashApiSecret
                if (secret.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $secret")
                }
            }
            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val content = reader.use { it.readText() }
                val json = JSONObject(content)
                json.optString("now").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun checkAndUpdate(data: BaseService.Data): Boolean {
        val proxy = data.proxy ?: return false
        if (!proxy.isInitialized()) return false
        val profile = proxy.profile
        val isBalancer = profile.type == ProxyEntity.TYPE_BALANCER
        val group = runCatching { SagerDatabase.groupDao.getById(profile.groupId) }.getOrNull()
        val isGroupStrategy = group != null && (
            runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false) ||
            runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)
        )

        if (!isBalancer && !isGroupStrategy) {
            if (activeLeafProfileId != profile.id) {
                activeLeafProfileId = profile.id
                activeLeafProfileName = profile.displayName()
                return true
            }
            return false
        }

        // Strategy group: resolve active member
        val balancerMembers = runCatching { proxy.config.balancerMemberMap[profile.id] }.getOrNull()
        val memberMap = balancerMembers
            ?: if (isGroupStrategy) runCatching { SagerDatabase.proxyDao.getByGroup(group!!.id).map { it.id } }.getOrNull() else null

        if (memberMap.isNullOrEmpty()) {
            if (activeLeafProfileId != 0L) {
                reset()
                return true
            }
            return false
        }

        // Target tag for this specific strategy group (do not blindly query "proxy")
        val balancerTag = runCatching { proxy.config.profileTagMap[profile.id] }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: profile.displayName()
        var candidateTag = queryClashNowTag(balancerTag)
        if (candidateTag.isNullOrBlank() && balancerTag != "proxy" && isGroupStrategy) {
            candidateTag = queryClashNowTag("proxy")
        }

        var candidateId: Long? = null
        if (!candidateTag.isNullOrBlank()) {
            val resolved = runCatching {
                proxy.config.profileTagMap.entries
                    .firstOrNull { it.value == candidateTag }
                    ?.key
                    ?.let { abs(it) }
            }.getOrNull()
            // Strict member whitelist check: candidate MUST belong to this strategy group's members!
            if (resolved != null && resolved in memberMap) {
                candidateId = resolved
            }
        }

        if (candidateId == null || candidateId <= 0L) {
            // Check traffic deltas in TrafficLooper
            val activeItem = proxy.looper?.getActiveTransmittingMember(memberMap)
            if (activeItem != null && activeItem > 0L && activeItem in memberMap) {
                candidateId = activeItem
            }
        }

        if (candidateId == null || candidateId <= 0L) {
            // If current leaf is already valid for this group, keep it
            if (activeLeafProfileId in memberMap) {
                candidateId = activeLeafProfileId
            } else {
                candidateId = memberMap.firstOrNull()
            }
        }

        if (candidateId != null && candidateId in memberMap && candidateId != activeLeafProfileId) {
            val oldId = activeLeafProfileId
            activeLeafProfileId = candidateId
            val ent = runCatching { SagerDatabase.proxyDao.getById(candidateId) }.getOrNull()
            activeLeafProfileName = ent?.displayName() ?: ""
            Logs.i("ActiveOutboundTracker: active node changed $oldId -> $candidateId ($activeLeafProfileName)")

            runOnDefaultDispatcher {
                if (oldId > 0L) ProfileManager.postUpdate(oldId, true)
                ProfileManager.postUpdate(candidateId, true)
                ProfileManager.postUpdate(profile.id, true)
            }
            return true
        }

        return false
    }

}
