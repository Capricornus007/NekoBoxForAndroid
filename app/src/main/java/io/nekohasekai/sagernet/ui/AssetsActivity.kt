package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.RuleAssets
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import java.io.File
import java.io.FileWriter
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_HTTP_JSON_BYTES = 10L * 1024 * 1024
private const val MAX_RULE_ASSET_BYTES = 256L * 1024 * 1024

/**
 * Reduce an untrusted file name (e.g. a content provider's DISPLAY_NAME) to a safe basename
 * for an importable asset, or null if invalid. Strips any directory component and rejects
 * path separators, "..", NUL, and non-".db" names so it can't be used for path traversal.
 */
fun sanitizeAssetFileName(raw: String): String? {
    // Reject anything that isn't already a bare basename: a path separator, "..", NUL, or a
    // name whose basename differs from the input (i.e. it carried a directory component).
    if (raw.isBlank()) return null
    if (raw.contains('/') || raw.contains('\\') || raw.contains('\u0000')) return null
    if (raw == "." || raw == "..") return null
    val base = File(raw).name
    if (base != raw) return null // defensive: any residual path component
    if (!base.endsWith(".db")) return null
    return base
}

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.START,
        ) {

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index < 2) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                adapter.remove(index)
                undoManager.remove(index to (viewHolder as AssetHolder).file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ) = false
        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    val assetNames = RuleAssets.MANAGED

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            val rawName = contentResolver.query(file, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
                .substringAfterLast('/')
                .substringAfter(':')

            // DISPLAY_NAME is attacker-controllable (a malicious content provider returns any
            // name). Reduce to a safe basename and reject path separators / ".." so a name like
            // "../../databases/sager_net.db" can't traverse out of the assets dir.
            val fileName = sanitizeAssetFileName(rawName) ?: run {
                alert(getString(R.string.route_not_asset, rawName)).show()
                return@registerForActivityResult
            }
            val filesDir = getExternalFilesDir(null) ?: filesDir

            runOnDefaultDispatcher {
                runCatching {
                    val outFile = File(filesDir, fileName)
                    // Defense-in-depth: assert the resolved paths stay within filesDir.
                    val baseCanonical = filesDir.canonicalPath + File.separator
                    require(outFile.canonicalPath.startsWith(baseCanonical)) { "unsafe path" }
                    outFile.parentFile?.mkdirs()

                    // Copy into a temp file first, then atomically replace the
                    // target so a failed copy can't leave a partial/corrupt .db.
                    val tmpFile = File(outFile.parentFile, "$fileName.tmp")
                    require(tmpFile.canonicalPath.startsWith(baseCanonical)) { "unsafe path" }
                    try {
                        contentResolver.openInputStream(file)?.use(tmpFile.outputStream())
                            ?: error("Unable to open the selected file")

                        if (outFile.exists()) outFile.delete()
                        if (!tmpFile.renameTo(outFile)) {
                            error("Unable to save the imported asset")
                        }
                    } finally {
                        if (tmpFile.exists()) tmpFile.delete()
                    }

                    File(outFile.parentFile, outFile.nameWithoutExtension + ".version.txt").apply {
                        if (isFile) delete()
                        createNewFile()
                        FileWriter(this).use { it.write("Custom") }
                    }

                    adapter.reloadAssets()
                }.onFailure {
                    Logs.w(it)
                    onMainDispatcher {
                        if (!isFinishing && !isDestroyed) {
                            alert(it.readableMessage).tryToShow()
                        }
                    }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
        }
        return false
    }

    inner class AssetAdapter :
        RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val files = filesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".db") && it.name !in assetNames }
            assets.clear()
            assets.add(File(filesDir, "geoip.db"))
            assets.add(File(filesDir, "geosite.db"))
            if (files != null) assets.addAll(files)

            layout.refreshLayout.post {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                groups.forEach { it.deleteRecursively() }
            }
        }
    }

    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    try {
                        versionFile.readText().trim()
                    } catch (e: Throwable) {
                        snackbar(e.readableMessage)
                        "<unknown>"
                    }
                } else {
                    getString(
                        R.string.asset_version_unknown,
                        DateFormat.getDateFormat(app).format(Date(file.lastModified())),
                    )
                }
            } else {
                "<unknown>"
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)

            binding.rulesUpdate.isInvisible = file.name !in assetNames
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching {
                        RuleAssets.updateAsset(file, versionFile, localVersion)
                    }.onSuccess { outcome ->
                        val message = if (outcome == RuleAssets.Outcome.UPDATED) {
                            R.string.route_asset_updated
                        } else {
                            R.string.route_asset_no_update
                        }
                        onMainDispatcher { snackbar(message).show() }
                    }.onFailure {
                        onMainDispatcher {
                            alert(it.readableMessage).tryToShow()
                        }
                    }

                    onMainDispatcher {
                        adapter.reloadAssets()
                        binding.rulesUpdate.isInvisible = false
                        binding.subscriptionUpdateProgress.isInvisible = true
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }
}
