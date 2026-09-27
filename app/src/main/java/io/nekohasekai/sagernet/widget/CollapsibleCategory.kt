package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R

/**
 * A [PreferenceCategory] whose row toggles its own children open and closed.
 *
 * The click is wired at the View level on purpose. `setOnPreferenceClickListener` was measured on device
 * (mod-81) to never fire for a category row: the row's View is `clickable=true`, the tap produced no
 * exception and no log output, and nothing changed - Preference's own dispatch drops it somewhere for
 * categories. `onBindViewHolder` is the only supported place where a Preference can reach `itemView`.
 */
class CollapsibleCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PreferenceCategory(context, attrs) {

    var onToggleClicked: (() -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnClickListener { onToggleClicked?.invoke() }
    }
}

/**
 * Wires every direct [CollapsibleCategory] child of this group to collapse/expand on tap.
 *
 * Collapsed by default: the point is that a settings screen with 73 entries opens short instead of
 * forcing a long scroll. The per-category choice survives across visits, keyed by [storeKey] so several
 * screens can share one store without colliding.
 *
 * Children are not simply "shown" on expand: some of them toggle their own visibility for feature reasons
 * (e.g. the rule-set URL fields only apply when `rulesProvider == 4`). Collapsing therefore snapshots each
 * child's current visibility first, and expanding restores that snapshot - so a collapsed and re-opened
 * group can never resurrect an item the screen deliberately hid.
 *
 * Plain `PreferenceCategory` entries are skipped: they stay exactly as they were, so pages whose XML has
 * not been switched to [CollapsibleCategory] keep their current behaviour.
 */
fun PreferenceGroup.enableCollapsibleCategories(storeKey: String) {
    val prefs = context.getSharedPreferences("ui_expand_state", Context.MODE_PRIVATE)
    for (i in 0 until preferenceCount) {
        val category = getPreference(i) as? CollapsibleCategory ?: continue
        val key = "$storeKey:${category.key ?: category.title}"
        val natural = HashMap<Preference, Boolean>()
        for (j in 0 until category.preferenceCount) {
            val child = category.getPreference(j)
            natural[child] = child.isVisible
        }
        var expanded = prefs.getBoolean(key, false)
        // 不動 isIconSpaceReserved：預設 false 時沒設圖標的既有分類會被框架直接 GONE，
        // 版面與改動前一致；我們這顆一定會 setIcon，所以不受影響。
        category.onToggleClicked = {
            if (expanded) {
                for (j in 0 until category.preferenceCount) {
                    natural[category.getPreference(j)] = category.getPreference(j).isVisible
                }
            }
            expanded = !expanded
            prefs.edit { putBoolean(key, expanded) }
            applyExpanded(category, expanded, natural)
        }
        applyExpanded(category, expanded, natural)
    }
}

private fun applyExpanded(category: PreferenceCategory, expanded: Boolean, natural: Map<Preference, Boolean>) {
    for (i in 0 until category.preferenceCount) {
        val child = category.getPreference(i)
        child.isVisible = expanded && (natural[child] ?: true)
    }
    category.setIcon(
        if (expanded) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24,
    )
}
