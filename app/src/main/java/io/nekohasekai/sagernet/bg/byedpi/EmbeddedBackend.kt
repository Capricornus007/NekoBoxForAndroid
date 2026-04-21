package io.nekohasekai.sagernet.bg.byedpi

import kotlinx.coroutines.flow.StateFlow

interface EmbeddedBackend<TConfig, TStatus> {
    val status: StateFlow<TStatus>

    fun start(config: TConfig): Boolean

    fun stop(force: Boolean = false): Boolean
}
