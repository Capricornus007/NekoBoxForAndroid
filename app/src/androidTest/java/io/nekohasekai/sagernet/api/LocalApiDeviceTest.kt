package io.nekohasekai.sagernet.api

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.ui.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class LocalApiDeviceTest {
    @Test
    fun loopbackAuthenticationDiscoveryAndFixtureCrud() {
        // This scenario owns a disposable emulator, not a physical install with saved profiles.
        assumeTrue(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.startsWith("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = LocalApiAccess.read(context)
        assumeTrue(original?.enabled != true)
        val config = LocalApiAccess.fresh().copy(port = 19091)
        LocalApiAccess.write(context, config)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var groupId: Long? = null
        try {
            var ready = false
            repeat(100) {
                if (!ready) {
                    ready = runCatching { call(config, "app.status").has("package") }.getOrDefault(false)
                    if (!ready) Thread.sleep(100)
                }
            }
            assertTrue("API did not start", ready)
            val unauthenticated = URL("http://127.0.0.1:${config.port}/v1").openConnection() as HttpURLConnection
            try {
                assertEquals(401, unauthenticated.responseCode)
            } finally {
                unauthenticated.disconnect()
            }
            val catalog = call(config, "api.describe").getJSONObject("commands")
            assertTrue(catalog.has("profiles.create"))
            val group = call(config, "groups.create", JSONObject().put("name", "API device fixture"))
            groupId = group.getLong("id")
            val profile = call(
                config,
                "profiles.create",
                JSONObject().put("groupId", groupId).put("type", 0).put(
                    "configuration",
                    JSONObject()
                        .put("name", "API device profile").put("serverAddress", "192.0.2.1").put("serverPort", 1080).put("password", "fictional-secret"),
                ),
            )
            val id = profile.getLong("id")
            assertFalse(profile.has("configuration"))
            call(config, "profiles.update", JSONObject().put("id", id).put("configuration", JSONObject().put("name", "API device edited")))
            val safe = call(config, "profiles.get", JSONObject().put("id", id))
            assertEquals("API device edited", safe.getString("name"))
            assertFalse(safe.toString().contains("fictional-secret"))
            val full = call(config, "profiles.get", JSONObject().put("id", id).put("includeSecrets", true))
            assertEquals("fictional-secret", full.getJSONObject("configuration").getString("password"))
            val rejected = request(config, "profiles.delete", JSONObject().put("id", id).put("confirm", false))
            assertEquals("confirmation_required", rejected.getJSONObject("error").getString("code"))
            call(config, "profiles.get", JSONObject().put("id", id))
            call(config, "profiles.delete", JSONObject().put("id", id).put("confirm", true))
            assertEquals(0, call(config, "profiles.list", JSONObject().put("groupId", groupId)).getInt("total"))
        } finally {
            try {
                groupId?.let { call(config, "groups.delete", JSONObject().put("id", it).put("confirm", true)) }
            } finally {
                LocalApiAccess.write(context, original ?: config.copy(enabled = false))
                context.stopService(Intent(context, LocalApiService::class.java))
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    private fun call(config: LocalApiAccess.Config, operation: String, parameters: JSONObject = JSONObject()): JSONObject {
        val response = request(config, operation, parameters)
        assertFalse("$operation: $response", response.has("error"))
        return response.getJSONObject("result")
    }

    private fun request(config: LocalApiAccess.Config, operation: String, parameters: JSONObject): JSONObject {
        val connection = URL("http://127.0.0.1:${config.port}/v1/$operation").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(parameters.toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
