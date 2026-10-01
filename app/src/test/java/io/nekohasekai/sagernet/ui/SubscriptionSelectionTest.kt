package io.nekohasekai.sagernet.ui

import android.app.Application
import android.content.ComponentName
import android.view.View
import android.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionSelectionTest {
    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).declareComponentUnbindable(
            ComponentName(application, "androidx.room.MultiInstanceInvalidationService"),
        )
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun queuedUpdateKeepsTheGroupSelectedAtTheTap() = verifyQueuedUpdate(hasSelection = true)

    @Test
    fun earlyUpdateUsesDefaultGroupWithoutFollowingALaterSelection() = verifyQueuedUpdate(hasSelection = false)

    private fun verifyQueuedUpdate(hasSelection: Boolean) = runBlocking {
        val groups = withContext(Dispatchers.IO) {
            List(2) { index ->
                val group = ProxyGroup(
                    userOrder = index + 1L,
                    type = GroupType.SUBSCRIPTION,
                    subscription = SubscriptionBean().applyDefaultValues().apply {
                        link = "http://subscription.invalid/list"
                    },
                )
                SagerDatabase.groupDao.createGroup(group)
            }
        }
        if (hasSelection) {
            DataStore.selectedGroup = groups[0]
        } else {
            DataStore.configurationStore.remove(Key.PROFILE_GROUP)
        }
        DataStore.configurationStore.awaitWrites()
        val previousState = DataStore.serviceState
        val previousInterface = GroupManager.userInterface
        DataStore.serviceState = BaseService.State.Stopped
        // With no confirmation UI, the HTTP update stops before any network request.
        GroupManager.userInterface = null
        val updated = CompletableDeferred<Long>()
        val listener = object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(groupId: Long) = Unit
            override suspend fun groupUpdated(group: ProxyGroup) {
                updated.complete(group.id)
            }
        }
        GroupManager.addListener(listener)
        val fragment = ConfigurationFragment()
        val lifecycle = fragment.lifecycle as LifecycleRegistry
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val application = RuntimeEnvironment.getApplication()
        val item = PopupMenu(application, View(application)).menu.add(0, R.id.action_update_subscription, 0, "Update")
        val workerCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val ready = CountDownLatch(workerCount)
        val release = CountDownLatch(1)
        // Hold Default workers so the tab changes before the update coroutine starts.
        val blockers = List(workerCount) {
            launch(Dispatchers.Default) {
                ready.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue("Default workers did not start", ready.await(5, TimeUnit.SECONDS))
            fragment.onMenuItemClick(item)
            DataStore.selectedGroup = groups[1]
            release.countDown()
            assertEquals(groups[0], withTimeout(5_000) { updated.await() })
        } finally {
            release.countDown()
            blockers.joinAll()
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            GroupManager.removeListener(listener)
            GroupManager.userInterface = previousInterface
            DataStore.serviceState = previousState
        }
    }
}
