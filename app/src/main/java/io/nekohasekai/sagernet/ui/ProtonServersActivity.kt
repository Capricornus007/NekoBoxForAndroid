package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.ActivityProtonServersBinding
import io.nekohasekai.sagernet.fmt.proton.ProtonFilter
import io.nekohasekai.sagernet.fmt.proton.ProtonGroups
import io.nekohasekai.sagernet.fmt.proton.ProtonJson
import io.nekohasekai.sagernet.fmt.proton.ProtonNode
import io.nekohasekai.sagernet.fmt.proton.ProtonNodeCache
import io.nekohasekai.sagernet.fmt.proton.ProtonNodesState
import io.nekohasekai.sagernet.fmt.proton.ProtonSidecar
import io.nekohasekai.sagernet.fmt.proton.placeLabel
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.launch

/**
 * Proton 的伺服器清單頁。登入／登出留在 ProtonActivity，這裡只有「看清單、挑一台加入」：
 * 兩件事擠在同一頁時，登進去之後上面那半截表單就變成永遠用不到的廢版面。
 *
 * 更新走下拉刷新（沒有「重新整理」按鈕），清單會落在磁碟快取裡，所以退出再進來還有東西可看。
 */
class ProtonServersActivity : ThemedActivity() {

    private lateinit var binding: ActivityProtonServersBinding
    private var nodes: List<ProtonNode> = emptyList()

    // 1576 條全建 View 既翻不完也卡：一次只渲染這麼多，其餘用「顯示更多」逐頁加。
    private val nodePage = 60
    private var query = ""
    private var shownLimit = nodePage
    private var filter = ProtonFilter()
    private var countryOptions = listOf<ProtonFilter.Option>()
    private var cityOptions = listOf<ProtonFilter.Option>()
    private var fetching = false

    private val sortOptions by lazy {
        listOf(
            ProtonFilter.Sort.IDLE to getString(R.string.proton_sort_idle),
            ProtonFilter.Sort.NAME to getString(R.string.proton_sort_name),
            ProtonFilter.Sort.COUNTRY to getString(R.string.proton_country),
            ProtonFilter.Sort.CITY to getString(R.string.proton_city),
        )
    }

    // 排序項沒有「這個地方有幾台」這種數目，count 一律給 0，由 OptionAdapter 負責留空，
    // 這樣它才能跟國家／城市共用同一個 labels() 與同一份 item_proton_option 佈局。
    private val sortItems by lazy {
        sortOptions.map { ProtonFilter.Option(it.first.name, 0, it.second) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProtonServersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.refreshLayout.setOnRefreshListener { fetch() }

        binding.filterText.doAfterTextChanged {
            query = it?.toString().orEmpty()
            shownLimit = nodePage
            renderNodes()
        }
        binding.countryFilter.setOnItemClickListener { _, _, position, _ ->
            // 換國家要把城市重設：留著「东京」再選美國，清單會直接空掉。
            filter = filter.copy(country = countryOptions.getOrNull(position)?.key ?: ProtonFilter.ALL, city = ProtonFilter.ALL)
            refreshPlaceOptions()
            shownLimit = nodePage
            renderNodes()
        }
        binding.cityFilter.setOnItemClickListener { _, _, position, _ ->
            filter = filter.copy(city = cityOptions.getOrNull(position)?.key ?: ProtonFilter.ALL)
            shownLimit = nodePage
            renderNodes()
        }
        binding.sortFilter.setOnItemClickListener { _, _, position, _ ->
            filter = filter.copy(sort = sortOptions.getOrNull(position)?.first ?: ProtonFilter.Sort.IDLE)
            renderSortLabel()
            shownLimit = nodePage
            renderNodes()
        }
        binding.ipv6Only.setOnCheckedChangeListener { _, checked ->
            filter = filter.copy(ipv6Only = checked)
            shownLimit = nodePage
            renderNodes()
        }
        binding.showMore.setOnClickListener {
            shownLimit += nodePage
            renderNodes()
        }
        // 排序框的 adapter 不在這裡設：實測證明「在 onCreate 就 setAdapter」的排序框點下去
        // 只有箭頭翻、清單不出來，而同一畫面裡國家／城市兩格都點得開——它們的差異就是設
        // adapter 的時機。所以排序也改到 refreshPlaceOptions() 裡，跟那兩格同時、同機制。
        renderSortLabel()

        if (!ProtonSidecar.isAvailable()) {
            binding.status.setText(R.string.proton_sidecar_missing)
            binding.refreshLayout.isEnabled = false
            binding.refreshHint.visibility = View.GONE
            return
        }

        val cached = ProtonNodeCache.load(cacheDir)
        if (cached != null) {
            showNodes(cached, fromCache = true)
            // 開著自動登入＝進頁就更新；關掉就只用快取，要新的自己下拉。
            if (DataStore.protonAutoLogin) fetch()
        } else {
            // 沒有任何快取時不能留一張白紙讓人猜，直接抓一次。
            fetch()
        }
    }

    private fun fetch() {
        if (fetching) return
        if (!ProtonSidecar.hasSession()) {
            binding.refreshLayout.isRefreshing = false
            binding.status.setText(R.string.proton_not_signed_in)
            return
        }
        fetching = true
        binding.refreshLayout.isRefreshing = true
        lifecycleScope.launch {
            val result = ProtonSidecar.nodes()
            fetching = false
            if (isFinishing || isDestroyed) return@launch
            binding.refreshLayout.isRefreshing = false
            if (!result.ok) {
                if (result.code == ProtonSidecar.SESSION_EXPIRED) {
                    // 過期不是一句要讀的錯誤，是一個用戶能修的狀態：給本地化文案並直接把他
                    // 送回登入頁。只印一句英文的話，他會卡在舊清單前面不知道該做什麼。
                    binding.status.setText(R.string.proton_session_expired)
                    startActivity(Intent(this@ProtonServersActivity, ProtonActivity::class.java))
                    finish()
                    return@launch
                }
                // 抓失敗時手上有快取就留著舊清單，別把已經看得到的東西一起清掉。
                binding.status.text = result.error
                return@launch
            }
            if (result.raw.isNotEmpty() && !ProtonNodeCache.save(cacheDir, result.raw)) {
                Logs.w("proton: failed to cache server list")
            }
            showNodes(result, fromCache = false)
        }
    }

    private fun showNodes(state: io.nekohasekai.sagernet.fmt.proton.ProtonNodesState, fromCache: Boolean) {
        nodes = state.nodes
        shownLimit = nodePage
        refreshPlaceOptions()
        val count = getString(R.string.proton_node_count, state.nodes.size, state.dropped)
        binding.status.text = if (fromCache) {
            count + " · " + getString(
                R.string.proton_cached_at,
                DateUtils.getRelativeTimeSpanString(
                    ProtonNodeCache.savedAt(cacheDir),
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ),
            )
        } else {
            count
        }
        renderNodes()
    }

    private fun visibleNodes(): List<ProtonNode> = filter.copy(query = query).apply(nodes)

    private fun labels(options: List<ProtonFilter.Option>) = OptionAdapter(options)

    // 選中之後框子裡只留名字：數目是給清單挑東西用的資訊，留在框子裡只會讓長名字被截。
    private fun optionText(key: String, options: List<ProtonFilter.Option>) = options.firstOrNull { it.key == key }?.label ?: ""

    // 名稱與數目分兩格是必要的：同一個 TextView 設了 ellipsize，「剛果（金夏沙）」
    // 這種長名字會把尾巴連同數目一起剪掉，而長名字恰恰最需要看到有幾台。
    private inner class OptionAdapter(
        options: List<ProtonFilter.Option>,
    ) : ArrayAdapter<ProtonFilter.Option>(this, R.layout.item_proton_option, options) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_proton_option, parent, false)
            val option = getItem(position)
            view.findViewById<TextView>(R.id.optionLabel).text = option?.label.orEmpty()
            view.findViewById<TextView>(R.id.optionCount).text = when {
                // count<=0 是排序那格用來的：它沒有「有幾台」這回事，右側要留空。
                option == null || option.key == ProtonFilter.ALL || option.count <= 0 -> ""

                else -> option.count.toString()
            }
            return view
        }
    }

    // 排序框不能留白：國家／城市那兩格都會回填目前選的是什麼，唯獨這格不回填的話，
    // 用戶看不出清單現在是按什麼排的，也看不出自己刚刚選到了哪裡。
    private fun renderSortLabel() {
        binding.sortFilter.setText(sortOptions.first { it.first == filter.sort }.second, false)
    }

    private fun refreshPlaceOptions() {
        countryOptions = listOf(
            ProtonFilter.Option(ProtonFilter.ALL, nodes.size, getString(R.string.proton_all_countries)),
        ) + ProtonFilter.countries(nodes)
        if (filter.country != ProtonFilter.ALL && !ProtonFilter.hasCountry(nodes, filter.country)) {
            filter = filter.copy(country = ProtonFilter.ALL, city = ProtonFilter.ALL)
        }
        cityOptions = listOf(
            ProtonFilter.Option(ProtonFilter.ALL, 0, getString(R.string.proton_all_cities)),
        ) + ProtonFilter.cities(nodes, filter.country)
        if (filter.city != ProtonFilter.ALL && !ProtonFilter.hasCity(nodes, filter.city)) {
            filter = filter.copy(city = ProtonFilter.ALL)
        }
        binding.countryFilter.setAdapter(labels(countryOptions))
        binding.cityFilter.setAdapter(labels(cityOptions))
        // 與上面兩格同一個時機、同一個 labels()：實測過在 onCreate 裡設的排序框點下去只有
        // 箭頭翻、清單不出來，換到這裡就與國家／城市一致。
        binding.sortFilter.setAdapter(labels(sortItems))
        binding.countryFilter.setText(optionText(filter.country, countryOptions), false)
        binding.cityFilter.setText(optionText(filter.city, cityOptions), false)
    }

    private fun renderNodes() {
        binding.nodeList.removeAllViews()
        val gap = (8 * resources.displayMetrics.density).toInt()
        val visible = visibleNodes()
        val hasList = nodes.isNotEmpty()
        binding.filterLayout.visibility = if (hasList) View.VISIBLE else View.GONE
        binding.placeFilterRow.visibility = if (hasList) View.VISIBLE else View.GONE
        binding.sortFilterRow.visibility = if (hasList) View.VISIBLE else View.GONE
        binding.refreshHint.visibility = if (hasList) View.VISIBLE else View.GONE
        for (node in visible.take(shownLimit)) {
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
            button.isSingleLine = true
            button.ellipsize = TextUtils.TruncateAt.END
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = gap }
            // 空閒度只在清單上出現：匯入之後的名字會跟著配置留下來，而這個數字每幾分鐘
            // 就變一次，留在節點名稱裡只會變成一個過期的謊。
            val idle = node.idlePercent
            button.text = buildString {
                append(getString(R.string.proton_add))
                append(" · ")
                append(node.placeLabel())
                if (idle != null) {
                    append(" ")
                    append(getString(R.string.proton_idle, idle))
                }
            }
            button.setOnClickListener { import(node) }
            binding.nodeList.addView(button)
        }
        val shown = minOf(shownLimit, visible.size)
        binding.listCount.text = getString(R.string.proton_showing, shown, visible.size)
        binding.listCount.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        binding.showMore.visibility = if (visible.size > shown) View.VISIBLE else View.GONE
    }

    private fun import(node: ProtonNode) {
        lifecycleScope.launch {
            val pair = ProtonSidecar.keyPair()
            if (isFinishing || isDestroyed) return@launch
            if (!pair.valid) {
                binding.status.setText(R.string.proton_sidecar_missing)
                return@launch
            }
            val address = if (node.supportsIPv6) {
                "$CLIENT_IP/32, $CLIENT_IP_V6/128"
            } else {
                "$CLIENT_IP/32"
            }
            val dns = if (node.supportsIPv6) "$SERVER_IP, $SERVER_IP_V6" else SERVER_IP
            try {
                val conf = ProtonJson.wireGuardConf(node, pair.privateKey, address, dns)
                val beans = RawUpdater.parseWireGuardConf(conf)
                if (beans.isEmpty()) {
                    if (!isFinishing && !isDestroyed) binding.status.setText(R.string.proton_no_nodes)
                    return@launch
                }
                val bean = beans.first()
                bean.name = node.placeLabel()
                val (groupId, created) = ProtonGroups.ensure()
                ProfileManager.createProfile(groupId, bean)
                if (!isFinishing && !isDestroyed) {
                    if (created) {
                        Toast.makeText(
                            this@ProtonServersActivity,
                            getString(R.string.proton_group_added, ProtonGroups.NAME),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    binding.status.text = getString(R.string.proton_added, node.name)
                }
            } catch (e: Exception) {
                Logs.w("proton import failed", e)
                if (!isFinishing && !isDestroyed) {
                    binding.status.text = e.localizedMessage ?: e.toString()
                }
            }
        }
    }

    companion object {
        // Proton's own client constants: the in-tunnel address is fixed rather than
        // assigned, and its DNS is the server side of that same pair.
        private const val CLIENT_IP = "10.2.0.2"
        private const val SERVER_IP = "10.2.0.1"
        private const val CLIENT_IP_V6 = "2a07:b944::2:2"
        private const val SERVER_IP_V6 = "2a07:b944::2:1"
    }
}
