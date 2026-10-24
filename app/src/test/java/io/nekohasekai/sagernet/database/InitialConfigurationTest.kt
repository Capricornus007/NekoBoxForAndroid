package io.nekohasekai.sagernet.database

import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.fmt.directFallbackConfig
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class InitialConfigurationTest {
    @Test fun excludesCredentialsAndInstallationState() {
        val settings = InitialConfiguration.settings()
        val keys = settings.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.none { it in setOf(
            Key.CLASH_API_SECRET, Key.MIXED_PASSWORD, Key.MIXED_USERNAME,
            Key.MIXED_PORT, Key.SOCKS_PORT, Key.HTTP_PORT,
            Key.PROFILE_CURRENT, Key.PROFILE_ID, Key.PROFILE_GROUP,
            "webdavPassword", "webdavServer", "networkProfiles",
        ) })
        assertEquals(false, settings.single { it.key == Key.GLOBAL_ALLOW_INSECURE }.boolean)
        assertEquals(false, settings.single { it.key == Key.ALLOW_ACCESS }.boolean)
        // SagerNet must not apply the older UI migration over the seeded dynamic theme.
        assertEquals(1L, settings.single { it.key == Key.UI_DESIGN_VERSION }.long)
        assertEquals(true, settings.single { it.key == Key.DYNAMIC_COLORS }.boolean)
    }

    @Test fun validatesFallbackWithoutRequiringAStoredProfile() {
        val settings = InitialConfiguration.settings().associateBy { it.key }
        fun int(key: String) = settings.getValue(key).string!!.toInt()
        assertEquals(true, settings.getValue(Key.DIRECT_FALLBACK).boolean)
        val config = directFallbackConfig(
            "proxy", settings.getValue(Key.DIRECT_FALLBACK_URL).string!!,
            int(Key.DIRECT_FALLBACK_INTERVAL), int(Key.DIRECT_FALLBACK_REQUIRED),
            int(Key.DIRECT_FALLBACK_FAILURES), int(Key.DIRECT_FALLBACK_RECOVERIES),
        )
        assertFalse(config.isEmpty())
        assertEquals(4, settings.getValue(Key.DIRECT_FALLBACK_URL).string!!.lines().size)
        assertEquals(2, int(Key.DIRECT_FALLBACK_REQUIRED))
    }

    @Test fun keepsLanDirectAndRussianRulesOnTheGlobalFallback() {
        val rules = InitialConfiguration.rules("LAN", "Domains", "IP")
        assertEquals(3, rules.size)
        assertTrue(rules.all { it.enabled && it.outbound == -1L && it.id == 0L })
        assertTrue(rules.first().config.contains("\"outbound\":\"direct\""))
        assertEquals("geosite:category-ru", rules[1].domains)
        assertEquals("geoip:ru", rules[2].ip)
        assertTrue(rules.drop(1).all { it.config.isEmpty() && it.fallbackMode == 0 && it.fallbackProfile == 0L })
    }

    @Test fun openingOrMigratingExistingDatabasesDoesNotReseedThem() {
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, _ -> error("Unexpected database write: ${method.name}") } as SupportSQLiteDatabase
        for (callback in listOf(InitialConfiguration.settingsCallback, InitialConfiguration.routesCallback)) {
            callback.onOpen(database)
            callback.onDestructiveMigration(database)
        }
    }
}
