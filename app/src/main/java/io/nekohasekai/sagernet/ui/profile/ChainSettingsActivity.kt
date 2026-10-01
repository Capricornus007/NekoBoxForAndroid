package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.chainContains
import io.nekohasekai.sagernet.fmt.internal.chainHops
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import moe.matsuri.nb4a.Protocols.getProtocolColor

// An earlier TCP-only proxy can carry encapsulated UDP, so only the exit's capability is
// definitive here. Other hints leave the requirements between hops conditional.
@StringRes
internal fun chainUdpHint(hops: List<ProxyEntity>): Int {
    val exit = hops.last()
    val bean = exit.requireBean()
    return when {
        exit.type == ProxyEntity.TYPE_HTTP || exit.type == ProxyEntity.TYPE_SSH || bean.network() == "tcp" ->
            R.string.chain_hint_udp_tcp_only

        bean is SOCKSBean && bean.sUoT == true -> R.string.chain_hint_udp_uot

        bean is SOCKSBean -> R.string.chain_hint_udp_socks

        else -> R.string.chain_hint_udp_generic
    }
}

class ChainSettingsActivity : ProfileSettingsActivity<ChainBean>(R.layout.layout_chain_settings) {

    override fun createEntity() = ChainBean()

    val proxyList = ArrayList<ProxyEntity>()

    override fun ChainBean.init() {
        DataStore.profileName = name!!
        DataStore.serverProtocol = proxies!!.joinToString(",")
    }

    override fun ChainBean.serialize() {
        name = DataStore.profileName
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.name_preferences)
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager
    private lateinit var chainHint: TextView
    private var hintGeneration = 0

    // Hop order plus the two things chaining does not change by itself: which side resolves
    // destination names and whether UDP survives the exit hop. Only the latest edit may write
    // the hint; an older lookup that finishes late is dropped.
    private fun updateHint() {
        val hops = proxyList.toList()
        val generation = ++hintGeneration
        runOnDefaultDispatcher {
            val text = hintText(hops)
            onMainDispatcher { if (generation == hintGeneration) chainHint.text = text }
        }
    }

    private fun hintText(hops: List<ProxyEntity>): String {
        if (hops.isEmpty()) return getString(R.string.chain_hint_empty)
        // The group's front proxy is dialed first and its landing proxy last, as in the config
        // builder; a nested chain is dialed through its own hops.
        val group = SagerDatabase.groupDao.getById(DataStore.editingGroup)
        val front = group?.frontProxy?.takeIf { it > 0 }?.let { SagerDatabase.proxyDao.getById(it) }
        val landing = group?.landingProxy?.takeIf { it > 0 }?.let { SagerDatabase.proxyDao.getById(it) }
        val order = listOfNotNull(front) + hops + listOfNotNull(landing)
        val dialed = order.flatMap { runCatching { chainHops(it) }.getOrDefault(listOf(it)) }
        val exit = dialed.last()
        val exitName = exit.displayName()
        return listOf(
            getString(R.string.chain_hint_order, order.joinToString(" \u2192 ") { it.displayName() }),
            if (DataStore.resolveDestination) {
                getString(R.string.chain_hint_dns_direct)
            } else {
                getString(R.string.chain_hint_dns_exit, exitName)
            },
            getString(chainUdpHint(dialed), exitName),
        ).joinToString("\n")
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.chain_settings)
        // ViewBinding intentionally not used here: content is set via the constructor
        // (@LayoutRes through ProfileSettingsActivity), not by inflating a binding.
        configurationList = findViewById(R.id.configuration_list)
        chainHint = findViewById(R.id.chain_hint)
        updateHint()
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter
        FastScrollerBuilder(configurationList).useMd2Style().build()

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START,
        ) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else {
                0
            }

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else {
                0
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = if (target !is ProfileHolder) {
                false
            } else {
                configurationAdapter.move(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition,
                )
                true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }
        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        // ViewBinding intentionally not used here: this reaches the preference list's
        // RecyclerView (recycler_view) via the fragment's root view hierarchy, which a single
        // layout binding does not own.
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view).apply {
            (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            val idList = DataStore.serverProtocol.split(",")
                .mapNotNull { it.takeIf { it.isNotBlank() }?.toLong() }
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    proxyList.add(profiles[id] ?: continue)
                }
            }
            onMainDispatcher {
                notifyDataSetChanged()
                updateHint()
            }
        }

        fun move(from: Int, to: Int) {
            proxyList.add(to - 1, proxyList.removeAt(from - 1))
            notifyItemMoved(from, to)
            DataStore.dirty = true
            updateHint()
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
            updateHint()
        }

        override fun getItemId(position: Int): Long = if (position == 0) 0 else proxyList[position - 1].id

        override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = if (viewType == 0) {
            AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
        } else {
            ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int = proxyList.size + 1
    }

    // A hop may not be this chain or contain it at any depth: that is the loop the config
    // builder rejects at connect time.
    fun testProfileAllowed(profile: ProxyEntity): Boolean = !chainContains(profile, DataStore.editingId)

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) {
                runOnDefaultDispatcher {
                    DataStore.dirty = true

                    val profile = ProfileManager.getProfile(
                        data!!.getLongExtra(
                            ProfileSelectActivity.EXTRA_PROFILE_ID,
                            0,
                        ),
                    )!!

                    if (!testProfileAllowed(profile)) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference)
                                .setMessage(R.string.circular_reference_sum)
                                .setPositiveButton(android.R.string.ok, null).show()
                        }
                    } else {
                        configurationList.post {
                            if (replacing != 0) {
                                proxyList[replacing - 1] = profile
                                configurationAdapter.notifyItemChanged(replacing)
                            } else {
                                proxyList.add(profile)
                                configurationAdapter.notifyItemInserted(proxyList.size)
                            }
                            updateHint()
                        }
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(
                        this@ChainSettingsActivity,
                        ProfileSelectActivity::class.java,
                    ),
                )
            }
        }
    }

    inner class ProfileHolder(binding: LayoutProfileBinding) : RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val shareLayout = binding.share

        fun bind(proxyEntity: ProxyEntity) {
            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = itemView.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(itemView.context, tx),
                    Formatter.formatFileSize(itemView.context, rx),
                )
            }

            editButton.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(
                    Intent(
                        this@ChainSettingsActivity,
                        ProfileSelectActivity::class.java,
                    ).apply {
                        putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                    },
                )
            }

            shareLayout.isVisible = false
        }
    }
}
