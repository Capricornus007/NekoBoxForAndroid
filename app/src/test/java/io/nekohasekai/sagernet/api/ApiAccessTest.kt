package io.nekohasekai.sagernet.api

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ApiAccessTest {
    private val token = "0123456789abcdef".repeat(4)
    private fun config() = JSONObject().put("enabled", true).put("port", 9091).put("token", token)

    @Test
    fun credentialsDefaultOffAndStayOutsideBackups() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(LocalApiAccess.read(context))
        val fresh = LocalApiAccess.fresh()
        assertTrue(fresh.token.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(fresh.token, LocalApiAccess.fresh().token)
        LocalApiAccess.write(context, fresh)
        assertEquals(fresh, LocalApiAccess.read(context))
        assertTrue(File(context.noBackupFilesDir, "local-api.json").isFile)
        assertFalse(File(context.filesDir, "local-api.json").exists())
        LocalApiAccess.write(context, fresh.copy(enabled = false))
        assertEquals(false, LocalApiAccess.read(context)?.enabled)
    }

    @Test
    fun credentialReadRecoversAnInterruptedAtomicWrite() {
        val context = RuntimeEnvironment.getApplication()
        val fresh = LocalApiAccess.fresh()
        LocalApiAccess.write(context, fresh)
        val base = File(context.noBackupFilesDir, "local-api.json")
        assertTrue(base.renameTo(File(context.noBackupFilesDir, "local-api.json.bak")))
        assertEquals(fresh, LocalApiAccess.read(context))
        assertTrue(base.exists())
    }

    @Test
    fun successfulAccessChangesApplyAfterTheActivityFinishes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val changes = mutableListOf<String>()
        val app = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun startService(intent: Intent): ComponentName? {
                changes += "start"
                return intent.component
            }
            override fun startForegroundService(intent: Intent): ComponentName? {
                changes += "start"
                return intent.component
            }
            override fun stopService(intent: Intent): Boolean {
                changes += "stop"
                return true
            }
        }
        val activity = object : Activity() {
            override fun getApplicationContext(): Context = app
            override fun isFinishing() = true
        }
        try {
            val config = LocalApiAccess.fresh()
            LocalApiAccess.saveFromUi(activity, config.copy(enabled = false)).join()
            assertEquals(listOf("stop"), changes)
            assertEquals(false, LocalApiAccess.read(app)?.enabled)
            LocalApiAccess.saveFromUi(activity, config).join()
            assertEquals(listOf("stop", "start"), changes)
            assertEquals(config, LocalApiAccess.read(app))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun invalidCredentialConfigurationNeverEnablesAListener() {
        val invalid = listOf(
            config().put("enabled", "true"), config().put("port", "9091"),
            config().put("port", 9091.5), config().put("port", 0), config().put("port", 65536),
            config().put("token", "short"), config().put("token", "z".repeat(64)),
            config().put("host", "0.0.0.0"), JSONObject().put("enabled", true),
        )
        invalid.forEach { json -> assertTrue(json.toString(), runCatching { LocalApiAccess.parse(json.toString()) }.isFailure) }
        val context = RuntimeEnvironment.getApplication()
        File(context.noBackupFilesDir, "local-api.json").writeText(" ".repeat(4097))
        assertTrue(runCatching { LocalApiAccess.read(context) }.isFailure)
    }

    @Test
    fun beanPatchesRejectUnknownTransientAndWrongTypedFieldsBeforeMutation() {
        val bean = SOCKSBean().apply {
            name = "original"
            initializeDefaultValues()
        }
        val bad = listOf(
            JSONObject().put("name", "changed").put("finalAddress", "198.51.100.1"),
            JSONObject().put("name", "changed").put("serverPort", "1080"),
            JSONObject().put("name", "changed").put("serverPort", Long.MAX_VALUE),
            JSONObject().put("name", "changed").put("serverPort", 1080.5),
            JSONObject().put("name", "changed").put("unknown", false),
        )
        bad.forEach { patch ->
            assertTrue(runCatching { ApiBean.patch(bean, patch) }.isFailure)
            assertEquals("original", bean.name)
        }
    }

    @Test
    fun assetOperationsRejectTraversalAndRequireConfirmationForReplacement() {
        val context = RuntimeEnvironment.getApplication()
        val files = ApiFiles(context)
        for (name in listOf("../configuration.db", "/tmp/fixture.db", "x\\fixture.db", "fixture.txt", "", "..")) {
            assertTrue(runCatching { files.import(name, "eA==", true) }.isFailure)
        }
        val encoded = Base64.getEncoder().encodeToString("fixture".toByteArray())
        val info = files.import("fixture.db", encoded, false)
        assertEquals(7L, info.getLong("bytes"))
        assertEquals(64, info.getString("sha256").length)
        assertTrue(runCatching { files.import("fixture.db", "eA==", false) }.exceptionOrNull() is ApiFailure)
        assertEquals("fixture", File(context.getExternalFilesDir(null), "fixture.db").readText())
        assertTrue(runCatching { files.delete("geoip.db") }.isFailure)
        assertTrue(files.delete("fixture.db").getBoolean("deleted"))
    }

    @Test
    fun logReadsAreBoundedAndDoNotAcceptPaths() {
        val context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "neko.log").writeText("0123456789")
        val files = ApiFiles(context)
        assertEquals("789", files.log(3).getString("text"))
        assertTrue(files.log(3).getBoolean("truncated"))
        assertTrue(runCatching { files.log(0) }.isFailure)
        assertTrue(runCatching { files.log(1_048_577) }.isFailure)
    }
}
