package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleAssetsTest {

    @Test
    fun matchesReleaseAsset_acceptsPlainAndXzOnly() {
        assertTrue(RuleAssets.matchesReleaseAsset("geoip.db", "geoip.db"))
        assertTrue(RuleAssets.matchesReleaseAsset("geoip.db.xz", "geoip.db"))
        // 不認別的檔名：changelog、備份檔，以及「另一個資料庫」都不能被抓去蓋 geoip.db
        assertFalse(RuleAssets.matchesReleaseAsset("changelog.txt", "geoip.db"))
        assertFalse(RuleAssets.matchesReleaseAsset("geoip.db.bak", "geoip.db"))
        assertFalse(RuleAssets.matchesReleaseAsset("geosite.db", "geoip.db"))
        assertFalse(RuleAssets.matchesReleaseAsset("geoip.dbxz", "geoip.db"))
        assertFalse(RuleAssets.matchesReleaseAsset(null, "geoip.db"))
    }

    @Test
    fun everyProviderCoversEveryManagedAsset() {
        for (provider in RuleAssets.providers) {
            for (name in RuleAssets.MANAGED) {
                assertNotNull(provider.repoByFileName[name])
            }
        }
    }
}
