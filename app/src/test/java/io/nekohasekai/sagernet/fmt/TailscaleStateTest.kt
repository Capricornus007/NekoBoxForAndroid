package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.pruneTailscaleState
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleStateFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TailscaleStateTest {

    @Test
    fun pruneKeepsLiveNodesAndRemovesOrphans() {
        ConfigBuilderTestEnv.reset()
        // Shared static state; another test may have left the service marked as running.
        DataStore.serviceState = BaseService.State.Idle
        val node = ProxyEntity(groupId = 1L).putBean(TailscaleBean().apply { initializeDefaultValues() })
        node.id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(node) }
        val live = tailscaleStateFile(node.id).apply {
            mkdirs()
            resolve("tailscaled.state").writeText("x")
        }
        val orphan = tailscaleStateFile(node.id + 100).apply {
            mkdirs()
            resolve("tailscaled.state").writeText("x")
        }
        val stray = live.parentFile!!.resolve("not-an-id").apply { mkdirs() }

        ConfigBuilderTestEnv.io { pruneTailscaleState() }

        assertTrue(live.resolve("tailscaled.state").isFile)
        assertFalse(orphan.exists())
        assertFalse(stray.exists())

        // A restore passes the ids whose profile content survived; everything else goes.
        val replaced = tailscaleStateFile(node.id + 1).apply { mkdirs() }
        ConfigBuilderTestEnv.io { pruneTailscaleState(keep = setOf(node.id)) }
        assertTrue(live.exists())
        assertFalse(replaced.exists())

        // Nothing is pruned while the service runs: the node may still be writing its state.
        ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.deleteById(node.id) }
        DataStore.serviceState = BaseService.State.Connected
        ConfigBuilderTestEnv.io { pruneTailscaleState() }
        assertTrue(live.exists())
        DataStore.serviceState = BaseService.State.Idle
        ConfigBuilderTestEnv.io { pruneTailscaleState() }
        assertFalse(live.exists())
    }
}
