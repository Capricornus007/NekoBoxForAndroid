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
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.FastestCandidateResolutionError
import io.nekohasekai.sagernet.fmt.internal.FastestCandidateResolutionException
import io.nekohasekai.sagernet.fmt.internal.FastestCandidateResolver
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class ChainSettingsActivity : ProfileSettingsActivity<ChainBean>(R.layout.layout_chain_settings) {

    companion object {
        const val EXTRA_STRATEGY = "chain_strategy"
        const val EXTRA_CANDIDATE_MODE = "fastest_candidate_mode"
    }

    private var currentStrategy = ChainBean.STRATEGY_CHAIN

    override fun createEntity() = ChainBean().apply {
        strategy = intent.getIntExtra(EXTRA_STRATEGY, ChainBean.STRATEGY_CHAIN)
        candidateMode = intent.getIntExtra(
            EXTRA_CANDIDATE_MODE,
            ChainBean.CANDIDATE_MODE_MANUAL,
        )
        if (strategy == ChainBean.STRATEGY_FASTEST &&
            candidateMode == ChainBean.CANDIDATE_MODE_REGEX
        ) {
            sourceGroupId = defaultSubscriptionGroupId()
        }
        currentStrategy = strategy
    }

    val proxyList = ArrayList<ProxyEntity>()

    override fun ChainBean.init() {
        currentStrategy = strategy
        DataStore.profileName = name
        DataStore.serverProtocol = proxies.joinToString(",")
        DataStore.fastestCandidateMode = candidateMode
        DataStore.fastestSourceGroup = sourceGroupId
        DataStore.fastestNameRegex = nameRegex
        DataStore.fastestIgnoreCase = ignoreCase
    }

    override fun ChainBean.serialize() {
        name = DataStore.profileName
        strategy = currentStrategy
        candidateMode = DataStore.fastestCandidateMode
        sourceGroupId = DataStore.fastestSourceGroup
        nameRegex = DataStore.fastestNameRegex.orEmpty()
        ignoreCase = DataStore.fastestIgnoreCase
        proxies = if (isRegexFastest()) {
            DataStore.serverProtocol.split(",").mapNotNull {
                it.takeIf(String::isNotBlank)?.toLongOrNull()
            }
        } else {
            proxyList.map { it.id }
        }
        initializeDefaultValues()
    }

    override suspend fun saveAndExit() {
        if (isRegexFastest()) {
            try {
                FastestCandidateResolver.resolve(regexFastestBean())
            } catch (e: FastestCandidateResolutionException) {
                onMainDispatcher {
                    Toast.makeText(
                        this@ChainSettingsActivity,
                        fastestResolutionMessage(e),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return
            }
        } else if (proxyList.isEmpty()) {
            onMainDispatcher {
                Toast.makeText(this@ChainSettingsActivity, R.string.profile_empty, Toast.LENGTH_SHORT)
                    .show()
            }
            return
        }
        super.saveAndExit()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        if (currentStrategy != ChainBean.STRATEGY_FASTEST) {
            addPreferencesFromResource(R.xml.name_preferences)
            return
        }

        addPreferencesFromResource(R.xml.fastest_preferences)

        val candidateMode = findPreference<SimpleMenuPreference>(Key.FASTEST_CANDIDATE_MODE)!!
        val sourceGroup = findPreference<SimpleMenuPreference>(Key.FASTEST_SOURCE_GROUP)!!
        val regexOptions = findPreference<PreferenceCategory>(Key.FASTEST_REGEX_OPTIONS)!!
        val preview = findPreference<Preference>(Key.FASTEST_REGEX_PREVIEW)!!

        val subscriptionGroups = SagerDatabase.groupDao.allGroups()
            .filter { it.type == GroupType.SUBSCRIPTION }
        sourceGroup.entries = subscriptionGroups.map { it.displayName() }.toTypedArray()
        sourceGroup.entryValues = subscriptionGroups.map { it.id.toString() }.toTypedArray()
        sourceGroup.isEnabled = subscriptionGroups.isNotEmpty()
        fun selectDefaultSourceGroupIfNeeded() {
            if (DataStore.fastestSourceGroup == 0L) {
                DataStore.fastestSourceGroup = subscriptionGroups.firstOrNull()?.id ?: 0L
            }
        }

        fun updateCandidateMode(mode: Int) {
            val useRegex = mode == ChainBean.CANDIDATE_MODE_REGEX
            if (useRegex) selectDefaultSourceGroupIfNeeded()
            regexOptions.isVisible = useRegex
            configurationList.isVisible = !useRegex
            configurationDivider.isVisible = !useRegex
            if (!useRegex && proxyList.isEmpty()) {
                runOnDefaultDispatcher {
                    configurationAdapter.reload()
                }
            }
        }

        updateCandidateMode(DataStore.fastestCandidateMode)
        candidateMode.setOnPreferenceChangeListener { _, newValue ->
            if (DataStore.fastestCandidateMode == ChainBean.CANDIDATE_MODE_MANUAL) {
                DataStore.serverProtocol = proxyList.joinToString(",") { it.id.toString() }
            }
            updateCandidateMode(newValue.toString().toInt())
            true
        }
        preview.setOnPreferenceClickListener {
            runOnDefaultDispatcher {
                val result = runCatching {
                    FastestCandidateResolver.resolve(regexFastestBean())
                }
                onMainDispatcher {
                    showRegexPreview(result)
                }
            }
            true
        }
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager
    lateinit var configurationDivider: View

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentStrategy = intent.getIntExtra(EXTRA_STRATEGY, ChainBean.STRATEGY_CHAIN)
        supportActionBar!!.setTitle(
            when (currentStrategy) {
                ChainBean.STRATEGY_WATERFALL -> R.string.waterfall_settings
                ChainBean.STRATEGY_FASTEST -> R.string.fastest_settings
                else -> R.string.chain_settings
            },
        )
        configurationList = findViewById(R.id.configuration_list)
        configurationDivider = findViewById(R.id.list_cell)
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter
        FastScrollerBuilder(configurationList).useMd2Style().build()

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START,
        ) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
                if (viewHolder is ProfileHolder) {
                    super.getSwipeDirs(recyclerView, viewHolder)
                } else {
                    0
                }

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
                if (viewHolder is ProfileHolder) {
                    super.getDragDirs(recyclerView, viewHolder)
                } else {
                    0
                }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) {
                    false
                } else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition,
                        target.bindingAdapterPosition,
                    )
                    true
                }
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

        if (!isRegexFastest()) {
            runOnDefaultDispatcher {
                configurationAdapter.reload()
            }
        }
    }

    private fun isRegexFastest() =
        currentStrategy == ChainBean.STRATEGY_FASTEST &&
            DataStore.fastestCandidateMode == ChainBean.CANDIDATE_MODE_REGEX

    private fun defaultSubscriptionGroupId(): Long {
        val selected = SagerDatabase.groupDao.getById(DataStore.selectedGroup)
        return selected?.takeIf { it.type == GroupType.SUBSCRIPTION }?.id
            ?: SagerDatabase.groupDao.allGroups().firstOrNull {
                it.type == GroupType.SUBSCRIPTION
            }?.id
            ?: 0L
    }

    private fun regexFastestBean() = ChainBean().apply {
        strategy = ChainBean.STRATEGY_FASTEST
        candidateMode = ChainBean.CANDIDATE_MODE_REGEX
        sourceGroupId = DataStore.fastestSourceGroup
        nameRegex = DataStore.fastestNameRegex.orEmpty()
        ignoreCase = DataStore.fastestIgnoreCase
    }

    private fun fastestResolutionMessage(exception: FastestCandidateResolutionException): String {
        return when (exception.error) {
            FastestCandidateResolutionError.EMPTY_REGEX ->
                getString(R.string.fastest_regex_error_empty)

            FastestCandidateResolutionError.INVALID_REGEX -> getString(
                R.string.fastest_regex_error_invalid,
                exception.cause?.message.orEmpty(),
            )

            FastestCandidateResolutionError.SOURCE_GROUP_MISSING ->
                getString(R.string.fastest_regex_error_source_missing)

            FastestCandidateResolutionError.SOURCE_GROUP_NOT_SUBSCRIPTION ->
                getString(R.string.fastest_regex_error_source_not_subscription)

            FastestCandidateResolutionError.DUPLICATE_MANUAL_CANDIDATES ->
                getString(R.string.profile_reference_not_allowed)
        }
    }

    private fun showRegexPreview(result: Result<List<ProxyEntity>>) {
        val message = result.fold(
            onSuccess = { candidates ->
                if (candidates.isEmpty()) {
                    getString(R.string.fastest_regex_preview_no_matches)
                } else {
                    buildString {
                        append(getString(R.string.fastest_regex_preview_count, candidates.size))
                        append("\n\n")
                        append(candidates.joinToString("\n") { it.displayName() })
                    }
                }
            },
            onFailure = { error ->
                if (error is FastestCandidateResolutionException) {
                    fastestResolutionMessage(error)
                } else {
                    error.message ?: error.toString()
                }
            },
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fastest_regex_preview)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            proxyList.clear()
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
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.dirty = true
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }
    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false
        if (proxyList.withIndex().any { (index, entity) ->
                index != replacing - 1 && entity.id == profile.id
            }
        ) {
            return false
        }

        if (profile.type == ProxyEntity.TYPE_CHAIN && chainContainsDynamicProfile(profile)) {
            return false
        }

        when (currentStrategy) {
            ChainBean.STRATEGY_CHAIN, ChainBean.STRATEGY_FASTEST -> {
                if (profile.type == ProxyEntity.TYPE_WATERFALL ||
                    profile.type == ProxyEntity.TYPE_FASTEST
                ) {
                    return false
                }
            }

            ChainBean.STRATEGY_WATERFALL -> {
                if (profile.type == ProxyEntity.TYPE_WATERFALL) return false
            }
        }

        return DataStore.editingId == 0L || !testProfileContains(profile, DataStore.editingId)
    }

    fun chainContainsDynamicProfile(profile: ProxyEntity, visited: MutableSet<Long> = HashSet()): Boolean {
        if (!visited.add(profile.id)) return false
        if (profile.type == ProxyEntity.TYPE_WATERFALL ||
            profile.type == ProxyEntity.TYPE_FASTEST
        ) {
            return true
        }
        if (profile.type != ProxyEntity.TYPE_CHAIN) return false
        return ProfileManager.getProfiles(profile.chainBean?.proxies.orEmpty()).any {
            chainContainsDynamicProfile(it, visited)
        }
    }

    fun testProfileContains(profile: ProxyEntity, profileId: Long, visited: MutableSet<Long> = HashSet()): Boolean {
        if (!visited.add(profile.id)) return false
        if (profile.id == profileId) return true
        val proxies = profile.chainBean?.proxies ?: return false
        if (proxies.contains(profileId)) return true
        for (entity in ProfileManager.getProfiles(proxies)) {
            if (testProfileContains(entity, profileId, visited)) return true
        }
        return false
    }

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
                                .setMessage(R.string.profile_reference_not_allowed)
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
                        }
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
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

    inner class ProfileHolder(binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val shareButton = binding.shareIcon

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

            shareButton.isVisible = false
        }
    }
}
