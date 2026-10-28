package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 2026-10-28：原本繼承 FastScrollRecyclerView、自己再畫一條 FastScroller。
 *
 * 使用者實測右側邊緣一直有多條滾動條（2 黃 + 1 灰）：主列表是 ViewPager2 分組分頁，
 * offscreenPageLimit=2 會把相鄰頁一起畫出來，每頁各畫一條；而父類即使呼叫
 * super.setFastScrollEnabled(false) 仍會 draw 自己的 FastScroller（庫內部行為），
 * 曾試過「可見寬度」「視窗座標」「反射把 thumb 設成 (-1,-1)」都擋不住。
 *
 * 使用者裁定「全部不保留也罷」，所以簡化成純 RecyclerView：不畫任何滾動條，
 * 也一併失去拖動 thumb 與字母索引（那兩項依賴 FastScroller 的繪製）。
 * 類名保留，所有 layout 引用不用跟著改。
 */
class StableFastScrollRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr)
