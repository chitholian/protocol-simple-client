package com.chitholian.protocolsimple.client

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var service: AudioBridgeService? = null
    private var bound = false
    private var pendingConnect = false
    private val devices = ArrayList<AudioDeviceInfo>()

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etRate: EditText
    private lateinit var etChannels: EditText
    private lateinit var swAnc: Switch
    private lateinit var swDisableMic: Switch
    private lateinit var btnConnect: Button
    private lateinit var btnMute: Button
    private lateinit var tvStatus: TextView
    private lateinit var rgDevices: RadioGroup
    private lateinit var btnRefresh: Button
    private var micMuted = false
    private lateinit var btnSetup: Button

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val recordAudioGranted = result[Manifest.permission.RECORD_AUDIO] == true
        if (swDisableMic.isChecked || recordAudioGranted) {
            if (pendingConnect) {
                pendingConnect = false
                doConnect()
            }
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            service = (binder as AudioBridgeService.LocalBinder).service()
            service?.setListener { state, msg -> runOnUiThread { onState(state, msg) } }
            val (s, m) = service?.currentState() ?: (BridgeState.DISCONNECTED to "Disconnected")
            onState(s, m)
            refreshDevices()
            if (pendingConnect) {
                pendingConnect = false
                doConnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etRate = findViewById(R.id.etRate)
        etChannels = findViewById(R.id.etChannels)
        swAnc = findViewById(R.id.swAnc)
        swDisableMic = findViewById(R.id.swDisableMic)
        btnConnect = findViewById(R.id.btnConnect)
        btnMute = findViewById(R.id.btnMute)
        tvStatus = findViewById(R.id.tvStatus)
        rgDevices = findViewById(R.id.rgDevices)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSetup = findViewById(R.id.btnSetup)

        val prefs = getSharedPreferences("pw_client", Context.MODE_PRIVATE)
        etHost.setText(prefs.getString("host", ""))
        etPort.setText(prefs.getString("port", "4711"))
        val nativeRate = getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        etRate.setText(prefs.getString("rate", nativeRate.toString()))
        etChannels.setText(prefs.getString("channels", "2"))
        swAnc.isChecked = prefs.getBoolean("anc", true)
        swDisableMic.isChecked = prefs.getBoolean("disable_mic", false)

        swDisableMic.setOnCheckedChangeListener { _, isChecked ->
            val (s, _) = service?.currentState() ?: (BridgeState.DISCONNECTED to "")
            val active = s == BridgeState.CONNECTED || s == BridgeState.CONNECTING
            swAnc.isEnabled = !active && !isChecked
            btnMute.isEnabled = s == BridgeState.CONNECTED && !isChecked
        }

        btnConnect.setOnClickListener { onConnectClick() }
        btnMute.isEnabled = false
        btnMute.setOnClickListener {
            micMuted = !micMuted
            service?.setMicMuted(micMuted)
            btnMute.text = if (micMuted) "Unmute mic" else "Mute mic"
        }
        btnRefresh.setOnClickListener { refreshDevices() }
        btnSetup.setOnClickListener { showSetupGuide() }
        rgDevices.setOnCheckedChangeListener { _, id -> onDeviceSelected(id) }
    }

    private fun generatePipeWireConfig(): String {
        val rate = etRate.text.toString().toIntOrNull() ?: 44100
        val channels = etChannels.text.toString().toIntOrNull() ?: 2
        val pos = if (channels == 1) "[ MONO ]" else "[ FL FR ]"
        return """
            context.modules = [
                {   name = libpipewire-module-protocol-simple
                    args = {
                        capture = true
                        playback = true
                        server.address = [ "tcp:4711" ]
                        audio.rate = $rate
                        audio.format = S16LE
                        audio.channels = $channels
                        audio.position = $pos
                        capture.props = {
                            stream.capture.sink = true
                        }
                        playback.props = {
                            media.class = "Audio/Source"
                            node.latency = "256/$rate"
                        }
                    }
                }
            ]
        """.trimIndent()
    }

    private fun showSetupGuide() {
        val config = generatePipeWireConfig()
        val view = layoutInflater.inflate(R.layout.dialog_setup_guide, null)
        val tvCodeConfig = view.findViewById<TextView>(R.id.tvCodeConfig)
        tvCodeConfig.text = config

        AlertDialog.Builder(this)
            .setTitle("PC setup guide")
            .setView(view)
            .setNeutralButton("Copy config") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("PipeWire Config", config)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Config copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_quit) {
            quit()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun quit() {
        service?.disconnect()
        stopService(Intent(this, AudioBridgeService::class.java))
        finish()
        Process.killProcess(Process.myPid())
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService(Intent(this, AudioBridgeService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (bound) {
            service?.setListener(null)
            unbindService(conn)
            bound = false
        }
        super.onStop()
    }

    private fun onConnectClick() {
        val active = service?.currentState()?.first == BridgeState.CONNECTED ||
            service?.currentState()?.first == BridgeState.CONNECTING
        if (active) {
            service?.disconnect()
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingConnect = true
            permLauncher.launch(missing.toTypedArray())
            return
        }
        doConnect()
    }

    private fun missingPermissions(): List<String> {
        val need = mutableListOf<String>()
        if (!swDisableMic.isChecked) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        return need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun doConnect() {
        android.util.Log.d("PWDBG", "doConnect click")
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull() ?: 4711
        val rate = etRate.text.toString().toIntOrNull() ?: 44100
        val channels = etChannels.text.toString().toIntOrNull() ?: 2
        val disableMic = swDisableMic.isChecked
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter PC IP address", Toast.LENGTH_SHORT).show()
            return
        }
        if (channels != 1 && channels != 2) {
            Toast.makeText(this, "Channels must be 1 or 2", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("pw_client", Context.MODE_PRIVATE).edit()
            .putString("host", host)
            .putString("port", port.toString())
            .putString("rate", rate.toString())
            .putString("channels", channels.toString())
            .putBoolean("anc", swAnc.isChecked)
            .putBoolean("disable_mic", disableMic)
            .apply()

        try {
            ContextCompat.startForegroundService(this, Intent(this, AudioBridgeService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Service blocked: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val svc = service
        if (svc != null) {
            svc.connect(host, port, rate, channels, swAnc.isChecked, disableMic)
        } else {
            // Service still starting; onServiceConnected will call doConnect()
            pendingConnect = true
        }
    }

    private fun onState(state: BridgeState, msg: String) {
        tvStatus.text = "${state.name} — $msg"
        val active = state == BridgeState.CONNECTED || state == BridgeState.CONNECTING
        val micDisabled = swDisableMic.isChecked
        btnConnect.text = if (active) "Disconnect" else "Connect"
        btnConnect.isEnabled = state != BridgeState.CONNECTING
        btnMute.isEnabled = state == BridgeState.CONNECTED && !micDisabled
        if ((state != BridgeState.CONNECTED || micDisabled) && micMuted) {
            micMuted = false
            btnMute.text = "Mute mic"
        }
        etHost.isEnabled = !active
        etPort.isEnabled = !active
        etRate.isEnabled = !active
        etChannels.isEnabled = !active
        swAnc.isEnabled = !active && !micDisabled
        swDisableMic.isEnabled = !active
    }

    private fun refreshDevices() {
        devices.clear()
        rgDevices.removeAllViews()
        val default = RadioButton(this)
        default.id = 0
        default.text = "Default (auto)"
        default.isChecked = true
        rgDevices.addView(default)

        val am = getSystemService(AudioManager::class.java)
        val list = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in OUTPUT_TYPES }
        list.forEachIndexed { i, d ->
            devices.add(d)
            val rb = RadioButton(this)
            rb.id = i + 1
            rb.text = "${d.productName} (${typeLabel(d.type)})"
            rgDevices.addView(rb)
        }

        val saved = getSharedPreferences("pw_client", Context.MODE_PRIVATE)
            .getInt("device_id", 0)
        if (saved == 0 || saved <= devices.size) {
            rgDevices.check(saved)
        }
    }

    private fun onDeviceSelected(id: Int) {
        val dev = if (id == 0) null else devices.getOrNull(id - 1)
        service?.setOutputDevice(dev)
        getSharedPreferences("pw_client", Context.MODE_PRIVATE)
            .edit().putInt("device_id", id).apply()
    }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "line out"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "digital out"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote"
        else -> "device"
    }

    companion object {
        private val OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        )
    }
}
