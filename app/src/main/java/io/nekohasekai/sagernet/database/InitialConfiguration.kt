package io.nekohasekai.sagernet.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.ktx.app
import java.util.Locale

/** Public defaults only. Never seed credentials, selected IDs or device-specific ports. */
object InitialConfiguration {
    val settingsCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.compileStatement("INSERT INTO KeyValuePair (`key`, valueType, value) VALUES (?, ?, ?)").use { statement ->
                settings().forEach { pair ->
                    statement.clearBindings()
                    statement.bindString(1, pair.key)
                    statement.bindLong(2, pair.valueType.toLong())
                    statement.bindBlob(3, pair.value)
                    statement.executeInsert()
                }
            }
        }
    }

    val routesCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            val country = Locale("", "RU").displayCountry
            val rules = rules(
                app.getString(R.string.route_opt_bypass_lan),
                app.getString(R.string.route_bypass_domain, country),
                app.getString(R.string.route_bypass_ip, country),
            )
            rules.forEachIndexed { index, rule ->
                db.execSQL(
                    "INSERT INTO rules (name, config, userOrder, enabled, domains, ip, port, sourcePort, network, source, protocol, ruleset, outbound, packages, fallbackMode, fallbackProfile) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(rule.name, rule.config, index + 1, 1, rule.domains, rule.ip, rule.port,
                        rule.sourcePort, rule.network, rule.source, rule.protocol, rule.ruleset,
                        rule.outbound, "", rule.fallbackMode, rule.fallbackProfile),
                )
            }
        }
    }

    fun settings(): List<KeyValuePair> = listOf(
        KeyValuePair(Key.SERVICE_MODE).put(Key.MODE_VPN),
        KeyValuePair("showGroupInNotification").put(false),
        KeyValuePair(Key.CONFIRM_PROFILE_DELETE).put(true),
        KeyValuePair(Key.ALWAYS_SHOW_ADDRESS).put(false),
        KeyValuePair(Key.METERED_NETWORK).put(false),
        KeyValuePair(Key.ACQUIRE_WAKE_LOCK).put(false),
        KeyValuePair(Key.HIDE_FROM_RECENT_APPS).put(false),
        KeyValuePair(Key.RULES_GEOSITE_URL).put("https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db"),
        KeyValuePair(Key.RULES_GEOIP_URL).put("https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"),
        KeyValuePair(Key.FRAGMENT_LENGTH).put("100-200"),
        KeyValuePair(Key.FRAGMENT_INTERVAL).put("10-20"),
        KeyValuePair(Key.APPEND_HTTP_PROXY).put(false),
        KeyValuePair(Key.CONNECTION_TEST_URL).put("http://www.gstatic.com/generate_204"),
        KeyValuePair(Key.ENABLE_CLASH_API).put(false),
        KeyValuePair(Key.WAKE_RESET_CONNECTIONS).put(false),
        KeyValuePair(Key.RESOLVE_DESTINATION).put(true),
        KeyValuePair(Key.PERSIST_ACROSS_REBOOT).put(true),
        // The backup disabled TLS verification globally; retain secure defaults instead.
        KeyValuePair(Key.GLOBAL_ALLOW_INSECURE).put(false),
        KeyValuePair(Key.STRICT_ROUTE).put(true),
        KeyValuePair(Key.RULES_UPDATE_INTERVAL).put("72h"),
        KeyValuePair(Key.CONCURRENT_DIAL).put(false),
        KeyValuePair(Key.NETWORK_CHANGE_RESET_CONNECTIONS).put(false),
        KeyValuePair(Key.RESTART_PROFILE_ON_NETWORK_CHANGE).put(true),
        KeyValuePair(Key.UI_DESIGN_VERSION).put(1L),
        KeyValuePair(Key.SHOW_DIRECT_SPEED).put(false),
        KeyValuePair(Key.APP_TLS_VERSION).put("1.3"),
        KeyValuePair(Key.PROXY_APPS).put(true),
        KeyValuePair(Key.BYPASS_MODE).put(true),
        KeyValuePair(Key.SHOW_BOTTOM_BAR).put(true),
        KeyValuePair(Key.SPEED_INTERVAL).put("0"),
        KeyValuePair(Key.NIGHT_THEME).put("1"),
        KeyValuePair(Key.BYPASS_LAN).put(true),
        KeyValuePair(Key.BYPASS_LAN_IN_CORE).put(true),
        KeyValuePair(Key.ENABLE_FAKEDNS).put(false),
        KeyValuePair(Key.ALLOW_INSECURE_ON_REQUEST).put(false),
        KeyValuePair(Key.MTU).put("1500"),
        KeyValuePair(Key.ALLOW_ACCESS).put(false),
        KeyValuePair("domain_strategy_for_remote").put("auto"),
        KeyValuePair(Key.IPV6_MODE).put("1"),
        KeyValuePair(Key.ENABLE_TLS_FRAGMENT).put(false),
        KeyValuePair(Key.TUN_IMPLEMENTATION).put("0"),
        KeyValuePair(Key.TRAFFIC_SNIFFING).put("1"),
        KeyValuePair("domain_strategy_for_direct").put("prefer_ipv4"),
        KeyValuePair(Key.DIRECT_DNS).put("localhost"),
        KeyValuePair(Key.INDIVIDUAL).put(listOf(
            "app.bearium.browser", "com.google.ar.core", "com.google.android.gsf",
            "org.kde.kdeconnect_tp", "app.mihon", "ru.nspk.mirpay",
            "com.notcvnt.rknhardering", "com.termux", "com.avito.android",
            "com.google.android.gms", "ru.ufanet.smarthome",
        ).joinToString("\n")),
        KeyValuePair(Key.ENABLE_DNS_ROUTING).put(false),
        KeyValuePair("domain_strategy_for_server").put("auto"),
        KeyValuePair(Key.RULES_PROVIDER).put("0"),
        KeyValuePair(Key.DOWNLOADS_PROXY_ONLY).put(false),
        KeyValuePair(Key.PROFILE_TRAFFIC_STATISTICS).put(true),
        KeyValuePair(Key.DIRECT_FALLBACK_INTERVAL).put("30"),
        KeyValuePair(Key.DIRECT_FALLBACK).put(true),
        KeyValuePair(Key.GLOBAL_CUSTOM_CONFIG).put(""),
        KeyValuePair(Key.DIRECT_FALLBACK_FAILURES).put("1"),
        KeyValuePair(Key.DIRECT_FALLBACK_RECOVERIES).put("1"),
        KeyValuePair("networkProfilesEnabled").put(false),
        KeyValuePair(Key.APP_THEME).put("0"),
        KeyValuePair(Key.AMOLED_THEME).put(false),
        KeyValuePair(Key.DYNAMIC_COLORS).put(true),
        KeyValuePair(Key.LOG_LEVEL).put("1"),
        KeyValuePair(Key.DIRECT_FALLBACK_URL).put(listOf(
            "https://www.kernel.org",
            "http://cp.cloudflare.com/generate_204",
            "http://www.gstatic.com/generate_204",
            "http://detectportal.firefox.com/success.txt",
        ).joinToString("\n")),
        KeyValuePair(Key.DIRECT_FALLBACK_REQUIRED).put("2"),
        KeyValuePair(Key.REMOTE_DNS).put("https://dns.adguard-dns.com/dns-query"),
    )

    fun rules(lanName: String, domainName: String, ipName: String): List<RuleEntity> = listOf(
        RuleEntity(name = lanName, enabled = true, outbound = -1,
            config = """{"ip_is_private":true,"outbound":"direct"}"""),
        RuleEntity(name = domainName, enabled = true, outbound = -1,
            domains = "geosite:category-ru"),
        RuleEntity(name = ipName, enabled = true, outbound = -1, ip = "geoip:ru"),
    )
}
