package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import android.text.InputType
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.ktx.snackbar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.io.BufferedOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private lateinit var binding: LayoutBackupBinding
    private lateinit var backupData: ByteArray
    private var isBackupInProgress = false
    private var isRestoreInProgress = false
    private var currentJob: kotlinx.coroutines.Job? = null
    private var snackbar: Snackbar? = null
    private var restoreJob: kotlinx.coroutines.Job? = null

    override fun onDestroyView() {
        if (::binding.isInitialized) binding.backupPassword.text?.clear()
        super.onDestroyView()
        snackbar?.dismiss()
        snackbar = null
        // 如果正在进行恢复操作，取消它
        if (isRestoreInProgress) {
            restoreJob?.cancel()
            restoreJob = null
            isRestoreInProgress = false
            MessageStore.showMessage(requireActivity(), R.string.restore_cancelled)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        currentJob = null
    }

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    requireActivity().contentResolver.openOutputStream(data)!!.use { os ->
                        os.write(backupData)
                    }
                    onMainDispatcher {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)
        this.binding = binding

        binding.actionExport.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                try {
                    backupData = doBackup(
                        binding.backupConfigurations.isChecked,
                        binding.backupRules.isChecked,
                        binding.backupSettings.isChecked
                    )
                    onMainDispatcher {
                        startFilesForResult(
                            exportSettings, "nekobox_backup_${Date().toLocaleString()}.${backupExtension(backupData)}"
                        )
                    }
                } catch (error: Exception) {
                    onMainDispatcher { snackbar(error.readableMessage).show() }
                }
            }
        }


        binding.actionShare.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                try {
                    backupData = doBackup(
                        binding.backupConfigurations.isChecked,
                        binding.backupRules.isChecked,
                        binding.backupSettings.isChecked
                    )
                    app.cacheDir.mkdirs()
                    val cacheFile = File(
                        app.cacheDir, "nekobox_backup_${Date().toLocaleString()}.${backupExtension(backupData)}"
                    )
                    cacheFile.writeBytes(backupData)
                    onMainDispatcher {
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType(if (BackupEncryption.isEncrypted(backupData)) "application/octet-stream" else "application/json")
                                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .putExtra(
                                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                            app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                        )
                                    ), app.getString(R.string.abc_shareactionprovider_share_with)
                            )
                        )
                    }

                } catch (error: Exception) {
                    onMainDispatcher { snackbar(error.readableMessage).show() }
                }
            }
        }


        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

        setupWebDAV(binding)
    }

    private fun setupWebDAV(binding: LayoutBackupBinding) {
        binding.webdavSettings.setOnClickListener {
            startActivity(Intent(requireContext(), WebDAVSettingsActivity::class.java))
        }

        binding.backupToWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            backupToWebDAV()
        }

        binding.restoreFromWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            restoreFromWebDAV()
        }
    }

    private fun backupToWebDAV() {
        if (isBackupInProgress) {
            showMessage(R.string.backup_in_progress)
            return
        }
        isBackupInProgress = true
        val activity = requireActivity()
        runOnDefaultDispatcher {
            try {
                val backupData = doBackup(
                    true,  // 备份配置和分组
                    true,  // 备份路由规则
                    true,  // 备份设置
                    zipped = true
                )

                val client = OkHttpClient()

                // 规范化 URL
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox"

                // 使用英文格式的时间戳作为文件名，修改后缀为 .zip
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val version = BuildConfig.VERSION_NAME
                val fileName = "nekobox_backup_${version}_$timestamp.${backupExtension(backupData, zipped = true)}"

                // 确保 baseUrl 是有效的 URL
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                // 使用 HttpUrl 构建路径，避免 # 等特殊字符被当作 fragment
                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(fileName)
                    .build()

                Logs.d("WebDAV backup - Directory URL: $dirUrl")
                Logs.d("WebDAV backup - File URL: $fileUrl")

                // 先检查目录是否存在
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "0")
                    .build()

                var needCreateDir = false
                client.newCall(propfindRequest).execute().use { response ->
                    Logs.d("WebDAV backup - PROPFIND response: ${response.code}")
                    when (response.code) {
                        404 -> needCreateDir = true
                        207 -> needCreateDir = false // 目录存在
                        401 -> throw Exception("Authentication failed")
                        else -> {
                            if (!response.isSuccessful) {
                                val errorBody = response.body?.string()
                                Logs.e("WebDAV backup - PROPFIND error: $errorBody")
                                throw Exception("Failed to check directory (${response.code}): ${response.message}")
                            }
                        }
                    }
                }

                // 如果需要，创建目录
                if (needCreateDir) {
                    Logs.d("WebDAV backup - Creating directory")
                    val mkcolRequest = Request.Builder()
                        .url(dirUrl)
                        .method("MKCOL", null)
                        .header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                        .build()

                    client.newCall(mkcolRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string()
                            Logs.e("WebDAV backup - MKCOL error: $errorBody")
                            throw Exception("Failed to create directory (${response.code}): ${response.message}")
                        }
                    }
                }

                // 上传文件时使用正确的 Content-Type
                val putRequest = Request.Builder()
                    .url(fileUrl)
                    .put(backupData.toRequestBody((if (BackupEncryption.isEncrypted(backupData)) "application/octet-stream" else "application/zip").toMediaType()))
                    .apply {
                        header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                    }
                    .build()

                client.newCall(putRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV backup - PUT error: $errorBody")
                        throw Exception("Upload failed (${response.code}): ${response.message}\n$errorBody")
                    }
                    Logs.d("WebDAV backup - Upload successful")
                }

                onMainDispatcher {
                    MessageStore.showMessage(activity, R.string.webdav_backup_success)
                }
            } catch (e: Exception) {
                Logs.w(e)

                val errorMessage = try {
                    if (isAdded) {
                        getString(R.string.webdav_backup_failed, e.message ?: "")
                    } else {
                        app.getString(R.string.webdav_backup_failed, e.message ?: "")
                    }
                } catch (ex: Exception) {
                    "WebDAV backup failed: ${e.message ?: ""}"
                }

                onMainDispatcher {
                    MessageStore.showMessage(activity, errorMessage)
                }
            } finally {
                isBackupInProgress = false
            }
        }
    }

    private fun restoreFromWebDAV() {
        if (isRestoreInProgress) {
            showMessage(R.string.restore_in_progress)
            return
        }
        isRestoreInProgress = true
        val activity = requireActivity()
        restoreJob = runOnDefaultDispatcher {
            try {
                val client = OkHttpClient()
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox"

                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                Logs.d("WebDAV restore - Directory URL: $dirUrl")

                // 先列出目录内容找到最新的备份文件
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "1")
                    .build()

                // 获取最新的备份文件名
                val latestBackup = client.newCall(propfindRequest).execute().use { response ->
                    if (!response.isSuccessful && response.code != 207) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - PROPFIND error: $errorBody")
                        throw Exception("Failed to list directory: ${response.message}")
                    }

                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    Logs.d("WebDAV restore - Directory listing: $responseBody")

                    val patterns = listOf(
                        """<D:href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip|nbk)</D:href>""".toRegex(),
                        """<d:href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip|nbk)</d:href>""".toRegex(),
                        """<href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip|nbk)</href>""".toRegex()
                    )

                    val backupFiles = mutableListOf<String>()

                    for (pattern in patterns) {
                        val matches = pattern.findAll(responseBody)
                        matches.forEach { match ->
                            val href = match.value
                            Logs.d("WebDAV restore - Found backup file with pattern ${pattern.pattern}: $href")
                            val fileName = """nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip|nbk)""".toRegex()
                                .find(href)?.value
                            if (fileName != null) {
                                backupFiles.add(fileName)
                            }
                        }
                        if (backupFiles.isNotEmpty()) break
                    }

                    Logs.d("WebDAV restore - Found ${backupFiles.size} backup files: ${backupFiles.joinToString()}")

                    backupFiles.maxByOrNull { fileName ->
                        """(\d{8}_\d{6})""".toRegex().find(fileName)?.value ?: ""
                    } ?: throw Exception("No backup found")
                }

                // 下载最新的备份文件
                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(latestBackup)
                    .build()
                Logs.d("WebDAV restore - File URL: $fileUrl")

                val getRequest = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .build()

                val content = client.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - GET error: $errorBody")
                        throw Exception("Download failed (${response.code}): ${response.message}")
                    }
                    response.body?.byteStream()?.use { it.readBytesLimited(MAX_BACKUP_BYTES) } ?: throw Exception("Empty backup file")
                }

                Logs.d("WebDAV restore - Successfully downloaded backup file, size: ${content.size}")

                val backupContent = decodeBackup(content)

                // 解析并导入备份数据
                val json = JSONObject(backupContent)
                onMainDispatcher {
                    // 如果 Fragment 已经被销毁，取消恢复操作
                    if (!isAdded) {
                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                        return@onMainDispatcher
                    }

                    val import = LayoutImportBinding.inflate(layoutInflater)
                    if (!json.has("profiles")) {
                        import.backupConfigurations.isVisible = false
                    }
                    if (!json.has("rules")) {
                        import.backupRules.isVisible = false
                    }
                    if (!json.has("settings")) {
                        import.backupSettings.isVisible = false
                    }

                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                        .setView(import.root)
                        .setPositiveButton(R.string.backup_import) { _, _ ->
                            SagerNet.stopService()

                            val binding = LayoutProgressBinding.inflate(layoutInflater)
                            binding.content.text = getString(R.string.backup_importing)
                            val dialog = AlertDialog.Builder(requireContext())
                                .setView(binding.root)
                                .setCancelable(false)
                                .show()
                            runOnDefaultDispatcher {
                                runCatching {
                                    // 再次检查是否已被取消
                                    if (!isAdded) {
                                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                                        return@runOnDefaultDispatcher
                                    }
                                    finishImport(
                                        json,
                                        import.backupConfigurations.isChecked,
                                        import.backupRules.isChecked,
                                        import.backupSettings.isChecked
                                    )
                                    ProcessPhoenix.triggerRebirth(
                                        activity, Intent(activity, MainActivity::class.java)
                                    )
                                }.onFailure {
                                    Logs.w(it)
                                    onMainDispatcher {
                                        MessageStore.showMessage(activity, it.readableMessage)
                                    }
                                }

                                onMainDispatcher {
                                    dialog.dismiss()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    MessageStore.showMessage(activity, e.readableMessage)
                }
            } finally {
                isRestoreInProgress = false
            }
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        try {
            writeToParcel(parcel, 0)
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private suspend fun doBackup(
        profile: Boolean,
        rule: Boolean,
        setting: Boolean,
        zipped: Boolean = false,
    ): ByteArray {
        val password = onMainDispatcher {
            check(isAdded && view != null) { "Backup screen closed" }
            binding.backupPassword.text?.toString()?.toCharArray() ?: charArrayOf()
        }
        try {
            require(password.isEmpty() || password.size >= 8) { getString(R.string.backup_password_short) }
            val out = JSONObject().apply {
                put("version", 1)
                if (profile) {
                    put("profiles", JSONArray().apply {
                        SagerDatabase.proxyDao.getAll().forEach {
                            put(it.toBase64Str())
                        }
                    })

                    put("groups", JSONArray().apply {
                        SagerDatabase.groupDao.allGroups().forEach {
                            put(it.toBase64Str())
                        }
                    })
                }
                if (rule) {
                    put("rules", JSONArray().apply {
                        SagerDatabase.rulesDao.allRules().forEach {
                            put(it.toBase64Str())
                        }
                    })
                }
                if (setting) {
                    put("settings", JSONArray().apply {
                        PublicDatabase.kvPairDao.all().forEach {
                            put(it.toBase64Str())
                        }
                    })
                }
            }

            val jsonBytes = out.toStringPretty().toByteArray(Charsets.UTF_8)
            require(jsonBytes.size <= MAX_BACKUP_BYTES) { "Backup exceeds size limit" }
            val payload = if (zipped) {
                ByteArrayOutputStream().use { bos ->
                    ZipOutputStream(bos).use { zos ->
                        zos.setLevel(Deflater.BEST_COMPRESSION)

                        val entry = ZipEntry("nekobox_backup.json").apply {
                            method = ZipEntry.DEFLATED
                        }

                        // 写入数据
                        zos.putNextEntry(entry)
                        zos.write(jsonBytes)
                        zos.closeEntry()

                        // 确保所有数据都被写入和压缩
                        zos.finish()
                    }
                    bos.toByteArray()
                }
            } else {
                jsonBytes
            }
            require(payload.size <= MAX_BACKUP_BYTES) { "Backup exceeds size limit" }
            return if (password.isEmpty()) payload else BackupEncryption.encrypt(payload, password)
        } finally { password.fill('\u0000') }
    }

    private fun backupExtension(bytes: ByteArray, zipped: Boolean = false) =
        if (BackupEncryption.isEncrypted(bytes)) "nbk" else if (zipped) "zip" else "json"

    private suspend fun decodeBackup(bytes: ByteArray): String {
        val payload = if (BackupEncryption.isEncrypted(bytes)) {
            val password = onMainDispatcher {
                suspendCancellableCoroutine<CharArray> { continuation ->
                    val input = EditText(requireContext()).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        isSaveEnabled = false
                        hint = getString(R.string.password)
                    }
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.backup_encryption_password)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val password = input.text.toString().toCharArray()
                            input.text.clear()
                            if (continuation.isActive) continuation.resume(password) else password.fill('\u0000')
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                    dialog.setOnDismissListener {
                        input.text.clear()
                        if (continuation.isActive) continuation.resumeWithException(kotlinx.coroutines.CancellationException("Restore cancelled"))
                    }
                    continuation.invokeOnCancellation { input.post { dialog.dismiss() } }
                    dialog.show()
                }
            }
            try {
                BackupEncryption.decrypt(bytes, password)
            } catch (failure: java.security.GeneralSecurityException) {
                error(getString(R.string.backup_decrypt_failed))
            } finally { password.fill('\u0000') }
        } else bytes
        return if (payload.size >= 2 && payload[0] == 0x50.toByte() && payload[1] == 0x4b.toByte()) {
            ZipInputStream(payload.inputStream()).use { zip ->
                val entry = zip.nextEntry ?: error("Empty backup archive")
                require(entry.name.endsWith(".json")) { "Invalid backup archive" }
                zip.readBytesLimited(MAX_BACKUP_BYTES).toString(Charsets.UTF_8)
            }
        } else payload.toString(Charsets.UTF_8)
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = requireActivity()
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json") && !fileName.endsWith(".zip") && !fileName.endsWith(".nbk")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        try {
            val bytes = requireContext().contentResolver.openInputStream(file)!!.use { input ->
                input.readBytesLimited(MAX_BACKUP_BYTES)
            }
            val content = decodeBackup(bytes)

            val json = JSONObject(content)
            onMainDispatcher {
                val import = LayoutImportBinding.inflate(layoutInflater)
                if (!json.has("profiles")) {
                    import.backupConfigurations.isVisible = false
                }
                if (!json.has("rules")) {
                    import.backupRules.isVisible = false
                }
                if (!json.has("settings")) {
                    import.backupSettings.isVisible = false
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                    .setView(import.root)
                    .setPositiveButton(R.string.backup_import) { _, _ ->
                        SagerNet.stopService()

                        val binding = LayoutProgressBinding.inflate(layoutInflater)
                        binding.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(binding.root)
                            .setCancelable(false)
                            .show()
                        runOnDefaultDispatcher {
                            runCatching {
                                finishImport(
                                    json,
                                    import.backupConfigurations.isChecked,
                                    import.backupRules.isChecked,
                                    import.backupSettings.isChecked
                                )
                                triggerFullRestart(requireContext())
                            }.onFailure {
                                Logs.w(it)
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(activity, it.readableMessage)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(activity, e.readableMessage)
            }
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        require(content.optInt("version", 1) == 1) { "Unsupported backup version" }
        fun <T> records(key: String, create: (Parcel) -> T): List<T> {
            val records = content.getJSONArray(key)
            require(records.length() <= 100_000) { "Too many backup records" }
            return (0 until records.length()).map { index ->
                val encoded = records.getString(index)
                require(encoded.length <= 6 * 1024 * 1024) { "Backup record is too large" }
                val bytes = Util.b64Decode(encoded)
                require(bytes.size <= 4 * 1024 * 1024) { "Backup record is too large" }
                val parcel = Parcel.obtain()
                try {
                    parcel.unmarshall(bytes, 0, bytes.size)
                    parcel.setDataPosition(0)
                    create(parcel)
                } finally { parcel.recycle() }
            }
        }
        val profiles = if (profile && content.has("profiles")) records("profiles") { ProxyEntity.CREATOR.createFromParcel(it) } else null
        val groups = if (profiles != null) records("groups") { ProxyGroup.CREATOR.createFromParcel(it) } else null
        val rules = if (rule && content.has("rules")) records("rules") { ParcelizeBridge.createRule(it) } else null
        val settings = if (setting && content.has("settings")) records("settings") { KeyValuePair.CREATOR.createFromParcel(it) } else null
        if (profiles != null && groups != null) {
            require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate profile IDs" }
            require(groups.map { it.id }.distinct().size == groups.size) { "Duplicate group IDs" }
            val groupIds = groups.map { it.id }.toSet()
            require(profiles.all { it.groupId in groupIds }) { "Profile refers to a missing group" }
            profiles.forEach { it.requireBean() }
        }
        if (rules != null) require(rules.map { it.id }.distinct().size == rules.size) { "Duplicate rule IDs" }
        if (settings != null) require(settings.map { it.key }.distinct().size == settings.size) { "Duplicate settings keys" }
        BackupRestore.commit(settings) {
            if (profiles != null && groups != null) {
                SagerDatabase.proxyDao.reset()
                SagerDatabase.groupDao.reset()
                SagerDatabase.groupDao.insert(groups)
                SagerDatabase.proxyDao.insert(profiles)
            }
            if (rules != null) {
                SagerDatabase.rulesDao.reset()
                SagerDatabase.rulesDao.insert(rules)
            }
        }
    }


    private fun showMessage(message: String) {
        MessageStore.showMessage(message)
    }

    private fun showMessage(@StringRes resId: Int) {
        MessageStore.showMessage(requireActivity(), resId)
    }

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        MessageStore.showMessage(requireActivity(), resId, *args)
    }

}
