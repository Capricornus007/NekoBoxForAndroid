package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.group.ImportPreview.Reason
import io.nekohasekai.sagernet.group.ImportPreview.Section
import io.nekohasekai.sagernet.group.SubscriptionFormat.AUTO
import io.nekohasekai.sagernet.group.SubscriptionFormat.CLASH
import io.nekohasekai.sagernet.group.SubscriptionFormat.DEFAULT
import io.nekohasekai.sagernet.group.SubscriptionFormat.LINKS
import io.nekohasekai.sagernet.group.SubscriptionFormat.SING_BOX
import io.nekohasekai.sagernet.group.SubscriptionFormat.UNKNOWN
import io.nekohasekai.sagernet.ktx.USER_AGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFormatTest {
    private fun SubscriptionFormat.Plan.formats() = first.map { it.format } to fallback.map { it.format }

    @Test
    fun plansRequestOnceExceptForAutoAndACustomUserAgentWins() {
        // Stored subscriptions without a format keep the single request they always made.
        val legacy = SubscriptionFormat.plan(DEFAULT, 0, true, "")
        assertEquals(listOf(USER_AGENT), legacy.first.map { it.userAgent })
        assertTrue(legacy.fallback.isEmpty())
        assertEquals(listOf(USER_AGENT), SubscriptionFormat.plan(37, 0, true, "").first.map { it.userAgent })
        assertEquals(listOf(LINKS) to emptyList<Int>(), SubscriptionFormat.plan(LINKS, SING_BOX, true, "").formats())
        for (format in listOf(DEFAULT, AUTO, LINKS, SING_BOX)) {
            val plan = SubscriptionFormat.plan(format, SING_BOX, true, "custom/1.0")
            assertEquals(listOf("custom/1.0"), plan.first.map { it.userAgent })
            assertEquals(1, plan.size)
        }
    }

    @Test
    fun autoNegotiatesOnceThenKeepsTheRequestTheProfilesCameFrom() {
        // Nothing stored yet: compare the formats.
        val first = SubscriptionFormat.plan(AUTO, DEFAULT, false, "")
        assertEquals(listOf(LINKS, SING_BOX, CLASH) to emptyList<Int>(), first.formats())
        assertEquals(3, first.first.map { it.userAgent }.distinct().size)
        assertEquals(USER_AGENT, first.first.last().userAgent)
        // The stored profiles' request alone, the others only when it stops working.
        assertEquals(listOf(SING_BOX) to listOf(LINKS, CLASH), SubscriptionFormat.plan(AUTO, SING_BOX, true, "").formats())
        assertEquals(listOf(CLASH) to listOf(LINKS, SING_BOX), SubscriptionFormat.plan(AUTO, CLASH, true, "").formats())
        // Profiles from the default request continue with the same request under Auto.
        assertEquals(listOf(CLASH) to listOf(LINKS, SING_BOX), SubscriptionFormat.plan(AUTO, DEFAULT, true, "").formats())
        // Profiles from a custom User-Agent or a file, or an unknown value, compare afresh.
        for (stored in listOf(UNKNOWN, 37)) {
            assertEquals(listOf(LINKS, SING_BOX, CLASH) to emptyList<Int>(), SubscriptionFormat.plan(AUTO, stored, true, "").formats())
        }
    }

    @Test
    fun requestChangesTreatTheDefaultAndClashRequestAsOne() {
        assertFalse(SubscriptionFormat.requestChanged(DEFAULT, CLASH))
        assertFalse(SubscriptionFormat.requestChanged(CLASH, DEFAULT))
        assertFalse(SubscriptionFormat.requestChanged(LINKS, LINKS))
        assertTrue(SubscriptionFormat.requestChanged(CLASH, LINKS))
        assertTrue(SubscriptionFormat.requestChanged(DEFAULT, SING_BOX))
        assertTrue(SubscriptionFormat.requestChanged(LINKS, SING_BOX))
        // A custom User-Agent or a file is not a format's request; two of them cannot be told apart.
        assertTrue(SubscriptionFormat.requestChanged(UNKNOWN, DEFAULT))
        assertTrue(SubscriptionFormat.requestChanged(DEFAULT, UNKNOWN))
        assertFalse(SubscriptionFormat.requestChanged(UNKNOWN, UNKNOWN))
    }

    @Test
    fun rankingCountsImportableProfilesAndKeepsTheEarlierFormatOnTies() {
        val links = ImportPreview(mapOf("VLESS" to 2), emptyMap(), emptyList())
        assertTrue(SubscriptionFormat.better(links.copy(accepted = mapOf("VLESS" to 2, "Trojan" to 1)), links))
        assertFalse(SubscriptionFormat.better(links, links))
        // A response that leaves unsupported nodes out is no better than one listing and skipping them.
        val listedAndSkipped = links.copy(skipped = mapOf(Reason.UNPARSED to 2))
        assertFalse(SubscriptionFormat.better(links, listedAndSkipped))
        assertFalse(SubscriptionFormat.better(listedAndSkipped, links))
        assertFalse(SubscriptionFormat.better(links, links.copy(dropped = listOf(Section.DNS, Section.RULES))))
    }
}
