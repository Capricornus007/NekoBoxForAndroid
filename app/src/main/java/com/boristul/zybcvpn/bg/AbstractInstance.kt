package com.boristul.zybcvpn.bg

import java.io.Closeable

interface AbstractInstance : Closeable {

    fun launch()

}