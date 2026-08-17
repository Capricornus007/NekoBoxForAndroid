package moe.matsuri.nb4a.hevtun

import androidx.annotation.Keep

/**
 * JNI bindings for hev-socks5-tunnel.
 *
 * The native methods are registered via RegisterNatives in JNI_OnLoad against
 * the class named by the -DPKGNAME/-DCLSNAME macros in
 * buildScript/compile-hevtun.sh; keep both sides in sync.
 */
@Keep
object HevTunNative {

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    @Keep
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    @Keep
    external fun TProxyStopService(): Boolean

    @JvmStatic
    @Keep
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    @Keep
    external fun TProxyGetStats(): LongArray
}
