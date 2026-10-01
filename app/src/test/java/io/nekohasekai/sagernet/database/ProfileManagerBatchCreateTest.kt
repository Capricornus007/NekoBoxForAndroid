package io.nekohasekai.sagernet.database

import android.app.Application
import android.database.sqlite.SQLiteException
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileManagerBatchCreateTest {
    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun batchImportCommitsOncePreservesOrderAndRollsBackBeforeNotification() = runTest {
        withContext(Dispatchers.IO) {
            val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup())
            val first = ProxyEntity(groupId = groupId, userOrder = 30).putBean(bean("existing"))
            first.id = SagerDatabase.proxyDao.addProxy(first)
            val reloadSizes = mutableListOf<Long>()
            val progress = mutableListOf<Long>()
            val listener = object : GroupManager.Listener {
                override suspend fun groupAdd(group: ProxyGroup) = Unit
                override suspend fun groupRemoved(groupId: Long) = Unit
                override suspend fun groupUpdated(group: ProxyGroup) = Unit
                override suspend fun groupUpdated(groupId: Long) {
                    reloadSizes += SagerDatabase.proxyDao.countByGroup(groupId)
                }
                override suspend fun groupProgressUpdated(groupId: Long) {
                    progress += groupId
                }
            }
            GroupManager.addListener(listener)
            try {
                ProfileManager.createProfiles(groupId, listOf(bean("first"), bean("second")))
                val saved = SagerDatabase.proxyDao.getByGroup(groupId)
                assertEquals(listOf("existing", "first", "second"), saved.map { it.displayName() })
                assertEquals(listOf(30L, 31L, 32L), saved.map { it.userOrder })
                assertEquals(first.id, saved.first().id)
                assertEquals(3, saved.map { it.id }.toSet().size)
                assertEquals(listOf(3L), reloadSizes)

                ProfileManager.createProfiles(groupId, emptyList())
                GroupManager.postProgress(groupId)
                assertEquals(listOf(groupId), progress)
                assertEquals(listOf(3L), reloadSizes)

                val db = SagerDatabase.instance.openHelper.writableDatabase
                db.execSQL("CREATE TEMP TRIGGER reject_second_import BEFORE INSERT ON proxy_entities WHEN NEW.userOrder = 34 BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
                try {
                    val failure = runCatching {
                        ProfileManager.createProfiles(groupId, listOf(bean("rolled-back"), bean("rejected")))
                    }.exceptionOrNull()
                    assertTrue(failure is SQLiteException)
                } finally {
                    db.execSQL("DROP TRIGGER reject_second_import")
                }
                assertEquals(saved.map { it.id }, SagerDatabase.proxyDao.getIdsByGroup(groupId))
                assertEquals(listOf(3L), reloadSizes)
            } finally {
                GroupManager.removeListener(listener)
            }
        }
    }

    private fun bean(name: String) = SOCKSBean().apply {
        this.name = name
        serverAddress = "192.0.2.1"
        serverPort = 1080
        initializeDefaultValues()
    }
}
