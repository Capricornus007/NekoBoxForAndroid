package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.ktx.ImportTooLargeException
import io.nekohasekai.sagernet.ktx.MAX_IMPORT_BYTES
import libcore.StringBox
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater

object Util {

    private const val SQLITE_MAGIC = "SQLite format 3\u0000"
    private val BBOLT_SIGNATURE = byteArrayOf(0xED.toByte(), 0xDA.toByte(), 0x0C.toByte(), 0xED.toByte())

    /**
     * Get the text value between two pieces of text
     *
     * @param text  source text, e.g. the full text to extract from is 12345
     * @param left  text before
     * @param right text after
     * @return returns String
     */

    fun getSubString(text: String, left: String?, right: String?): String {
        var zLen: Int
        if (left.isNullOrEmpty()) {
            zLen = 0
        } else {
            zLen = text.indexOf(left)
            if (zLen > -1) {
                zLen += left.length
            } else {
                zLen = 0
            }
        }
        var yLen = if (right == null) -1 else text.indexOf(right, zLen)
        if (yLen < 0 || right.isNullOrEmpty()) {
            yLen = text.length
        }
        return text.substring(zLen, yLen)
    }

    /**
     * 讀文件頭魔數判斷這個檔「確實是一份資料庫」；不對就改名留一份樣本便於事後判斷
     * 是真損毀還是被截斷，留不下來就直接刪，別擋著啟動。附帶的 -wal / -shm 一起清掉，否則
     * 殘留日誌會被拿去跟新建的庫對帳。
     *
     * 兩種格式都得認：Room 的庫是 SQLite（偏移 0 起 16 字節魔數），sing-box 的 cache-file 是
     * bbolt（偏移 16 起 supermagic 0xED0CDAED，實測設備上兩者標頭一致）。只認 SQLite 的話，
     * 每次啟動都會把健康的 fakeip 快取當損毀刪掉。
     *
     * 刻意只認魔數、不去比對異常訊息關鍵字：關鍵字比對會把別的故障誤判成資料庫損毀。
     *
     * @return true 表示做了隔離處理（呼叫端該知道原本的庫已不可用）
     */
    fun quarantineIfNotDatabase(db: File): Boolean {
        try {
            if (!db.isFile) return false
            val header = ByteArray(32)
            if (db.length() >= header.size) {
                RandomAccessFile(db, "r").use { it.readFully(header) }
                if (String(header, 0, 16, Charsets.ISO_8859_1) == SQLITE_MAGIC) return false
                if (isBbolt(header)) return false
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            if (!db.renameTo(File(db.parentFile, "${db.name}.corrupt-$stamp"))) {
                db.delete()
            }
            File(db.parentFile, "${db.name}-wal").delete()
            File(db.parentFile, "${db.name}-shm").delete()
            return true
        } catch (ignored: Throwable) {
            return false
        }
    }

    private fun isBbolt(header: ByteArray): Boolean {
        for (index in BBOLT_SIGNATURE.indices) {
            if (header[16 + index] != BBOLT_SIGNATURE[index]) return false
        }
        val pageSize = (header[24].toInt() and 0xFF) or ((header[25].toInt() and 0xFF) shl 8) or
            ((header[26].toInt() and 0xFF) shl 16) or ((header[27].toInt() and 0xFF) shl 24)
        return pageSize in 512..65536 && pageSize and (pageSize - 1) == 0
    }

    fun b64EncodeUrlSafe(s: String): String {
        return b64EncodeUrlSafe(s.toByteArray())
    }

    fun b64EncodeUrlSafe(b: ByteArray): String {
        return String(Base64.encode(b, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE))
    }

    fun b64EncodeOneLine(b: ByteArray): String {
        return String(Base64.encode(b, Base64.NO_WRAP))
    }

    fun b64EncodeDefault(b: ByteArray): String {
        return String(Base64.encode(b, Base64.DEFAULT))
    }

    fun b64Decode(b: String): ByteArray {
        var ret: ByteArray? = null

        // padding is handled automatically, no need to worry about it
        // URLSafe needs to replace these two; don't use URL_SAFE, otherwise non-Safe input will be garbled
        val str = b.replace("-", "+").replace("_", "/")

        val flags = listOf(
            Base64.DEFAULT, // multi-line
            Base64.NO_WRAP, // single-line
        )

        for (flag in flags) {
            try {
                ret = Base64.decode(str, flag)
            } catch (_: Exception) {
            }
            if (ret != null) return ret
        }

        // If it failed because of missing padding, try manually padding it
        val stripped = str.replace("\\s".toRegex(), "")
        val padCount = (4 - stripped.length % 4) % 4
        if (padCount > 0) {
            val paddedStr = str + "=".repeat(padCount)
            for (flag in flags) {
                try {
                    ret = Base64.decode(paddedStr, flag)
                } catch (_: Exception) {
                }
                if (ret != null) return ret
            }
        }

        throw IllegalStateException("Cannot decode base64")
    }

    fun zlibCompress(input: ByteArray, level: Int): ByteArray {
        val output = ByteArray(input.size * 4)
        val compressor = Deflater(level).apply {
            setInput(input)
            finish()
        }
        val compressedDataLength: Int = compressor.deflate(output)
        compressor.end()
        return output.copyOfRange(0, compressedDataLength)
    }

    fun zlibDecompress(input: ByteArray, limit: Long = MAX_IMPORT_BYTES): ByteArray {
        val inflater = Inflater()
        val outputStream = ByteArrayOutputStream()

        return outputStream.use {
            val buffer = ByteArray(1024)

            inflater.setInput(input)

            try {
                var total = 0L
                var count = -1
                while (count != 0) {
                    count = inflater.inflate(buffer)
                    total += count
                    // Bound the INFLATED (output) size: a tiny crafted input can otherwise
                    // inflate to gigabytes (decompression bomb) and OOM the process before any
                    // user confirmation. Mirrors the import cap in BoundedRead.
                    if (total > limit) throw ImportTooLargeException(limit)
                    outputStream.write(buffer, 0, count)
                }
                // inflate() also returns 0 when it needs more input (truncated/corrupt stream),
                // not only when finished; reject partial output instead of accepting it.
                if (!inflater.finished()) {
                    throw java.util.zip.DataFormatException("truncated or corrupt zlib stream")
                }
                outputStream.toByteArray()
            } finally {
                // Always release the native inflater, even on a malformed-input exception.
                inflater.end()
            }
        }
    }

    fun map2StringMap(m: Map<*, *>): MutableMap<String, Any?> {
        val o = mutableMapOf<String, Any?>()
        m.forEach {
            if (it.key is String) {
                o[it.key as String] = it.value as Any
            }
        }
        return o
    }

    fun mergeMap(dst: MutableMap<String, Any?>, src: Map<String, Any?>): MutableMap<String, Any?> {
        src.forEach { (k, v) ->
            if (v is Map<*, *> && dst[k] is Map<*, *>) {
                val currentMap = (dst[k] as Map<*, *>).toMutableMap()
                dst[k] = mergeMap(map2StringMap(currentMap), map2StringMap(v))
            } else if (v is List<*>) {
                if (k.startsWith("+")) { // prepend
                    val dstKey = k.removePrefix("+")
                    var currentList = (dst[dstKey] as? List<*>)?.toMutableList() ?: mutableListOf()
                    currentList = (v + currentList).toMutableList()
                    dst[dstKey] = currentList
                } else if (k.endsWith("+")) { // append
                    val dstKey = k.removeSuffix("+")
                    var currentList = (dst[dstKey] as? List<*>)?.toMutableList() ?: mutableListOf()
                    currentList = (currentList + v).toMutableList()
                    dst[dstKey] = currentList
                } else {
                    dst[k] = v
                }
            } else {
                dst[k] = v
            }
        }
        return dst
    }

    fun mergeJSON(dst: MutableMap<String, Any?>, j: String) {
        if (j.isBlank()) return
        val src = JavaUtil.gson.fromJson(j, dst.javaClass)
        mergeMap(dst, src)
    }

    fun mergeJsonElement(dst: JsonObject, json: String) {
        if (json.isBlank()) return
        mergeJsonObject(dst, JsonParser.parseString(json).asJsonObject)
    }

    private fun mergeJsonObject(dst: JsonObject, src: JsonObject) {
        src.entrySet().forEach { (key, value) ->
            val current = dst.get(key)
            if (value.isJsonObject && current?.isJsonObject == true) {
                mergeJsonObject(current.asJsonObject, value.asJsonObject)
            } else if (value.isJsonArray && (key.startsWith("+") || key.endsWith("+"))) {
                val destinationKey = if (key.startsWith("+")) {
                    key.removePrefix("+")
                } else {
                    key.removeSuffix("+")
                }
                val currentArray = dst.get(destinationKey)?.takeIf { it.isJsonArray }?.asJsonArray
                dst.add(
                    destinationKey,
                    if (key.startsWith("+")) {
                        combineJsonArrays(value.asJsonArray, currentArray)
                    } else {
                        combineJsonArrays(currentArray, value.asJsonArray)
                    },
                )
            } else {
                dst.add(key, value.deepCopy())
            }
        }
    }

    private fun combineJsonArrays(first: JsonArray?, second: JsonArray?) = JsonArray().apply {
        first?.forEach { add(it.deepCopy()) }
        second?.forEach { add(it.deepCopy()) }
    }

    // Format Time

    @SuppressLint("SimpleDateFormat")
    val sdf1 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    fun timeStamp2Text(t: Long): String {
        return sdf1.format(Date(t))
    }

    fun tryToSetField(o: Any, name: String, value: Any) {
        try {
            o.javaClass.getField(name).set(o, value)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("WrongConstant")
    fun collapseStatusBar(context: Context) {
        try {
            val statusBarManager = context.getSystemService("statusbar")
            val collapse = statusBarManager.javaClass.getMethod("collapsePanels")
            collapse.invoke(statusBarManager)
        } catch (_: Exception) {
        }
    }

    fun getStringBox(b: StringBox?): String {
        if (b != null && b.value != null) {
            return b.value
        }
        return ""
    }

    fun decodeFilename(headerValue: String): String {
        val regex = Regex("filename\\*=[^']*''(.+)")
        val match = regex.find(headerValue)
        val encoded = match?.groupValues?.get(1) ?: ""
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }

    fun generateCryptoSecurePassword(length: Int = 20): String {
        val chars =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_-+=."
        val secureRandom = java.security.SecureRandom()
        return (1..length)
            .map { chars[secureRandom.nextInt(chars.length)] }
            .joinToString("")
    }
}
