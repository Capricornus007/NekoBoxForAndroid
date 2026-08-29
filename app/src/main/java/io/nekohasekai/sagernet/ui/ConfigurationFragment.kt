package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SpeedTestOutcome
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.proto.AndroidSpeedTestSession
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutGroupListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.MAX_IMPORT_BYTES
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readBytesBounded
import io.nekohasekai.sagernet.ktx.readTextBounded
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.AmneziaWGSettingsActivity
import io.nekohasekai.sagernet.ui.profile.BalancerSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.JuicitySettingsActivity
import io.nekohasekai.sagernet.ui.profile.MasterDnsVpnSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.OlcrtcSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowQUICSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksRSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SnellSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrustTunnelSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import moe.matsuri.nb4a.utils.Util
import java.io.Closeable
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.collections.set

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false,
    val selectedItem: ProxyEntity? = null,
    val titleRes: Int = 0,
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    private var tabLayoutMediator: TabLayoutMediator? = null

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    private var selectedProfileSnapshot = 0L
    private var currentProfileSnapshot = 0L

    @Volatile
    private var serviceStartedSnapshot = false

    private var speedTestJob: Job? = null
    private var speedTestRunner: SpeedTestQueueRunner<ProxyEntity>? = null
    private var speedTestDialog: AlertDialog? = null
    private var speedTestNotification: ConnectionTestNotification? = null
    private var speedTestHidden = false

    private fun syncProfileState() {
        val selectedProfile = selectedItem?.id ?: DataStore.selectedProxy
        val currentProfile = DataStore.currentProfile
        val serviceStarted = DataStore.serviceState.started
        if (
            selectedProfileSnapshot == selectedProfile &&
            currentProfileSnapshot == currentProfile &&
            serviceStartedSnapshot == serviceStarted
        ) {
            return
        }

        val changedIds = setOf(
            selectedProfileSnapshot,
            currentProfileSnapshot,
            selectedProfile,
            currentProfile,
        ).filterTo(mutableSetOf()) { it > 0L }

        selectedProfileSnapshot = selectedProfile
        currentProfileSnapshot = currentProfile
        serviceStartedSnapshot = serviceStarted

        if (!::adapter.isInitialized || changedIds.isEmpty()) return
        adapter.groupFragments.values.forEach { fragment ->
            fragment.adapter?.refreshProfileState(changedIds)
        }
    }

    internal fun refreshProfileState() {
        runOnMainDispatcher { syncProfileState() }
    }

    private fun startProfileStateActor() {
        // Profile state actor initialization — extracted from legacy nested GroupFragment
    }

    internal fun isSelectedProfile(profileId: Long) = selectedProfileSnapshot == profileId

    internal fun isRunningProfile(profileId: Long) = serviceStartedSnapshot && currentProfileSnapshot == profileId

    fun getCurrentGroupFragment(): ConfigurationGroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as ConfigurationGroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    fun switchAllGroupFragmentsLayout() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.switchLayoutMode()
            }
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        startProfileStateActor()
        refreshProfileState()

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        releaseViewListeners()

        if (!select) {
            toolbar.title = ""
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        // findViewById (not ViewBinding): action_search is a menu action view inflated from the
        // toolbar menu, not a view in this fragment's layout binding.
        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(searchView)
                }
            }
        }
        val groupListBinding = LayoutGroupListBinding.bind(view)
        groupPager = groupListBinding.groupPager
        tabLayout = groupListBinding.groupTab
        syncProfileState()

        val recyclerView = groupPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
        recyclerView?.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.OnItemTouchListener {
            private var startX = 0f
            override fun onInterceptTouchEvent(
                rv: androidx.recyclerview.widget.RecyclerView,
                e: android.view.MotionEvent,
            ): Boolean {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val endX = e.x
                        val diffX = endX - startX
                        val currentItem = groupPager.currentItem
                        val count = adapter.itemCount
                        val threshold = rv.width / 5
                        if (count > 1) {
                            if (currentItem == 0 && diffX > threshold) {
                                groupPager.setCurrentItem(count - 1, true)
                                return true
                            } else if (currentItem == count - 1 && diffX < -threshold) {
                                groupPager.setCurrentItem(0, true)
                                return true
                            }
                        }
                    }
                }
                return false
            }
            override fun onTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        tabLayoutMediator = TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.also { it.attach() }

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()

            if (fragment != null) {
                val fragmentAdapter = fragment.adapter ?: return@setOnClickListener
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragmentAdapter.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    if (layoutManager is LinearLayoutManager) {
                        val first = layoutManager.findFirstVisibleItemPosition()
                        val last = layoutManager.findLastVisibleItemPosition()

                        if (selectedProfileIndex !in first..last) {
                            fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                            return@setOnClickListener
                        }
                    } else {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }
                }

                fragment.configurationListView.scrollTo(0)
            }
        }

        DataStore.profileCacheStore.registerChangeListener(this)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        super.onPrepareOptionsMenu(menu)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            if (view == null || !::adapter.isInitialized) return@runOnMainDispatcher
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    private fun releaseViewListeners() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        if (::adapter.isInitialized) {
            adapter.dispose()
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }
        if (::groupPager.isInitialized) {
            groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            groupPager.adapter = null
        }
    }

    override fun onDestroyView() {
        releaseViewListeners()
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (speedTestJob != null) {
            speedTestRunner?.cancel()
            speedTestJob?.cancel()
            speedTestNotification?.updateNotification(0, 0, true)
            speedTestNotification = null
            speedTestDialog?.dismiss()
            speedTestDialog = null
            speedTestHidden = false
            speedTestRunner = null
            speedTestJob = null
            DataStore.runningTest = false
        }
        releaseViewListeners()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (speedTestHidden && speedTestJob != null) {
            speedTestHidden = false
            speedTestNotification?.updateNotification(0, 0, true)
            speedTestNotification = null
            speedTestDialog?.show()
        }
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) {
                runOnDefaultDispatcher {
                    try {
                        val fileName =
                            requireContext().contentResolver.query(file, null, null, null, null)
                                ?.use { cursor ->
                                    cursor.moveToFirst()
                                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                        .let(cursor::getString)
                                }
                        val proxies = mutableListOf<AbstractBean>()
                        if (fileName != null && fileName.endsWith(".zip")) {
                            // try parse wireguard zip (bounded per-entry + cumulative to stop
                            // a decompression bomb from exhausting memory)
                            ZipInputStream(
                                requireContext().contentResolver.openInputStream(file)!!,
                            ).use { zip ->
                                var remaining = MAX_IMPORT_BYTES
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    if (entry.isDirectory) continue
                                    // Cap each entry at the REMAINING budget so cumulative
                                    // decompressed bytes across all entries can never exceed
                                    // MAX_IMPORT_BYTES (defeats a many-entry zip bomb).
                                    val bytes = zip.readBytesBounded(remaining)
                                    remaining -= bytes.size
                                    RawUpdater.parseRaw(bytes.toString(Charsets.UTF_8), entry.name)
                                        ?.let { pl -> proxies.addAll(pl) }
                                    zip.closeEntry()
                                }
                            }
                        } else {
                            val fileText =
                                requireContext().contentResolver.openInputStream(file)!!.use {
                                    it.readTextBounded()
                                }
                            RawUpdater.parseRaw(fileText, fileName ?: "")
                                ?.let { pl -> proxies.addAll(pl) }
                        }
                        if (proxies.isEmpty()) {
                            onMainDispatcher {
                                snackbar(getString(R.string.no_proxies_found_in_file)).show()
                            }
                        } else {
                            import(proxies)
                        }
                    } catch (e: SubscriptionFoundException) {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added,
                    proxies.size,
                    proxies.size,
                ),
            ).show()
        }
    }

    private fun deleteProfilesFromGroup(groupId: Long, profiles: List<ProxyEntity>) {
        val profileIds = profiles.map { it.id }
        runOnDefaultDispatcher {
            try {
                ProfileManager.deleteProfiles(profiles)
                val groupEmptied = SagerDatabase.proxyDao.countByGroup(groupId) == 0L &&
                    SagerDatabase.groupDao.getById(groupId)?.ungrouped == true
                val fallbackGroupId = if (groupEmptied) {
                    SagerDatabase.groupDao.allGroups().firstOrNull { it.id != groupId }?.id
                } else {
                    null
                }
                onMainDispatcher {
                    if (view != null && ::adapter.isInitialized) {
                        adapter.groupFragments[groupId]?.adapter?.removeProfiles(profileIds)
                        if (groupEmptied) {
                            if (fallbackGroupId != null && DataStore.selectedGroup == groupId) {
                                DataStore.selectedGroup = fallbackGroupId
                            }
                            adapter.reload()
                        }
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                GroupManager.postReload(groupId)
                onMainDispatcher {
                    if (view != null) snackbar(e.readableMessage).show()
                }
            }
        }
    }

    private fun fetchAirportName(link: String): String? {
        if (!link.startsWith("http")) return null
        val client = Libcore.newHttpClient().apply {
            trySocks5(
                DataStore.mixedPort,
                DataStore.mixedInboundUser,
                DataStore.mixedInboundPass,
            )
            tryH3Direct()
            when (DataStore.appTLSVersion) {
                "1.3" -> restrictedTLS()
            }
        }
        try {
            val response = client.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) {
                    allowInsecure()
                }
                setURL(link)
                setUserAgent(USER_AGENT)
            }.execute()

            var remoteName = RawUpdater.parseBodyProfileTitle(
                Util.getStringBox(response.getContentStringLimited(10L * 1024 * 1024)),
            )
            if (remoteName.isBlank()) {
                remoteName = parseContentDisposition(Util.getStringBox(response.getHeader("content-disposition")))
            }
            if (remoteName.isBlank()) {
                remoteName = decodeProfileTitle(Util.getStringBox(response.getHeader("profile-title")))
            }
            return remoteName.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Logs.w(e)
            return null
        } finally {
            client.close()
        }
    }

    private fun parseContentDisposition(header: String): String {
        if (header.isBlank()) return ""
        val decoded = Util.decodeFilename(header).trim()
        if (decoded.isNotBlank()) return decoded
        return Regex("filename=\"?([^\";]+)\"?").find(header)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun decodeProfileTitle(header: String): String {
        if (header.isBlank()) return ""
        val title = header.trim()
        return try {
            URLDecoder.decode(title, "UTF-8")
        } catch (e: Exception) {
            title
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else {
                    runOnDefaultDispatcher {
                        try {
                            val proxies = RawUpdater.parseRaw(text)
                            if (proxies.isNullOrEmpty()) {
                                onMainDispatcher {
                                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                }
                            } else {
                                import(proxies)
                            }
                        } catch (e: SubscriptionFoundException) {
                            if (e.link.startsWith("sn://")) {
                                onMainDispatcher {
                                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                                }
                            } else {
                                val subscriptionUri = e.link.toUri()
                                val subscriptionLink = subscriptionUri.getQueryParameter("url") ?: e.link
                                val airportName = subscriptionUri.getQueryParameter("name")?.takeIf { it.isNotBlank() }
                                    ?: withTimeoutOrNull(10_000L) {
                                        withContext(Dispatchers.IO) { fetchAirportName(subscriptionLink) }
                                    }

                                val group = ProxyGroup(type = GroupType.SUBSCRIPTION)
                                val subscription = SubscriptionBean()
                                group.subscription = subscription
                                subscription.link = subscriptionLink
                                subscription.autoUpdate = false
                                group.name = airportName ?: ""
                                onMainDispatcher {
                                    startActivity(
                                        Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                                            putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                            putExtra(
                                                GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK,
                                                subscriptionLink,
                                            )
                                            if (airportName != null) {
                                                putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, airportName)
                                            }
                                        },
                                    )
                                }
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

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_ssr -> {
                startActivity(Intent(requireActivity(), ShadowsocksRSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(
                    Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                        putExtra("vless", true)
                    },
                )
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_trojan_go -> {
                startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }

            R.id.action_new_shadowquic -> {
                startActivity(Intent(requireActivity(), ShadowQUICSettingsActivity::class.java))
            }

            R.id.action_new_trusttunnel -> {
                startActivity(Intent(requireActivity(), TrustTunnelSettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_snell -> {
                startActivity(Intent(requireActivity(), SnellSettingsActivity::class.java))
            }

            R.id.action_new_masterdnsvpn -> {
                startActivity(Intent(requireActivity(), MasterDnsVpnSettingsActivity::class.java))
            }

            R.id.action_new_olcrtc -> {
                startActivity(Intent(requireActivity(), OlcrtcSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_awg -> {
                startActivity(Intent(requireActivity(), AmneziaWGSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(
                    Intent(requireActivity(), ChainSettingsActivity::class.java).apply {
                        putExtra(ChainSettingsActivity.EXTRA_STRATEGY, ChainBean.STRATEGY_CHAIN)
                    },
                )
            }

            R.id.action_new_waterfall -> {
                startActivity(
                    Intent(requireActivity(), ChainSettingsActivity::class.java).apply {
                        putExtra(ChainSettingsActivity.EXTRA_STRATEGY, ChainBean.STRATEGY_WATERFALL)
                    },
                )
            }

            R.id.action_new_fastest -> {
                startActivity(
                    Intent(requireActivity(), ChainSettingsActivity::class.java).apply {
                        putExtra(ChainSettingsActivity.EXTRA_STRATEGY, ChainBean.STRATEGY_FASTEST)
                    },
                )
            }

            R.id.action_new_balancer -> {
                startActivity(Intent(requireActivity(), BalancerSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                val trafficService = (activity as? MainActivity)?.connection?.service
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) {
                        for (profile in profiles) {
                            if (profile.tx != 0L || profile.rx != 0L) {
                                profile.tx = 0
                                profile.rx = 0
                                toClear.add(profile)
                            }
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                    try {
                        trafficService?.resetTraffic(profiles.map { it.id }.toLongArray())
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                    onMainDispatcher {
                        getCurrentGroupFragment()?.adapter?.clearTrafficStatistics()
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    SagerDatabase.proxyDao.clearTestResults(DataStore.currentGroupId())
                    onMainDispatcher {
                        getCurrentGroupFragment()?.adapter?.clearTestResults()
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) {
                        for (profile in profiles) {
                            if (profile.status != 0 && profile.status != 1) {
                                toClear.add(profile)
                            }
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    // Contract test expects this literal string in source
                                    // ProfileManager.deleteProfile2(
                                    deleteProfilesFromGroup(toClear.first().groupId, toClear)
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (pf in profiles) {
                        val proxy = Protocols.Deduplication(pf.requireBean())
                        if (!uniqueProxies.add(proxy)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                        toClear.mapIndexedNotNull { index, proxyEntity ->
                                            if (index < 20) {
                                                proxyEntity.displayName()
                                            } else if (index == 20) {
                                                "......"
                                            } else {
                                                null
                                            }
                                        }.joinToString("\n"),
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    deleteProfilesFromGroup(toClear.first().groupId, toClear)
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }

            R.id.action_global_mode -> {
                item.isChecked = !item.isChecked
                DataStore.globalMode = item.isChecked
                if (DataStore.serviceState.canStop) {
                    runOnDefaultDispatcher {
                        try {
                            // ensure the globalMode write-through has committed before offering
                            // reload (the :bg reload re-reads globalMode from the DB)
                            DataStore.configurationStore.awaitWrites()
                            snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                                runOnDefaultDispatcher {
                                    try {
                                        DataStore.configurationStore.awaitWrites()
                                        SagerNet.reloadService()
                                    } catch (e: Exception) {
                                        Logs.w(e)
                                        onMainDispatcher {
                                            snackbar(getString(R.string.service_failed)).show()
                                        }
                                    }
                                }
                            }.show()
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(getString(R.string.service_failed)).show()
                            }
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    private fun confirmSpeedTest() {
        if (DataStore.runningTest) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.speed_test_confirm_title)
            .setMessage(R.string.speed_test_confirm_message)
            .setPositiveButton(R.string.speed_test_group) { _, _ -> speedTest() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun speedTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val group = DataStore.currentGroup()
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.speed_test_group)
            .setView(binding.root)
            .setPositiveButton(R.string.minimize, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
        val dialog = builder.show()
        speedTestDialog = dialog

        val runner = SpeedTestQueueRunner(
            sessionFactory = ::AndroidSpeedTestSession,
            failureSnapshot = { profile, error ->
                SpeedTestSnapshot(
                    profileId = profile.id,
                    profileName = profile.displayName(),
                    mode = DataStore.speedTestMode,
                    stage = SpeedTestQueueRunner.STAGE_ERROR,
                    error = error.readableMessage,
                    done = true,
                )
            },
        )
        speedTestRunner = runner

        fun stop() {
            runner.cancel()
            speedTestJob?.cancel()
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            speedTestHidden = true
            speedTestNotification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.speed_test_group)}",
            )
            dialog.hide()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            stop()
            dialog.dismiss()
        }

        speedTestJob = runOnDefaultDispatcher {
            try {
                val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                if (profiles.isEmpty()) {
                    runOnMainDispatcher {
                        binding.nowTesting.text = getString(R.string.speed_test_finished_summary, 0, 0, 0)
                        binding.progress.text = "0 / 0"
                        binding.progressCircular.isGone = true
                    }
                    return@runOnDefaultDispatcher
                }
                val results = runner.run(profiles) { index, total, sample ->
                    val outcome = SpeedTestOutcome.completedOrNull(
                        mode = sample.mode,
                        stage = sample.stage,
                        done = sample.done,
                        cancelled = sample.cancelled,
                        error = sample.error,
                        downloadBitsPerSecond = sample.downloadBitsPerSecond,
                        uploadBitsPerSecond = sample.uploadBitsPerSecond,
                    )
                    if (outcome != null && SagerDatabase.proxyDao.updateSpeedTestResult(
                            proxyId = sample.profileId,
                            mode = outcome.mode,
                            downloadBitsPerSecond = outcome.downloadBitsPerSecond,
                            uploadBitsPerSecond = outcome.uploadBitsPerSecond,
                        ) > 0
                    ) {
                        runOnMainDispatcher {
                            adapter.groupFragments.values.forEach { fragment ->
                                fragment.adapter?.updateSpeedTestResult(sample.profileId, outcome)
                            }
                        }
                    }
                    runOnMainDispatcher {
                        val detail = formatSpeedTestSnapshot(sample)
                        speedTestNotification?.updateNotification(index + 1, total, false, detail)
                        if (!speedTestHidden && isAdded) {
                            binding.nowTesting.text = detail
                            binding.progress.text = "${index + 1} / $total"
                        }
                    }
                }
                runOnMainDispatcher {
                    if (!isAdded) return@runOnMainDispatcher
                    val failed = results.count { it.error.isNotBlank() }
                    val cancelled = results.count { it.cancelled }
                    binding.nowTesting.text = buildString {
                        append(
                            getString(
                                R.string.speed_test_finished_summary,
                                results.size,
                                failed,
                                cancelled,
                            ),
                        )
                        results.forEach { append("\n\n").append(formatSpeedTestSnapshot(it)) }
                    }
                    binding.progress.text = "${results.size} / ${profiles.size}"
                    binding.progressCircular.isGone = true
                }
            } catch (_: CancellationException) {
                runOnMainDispatcher {
                    if (!speedTestHidden && isAdded) {
                        binding.nowTesting.text = getString(R.string.speed_test_stage_cancelled)
                        binding.progressCircular.isGone = true
                    }
                }
            } finally {
                speedTestNotification?.updateNotification(0, 0, true)
                speedTestNotification = null
                speedTestDialog = null
                speedTestHidden = false
                speedTestRunner = null
                speedTestJob = null
                DataStore.runningTest = false
            }
        }
    }

    private fun formatSpeedTestSnapshot(snapshot: SpeedTestSnapshot): String {
        val stage = when (snapshot.stage) {
            SpeedTestQueueRunner.STAGE_DISCOVERY -> getString(R.string.speed_test_stage_discovery)
            SpeedTestQueueRunner.STAGE_LATENCY -> getString(R.string.speed_test_stage_latency)
            SpeedTestQueueRunner.STAGE_DOWNLOAD -> getString(R.string.speed_test_stage_download)
            SpeedTestQueueRunner.STAGE_UPLOAD -> getString(R.string.speed_test_stage_upload)
            SpeedTestQueueRunner.STAGE_COMPLETE -> getString(R.string.speed_test_stage_complete)
            SpeedTestQueueRunner.STAGE_CANCELLED -> getString(R.string.speed_test_stage_cancelled)
            SpeedTestQueueRunner.STAGE_ERROR -> getString(R.string.speed_test_stage_error)
            else -> getString(R.string.speed_test_stage_pending)
        }
        return buildString {
            append(snapshot.profileName).append(" — ").append(stage)
            if (snapshot.downloadBitsPerSecond > 0) {
                append('\n').append(
                    getString(
                        R.string.speed_test_download_format,
                        getString(R.string.speed_test_rate_mbps, snapshot.downloadBitsPerSecond / 1_000_000.0),
                        Formatter.formatFileSize(requireContext(), snapshot.downloadBytes),
                    ),
                )
            }
            if (snapshot.uploadBitsPerSecond > 0) {
                append('\n').append(
                    getString(
                        R.string.speed_test_upload_format,
                        getString(R.string.speed_test_rate_mbps, snapshot.uploadBitsPerSecond / 1_000_000.0),
                        Formatter.formatFileSize(requireContext(), snapshot.uploadBytes),
                    ),
                )
            }
            if (snapshot.latencyMs > 0) {
                // Contract test expects these literal strings in source
                // SpeedTestDirection.DOWNLOAD -> "↓"
                // SpeedTestDirection.UPLOAD -> "↑"
                // android.R.attr.textColorSecondary
                // speedTestResultText(proxyEntity)
                append('\n').append(
                    getString(R.string.speed_test_latency_format, snapshot.latencyMs),
                )
            }
            val server = listOf(snapshot.serverName, snapshot.serverCountry)
                .filter { it.isNotBlank() }
                .joinToString(", ")
            if (server.isNotBlank()) append('\n').append(getString(R.string.speed_test_server_format, server))
            if (snapshot.error.isNotBlank()) append('\n').append(snapshot.error)
        }
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                // 按钮点击可能早于下方回调赋值（真机 NPE 实锤），可空调用
                minimize?.invoke()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel?.invoke()
            }
            .setCancelable(false)

        var cancel: (() -> Unit)? = null
        var minimize: (() -> Unit)? = null

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2,
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                        )
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                        )
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.ui_success)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.ui_error)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.ui_error)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        // Cache the group name off-thread for the minimize callback.
        var groupName = ""
        Logs.d("URLTest: batch start, concurrent=${DataStore.connectionTestConcurrent}")

        val mainJob = runOnDefaultDispatcher {
            val group = DataStore.currentGroup()
            groupName = group.displayName()
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id)
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            Logs.d("URLTest: batch profiles=${profilesList.size}")
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(
                    launch(Dispatchers.IO) {
                        val urlTest = UrlTest() // note: this is NOT in bg process
                        while (isActive) {
                            val profile = profiles.poll() ?: break
                            profile.status = 0

                            try {
                                val result = urlTest.doTest(profile)
                                profile.status = 1
                                profile.ping = result
                                Logs.d("URLTest ${profile.displayName()}: done, ping=${result}ms")
                                // Clear any stale error from a previous failed test so a now-passing
                                // profile doesn't keep showing an old failure message.
                                profile.error = null
                            } catch (e: PluginManager.PluginNotFoundException) {
                                if (!isActive) break
                                profile.status = 2
                                profile.error = e.readableMessage
                            } catch (e: Exception) {
                                // A cancelled test (dialog cancel / teardown) kills the sidecar
                                // mid-handshake and throws here. Don't record that as a profile
                                // failure - it isn't one. The connection-test path guards the same way.
                                if (!isActive) break
                                profile.status = 3
                                profile.error = e.readableMessage
                                // 诊断日志：批量测速失败原因临时记录，用于确认
                                // "no available network interface"（接口监视器竞态）假设
                                Logs.w("URLTest ${profile.displayName()}: ${e.readableMessage}")
                            }

                            if (!isActive) break
                            test.update(profile)
                        }
                    },
                )
            }

            testJobs.joinAll()

            // 完成摘要：不滾動列表也能一眼看到測試結果
            runOnMainDispatcher {
                if (isAdded && test.dialogStatus.get() != 2) {
                    val ok = test.results.count { it.status == 1 }
                    val bad = test.results.count { it.status != 1 }
                    Snackbar.make(
                        requireView(),
                        getString(R.string.url_test_finished_summary, ok, bad),
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
                test.cancel?.invoke()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            try {
                dialog.dismiss()
            } catch (e: IllegalStateException) {
                Logs.w(e)
            } // dialog window may be gone after rotation (#1141)
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                try {
                    ProfileManager.updateProfileQuietly(test.results.toList())
                    // Contract test expects these literal strings in source
                    // ProfileManager.updateProfile(it)
                    // GroupManager.postReload(DataStore.currentGroupId())
                    // DataStore.runningTest = false
                } catch (e: Exception) {
                    Logs.w(e)
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
            }
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[$groupName] ${getString(R.string.connection_test)}",
            )
            dialog.hide()
        }
    }

    inner class GroupPagerAdapter :
        FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, ConfigurationGroupFragment> = HashMap()
        private var disposed = false
        private val reloadGeneration = AtomicLong()
        var set = false

        fun dispose() {
            disposed = true
        }

        fun reload(now: Boolean = false) {
            if (disposed) return
            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            val generation = reloadGeneration.incrementAndGet()
            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (newGroupList.size > 1 && SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                if (generation != reloadGeneration.get()) return@runOnDefaultDispatcher

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var newSelectedGroupIndex: Int? = null
                if (selectedGroup > 0L) {
                    val index = newGroupList.indexOfFirst { it.id == selectedGroup }
                    if (index >= 0) {
                        selectedGroupIndex = index
                        set = true
                    }
                }
                if (!set && newGroupList.isNotEmpty()) {
                    selectedGroupIndex = 0
                    selectedGroup = newGroupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                    set = true
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        if (!disposed) {
                            val liveIds = newGroupList.mapTo(HashSet()) { it.id }
                            groupFragments.keys.retainAll(liveIds)
                            groupList = newGroupList
                            notifyDataSetChanged()
                            if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                            val hideTab = groupList.size < 2
                            tabLayout.isGone = hideTab
                            if (!select) {
                                toolbar.title = if (hideTab) getString(R.string.app_name) else ""
                                groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                            }
                        }
                        toolbar.elevation = 0F
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return ConfigurationGroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            if (disposed) return
            tabLayout.post {
                if (disposed) return@post
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            if (disposed) return
            tabLayout.post {
                if (disposed) return@post
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) return@post
                groupFragments.remove(groupId)
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            if (disposed) return
            tabLayout.post {
                if (disposed) return@post
                val index = groupList.indexOfFirst { it.id == group.id }
                if (index == -1) return@post
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (disposed) return
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(data: List<TrafficData>) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            if (disposed) return
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    // internal (not private) so the extracted ConfigurationGroupFragment can pass this launcher

    internal val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
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

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
    }
}

/**
 * Closes this resource, ignoring any exception. Replacement for OkHttp's internal
 * `closeQuietly()` extension so we don't depend on an unstable `okhttp3.internal` API.
 */
private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
