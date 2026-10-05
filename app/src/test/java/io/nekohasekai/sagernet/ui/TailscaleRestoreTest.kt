package io.nekohasekai.sagernet.ui

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.acquireTailscaleState
import io.nekohasekai.sagernet.fmt.tailscale.recoverTailscaleRestore
import io.nekohasekai.sagernet.fmt.tailscale.resetTailscaleIdentity
import io.nekohasekai.sagernet.fmt.tailscale.stageTailscaleRestore
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleRestoreDirectory
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleStateFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TailscaleRestoreTest {
    @Test
    fun matchingNonemptyMarkerAndCompatibleContentPreserveLocalState() {
        val local = setup()
        val restored = local.copy(tailscaleBean = local.tailscaleBean!!.clone().apply { name = "renamed" })
        restore(restored)
        assertTrue(tailscaleStateFile(local.id).exists())
        assertEquals(local.uuid, stored(local.id).uuid)
    }

    @Test
    fun foreignBlankOrIncompatibleContentRequireNewIdentityAndFreshLocalMarker() {
        for (kind in listOf("foreign", "blank", "incompatible", "legacy-local")) {
            val local = setup()
            val restored = local.copy(tailscaleBean = local.tailscaleBean!!.clone())
            when (kind) {
                "foreign" -> restored.uuid = "foreign-marker"

                "blank" -> restored.uuid = ""

                "incompatible" -> restored.tailscaleBean!!.hostname = "different-host"

                "legacy-local" -> {
                    ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.setTailscaleMarker(local.id, "") }
                    restored.uuid = ""
                }
            }
            val importedMarker = restored.uuid
            restore(restored)
            assertFalse("$kind must remove local credentials", tailscaleStateFile(local.id).exists())
            val marker = stored(local.id).uuid
            assertTrue(marker.isNotBlank())
            assertNotEquals(importedMarker, marker)
            assertNotEquals(local.uuid, marker)
        }
    }

    @Test
    fun duplicateProfilesRollBackRowsMarkersAndExactCredentialFiles() {
        val local = setup()
        val rows = databaseRows()
        val files = credentialFiles(local.id)
        val foreign = local.copy(uuid = "foreign")
        val failure = runCatching {
            replace(listOf(foreign, foreign.copy()), listOf(ProxyGroup(id = 1, name = "replacement")))
        }.exceptionOrNull()
        assertTrue("Expected duplicate profile ID constraint failure", failure is SQLiteConstraintException)
        assertEquals(rows, databaseRows())
        assertEquals(local.uuid, stored(local.id).uuid)
        assertEquals(files, credentialFiles(local.id))
        assertFalse(tailscaleRestoreDirectory(local.id).exists())
    }

    @Test
    fun duplicateGroupsRollBackRowsMarkersAndExactCredentialFiles() {
        val local = setup()
        val rows = databaseRows()
        val files = credentialFiles(local.id)
        val failure = runCatching {
            replace(listOf(local.copy(uuid = "foreign")), listOf(ProxyGroup(id = 1), ProxyGroup(id = 1)))
        }.exceptionOrNull()
        assertTrue("Expected duplicate group ID constraint failure", failure is SQLiteConstraintException)
        assertEquals(rows, databaseRows())
        assertEquals(local.uuid, stored(local.id).uuid)
        assertEquals(files, credentialFiles(local.id))
        assertFalse(tailscaleRestoreDirectory(local.id).exists())
    }

    @Test
    fun emptyOrIncompleteCreationBookkeepingDoesNotBlockExistingIdentity() {
        for (metadata in listOf(null, "", "{\"version\":")) {
            val local = setup()
            val rows = databaseRows()
            val files = credentialFiles(local.id)
            val staging = tailscaleRestoreDirectory(local.id).apply { assertTrue(mkdirs()) }
            metadata?.let { staging.resolve("metadata.json").writeText(it) }
            repeat(2) {
                ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() }
                assertFalse(staging.exists())
                assertEquals(rows, databaseRows())
                assertEquals(local.uuid, stored(local.id).uuid)
                assertEquals(files, credentialFiles(local.id))
            }
        }
    }

    @Test
    fun emptyBookkeepingAfterRollbackCleanupPreservesRestoredCredentials() {
        val local = setup()
        val rows = databaseRows()
        val files = credentialFiles(local.id)
        val staging = tailscaleRestoreDirectory(local.id)
        acquireTailscaleState(listOf(local.id)).use {
            stageTailscaleRestore(local.id, local.uuid, "replacement")
            assertTrue(staging.resolve("state").renameTo(tailscaleStateFile(local.id)))
            assertTrue(staging.resolve("metadata.json").delete())
        }
        repeat(2) {
            ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() }
            assertFalse(staging.exists())
            assertEquals(rows, databaseRows())
            assertEquals(local.uuid, stored(local.id).uuid)
            assertEquals(files, credentialFiles(local.id))
        }
    }

    @Test
    fun emptyOrMetadataOnlyBookkeepingAfterRetirementCannotReviveOldIdentity() {
        for (deleted in listOf(false, true)) {
            for (metadataRemains in listOf(false, true)) {
                val local = setup()
                val staging = tailscaleRestoreDirectory(local.id)
                ConfigBuilderTestEnv.io {
                    acquireTailscaleState(listOf(local.id)).use {
                        stageTailscaleRestore(local.id, local.uuid, if (deleted) null else "replacement")
                        if (deleted) {
                            SagerDatabase.proxyDao.deleteById(
                                local.id,
                            )
                        } else {
                            SagerDatabase.proxyDao.setTailscaleMarker(local.id, "replacement")
                        }
                        assertTrue(staging.resolve("state").deleteRecursively())
                        if (!metadataRemains) assertTrue(staging.resolve("metadata.json").delete())
                    }
                }
                val rows = databaseRows()
                repeat(2) {
                    ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() }
                    assertFalse(staging.exists())
                    assertFalse(tailscaleStateFile(local.id).exists())
                    assertEquals(rows, databaseRows())
                    if (!deleted) assertEquals("replacement", stored(local.id).uuid)
                }
            }
        }
    }

    @Test
    fun unexpectedBookkeepingEntriesArePreservedWithoutChangingActiveIdentity() {
        for (name in listOf("unexpected", "metadata.json")) {
            val local = setup()
            val rows = databaseRows()
            val files = credentialFiles(local.id)
            val staging = tailscaleRestoreDirectory(local.id).apply { assertTrue(mkdirs()) }
            staging.resolve(name).apply { mkdirs() }.resolve("preserve").writeBytes(byteArrayOf(0, -1, 42))
            val before = fileTree(staging)
            try {
                val failure = ConfigBuilderTestEnv.io {
                    runCatching { acquireTailscaleState(listOf(local.id)).close() }.exceptionOrNull()
                }
                assertNotNull(failure)
                assertEquals(before, fileTree(staging))
                assertEquals(rows, databaseRows())
                assertEquals(files, credentialFiles(local.id))
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    @Test
    fun recoveryRejectsLinkedBookkeepingWithoutFollowingTargets() {
        for (entry in listOf("metadata.json", "state", "staging", "dangling")) {
            val local = setup()
            val rows = databaseRows()
            val files = credentialFiles(local.id)
            val staging = tailscaleRestoreDirectory(local.id)
            staging.parentFile!!.mkdirs()
            val link = if (entry == "staging" || entry == "dangling") {
                staging
            } else {
                assertTrue(staging.mkdir())
                staging.resolve(entry)
            }
            val target = when (entry) {
                "metadata.json" -> tailscaleStateFile(local.id).resolve("tailscaled.state")
                "dangling" -> staging.parentFile!!.resolve("absent-target")
                else -> tailscaleStateFile(local.id)
            }
            Files.createSymbolicLink(link.toPath(), target.toPath())
            try {
                val failure = ConfigBuilderTestEnv.io {
                    runCatching { acquireTailscaleState(listOf(local.id)).close() }.exceptionOrNull()
                }
                assertNotNull(failure)
                assertTrue(Files.isSymbolicLink(link.toPath()))
                assertEquals(rows, databaseRows())
                assertEquals(files, credentialFiles(local.id))
            } finally {
                Files.delete(link.toPath())
                if (link != staging) assertTrue(staging.delete())
            }
        }
    }

    @Test
    fun interruptionBeforeCommitRecoversOriginalIncludingLegacyBlankMarker() {
        for (legacy in listOf(false, true)) {
            val local = setup()
            if (legacy) ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.setTailscaleMarker(local.id, "") }
            val rows = databaseRows()
            val files = credentialFiles(local.id)
            val original = stored(local.id).uuid
            acquireTailscaleState(listOf(local.id)).use {
                stageTailscaleRestore(local.id, original, "replacement")
            }
            assertFalse(tailscaleStateFile(local.id).exists())
            assertTrue(tailscaleRestoreDirectory(local.id).resolve("state").exists())
            ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() }
            assertEquals(rows, databaseRows())
            assertEquals(files, credentialFiles(local.id))
            assertFalse(tailscaleRestoreDirectory(local.id).exists())
        }
    }

    @Test
    fun interruptionWithMetadataButOriginalDirectoryStillActivePreservesExactBytes() {
        val local = setup()
        val files = credentialFiles(local.id)
        acquireTailscaleState(listOf(local.id)).use {
            stageTailscaleRestore(local.id, local.uuid, "replacement")
            // Same layout as interruption after metadata write but before the staging rename,
            // or after the rollback rename but before metadata cleanup.
            assertTrue(tailscaleRestoreDirectory(local.id).resolve("state").renameTo(tailscaleStateFile(local.id)))
        }
        ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() }
        assertEquals(files, credentialFiles(local.id))
        assertFalse(tailscaleRestoreDirectory(local.id).exists())
    }

    @Test
    fun unknownCommittedMarkerFailsClosedWithoutRetiringPotentiallyValidCredentials() {
        val local = setup()
        acquireTailscaleState(listOf(local.id)).use { stageTailscaleRestore(local.id, local.uuid, "replacement") }
        val files = fileTree(tailscaleRestoreDirectory(local.id))
        try {
            ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.setTailscaleMarker(local.id, "unknown") }
            val failure = ConfigBuilderTestEnv.io {
                runCatching { acquireTailscaleState(listOf(local.id)).close() }.exceptionOrNull()
            }
            assertNotNull(failure)
            assertEquals(files, fileTree(tailscaleRestoreDirectory(local.id)))
            assertFalse(tailscaleStateFile(local.id).exists())
        } finally {
            ConfigBuilderTestEnv.io {
                SagerDatabase.proxyDao.setTailscaleMarker(local.id, local.uuid)
                acquireTailscaleState(listOf(local.id)).close()
            }
        }
    }

    @Test
    fun interruptionAfterCommitNeverExposesRetiredStateToReplacementOrDeletedNode() {
        for (deleted in listOf(false, true)) {
            val local = setup()
            if (deleted) {
                local.uuid = ""
                ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.setTailscaleMarker(local.id, "") }
            }
            ConfigBuilderTestEnv.io {
                acquireTailscaleState(listOf(local.id)).use {
                    stageTailscaleRestore(local.id, local.uuid, if (deleted) null else "replacement")
                    SagerDatabase.instance.runInTransaction {
                        if (deleted) {
                            SagerDatabase.proxyDao.deleteById(
                                local.id,
                            )
                        } else {
                            SagerDatabase.proxyDao.setTailscaleMarker(local.id, "replacement")
                        }
                    }
                }
            }
            assertFalse(tailscaleStateFile(local.id).exists())
            assertTrue(tailscaleRestoreDirectory(local.id).resolve("state").exists())
            ConfigBuilderTestEnv.io {
                acquireTailscaleState(listOf(local.id)).use {
                    assertFalse(tailscaleStateFile(local.id).exists())
                    assertFalse(tailscaleRestoreDirectory(local.id).exists())
                }
            }
        }
    }

    @Test
    fun unexpectedActiveDirectoryPreservesStagedCredentialsAndRetriesWithoutOverwrite() {
        val local = setup()
        val files = credentialFiles(local.id)
        acquireTailscaleState(listOf(local.id)).use { stageTailscaleRestore(local.id, local.uuid, "replacement") }
        val stagedFiles = fileTree(tailscaleRestoreDirectory(local.id))
        val active = tailscaleStateFile(local.id).apply { mkdirs() }.resolve("unexpected")
        active.writeBytes(byteArrayOf(3, 1, 4))
        val failure = ConfigBuilderTestEnv.io {
            runCatching { acquireTailscaleState(listOf(local.id)).close() }.exceptionOrNull()
        }
        assertNotNull(failure)
        assertEquals(stagedFiles, fileTree(tailscaleRestoreDirectory(local.id)))
        assertEquals(listOf<Byte>(3, 1, 4), active.readBytes().toList())
        assertEquals(local.uuid, stored(local.id).uuid)
        tailscaleStateFile(local.id).deleteRecursively()
        ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() }
        assertEquals(files, credentialFiles(local.id))
    }

    @Test
    fun malformedStagingFailsClosedWithoutDeletingCredentialBytes() {
        val local = setup()
        acquireTailscaleState(listOf(local.id)).use { stageTailscaleRestore(local.id, local.uuid, "replacement") }
        val staging = tailscaleRestoreDirectory(local.id)
        val metadata = staging.resolve("metadata.json")
        val valid = metadata.readBytes()
        try {
            metadata.writeText("invalid")
            val before = fileTree(staging)
            val failure = ConfigBuilderTestEnv.io {
                runCatching { acquireTailscaleState(listOf(local.id)).close() }.exceptionOrNull()
            }
            assertNotNull(failure)
            assertEquals(before, fileTree(staging))
            assertFalse(tailscaleStateFile(local.id).exists())
        } finally {
            metadata.writeBytes(valid)
            ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() }
        }
    }

    @Test
    fun validForeignRestoreRemovesStagingBeforeTheNewNodeCanAcquireItsIdentity() {
        val local = setup()
        restore(local.copy(uuid = "foreign"))
        ConfigBuilderTestEnv.io {
            acquireTailscaleState(listOf(local.id)).use {
                assertNotEquals(local.uuid, stored(local.id).uuid)
                assertFalse(tailscaleStateFile(local.id).exists())
                assertFalse(tailscaleRestoreDirectory(local.id).exists())
            }
        }
    }

    @Test
    fun cachedIdleCannotRestoreOverActiveProbeAndFailureMakesNoDatabaseChanges() {
        val local = setup()
        acquireTailscaleState(listOf(local.id)).use {
            assertNotNull(runCatching { restore(local.copy(uuid = "foreign")) }.exceptionOrNull())
            assertEquals(local.uuid, stored(local.id).uuid)
            assertEquals("original", ConfigBuilderTestEnv.io { SagerDatabase.groupDao.allGroups().single().name })
            assertTrue(tailscaleStateFile(local.id).exists())
        }
    }

    @Test
    fun stoppingOrConnectingTimeoutAbortsBeforeAnyMutation() = runTest {
        for (state in listOf(BaseService.State.Stopping, BaseService.State.Connecting)) {
            val local = setup()
            DataStore.serviceState = state
            try {
                val failure = runCatching {
                    DatabaseBackupRestoreOperations.replaceProfiles(listOf(local.copy(uuid = "foreign")), emptyList())
                }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(local.uuid, stored(local.id).uuid)
                assertEquals("original", ConfigBuilderTestEnv.io { SagerDatabase.groupDao.allGroups().single().name })
                assertTrue(tailscaleStateFile(local.id).exists())
            } finally {
                DataStore.serviceState = BaseService.State.Idle
            }
        }
    }

    @Test
    fun resetMarkerWriteFailuresRestoreExactCredentialsAndRows() {
        for ((timing, action) in listOf(
            "BEFORE" to "IGNORE",
            "BEFORE" to "ABORT, 'reset rejection'",
            "AFTER" to "ABORT, 'reset rollback'",
        )) {
            val local = setup()
            val rows = databaseRows()
            val files = credentialFiles(local.id)
            ConfigBuilderTestEnv.io {
                val db = SagerDatabase.instance.openHelper.writableDatabase
                db.execSQL(
                    "CREATE TRIGGER reject_reset $timing UPDATE OF uuid ON proxy_entities BEGIN SELECT RAISE($action); END",
                )
                try {
                    assertNotNull(runCatching { resetTailscaleIdentity(local.id) }.exceptionOrNull())
                } finally {
                    db.execSQL("DROP TRIGGER reject_reset")
                }
            }
            assertEquals(rows, databaseRows())
            assertEquals(files, credentialFiles(local.id))
            assertFalse(tailscaleRestoreDirectory(local.id).exists())
            repeat(2) { ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() } }
            assertEquals(files, credentialFiles(local.id))
        }
    }

    @Test
    fun committedResetChangesOnlyMarkerAndNeverRevivesOldCredentials() {
        val local = setup()
        val marker = ConfigBuilderTestEnv.io { resetTailscaleIdentity(local.id) }
        assertNotEquals(local.uuid, marker)
        assertEquals(local.copy(uuid = marker), stored(local.id))
        assertFalse(tailscaleStateFile(local.id).exists())
        assertFalse(tailscaleRestoreDirectory(local.id).exists())
        repeat(2) { ConfigBuilderTestEnv.io { acquireTailscaleState(listOf(local.id)).close() } }
        assertFalse(tailscaleStateFile(local.id).exists())
        assertEquals(marker, stored(local.id).uuid)
    }

    @Test
    fun resetStagingFailureRecoveryPreservesOriginalBytes() {
        val local = setup()
        val files = credentialFiles(local.id)
        val rows = databaseRows()
        ConfigBuilderTestEnv.io {
            acquireTailscaleState(listOf(local.id)).use {
                // Inject at the existing staging helper after acquisition, before any marker write.
                assertTrue(tailscaleRestoreDirectory(local.id).mkdirs())
                assertNotNull(
                    runCatching { stageTailscaleRestore(local.id, local.uuid, "reset-marker") }.exceptionOrNull(),
                )
                recoverTailscaleRestore(local.id)
                recoverTailscaleRestore(local.id)
            }
        }
        assertEquals(rows, databaseRows())
        assertEquals(files, credentialFiles(local.id))
        assertFalse(tailscaleRestoreDirectory(local.id).exists())
    }

    @Test
    fun resetPostCommitRecoveryFailurePreservesStagingWithoutResurrection() {
        val local = setup()
        val files = credentialFiles(local.id)
        ConfigBuilderTestEnv.io {
            acquireTailscaleState(listOf(local.id)).use {
                stageTailscaleRestore(local.id, local.uuid, "reset-marker")
                SagerDatabase.instance.runInTransaction {
                    assertEquals(1, SagerDatabase.proxyDao.setTailscaleMarker(local.id, "reset-marker"))
                }
                val staging = tailscaleRestoreDirectory(local.id)
                val unexpected = staging.resolve("unexpected").apply { writeText("preserve") }
                try {
                    repeat(2) {
                        assertNotNull(runCatching { recoverTailscaleRestore(local.id) }.exceptionOrNull())
                        assertFalse(tailscaleStateFile(local.id).exists())
                        assertEquals(files, fileTree(staging.resolve("state")))
                        assertEquals("reset-marker", SagerDatabase.proxyDao.getById(local.id)!!.uuid)
                    }
                } finally {
                    assertTrue(unexpected.delete())
                }
                recoverTailscaleRestore(local.id)
                recoverTailscaleRestore(local.id)
                assertFalse(staging.exists())
                assertFalse(tailscaleStateFile(local.id).exists())
            }
        }
        assertEquals(local.copy(uuid = "reset-marker"), stored(local.id))
    }

    private fun setup(): ProxyEntity {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Idle
        val node = ProxyEntity(id = 42, groupId = 1).putBean(TailscaleBean().apply { initializeDefaultValues() })
        ConfigBuilderTestEnv.io {
            SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 1, name = "original")))
            SagerDatabase.proxyDao.addProxy(node)
        }
        tailscaleStateFile(node.id).apply {
            deleteRecursively()
            mkdirs()
            resolve("tailscaled.state").writeBytes(byteArrayOf(0, 1, -1, 0, 127))
            resolve("nested").mkdirs()
            resolve("nested/preferences").writeBytes(byteArrayOf(42, 0, -128))
            resolve("empty-directory").mkdirs()
        }
        return node
    }

    private fun stored(id: Long) = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.getById(id)!! }

    private fun restore(node: ProxyEntity) = replace(listOf(node), listOf(ProxyGroup(id = 1, name = "restored")))

    private fun replace(profiles: List<ProxyEntity>, groups: List<ProxyGroup>) = ConfigBuilderTestEnv.io {
        runBlocking { DatabaseBackupRestoreOperations.replaceProfiles(profiles, groups) }
    }

    private fun databaseRows() = ConfigBuilderTestEnv.io {
        SagerDatabase.proxyDao.getAll() to SagerDatabase.groupDao.allGroups()
    }

    private fun credentialFiles(id: Long) = fileTree(tailscaleStateFile(id))

    private fun fileTree(directory: File) = directory.walkTopDown().associate {
        it.relativeTo(directory).path to (it.isDirectory to if (it.isFile) it.readBytes().toList() else emptyList())
    }
}
