package io.nekohasekai.sagernet.fmt.proton

import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase

/**
 * Proton 節點一律收進一個自己的分組，不再丟進「目前選中的那個組」：混在用戶自己整理的
 * 清單裡，之後想整組刪掉、或想關掉自動選最快，都找不到邊界。
 *
 * 這個分組建出來時就帶兩個預設：**按延遲排序** + **啟用 selector**。使用者要的是
 * 「連上去自己挑最快的」，那兩項少一個都不成立。
 */
object ProtonGroups {

    // 分組名稱是資料標識、不是介面文案：翻譯的話換個系統語言就會「找不到自己建的組」，
    // 於是又長出一個第二組。
    const val NAME = "Proton"

    /**
     * @return 分組 id，以及「這一次是不是新建的」（新建才要 toast，重複進頁面不該一直跳）。
     */
    suspend fun ensure(): Pair<Long, Boolean> {
        SagerDatabase.groupDao.allGroups().firstOrNull { it.name == NAME }?.let { return it.id to false }
        val group = GroupManager.createGroup(
            ProxyGroup(
                name = NAME,
                type = GroupType.BASIC,
                order = GroupOrder.BY_DELAY,
                isSelector = true,
            ),
        )
        return group.id to true
    }
}
