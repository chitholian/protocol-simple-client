package com.chitholian.protocolsimple.client

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM playback engine. Wraps a streaming AudioTrack.
 * In media mode, uses PERFORMANCE_MODE_LOW_LATENCY and small buffer sizes.
 * In voice communication mode (mic enabled + ANC), uses PERFORMANCE_MODE_NONE
 * and natural minBuf sizing to avoid voice DSP buffer underruns (robotic audio).
 * Output routing is done by (re)creating the track with setPreferredDevice.
 * Downmixes stereo to mono dynamically when target device/voice stream requires it.
 */
class PlaybackEngine(
    val sampleRate: Int,
    private val inputChannels: Int,
    private val useVoiceCallStream: Boolean = false,
) {
    private var track: AudioTrack? = null
    private var framesWritten = 0L

    @Volatile
    private var pendingDevice: AudioDeviceInfo? = null

    @Volatile
    private var currentDevice: AudioDeviceInfo? = null

    @Volatile
    private var deviceChanged: Boolean = true

    @Volatile
    private var outputChannels: Int = inputChannels

    val bytesPerFrame: Int get() = 2 * inputChannels

    /** bytes for one 5ms chunk of input PCM (frame aligned) */
    val chunkBytes: Int get() = sampleRate / 200 * bytesPerFrame

    private val channelMask: Int
        get() = if (outputChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    fun open() {
        if (track != null) return
        deviceChanged = false
        rebuild(pendingDevice)
        val t = track ?: return
        // Kick the HAL clock: the playback head only advances once data flows.
        try {
            val initBuf = if (inputChannels == 2 && outputChannels == 1) {
                downmixStereoToMono(ByteArray(chunkBytes))
            } else {
                ByteArray(chunkBytes)
            }
            t.write(initBuf, 0, initBuf.size, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) {
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 2000
        while (t.playbackHeadPosition == 0 && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    /** Blocking write of one chunk. Returns false if the engine was released. */
    fun write(buf: ByteArray): Boolean {
        if (deviceChanged) {
            deviceChanged = false
            try {
                rebuild(pendingDevice)
            } catch (_: Exception) {
                return false
            }
        }
        val t = track ?: return false
        return try {
            framesWritten += buf.size / bytesPerFrame
            val writeData = if (inputChannels == 2 && outputChannels == 1) {
                downmixStereoToMono(buf)
            } else {
                buf
            }
            t.write(writeData, 0, writeData.size, AudioTrack.WRITE_BLOCKING) == writeData.size
        } catch (_: Exception) {
            false
        }
    }

    /** null = system default routing. Safe from any thread; applied on write. */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        pendingDevice = device
        deviceChanged = true
    }

    fun fillFrames(): Int {
        val t = track ?: return 0
        return try {
            (framesWritten - t.playbackHeadPosition).toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun determineOutputChannels(device: AudioDeviceInfo?): Int {
        if (inputChannels == 1) return 1
        if (useVoiceCallStream) return 1
        if (device != null) {
            val counts = device.channelCounts
            if (counts.isNotEmpty() && !counts.contains(2) && counts.contains(1)) {
                return 1
            }
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) {
                return 1
            }
        }
        return 2
    }

    private fun rebuild(device: AudioDeviceInfo?) {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
        track = null

        outputChannels = determineOutputChannels(device)

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        android.util.Log.d(
            "PlaybackEngine",
            "rebuild rate=$sampleRate inCh=$inputChannels outCh=$outputChannels dev=$device minBuf=$minBuf frames=${minBuf / (2 * outputChannels)}"
        )
        val usage = if (useVoiceCallStream) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val contentType = if (useVoiceCallStream) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC
        val perfMode = if (useVoiceCallStream) AudioTrack.PERFORMANCE_MODE_NONE else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(perfMode)
            .setBufferSizeInBytes(minBuf)
            .build()

        t.setPreferredDevice(device)
        t.play()

        val targetFrames = if (useVoiceCallStream) 1024 else 512
        val sz = t.setBufferSizeInFrames(targetFrames)
        android.util.Log.d("PlaybackEngine", "shrunk buffer $sz frames (${sz * 1000 / sampleRate}ms)")

        track = t
        currentDevice = device
    }

    private fun downmixStereoToMono(stereo: ByteArray): ByteArray {
        val numFrames = stereo.size / 4
        val mono = ByteArray(numFrames * 2)
        val src = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val dst = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var i = 0
        while (i < numFrames) {
            val l = src.get().toInt()
            val r = src.get().toInt()
            dst.put(((l + r) / 2).toShort())
            i++
        }
        return mono
    }

    fun release() {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
        track = null
    }
}
