package com.boristul.zybcvpn.bg.byedpi

internal class ByeDpiNativeProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    external fun jniStartProxy(args: Array<String>): Int
    external fun jniStopProxy(): Int
    external fun jniForceClose(): Int
}
