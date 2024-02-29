package org.amnezia.vpn

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.getStatus
import org.amnezia.vpn.util.Log

private const val TAG = "AmneziaTileService"

private const val BIND_SERVICE_TIMEOUT = 1000L

class AmneziaTileService : TileService() {

    private lateinit var mainScope: CoroutineScope
    private lateinit var vpnServiceMessenger: IpcMessenger
    private var isWaitingStatus = true
    private var isServiceConnected = false
    private var tileActive = false
    private var isInBoundState = false
    private var tapPending = false
    private var connectionPending = false
    private var configName = "AmneziaVPN"
    private var bindTimeoutJob: Job? = null

    private val vpnServiceEventHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val event = msg.extractIpcMessage<ServiceEvent>()
                Log.d(TAG, "Handle event: $event")
                when (event) {
                    ServiceEvent.CONNECTED -> {
                        tileActive = true
                        connectionPending = false
                    }

                    ServiceEvent.DISCONNECTED -> {
                        tileActive = false
                        connectionPending = false
                    }

                    ServiceEvent.STATUS -> {
                        if (isWaitingStatus) {
                            isWaitingStatus = false
                        }

                        msg.data?.getStatus()?.let { (state, lastConfigName) ->
                            tileActive =
                                ProtocolState.CONNECTED == state || ProtocolState.CONNECTING == state

                            if (lastConfigName != null) {
                                configName = lastConfigName
                            }

                            if (connectionPending && lastConfigName == null) {
                                onVpnConnectionError()
                            }
                        }
                    }

                    else -> {}
                }
                updateTile()
            }
        }
    }

    private val activityMessenger: Messenger by lazy(LazyThreadSafetyMode.NONE) {
        Messenger(vpnServiceEventHandler)
    }

    private val serviceConnection: ServiceConnection by lazy(LazyThreadSafetyMode.NONE) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bindTimeoutJob?.cancel()
                Log.d(TAG, "Service ${name?.flattenToString()} was connected")
                vpnServiceMessenger.set(Messenger(service))
                vpnServiceMessenger.send {
                    Action.REGISTER_CLIENT.packToMessage().apply {
                        replyTo = activityMessenger
                    }
                }
                isServiceConnected = true
                if (isWaitingStatus) {
                    vpnServiceMessenger.send(Action.REQUEST_STATUS)
                }
                if (tapPending) {
                    tapPending = false
                    onClick()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Service ${name?.flattenToString()} was unexpectedly disconnected")
                isServiceConnected = false
                vpnServiceMessenger.reset()
                isWaitingStatus = true
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.w(TAG, "Binding to the ${name?.flattenToString()} unexpectedly died")
                doUnbindService()
                doBindService()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Start Amnezia Tile Service")
        mainScope.launch {
            doBindService()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        vpnServiceMessenger = IpcMessenger(
            onDeadObjectException = ::doUnbindService,
            messengerName = "VpnService"
        )
    }

    /**
     * Methods for service binding
     */
    @MainThread
    private fun doBindService() {
        Log.d(TAG, "Bind service")
        Intent(this, AmneziaVpnService::class.java).also {
            bindService(it, serviceConnection, BIND_ABOVE_CLIENT)
        }
        isInBoundState = true
        handleBindTimeout()
    }


    @MainThread
    private fun doUnbindService() {
        if (isInBoundState) {
            Log.d(TAG, "Unbind service")
            isWaitingStatus = true
            vpnServiceMessenger.reset()
            isServiceConnected = false
            isInBoundState = false
            unbindService(serviceConnection)
            bindTimeoutJob?.cancel();
        }
    }

    private fun handleBindTimeout() {
        bindTimeoutJob = mainScope.launch {
            if (isWaitingStatus) {
                delay(BIND_SERVICE_TIMEOUT)
                if (isWaitingStatus && !isServiceConnected) {
                    Log.d(TAG, "Bind timeout, reset connection status")
                    isWaitingStatus = false
                    onVpnServiceInactive();
                }
            }
            bindTimeoutJob = null
        }
    }

    private fun onVpnServiceInactive() {
        tileActive = false;
        updateTile()
    }

    // Called when the user adds your tile.
    override fun onTileAdded() {
        Log.d(TAG, "onTileAdded")
        super.onTileAdded()
    }

    // Called when your app can update your tile.
    override fun onStartListening() {
        Log.d(TAG, "onStartListening")
        doBindService()
        tileActive = this.qsTile.state == Tile.STATE_ACTIVE
        super.onStartListening()
    }

    // Called when your app can no longer update your tile.
    override fun onStopListening() {
        Log.d(TAG, "onStopListening")
        doUnbindService()
        super.onStopListening()
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroy service")
    }

    private fun onVpnConnectionError() {
        Toast.makeText(
            this,
            "For the first time, connect through the application",
            Toast.LENGTH_LONG
        ).show()
        Intent(this, AmneziaActivity::class.java)
            .apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.also {
                startActivityAndCollapse(it)
            }
    }

    // Called when the user taps on your tile in an active or inactive state.
    override fun onClick() {

        if (tileActive) {
            if (!isServiceConnected) {
                tapPending = true
                return
            }
            Log.d(TAG, "Disconnect from VPN")
            stopVpn()

        } else {
            Log.d(TAG, "Connect to VPN")
            startVpn()
        }
    }

    private fun stopVpn() {
        vpnServiceMessenger.send {
            Action.DISCONNECT.packToMessage {}
        }
        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            if(!isServiceConnected && tileActive) {
                onVpnServiceInactive()
            }
        }
    }

    @MainThread
    private fun startVpn() {
        connectionPending = true
        startVpnService()
    }

    private fun startVpnService() {
        Log.d(TAG, "Start VPN service")
        Intent(this, AmneziaVpnService::class.java).apply {
        }.also {
            ContextCompat.startForegroundService(this, it)
        }
    }

    // Called when the user removes your tile.
    override fun onTileRemoved() {
        super.onTileRemoved()
    }

    private fun updateTile() {
        val tile: Tile = this.qsTile
        val newLabel = configName

        val newState: Int = if (tileActive) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }

        tile.state = newState
        tile.label = newLabel
        tile.updateTile()
    }
}
