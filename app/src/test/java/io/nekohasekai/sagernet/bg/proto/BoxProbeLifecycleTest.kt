package io.nekohasekai.sagernet.bg.proto

import android.app.Application
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.acquireTailscaleState
import io.nekohasekai.sagernet.fmt.tailscale.pruneTailscaleState
import io.nekohasekai.sagernet.fmt.tailscale.resetTailscaleIdentity
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleStateFile
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoxProbeLifecycleTest {
    @Before
    fun setup() {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Idle
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cancelledSynchronousInitializationIsJoinedBeforeCloseAndLeaseRelease() = runBlocking {
        val node = node()
        val release = CountDownLatch(1)
        val instance = Probe(node, prepared(node), releaseInit = release)
        val caller = async(Dispatchers.Default) { instance.runProbe { 1 } }
        try {
            await(instance.initEntered)
            caller.cancel()
            assertFalse(caller.isCompleted)
            assertEquals(0, instance.closes.get())
            assertNotNull(runCatching { acquireTailscaleState(listOf(node.id)) }.exceptionOrNull())
        } finally {
            release.countDown()
            caller.join()
        }
        assertTrue(instance.published.get())
        assertFalse(instance.launched.get())
        assertEquals(1, instance.closes.get())
        acquireTailscaleState(listOf(node.id)).use { }
    }

    @Test
    fun successfulResultCannotCompleteOrStartNextProbeBeforeCloseFinishes() = runBlocking {
        val node = node()
        val release = CountDownLatch(1)
        val instance = Probe(node, prepared(node), releaseClose = release)
        val caller = async(Dispatchers.Default) { instance.runProbe { 7 } }
        try {
            await(instance.closeEntered)
            assertFalse(caller.isCompleted)
            assertNotNull(runCatching { acquireTailscaleState(listOf(node.id)) }.exceptionOrNull())
        } finally {
            release.countDown()
        }
        assertEquals(7, caller.await())
        val next = Probe(node, prepared(node))
        assertEquals(8, next.runProbe { 8 })
        assertEquals(1, instance.closes.get())
    }

    @Test
    fun resetAfterConfigPreparationRejectsStaleSnapshotBeforeNativeConstruction() = runBlocking {
        val node = node()
        val instance = Probe(node, prepared(node))
        ConfigBuilderTestEnv.io { resetTailscaleIdentity(node.id) }
        assertNotNull(runCatching { instance.runProbe { 1 } }.exceptionOrNull())
        assertFalse(instance.published.get())
        acquireTailscaleState(listOf(node.id)).use { }
    }

    @Test
    fun sidecarListenerMustBeReadyBeforeQueryAndPoolIsInitializedBeforeLaunch() = runBlocking {
        val node = node()
        val port = ServerSocket(0).use { it.localPort }
        val instance = Probe(node, prepared(node, port), external = true)
        val queried = AtomicBoolean(false)
        val caller = async(Dispatchers.Default) {
            instance.runProbe {
                queried.set(true)
                9
            }
        }
        await(instance.launchEntered)
        assertFalse(queried.get())
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use {
            assertEquals(9, caller.await())
        }
        assertEquals(1, instance.closes.get())
    }

    @Test
    fun sidecarFailurePropagatesOnlyAfterCleanup() = runBlocking {
        val node = node()
        val port = ServerSocket(0).use { it.localPort }
        val release = CountDownLatch(1)
        val instance = Probe(node, prepared(node, port), releaseClose = release, external = true)
        val failure = IOException("sidecar failed")
        val caller = async(Dispatchers.Default) { runCatching { instance.runProbe { error("query before ready") } } }
        try {
            await(instance.launchEntered)
            @Suppress("UNCHECKED_CAST")
            val onFatal = GuardedProcessPool::class.java.getDeclaredField("onFatal").apply { isAccessible = true }
                .get(instance.processes) as suspend (IOException) -> Unit
            onFatal(failure)
            await(instance.closeEntered)
            assertFalse(caller.isCompleted)
            assertNotNull(runCatching { acquireTailscaleState(listOf(node.id)) }.exceptionOrNull())
        } finally {
            release.countDown()
        }
        val thrown = caller.await().exceptionOrNull()
        assertTrue(generateSequence(thrown) { it.cause }.any { it === failure || it.message == failure.message })
        acquireTailscaleState(listOf(node.id)).use { }
    }

    @Test
    fun preparedGroupFrontAndLandingNodesAreBothLeased() = runBlocking {
        val front = node()
        val landing = node()
        val root = ProxyEntity(groupId = 2).putBean(
            SOCKSBean().apply {
                serverAddress = "192.0.2.1"
                serverPort = 1080
                initializeDefaultValues()
            },
        )
        val config = ConfigBuilderTestEnv.io {
            SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 2, frontProxy = front.id, landingProxy = landing.id)))
            root.id = SagerDatabase.proxyDao.addProxy(root)
            buildConfig(root, forTest = true)
        }
        assertEquals(setOf(front.id, landing.id), config.tailscaleEndpoints.keys)
        assertEquals(config.tailscaleEndpoints.keys, config.profileTailscaleNodes[root.id])
        Probe(root, config).runProbe {
            for (id in config.tailscaleEndpoints.keys) {
                assertNotNull(runCatching { acquireTailscaleState(listOf(id)) }.exceptionOrNull())
            }
        }
        acquireTailscaleState(config.tailscaleEndpoints.keys).use { }
    }

    @Test
    fun closePrunesOnlyItsOwnDeletedEndpointsWithoutScanningOtherStateDirectories() = runBlocking {
        val node = node()
        val own = tailscaleStateFile(node.id).apply { mkdirs() }.resolve("state")
        own.writeText("owned")
        val unrelated = tailscaleStateFile(node.id + 100_000).apply { mkdirs() }.resolve("state")
        unrelated.writeText("unrelated")
        val stray = unrelated.parentFile!!.parentFile!!.resolve("unrelated-stray").apply { mkdirs() }
        try {
            Probe(node, prepared(node)).runProbe {
                SagerDatabase.proxyDao.deleteById(node.id)
            }
            assertFalse(own.parentFile!!.exists())
            assertEquals("unrelated", unrelated.readText())
            assertTrue(stray.isDirectory)
        } finally {
            unrelated.parentFile!!.deleteRecursively()
            stray.deleteRecursively()
        }
    }

    @Test
    fun handledLockFailurePreservesProbeOutcomesAndStateWithoutCleanupWarning() = runBlocking {
        val node = node()
        val state = tailscaleStateFile(node.id).apply { mkdirs() }
        val credentials = state.resolve("state").apply { writeText("retained") }
        // No lease/native instance is opened by this fixture. Obstruct only its unused lock path.
        val lockPath = File(SagerNet.application.noBackupFilesDir, "tailscale-locks/${node.id}.lock")
        lockPath.parentFile!!.mkdirs()
        assertTrue(lockPath.mkdir())
        val messages = mutableListOf<String>()
        val previousSink = Logs.sink
        Logs.sink = { messages += it }
        try {
            assertEquals(17, cleanupOnlyProbe(node).runProbe { 17 })
            assertEquals("retained", credentials.readText())
            val failure = IOException("original query failure")
            val thrown = runCatching { cleanupOnlyProbe(node).runProbe { throw failure } }.exceptionOrNull()
            assertTrue(generateSequence(thrown) { it.cause }.any { it === failure || it.message == failure.message })
            assertTrue(messages.isEmpty())
            assertEquals("retained", credentials.readText())
        } finally {
            Logs.sink = previousSink
            lockPath.deleteRecursively()
            state.deleteRecursively()
        }
    }

    @Test
    fun closeFailureIsTheOutcomeOnlyAfterASuccessfulQuery() = runBlocking {
        val node = node()
        fun failingClose() = object : BoxInstance(node) {
            override suspend fun init() {
                config = prepared(node)
            }

            override fun launch() = Unit

            override fun close() {
                super.close()
                throw IOException("close failed")
            }
        }
        assertEquals("close failed", runCatching { failingClose().runProbe { 1 } }.exceptionOrNull()?.message)
        val thrown = runCatching { failingClose().runProbe { error("start failed") } }.exceptionOrNull()
        assertTrue(generateSequence(thrown) { it.cause }.any { it.message == "start failed" })
    }

    @Test
    fun propagatedPruneFailurePreservesSuccessfulResultAndOriginalQueryError() = runBlocking {
        val node = node()
        val state = tailscaleStateFile(node.id).apply { mkdirs() }
        val credentials = state.resolve("state").apply { writeText("retained") }
        val cleanupFailure = IOException("candidate enumeration failed")
        val attempts = AtomicInteger()
        // Candidate enumeration precedes the per-node acquisition catch in prune.
        val ids = object : AbstractSet<Long>() {
            override val size = 1
            override fun iterator(): Iterator<Long> {
                attempts.incrementAndGet()
                throw cleanupFailure
            }
        }
        val endpoints = object : Map<Long, ConfigBuildResult.TailscaleEndpoint> by prepared(node).tailscaleEndpoints {
            override val keys: Set<Long> = ids
        }
        val config = prepared(node, endpoints = endpoints)
        val messages = mutableListOf<String>()
        val previousSink = Logs.sink
        Logs.sink = { messages += it }
        try {
            assertSame(cleanupFailure, runCatching { pruneTailscaleState(profileIds = ids) }.exceptionOrNull())
            attempts.set(0)
            assertEquals(17, cleanupOnlyProbe(node, config).runProbe { 17 })
            assertEquals("retained", credentials.readText())
            val failure = IOException("original query failure")
            val thrown = runCatching { cleanupOnlyProbe(node, config).runProbe { throw failure } }.exceptionOrNull()
            assertTrue(generateSequence(thrown) { it.cause }.any { it === failure || it.message == failure.message })
            assertEquals(2, attempts.get())
            assertEquals(2, messages.size)
            assertTrue(messages.all { it.endsWith("Tailscale state cleanup deferred") })
            assertTrue(messages.none { it.contains(cleanupFailure.message!!) })
            assertEquals("retained", credentials.readText())
        } finally {
            Logs.sink = previousSink
            state.deleteRecursively()
        }
    }

    @Test
    fun bestEffortPruneGuardDoesNotSuppressLeaseReleaseFailure() {
        val node = node()
        val instance = cleanupOnlyProbe(node).apply { config = prepared(node) }
        val failure = IOException("lease release failed")
        BoxInstance::class.java.getDeclaredField("stateLease").apply {
            isAccessible = true
            set(instance, Closeable { throw failure })
        }
        assertSame(failure, runCatching { instance.close() }.exceptionOrNull())
    }

    @Test
    fun testAndPeerInstancesReuseTheExactPreparedConfig() {
        val node = node()
        val config = prepared(node)
        val instances =
            listOf(TestInstance(node, "https://example.invalid", 1_000, config), TailscalePeersInstance(node, config))
        for (instance in instances) {
            instance.javaClass.getDeclaredMethod("buildConfig").apply { isAccessible = true }.invoke(instance)
            assertSame(config, instance.config)
        }
    }

    private fun cleanupOnlyProbe(node: ProxyEntity, preparedConfig: ConfigBuildResult = prepared(node)) = object : BoxInstance(node) {
        override suspend fun init() {
            config = preparedConfig
        }

        override fun launch() = Unit
    }

    private fun node() = ProxyEntity(groupId = 1).putBean(TailscaleBean().apply { initializeDefaultValues() }).also {
        it.id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(it) }
    }

    private fun prepared(
        node: ProxyEntity,
        port: Int? = null,
        endpoints: Map<Long, ConfigBuildResult.TailscaleEndpoint> =
            mapOf(node.id to ConfigBuildResult.TailscaleEndpoint("node", false)),
    ) = ConfigBuildResult(
        config = "{}",
        externalIndex = if (port == null) {
            emptyList()
        } else {
            listOf(
                ConfigBuildResult.IndexEntity(linkedMapOf(port to node)),
            )
        },
        mainEntId = node.id,
        trafficMap = mapOf("node" to listOf(node)),
        profileTagMap = mapOf(node.id to "node"),
        selectorGroupId = -1,
        tailscaleEndpoints = endpoints,
    )

    private fun await(latch: CountDownLatch) = assertTrue("Barrier timed out", latch.await(10, TimeUnit.SECONDS))

    private class Probe(
        node: ProxyEntity,
        private val prepared: ConfigBuildResult,
        private val releaseInit: CountDownLatch = CountDownLatch(0),
        private val releaseClose: CountDownLatch = CountDownLatch(0),
        private val external: Boolean = false,
    ) : BoxInstance(node) {
        val initEntered = CountDownLatch(1)
        val launchEntered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val launched = AtomicBoolean(false)
        val closes = AtomicInteger()

        override fun buildConfig() {
            config = prepared
        }

        override suspend fun loadConfig() {
            initEntered.countDown()
            check(releaseInit.await(10, TimeUnit.SECONDS))
            published.set(true)
        }

        override fun launch() {
            check(processes.processCount == 0)
            if (external) processes.processCount = 1
            launched.set(true)
            launchEntered.countDown()
        }

        override fun close() {
            closes.incrementAndGet()
            closeEntered.countDown()
            try {
                check(releaseClose.await(10, TimeUnit.SECONDS))
            } finally {
                super.close()
            }
        }
    }
}
