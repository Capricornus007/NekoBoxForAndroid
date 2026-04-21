package io.nekohasekai.sagernet.bg.byedpi

data class ByeDpiConfig(
    val listenAddress: String = "127.0.0.1",
    val listenPort: Int = 1080,
    val extraArgs: List<String> = DEFAULT_EXTRA_ARGS,
) {
    fun toCommandLine(): Array<String> {
        return buildList {
            add("ciadpi")
            add("--ip")
            add(listenAddress)
            add("--port")
            add(listenPort.toString())
            addAll(extraArgs)
        }.toTypedArray()
    }

    companion object {
        // Mirrors the donor app's default command-mode arguments.
        val DEFAULT_EXTRA_ARGS = listOf("-Ku", "-a1", "-An", "-o1", "-At,r,s", "-d1")
    }
}
