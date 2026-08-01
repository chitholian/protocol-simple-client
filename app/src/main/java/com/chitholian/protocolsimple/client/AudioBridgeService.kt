package com.chitholian.protocolsimple.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioDeviceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service so audio keeps streaming when the app is backgrounded.
 * Owns the ClientSession; activities bind for control and state updates.
 */
class AudioBridgeService : Service() {

    private val binder = LocalBinder()
    private var listener: ((BridgeState, String) -> Unit)? = null
    private var lastState = BridgeState.DISCONNECTED
    private var lastMsg = "Disconnected"
    private var session: ClientSession? = null

    inner class LocalBinder : Binder() {
        fun service() = this@AudioBridgeService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(lastState, lastMsg))
        return START_STICKY
    }

    fun setListener(l: ((BridgeState, String) -> Unit)?) {
        listener = l
    }

    fun currentState(): Pair<BridgeState, String> = lastState to lastMsg

    fun connect(host: String, port: Int, rate: Int, channels: Int, anc: Boolean) {
        if (session?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            lastState = BridgeState.ERROR
            lastMsg = "Microphone permission missing"
            listener?.invoke(lastState, lastMsg)
            stopSelf()
            return
        }
        session = ClientSession { state, msg ->
            lastState = state
            lastMsg = msg
            listener?.invoke(state, msg)
            updateNotification(state, msg)
            if (state == BridgeState.DISCONNECTED || state == BridgeState.ERROR) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        session?.connect(host, port, rate, channels, anc)
    }

    fun disconnect() {
        session?.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setOutputDevice(device: AudioDeviceInfo?) {
        session?.setOutputDevice(device)
    }

    fun setMicMuted(muted: Boolean) {
        session?.setMicMuted(muted)
    }

    private fun updateNotification(state: BridgeState, msg: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification(state, msg))
    }

    private fun buildNotification(state: BridgeState, msg: String): android.app.Notification {
        val title =
            if (state == BridgeState.CONNECTED) "Streaming"
            else if (state == BridgeState.CONNECTING) "Connecting"
            else "Protocol Simple Client"
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(title)
            .setContentText(msg)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        session?.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "audio_bridge"
        private const val NOTIF_ID = 1
    }
}
