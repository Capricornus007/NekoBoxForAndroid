package io.nekohasekai.sagernet.bg

internal enum class NetworkChangeAction {
    NONE,
    RESET_CONNECTIONS,
    RESTART_PROFILE,
}

internal fun resetConnectionsInGoOnNetworkChange(restartProfile: Boolean, resetConnections: Boolean): Boolean =
    !restartProfile && resetConnections

internal fun networkChangeAction(
    oldInterface: String?,
    newInterface: String?,
    restartProfile: Boolean,
    resetConnections: Boolean,
): NetworkChangeAction {
    if (oldInterface == null || newInterface == null || oldInterface == newInterface) {
        return NetworkChangeAction.NONE
    }
    return when {
        restartProfile -> NetworkChangeAction.RESTART_PROFILE
        resetConnectionsInGoOnNetworkChange(restartProfile, resetConnections) ->
            NetworkChangeAction.RESET_CONNECTIONS
        else -> NetworkChangeAction.NONE
    }
}
