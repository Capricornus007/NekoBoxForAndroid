package io.nekohasekai.sagernet.api

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.sanitizeAssetFileName
import libcore.Libcore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

internal class ApiFiles(private val context: Context) {
    private val root get() = context.getExternalFilesDir(null) ?: context.filesDir

    private fun asset(name: String): File {
        requireApi(sanitizeAssetFileName(name) == name, "An asset basename ending in .db is required")
        val parent = root.canonicalFile
        val file = File(parent, name)
        requireApi(file.canonicalPath == file.absolutePath, "Symbolic links are not allowed")
        return file
    }

    fun list() = jsonArray(
        root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".db") && it.canonicalPath == it.absolutePath }.map {
            JSONObject().put("name", it.name).put("bytes", it.length()).put("modifiedAt", it.lastModified())
        },
    )

    fun import(name: String, base64: String, confirm: Boolean): JSONObject {
        val file = asset(name)
        if (file.exists() && !confirm) reject("confirmation_required", "Replacing an asset requires confirm=true")
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            reject("invalid_parameters", "Invalid base64 content")
        }
        requireApi(bytes.isNotEmpty() && bytes.size <= 1_500_000, "Asset import must contain 1..1500000 bytes; use assets.download for larger files")
        val temporary = File.createTempFile("api-asset-", ".tmp", root)
        try {
            temporary.outputStream().use {
                it.write(bytes)
                it.fd.sync()
            }
            check(temporary.renameTo(file)) { "Asset could not be replaced" }
        } finally {
            temporary.delete()
        }
        return info(file)
    }

    fun download(name: String, url: String, sha256: String, confirm: Boolean): JSONObject {
        val file = asset(name)
        if (file.exists() && !confirm) reject("confirmation_required", "Replacing an asset requires confirm=true")
        requireApi(url.toHttpUrlOrNull()?.isHttps == true, "Asset downloads require HTTPS")
        requireApi(sha256.matches(Regex("[0-9a-f]{64}")), "A lowercase SHA-256 checksum is required")
        val temporary = File.createTempFile("api-asset-", ".tmp", root)
        val client = Libcore.newHttpClient()
        try {
            client.modernTLS()
            client.setTimeoutMs(120_000)
            client.trySocks5(DataStore.mixedPort, DataStore.mixedInboundUser, DataStore.mixedInboundPass)
            val response = client.newRequest().apply { setURL(url) }.execute()
            response.writeToLimited(temporary.path, 256L * 1024 * 1024)
            requireApi(hash(temporary) == sha256, "Asset checksum does not match")
            check(temporary.renameTo(file)) { "Asset could not be replaced" }
        } finally {
            client.close()
            temporary.delete()
        }
        return info(file)
    }

    fun delete(name: String): JSONObject {
        requireApi(name !in setOf("geoip.db", "geosite.db"), "Bundled routing assets cannot be deleted")
        val file = asset(name)
        if (!file.isFile) reject("not_found", "Asset does not exist")
        if (!file.delete()) reject("operation_failed", "Asset could not be deleted")
        return JSONObject().put("deleted", true)
    }

    fun log(maxBytes: Long): JSONObject {
        requireApi(maxBytes in 1..1_048_576, "maxBytes must be 1..1048576")
        val file = File(context.cacheDir, "neko.log")
        if (!file.isFile) return JSONObject().put("text", "").put("bytes", 0)
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            input.seek((length - maxBytes).coerceAtLeast(0))
            val bytes = ByteArray(minOf(length, maxBytes).toInt())
            val count = input.read(bytes).coerceAtLeast(0)
            JSONObject().put("text", String(bytes, 0, count, Charsets.UTF_8)).put("bytes", count)
                .put("truncated", length > maxBytes)
        }
    }

    private fun info(file: File) = JSONObject().put("name", file.name).put("bytes", file.length()).put("sha256", hash(file))
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
