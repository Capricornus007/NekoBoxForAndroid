package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.tailscale.pruneTailscaleState
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.IOException
import java.sql.SQLException
import java.util.*

object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: TrafficData)
        suspend fun onUpdated(data: List<TrafficData>) {
            data.forEach { onUpdated(it) }
        }
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    // 首次建庫時自動加的「Google Play」規則內容。Play 客戶端只畫介面與派工，真正拉 APK 的是
    // 下載管理器與 GMS/GSF，CDN 也不是 googleapis.cn 那條域名，所以兩邊都要列齊：
    // 缺 domains 那半 → 安裝卡在 0%；缺 packages 那半 → 規則對全機生效、白吃代理流量。
    private val PLAY_STORE_DOMAINS = listOf(
        "domain:googleapis.cn",
        "domain:xn--ngstr-lra8j.com",
        "domain:xn--ngstr-cn-8za9o.com",
        "domain:gvt1.com",
        "domain:gvt2.com",
        "domain:gvt3.com",
        "domain:gvt5.com",
        "domain:gvt6.com",
        "domain:gvt7.com",
        "domain:gvt9.com",
        "domain:gvt1-cn.com",
        "domain:gvt2-cn.com",
        "domain:googleusercontent.com",
        "domain:play.googleapis.com",
        "domain:android.clients.google.com",
        "domain:playstoregatewayadapter-pa.googleapis.com",
        "domain:firebaselogging-pa.googleapis.com",
        "domain:ggpht.com",
    ).joinToString("\n")
    private val PLAY_STORE_PACKAGES = setOf(
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
    )

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateProxy(profile)
        iterator { onUpdated(profile, false) }
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        SagerDatabase.proxyDao.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
    }

    /**
     * Batch-persist profiles WITHOUT firing per-profile onUpdated listener rounds.
     * For callers that follow up with GroupManager.postReload(groupId), which
     * re-renders the whole group anyway (e.g. connection-test finalization).
     */
    suspend fun updateProfileQuietly(profiles: List<ProxyEntity>) {
        if (profiles.isEmpty()) return
        SagerDatabase.proxyDao.updateProxy(profiles)
    }

    suspend fun updateTraffic(profileId: Long, rx: Long, tx: Long) {
        SagerDatabase.proxyDao.updateTraffic(profileId, rx, tx)
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        if (profileIds.isNotEmpty()) {
            SagerDatabase.proxyDao.resetTraffic(profileIds)
        }
    }

    // Add a per-session DELTA (never absolute) into the profile's lifetime columns (schema v12).
    suspend fun addLifetimeTraffic(profileId: Long, rxDelta: Long, txDelta: Long) {
        SagerDatabase.proxyDao.addLifetimeTraffic(profileId, rxDelta, txDelta)
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
    }

    suspend fun deleteProfiles(profiles: List<ProxyEntity>) {
        if (profiles.isEmpty()) return
        val ids = profiles.map { it.id }
        var deleted = 0
        SagerDatabase.instance.runInTransaction {
            ids.chunked(500).forEach { deleted += SagerDatabase.proxyDao.deleteByIds(it) }
        }
        if (deleted > 0 && DataStore.selectedProxy in ids) {
            DataStore.selectedProxy = 0L
        }
        pruneTailscaleState()
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
        pruneTailscaleState()
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: List<TrafficData>) {
        if (data.isEmpty()) return
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    suspend fun getRules(): List<RuleEntity> {
        var rules = SagerDatabase.rulesDao.allRules()
        if (rules.isEmpty() && !DataStore.rulesFirstCreate) {
            DataStore.rulesFirstCreate = true
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_quic),
                    network = "udp",
                    outbound = -2,
                ),
            )
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_ads),
                    domains = "geosite:category-ads-all",
                    outbound = -2,
                ),
            )
            val fuckedCountry = mutableListOf("cn:中国")
            if (Locale.getDefault().country != Locale.CHINA.country) {
                // non-Chinese users
                fuckedCountry += "ir:Iran"
                fuckedCountry += "ru:Russia"
            }
            for (c in fuckedCountry) {
                val country = c.substringBefore(":")
                val displayCountry = c.substringAfter(":")
                //
                if (country == "cn") {
                    createRule(
                        RuleEntity(
                            name = app.getString(R.string.route_play_store, displayCountry),
                            domains = PLAY_STORE_DOMAINS,
                            packages = PLAY_STORE_PACKAGES,
                        ),
                        false,
                    )
                }
                createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_bypass_domain, displayCountry),
                        domains = "geosite:$country",
                        outbound = -1,
                    ),
                    false,
                )
                createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_bypass_ip, displayCountry),
                        ip = "geoip:$country",
                        outbound = -1,
                    ),
                    false,
                )
            }
            rules = SagerDatabase.rulesDao.allRules()
        } else if (rules.isNotEmpty()) {
            if (enrichPlayStoreRules(rules)) {
                rules = SagerDatabase.rulesDao.allRules()
            }
        }
        return rules
    }

    // 舊安裝的「Google Play」規則是在 CDN 網域與下載方套件補齊之前建好的，只改首建清單救不到他們：
    // 那條規則缺 gvt*/playstoregatewayadapter 那批域，安裝會卡在 0%；也缺 packages，所以它對全機
    // 生效、白吃代理流量。這裡就地補一次（own 1a27397dd 的遷移思路）。
    //
    // 跟 own 的差別：只認「走代理（outbound == 0）且已含 domain:googleapis.cn 或 com.android.vending」
    // 的規則，避免把使用者自己寫的 googleapis.cn 直連/封鎖規則一起改壞；補齊的內容直接復用
    // PLAY_STORE_DOMAINS/PLAY_STORE_PACKAGES，不再抄一份清單。補過一次之後條件就不再成立，不會
    // 每次進頁面都寫庫；原規則裡多出來的欄位一律保留。
    private suspend fun enrichPlayStoreRules(rules: List<RuleEntity>): Boolean {
        val wantedDomains = PLAY_STORE_DOMAINS.split("\n")
        var changed = false
        rules.forEach { rule ->
            if (rule.outbound != 0L) return@forEach
            val haveDomains = rule.domains.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() }.toSet()
            if (!haveDomains.contains("domain:googleapis.cn") && !rule.packages.contains("com.android.vending")) {
                return@forEach
            }
            val missingDomains = wantedDomains.filterNot { it in haveDomains }
            val missingPackages = PLAY_STORE_PACKAGES - rule.packages
            if (missingDomains.isEmpty() && missingPackages.isEmpty()) return@forEach
            rule.domains = (haveDomains + missingDomains).joinToString("\n")
            rule.packages = rule.packages + missingPackages
            SagerDatabase.rulesDao.updateRule(rule)
            changed = true
        }
        return changed
    }
}
