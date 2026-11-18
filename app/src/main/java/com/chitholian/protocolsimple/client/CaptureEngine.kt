package com.chitholian.protocolsimple.client

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-latency mic capture engine. Captures mono S16 PCM, optionally with
 * acoustic echo cancellation + noise suppression (ANC) attached to the
 * recording session. mono -> N-channel expansion happens in [toWire] so the
 * wire format always matches the server config (default stereo).
 */
// RECORD_AUDIO is verified by AudioBridgeService.connect() before a CaptureEngine is created.
@SuppressLint("MissingPermission")
class CaptureEngine(
    private val context: Context,
    private val sampleRate: Int,
    private val outChannels: Int,
    private val anc: Boolean,
) {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var wireBuf: ByteArray? = null
    private var deviceCallback: AudioDeviceCallback? = null

    @Volatile
    private var selectedInput: AudioDeviceInfo? = null

    /** When true, the mic stream is replaced with digital silence. */
    @Volatile
    var muted: Boolean = false

    /** mono capture chunk (10ms) in bytes */
    /** bytes for one 5ms chunk of mono S16 */
    val monoChunkBytes: Int get() = sampleRate / 200 * 2

    fun open() {
        if (record != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val primarySource = if (anc) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val rec: AudioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(primarySource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .build()
        } catch (_: Exception) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .build()
        }

        if (anc) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply {
                        enabled = true
                    }
                    android.util.Log.d("CaptureEngine", "AEC enabled on audioSessionId=${rec.audioSessionId}, active=${aec?.enabled}")
                } else {
                    android.util.Log.w("CaptureEngine", "AcousticEchoCanceler is not available on this device")
                }
            } catch (e: Exception) {
                android.util.Log.e("CaptureEngine", "Failed to enable AEC: ${e.message}")
            }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(rec.audioSessionId)?.apply {
                        enabled = true
                    }
                    android.util.Log.d("CaptureEngine", "NoiseSuppressor enabled on audioSessionId=${rec.audioSessionId}, active=${ns?.enabled}")
                } else {
                    android.util.Log.w("CaptureEngine", "NoiseSuppressor is not available on this device")
                }
            } catch (e: Exception) {
                android.util.Log.e("CaptureEngine", "Failed to enable NS: ${e.message}")
            }
        }

        record = rec
        rec.startRecording()

        setupBluetoothAndRouting(rec)
    }

    private fun setupBluetoothAndRouting(rec: AudioRecord) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        updateMicRouting(am, rec)

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                updateMicRouting(am, record)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                updateMicRouting(am, record)
            }
        }
        am.registerAudioDeviceCallback(cb, null)
        deviceCallback = cb
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        selectedInput = device
        val rec = record
        if (rec != null) {
            if (device != null) {
                rec.setPreferredDevice(device)
                android.util.Log.d("CaptureEngine", "Mic preferred device (user) set to: ${device.productName} (type ${device.type})")
            } else {
                context.getSystemService(AudioManager::class.java)?.let { updateMicRouting(it, rec) }
            }
        }
    }

    private fun updateMicRouting(am: AudioManager, rec: AudioRecord?) {
        val targetRec = rec ?: return
        val explicit = selectedInput
        if (explicit != null) {
            targetRec.setPreferredDevice(explicit)
            if (isBluetoothInput(explicit)) boostBluetoothCallVolume(am)
            android.util.Log.d("CaptureEngine", "Mic preferred device (user) = ${explicit.productName}")
            return
        }
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val btInput = inputs.firstOrNull { isBluetoothInput(it) }
        val externalMic = btInput ?: inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }

        if (btInput != null) boostBluetoothCallVolume(am)

        if (externalMic != null) {
            targetRec.setPreferredDevice(externalMic)
            android.util.Log.d("CaptureEngine", "Mic preferred device set to: ${externalMic.productName} (type ${externalMic.type})")
        } else {
            targetRec.setPreferredDevice(null)
            android.util.Log.d("CaptureEngine", "Mic preferred device reset to default built-in mic")
        }
    }

    private fun isBluetoothInput(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun boostBluetoothCallVolume(am: AudioManager) {
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val curVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        if (curVol < (maxVol * 0.8).toInt()) {
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.85).toInt(), 0)
            android.util.Log.d("CaptureEngine", "Boosted Bluetooth Call Volume to ${(maxVol * 0.85).toInt()}/$maxVol")
        }
    }

    /** Blocking read of one mono chunk. Returns bytes read (>0 ok). */
    fun read(mono: ByteArray): Int {
        val n = record?.read(mono, 0, mono.size, AudioRecord.READ_BLOCKING) ?: 0
        if (muted && n > 0) {
            mono.fill(0)
        }
        return n
    }

    /**
     * Expand [count] mono bytes to the wire channel count (duplicate samples
     * for stereo). Returns a new array of count * outChannels / 1.
     */
    fun toWire(mono: ByteArray, count: Int): ByteArray {
        if (outChannels == 1) return mono.copyOf(count)
        val n = count / 2 // shorts
        val out = wireBuf ?: ByteArray(mono.size * 2).also { wireBuf = it }
        val src = ByteBuffer.wrap(mono, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var i = 0
        while (i < n) {
            val s = src.get()
            dst.put(s)
            dst.put(s)
            i++
        }
        return out.copyOf(n * 4)
    }

    fun release() {
        val am = context.getSystemService(AudioManager::class.java)
        deviceCallback?.let {
            am?.unregisterAudioDeviceCallback(it)
            deviceCallback = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am?.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            if (am?.isBluetoothScoOn == true) {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
        }
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        try {
            aec?.release()
            ns?.release()
        } catch (_: Exception) {
        }
        record = null
        aec = null
        ns = null
    }
}
