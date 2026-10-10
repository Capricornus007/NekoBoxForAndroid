package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRecoveryTest {
    private val link = "https://panel.example:2096/sub/token"

    @Test
    fun backupLinksKeepTheApprovedOrderWithoutRepeatsOrUnusableEntries() {
        val stored = listOf(
            " https://b1.example/s ", link, "ftp://files.example/x", "", "https://b1.example/s",
            "http://cleartext.example/s", "not a link", "https://b3.example/s", "https://b4.example/s",
            "https://b5.example/s", "https://b6.example/s", "https://b7.example/s",
        ).joinToString("\n")
        // A backup never sends the subscription in the clear, even behind an HTTPS main link.
        assertEquals(
            listOf("https://b1.example/s", "https://b3.example/s", "https://b4.example/s", "https://b5.example/s", "https://b6.example/s"),
            SubscriptionRecovery.backupLinks(link, stored),
        )
        assertEquals(emptyList<String>(), SubscriptionRecovery.backupLinks(link, ""))
        assertFalse(SubscriptionRecovery.isHttpsLink("http://cleartext.example/s"))
        assertTrue(SubscriptionRecovery.isHttpsLink("HTTPS://upper.example/s"))
    }

    @Test
    fun providerProposalsAreHttpsOnlyAndNeverTheCurrentLink() {
        // new-url wins over new-domain; new-domain keeps path, query and port.
        assertEquals("https://new.example/s?t=1", SubscriptionRecovery.proposedLink(link, " https://new.example/s?t=1 ", "ignored.example"))
        assertEquals("https://moved.example:2096/sub/token", SubscriptionRecovery.proposedLink(link, null, "moved.example"))
        assertEquals("https://moved.example/sub", SubscriptionRecovery.proposedLink("http://panel.example/sub", "", "moved.example"))
        assertNull(SubscriptionRecovery.proposedLink(link, "http://cleartext.example/s", null))
        assertNull(SubscriptionRecovery.proposedLink(link, link, null))
        assertNull(SubscriptionRecovery.proposedLink(link, null, "panel.example"))
        assertNull(SubscriptionRecovery.proposedLink(link, null, "bad domain/x"))
        assertNull(SubscriptionRecovery.proposedLink(link, null, null))
        // A local file has no address to replace.
        assertNull(SubscriptionRecovery.proposedLink("content://files/list.txt", "https://new.example/s", null))
        assertEquals("https://backup.example/s", SubscriptionRecovery.offer(link, "https://backup.example/s"))
        assertNull(SubscriptionRecovery.offer(link, "javascript:alert(1)"))
        assertNull(SubscriptionRecovery.offer(link, "https://backup.example/\u0000"))
        assertNull(SubscriptionRecovery.offer(link, "https://" + "a".repeat(2100) + ".example/"))
    }
}
