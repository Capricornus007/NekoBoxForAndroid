package io.nekohasekai.sagernet.ui

import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleStatusViewModelTest {
    @Before
    fun setup() {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun invalidIdsAndMissingProfileProduceFailedState() = runTest {
        for (id in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertFailedInitialization(id)
        }
    }

    @Test
    fun missingTailscaleBeanProducesFailedStateInsteadOfThrowingOnMain() = runTest {
        val id = ConfigBuilderTestEnv.io {
            SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = 1L, type = ProxyEntity.TYPE_TAILSCALE))
        }
        assertFailedInitialization(id)
    }

    @Test
    fun nonTailscaleProfileProducesFailedState() = runTest {
        val profile = ProxyEntity(groupId = 1L).putBean(SOCKSBean().apply { initializeDefaultValues() })
        val id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(profile) }
        assertFailedInitialization(id)
    }

    private suspend fun assertFailedInitialization(profileId: Long) {
        val model = TailscaleStatusViewModel(RuntimeEnvironment.getApplication())
        try {
            model.initialize(profileId)
            // IO uses a real dispatcher; keep the timeout off the virtual test clock too.
            val state = withContext(Dispatchers.Default) {
                withTimeout(5_000) { model.state.first { it.failed } }
            }
            assertTrue(state.failed)
            assertNull(model.session)
            assertEquals("", model.profileName)
            model.foreground()
            assertNull(model.session)
        } finally {
            model.viewModelScope.cancel()
        }
    }
}
