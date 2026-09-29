package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * global_preferences.xml is a hub of nested PreferenceScreens that SettingsPreferenceFragment
 * opens one at a time by key. This guards the shape that navigation relies on: every hub row is a
 * keyed, titled, summarized screen, and moving items between pages never drops a setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GlobalPreferencesLayoutTest {

    @Test
    fun hubRowsAreKeyedScreensAndNoSettingIsLost() {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.global_preferences)
        val screens = mutableListOf<String>()
        val leafKeys = mutableListOf<String>()
        var depth = 0
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val key = parser.getAttributeValue(APP_NAMESPACE, "key")
                        when {
                            depth == 1 -> assertEquals("PreferenceScreen", parser.name)

                            depth == 2 -> {
                                assertEquals("hub row $key", "PreferenceScreen", parser.name)
                                assertTrue("hub row without key", key.orEmpty().startsWith("screen_"))
                                for (attribute in listOf("title", "summary", "icon")) {
                                    assertTrue(
                                        "hub row $key lacks $attribute",
                                        parser.getAttributeResourceValue(APP_NAMESPACE, attribute, 0) != 0,
                                    )
                                }
                                screens += requireNotNull(key)
                            }

                            parser.name != "PreferenceCategory" -> {
                                assertFalse("nested screens deeper than one level", parser.name == "PreferenceScreen")
                                leafKeys += requireNotNull(key) { "${parser.name} without key at depth $depth" }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> depth--
                }
                parser.next()
            }
        } finally {
            parser.close()
        }

        assertEquals(screens, screens.distinct())
        assertEquals(leafKeys.sorted(), leafKeys.distinct().sorted())
        assertEquals(EXPECTED_KEYS, leafKeys.toSet())
    }

    private companion object {
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"

        val EXPECTED_KEYS = setOf(
            "acquireWakeLock", "allowAccess", "allowInsecureOnRequest", "alwaysShowAddress", "appLanguage",
            "appTLSVersion", "appTheme", "appendHttpProxy", "bypassLan", "bypassLanInCore", "clearCache",
            "concurrentDial", "confirmProfileDelete", "connectionTestURL", "directDns", "dnsHosts",
            "domain_strategy_for_direct", "domain_strategy_for_remote", "domain_strategy_for_server",
            "enableClashAPI", "enableDnsRouting", "enableFakeDns", "enableTLSFragment", "fragmentInterval",
            "fragmentLength", "globalAllowInsecure", "globalCustomConfig", "hideFromRecentApps",
            "httpProxyBypass", "ipv6Mode", "isAutoConnect", "logLevel", "meteredNetwork", "mixedPort", "mtu",
            "networkChangeResetConnections", "nightTheme", "profileTrafficStatistics", "protectionAlwaysOn",
            "protectionBattery", "protectionLockdown", "protectionNotes", "proxyApps", "proxyModeInboundAuth", "remoteDns", "requireProxyInVPN", "resetSettings", "resolveDestination",
            "rulesGeoipUrl", "rulesGeositeUrl", "rulesProvider", "rulesUpdateInterval", "serviceMode",
            "showBottomBar", "showDirectSpeed", "showGroupInNotification", "speedInterval", "strictRoute",
            "trafficSniffing", "tunImplementation", "wakeResetConnections",
        )
    }
}
