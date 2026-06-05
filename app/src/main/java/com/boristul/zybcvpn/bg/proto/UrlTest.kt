package com.boristul.zybcvpn.bg.proto

import com.boristul.zybcvpn.database.DataStore
import com.boristul.zybcvpn.database.ProxyEntity

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = 5000

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest()
    }

}