package io.nekohasekai.sagernet.ui.profile

import android.app.Application
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.TailscaleProfileStore
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TailscaleEditorBaselineTest {
    @Test fun cleanExitRefreshDoesNotBecomeAnEdit() {
        val baseline = TailscaleEditorBaseline("identity", "100.64.0.2")
        val refreshed = baseline.refreshed("identity", "100.64.0.3")
        assertEquals("100.64.0.3", refreshed.exit)
        assertFalse(refreshed.exitEdited)
        assertEquals(baseline, baseline.refreshed("other-identity", "100.64.0.3"))
        val edited = baseline.copy(exitEdited = true)
        assertEquals(edited, edited.refreshed("identity", "100.64.0.3"))
    }

    @Test fun unrelatedDraftSaveKeepsExitCommittedWhileEditorWasOpen() = ConfigBuilderTestEnv.io {
        val original = setup()
        val draft = original.tailscaleBean!!.clone().apply {
            name = "unsaved name"
            acceptRoutes = true
        }
        TailscaleProfileStore.compareAndSetExit(original.id, original.uuid, "100.64.0.2", "100.64.0.3")
        val saved = TailscaleProfileStore.saveEditor(original.id, original.uuid, "100.64.0.2", draft, false)
        assertEquals("100.64.0.3", saved.tailscaleBean!!.exitNode)
        assertEquals("unsaved name", saved.tailscaleBean!!.name)
        assertEquals(true, saved.tailscaleBean!!.acceptRoutes)
        assertEquals("100.64.0.2", draft.exitNode)
    }

    @Test fun manualExitEditConflictsWithoutDiscardingOtherDrafts() = ConfigBuilderTestEnv.io {
        val original = setup()
        val draft = original.tailscaleBean!!.clone().apply {
            name = "keep this draft"
            exitNode = "100.64.0.4"
        }
        TailscaleProfileStore.compareAndSetExit(original.id, original.uuid, "100.64.0.2", "100.64.0.3")
        val failure = runCatching {
            TailscaleProfileStore.saveEditor(original.id, original.uuid, "100.64.0.2", draft, true)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("tailscale:conflict"))
        assertEquals("keep this draft", draft.name)
        assertEquals("100.64.0.4", draft.exitNode)
        val current = SagerDatabase.proxyDao.getById(original.id)!!
        assertEquals("100.64.0.3", current.tailscaleBean!!.exitNode)
        assertEquals("original", current.tailscaleBean!!.name)
    }

    @Test fun deliberateManualClearSavesWhenBaselineStillMatches() = ConfigBuilderTestEnv.io {
        val original = setup()
        val draft = original.tailscaleBean!!.clone().apply { exitNode = "" }
        val saved = TailscaleProfileStore.saveEditor(original.id, original.uuid, "100.64.0.2", draft, true)
        assertEquals("", saved.tailscaleBean!!.exitNode)
    }

    @Test fun identityChangeRejectsEvenCleanExitWithoutReplacingDraft() = ConfigBuilderTestEnv.io {
        val original = setup()
        val draft = original.tailscaleBean!!.clone().apply { name = "draft" }
        SagerDatabase.proxyDao.setTailscaleMarker(original.id, "replacement")
        val failure = runCatching {
            TailscaleProfileStore.saveEditor(original.id, original.uuid, "100.64.0.2", draft, false)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("draft", draft.name)
        assertEquals("replacement", SagerDatabase.proxyDao.getById(original.id)!!.uuid)
    }

    private fun setup(): ProxyEntity {
        ConfigBuilderTestEnv.reset()
        val entity = ProxyEntity(id = 42, groupId = 1).putBean(
            TailscaleBean().apply {
                initializeDefaultValues()
                name = "original"
                exitNode = "100.64.0.2"
            },
        )
        SagerDatabase.proxyDao.addProxy(entity)
        return SagerDatabase.proxyDao.getById(entity.id)!!
    }
}
