package com.boristul.zybcvpn.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.boristul.zybcvpn.Key
import com.boristul.zybcvpn.R
import com.boristul.zybcvpn.SagerNet
import com.boristul.zybcvpn.database.DataStore
import com.boristul.zybcvpn.ktx.Logs
import com.boristul.zybcvpn.ktx.broadcastReceiver

class VpnRequestActivity : AppCompatActivity() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService<KeyguardManager>()!!.isKeyguardLocked) {
            receiver = broadcastReceiver { _, _ -> connect.launch(null) }
            if (SDK_INT >= 33) {
                registerReceiver(
                    receiver,
                    IntentFilter(Intent.ACTION_USER_PRESENT),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }
        } else connect.launch(null)
    }

    private val connect = registerForActivityResult(StartService()) {
        if (it) Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiver != null) unregisterReceiver(receiver)
    }

    class StartService : ActivityResultContract<Void?, Boolean>() {
        private var cachedIntent: Intent? = null

        override fun getSynchronousResult(
            context: Context,
            input: Void?,
        ): SynchronousResult<Boolean>? {
            if (DataStore.serviceMode == Key.MODE_VPN) VpnService.prepare(context)?.let { intent ->
                cachedIntent = intent
                return null
            }
            SagerNet.startService()
            return SynchronousResult(false)
        }

        override fun createIntent(context: Context, input: Void?) =
            cachedIntent!!.also { cachedIntent = null }

        override fun parseResult(resultCode: Int, intent: Intent?) =
            if (resultCode == Activity.RESULT_OK) {
                SagerNet.startService()
                false
            } else {
                Logs.e("Failed to start VpnService: $intent")
                true
            }
    }


}
