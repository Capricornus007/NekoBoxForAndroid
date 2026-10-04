package io.nekohasekai.sagernet.ui.profile

/** Only exit edits participate in exit CAS; unrelated drafts keep the latest committed exit. */
internal data class TailscaleEditorBaseline(
    val identity: String,
    val exit: String,
    val exitEdited: Boolean = false,
) {
    fun refreshed(identity: String, exit: String): TailscaleEditorBaseline = if (exitEdited || this.identity != identity) this else copy(exit = exit)
}
