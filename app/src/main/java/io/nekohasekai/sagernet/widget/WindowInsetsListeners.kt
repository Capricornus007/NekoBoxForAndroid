package io.nekohasekai.sagernet.widget

import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.WeakHashMap

object ListListener : OnApplyWindowInsetsListener {
    // updatePadding(bottom = …) 是「設成」而不是「加上」，所以導航欄顯隱、旋轉、鍵盤收起
    // 每次回調都會把版面自己定義的底部留白抹掉，列表最後一項直接貼到導航欄下面。
    // 記下每個 view 首次的 paddingBottom 再累加；用 WeakHashMap 才不會讓已銷毀的 view
    // 被這個進程級單例一直拖住。
    private val originalPaddingBottom = WeakHashMap<View, Int>()

    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val original = originalPaddingBottom.getOrPut(view) { view.paddingBottom }
        view.updatePadding(
            bottom = original + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
        )
        return insets
    }
}
