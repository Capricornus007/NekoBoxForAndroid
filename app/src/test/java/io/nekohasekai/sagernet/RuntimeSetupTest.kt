package io.nekohasekai.sagernet

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.Network
import android.os.Build
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [23, 24, 26, 28, 31, 35])
class RuntimeSetupTest {
    @Before
    fun attachApplicationWithoutNativeInitialization() {
        val base = RuntimeEnvironment.getApplication()
        SagerNet::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
            invoke(SagerNet(), base)
        }
    }

    @Test
    fun notificationChannelsExistAndRepeatedSetupPreservesThem() {
        repeat(2) { SagerNet.updateNotificationChannels() }
        if (Build.VERSION.SDK_INT < 26) return

        val channels = SagerNet.notification.notificationChannels.associateBy { it.id }
        assertEquals(
            setOf("service-vpn", "service-proxy", "service-subscription", "connection-test", CORE_NOTIFICATION_CHANNEL),
            channels.keys,
        )
        assertEquals(
            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW,
            channels.getValue("service-vpn").importance,
        )
        assertEquals(NotificationManager.IMPORTANCE_LOW, channels.getValue("service-proxy").importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channels.getValue("service-subscription").importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channels.getValue("connection-test").importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channels.getValue(CORE_NOTIFICATION_CHANNEL).importance)
        assertEquals(SagerNet.application.getString(R.string.service_vpn), channels.getValue("service-vpn").name)
    }

    @Test
    fun listenersShareARealCallbackAndReleaseItAfterTheLastStop() = runBlocking {
        val manager = shadowOf(SagerNet.connectivity)
        val first = Any()
        val second = Any()
        val received = mutableListOf<Network?>()
        val network = ShadowNetwork.newInstance(42)
        repeat(2) {
            try {
                DefaultNetworkListener.start(first) { received += it }
                assertEquals(1, manager.networkCallbacks.size)
                val callback = manager.networkCallbacks.single()
                callback.onAvailable(network)
                assertEquals(network, received.last())

                DefaultNetworkListener.start(second) { received += it }
                assertEquals(1, manager.networkCallbacks.size)
                assertEquals(network, received.last())
                DefaultNetworkListener.stop(first)
                assertEquals(1, manager.networkCallbacks.size)
                callback.onLost(network)
                assertEquals(null, received.last())
            } finally {
                DefaultNetworkListener.stop(first)
                DefaultNetworkListener.stop(second)
            }
            assertTrue(manager.networkCallbacks.isEmpty())
        }
    }
}
