package io.nekohasekai.sagernet.api

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import java.lang.reflect.Proxy

internal class ApiServiceFixture(base: Context, state: () -> BaseService.State = { DataStore.serviceState }) {
    private val binder = Binder()
    lateinit var callback: ISagerNetServiceCallback
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val bindings = mutableListOf<ComponentName>()
    var bindImmediately = true
    private val pending = mutableListOf<Pair<ComponentName, ServiceConnection>>()
    val api = Proxy.newProxyInstance(ISagerNetService::class.java.classLoader, arrayOf(ISagerNetService::class.java)) { _, method, args ->
        when (method.name) {
            "asBinder" -> binder

            "getState" -> state().ordinal

            "registerCallback" -> {
                callback = args!![0] as ISagerNetServiceCallback
                null
            }

            "unregisterCallback" -> null

            else -> {
                calls += method.name to args.orEmpty().toList()
                null
            }
        }
    } as ISagerNetService
    init {
        binder.attachInterface(api, "io.nekohasekai.sagernet.aidl.ISagerNetService")
    }
    val context = object : ContextWrapper(base) {
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val component = checkNotNull(intent.component)
            bindings += component
            if (bindImmediately) connection.onServiceConnected(component, binder) else pending += component to connection
            return true
        }
        override fun unbindService(connection: ServiceConnection) = Unit
    }

    fun completeBinding() {
        val (component, connection) = pending.removeAt(0)
        connection.onServiceConnected(component, binder)
    }
}
