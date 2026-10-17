package io.nekohasekai.sagernet.bg

internal fun pauseHealthChecks(interactive: Boolean, keyguardLocked: Boolean, deviceIdle: Boolean) =
    !interactive || keyguardLocked || deviceIdle
