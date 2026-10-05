package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TailscaleProfileStoreTest {
    @Before
    fun reset() = ConfigBuilderTestEnv.reset()

    private fun node() = ProxyEntity(groupId = 12, tx = 100, rx = 200, userOrder = 7).putBean(
        TailscaleBean().apply {
            initializeDefaultValues()
            name = "node"
            hostname = "host"
            exitNode = "100.64.0.1"
            acceptRoutes = true
        },
    ).also { it.id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(it) } }

    @Test
    fun exitCasPreservesMetadataBeanFieldsAndOtherRows() = ConfigBuilderTestEnv.io {
        val a = node()
        val b = node()
        val saved = TailscaleProfileStore.compareAndSetExit(a.id, a.uuid, "100.64.0.1", "100.64.0.2")
        assertEquals(a.copy(tailscaleBean = saved.tailscaleBean), saved)
        assertEquals("host", saved.tailscaleBean!!.hostname)
        assertEquals(true, saved.tailscaleBean!!.acceptRoutes)
        assertEquals("100.64.0.1", TailscaleProfileStore.read(b.id).tailscaleBean!!.exitNode)
    }

    @Test
    fun competingChoiceAndIdentityMismatchDoNotOverwrite() = ConfigBuilderTestEnv.io {
        val a = node()
        TailscaleProfileStore.compareAndSetExit(a.id, a.uuid, "100.64.0.1", "100.64.0.2")
        assertEquals(
            "tailscale:conflict",
            runCatching {
                TailscaleProfileStore.compareAndSetExit(a.id, a.uuid, "100.64.0.1", "100.64.0.3")
            }.exceptionOrNull()?.message,
        )
        assertEquals(
            "tailscale:conflict",
            runCatching {
                TailscaleProfileStore.compareAndSetExit(a.id, "replaced", "100.64.0.2", "")
            }.exceptionOrNull()?.message,
        )
        assertEquals("100.64.0.2", TailscaleProfileStore.read(a.id).tailscaleBean!!.exitNode)
    }

    @Test
    fun concurrentChoicesCannotBothCommitAgainstTheSameSelection() = runBlocking {
        val a = node()
        val begin = CompletableDeferred<Unit>()
        val choices = listOf("100.64.0.2", "100.64.0.3").map { value ->
            async(Dispatchers.IO) {
                begin.await()
                runCatching { TailscaleProfileStore.compareAndSetExit(a.id, a.uuid, "100.64.0.1", value) }
            }
        }
        begin.complete(Unit)
        val results = choices.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals("tailscale:conflict", results.single { it.isFailure }.exceptionOrNull()?.message)
        val winner = results.single { it.isSuccess }.getOrThrow().tailscaleBean!!.exitNode
        assertEquals(winner, ConfigBuilderTestEnv.io { TailscaleProfileStore.read(a.id).tailscaleBean!!.exitNode })
    }

    @Test
    fun staleWholeRowPreservesLatestExitAndIdentity() = ConfigBuilderTestEnv.io {
        val a = node()
        TailscaleProfileStore.compareAndSetExit(a.id, a.uuid, "100.64.0.1", "")
        SagerDatabase.proxyDao.setTailscaleMarker(a.id, "fresh")
        SagerDatabase.proxyDao.updateProxy(a)
        val saved = TailscaleProfileStore.read(a.id)
        assertEquals("fresh", saved.uuid)
        assertEquals("", saved.tailscaleBean!!.exitNode)
    }

    @Test
    fun editorKeepsConcurrentExitUnlessExplicitlyEdited() = ConfigBuilderTestEnv.io {
        val a = node()
        val draft = a.tailscaleBean!!.clone().apply { hostname = "draft-host" }
        TailscaleProfileStore.compareAndSetExit(a.id, a.uuid, "100.64.0.1", "100.64.0.2")
        val saved = TailscaleProfileStore.saveEditor(a.id, a.uuid, "100.64.0.1", draft, false)
        assertEquals("100.64.0.2", saved.tailscaleBean!!.exitNode)
        assertEquals("draft-host", saved.tailscaleBean!!.hostname)
        assertEquals(a.tx, saved.tx)
        assertEquals(
            "tailscale:conflict",
            runCatching {
                TailscaleProfileStore.saveEditor(a.id, a.uuid, "100.64.0.1", draft, true)
            }.exceptionOrNull()?.message,
        )
        assertEquals("100.64.0.1", draft.exitNode)
        val cleared = TailscaleProfileStore.saveEditor(
            a.id,
            a.uuid,
            "100.64.0.2",
            draft.clone().apply { exitNode = "" },
            true,
        )
        assertEquals("", cleared.tailscaleBean!!.exitNode)
    }

    @Test
    fun liveCasRechecksRuntimeConfigurationInTransaction() = ConfigBuilderTestEnv.io {
        val a = node()
        val draft = a.tailscaleBean!!.clone().apply { controlUrl = "https://control.example" }
        TailscaleProfileStore.saveEditor(a.id, a.uuid, "100.64.0.1", draft, false)
        assertEquals(
            "tailscale:conflict",
            runCatching {
                TailscaleProfileStore.compareAndSetRuntimeExit(a.id, a.uuid, "100.64.0.1", "", a.tailscaleBean)
            }.exceptionOrNull()?.message,
        )
        assertEquals("100.64.0.1", TailscaleProfileStore.read(a.id).tailscaleBean!!.exitNode)
    }
}
