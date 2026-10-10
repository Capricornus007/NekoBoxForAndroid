package io.nekohasekai.sagernet.ui.profile

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.wireguard.checkWireGuardLimits
import io.nekohasekai.sagernet.fmt.wireguard.extraPeerCount
import io.nekohasekai.sagernet.ktx.readableMessage

/** Summary of the additional peers: a count, never the text, which holds pre-shared keys. */
internal object ExtraPeersSummaryProvider : Preference.SummaryProvider<EditTextPreference> {
    override fun provideSummary(preference: EditTextPreference): CharSequence {
        val text = preference.text.orEmpty()
        if (text.isBlank()) return preference.context.getString(androidx.preference.R.string.not_set)
        val count = extraPeerCount(text)
        return preference.context.resources.getQuantityString(R.plurals.wireguard_extra_peers_count, count, count)
    }
}

/** Keeps only whole numbers in [range]; anything else is refused, not stored as 0. */
internal fun EditTextPreference.acceptNumbers(range: IntRange) {
    setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
    setOnPreferenceChangeListener { _, value ->
        (value.toString().toIntOrNull()?.let { it in range } == true).also { accepted ->
            if (!accepted) {
                Toast.makeText(context, context.getString(R.string.wireguard_number_out_of_range, range.first, range.last), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Why imports and the config builder would refuse [draft], or null when they accept it. */
internal fun wireGuardDraftProblem(draft: AbstractBean): String? = runCatching { draft.checkWireGuardLimits() }.exceptionOrNull()?.readableMessage
