package io.nekohasekai.sagernet.api

import android.util.Xml
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.readBytesBounded
import io.nekohasekai.sagernet.ktx.readTextBounded
import io.nekohasekai.sagernet.ui.BackupArchiveFormat
import io.nekohasekai.sagernet.ui.WebDAVSecurity
import io.nekohasekai.sagernet.ui.backupFileName
import io.nekohasekai.sagernet.ui.encodeBackupArchive
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal class ApiWebDav {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS).build()

    private fun directory(): HttpUrl {
        val base = DataStore.webdavServer?.takeIf { it.isNotBlank() } ?: reject("not_configured", "Set the WebDAV server first")
        val url = WebDAVSecurity.requireSecureUrl(base)
        requireApi(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null, "Use a WebDAV URL without credentials, a query or a fragment")
        return url.newBuilder().apply {
            DataStore.webdavPath.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        }.build()
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", Credentials.basic(DataStore.webdavUsername.orEmpty(), DataStore.webdavPassword.orEmpty()))

    fun list(): JSONArray {
        val directory = directory()
        val request = request(directory).method("PROPFIND", "".toRequestBody()).header("Depth", "1").build()
        return client.newCall(request).execute().use { response ->
            if (response.code != 207) reject("remote_error", "WebDAV did not return a directory listing")
            val body = response.body.byteStream().use { it.readTextBounded(2L * 1024 * 1024) }
            parseListing(body, directory)
        }
    }

    fun upload(content: JSONObject): JSONObject {
        val directory = directory()
        client.newCall(request(directory).method("MKCOL", "".toRequestBody()).build()).execute().use {
            if (it.code !in setOf(200, 201, 204, 405)) reject("remote_error", "Could not create the WebDAV backup directory")
        }
        val name = backupFileName().removeSuffix(".json") + ".zip"
        val bytes = encodeBackupArchive(content.toString(), BackupArchiveFormat.ZIP)
        val url = directory.newBuilder().addPathSegment(name).build()
        client.newCall(request(url).header("If-None-Match", "*").put(bytes.toRequestBody("application/zip".toMediaType())).build()).execute().use {
            if (!it.isSuccessful) reject("remote_error", "Could not upload the WebDAV backup")
        }
        return JSONObject().put("name", name).put("bytes", bytes.size)
    }

    fun download(name: String): JSONObject {
        requireApi(validName(name), "Invalid backup filename")
        val url = directory().newBuilder().addPathSegment(name).build()
        return client.newCall(request(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) reject("remote_error", "Could not download the WebDAV backup")
            val bytes = response.body.byteStream().use { it.readBytesBounded() }
            val content = if (name.endsWith(".zip")) {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    requireApi(zip.nextEntry?.name?.endsWith(".json") == true, "Invalid backup archive")
                    zip.readTextBounded()
                }
            } else {
                bytes.toString(Charsets.UTF_8)
            }
            JSONObject(content)
        }
    }

    companion object {
        internal fun validName(name: String) = name.startsWith("nekobox_backup_") &&
            (name.endsWith(".json") || name.endsWith(".zip")) &&
            name.length <= 200 && name.none { it == '/' || it == '\\' || it.isISOControl() }

        internal fun parseListing(xml: String, directory: HttpUrl): JSONArray {
            requireApi(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true), "DTD declarations are not allowed")
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(xml.reader())
            val files = linkedSetOf<String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "href" || parser.namespace != "DAV:") continue
                val href = parser.nextText()
                val url = directory.newBuilder().addPathSegment("").build().resolve(href) ?: continue
                if (url.scheme != directory.scheme || url.host != directory.host || url.port != directory.port || url.query != null || url.fragment != null) continue
                if (url.pathSegments.dropLast(1).filter { it.isNotEmpty() } != directory.pathSegments.filter { it.isNotEmpty() }) continue
                val name = url.pathSegments.last()
                if (validName(name)) files += name
                requireApi(files.size <= 1000, "WebDAV listing is too large")
            }
            return JSONArray(files.sortedDescending())
        }
    }
}
