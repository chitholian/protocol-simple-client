package com.chitholian.protocolsimple.client

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Low-latency PCM playback engine. Wraps a streaming AudioTrack in
 * PERFORMANCE_MODE_LOW_LATENCY. Output routing is done by (re)creating the
 * track with setPreferredDevice — setting `preferredDevice` on a live track is
 * ignored by many OEMs (notably Samsung), so the track is rebuilt on the
 * pw-read thread when a device change is pending.
 */
class PlaybackEngine(
    val sampleRate: Int,
    private val channels: Int,
    private val micEnabled: Boolean = false,
) {
    private var track: AudioTrack? = null
    private var framesWritten = 0L

    /** Device requested by the UI thread; consumed on the next write. */
    @Volatile
    private var pendingDevice: AudioDeviceInfo? = null

    /** Identity of the device the current track was built with. */
    @Volatile
    private var currentDevice: AudioDeviceInfo? = null

    val bytesPerFrame: Int get() = 2 * channels

    /** bytes for one 5ms chunk (frame aligned) */
    val chunkBytes: Int get() = sampleRate / 200 * bytesPerFrame

    private val channelMask: Int
        get() = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    fun open() {
        if (track != null) return
        rebuild(currentDevice)
        val t = track ?: return
        // Kick the HAL clock: the playback head only advances once data flows.
        try {
            t.write(ByteArray(chunkBytes), 0, chunkBytes, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) {
        }
        // Wait until the HAL actually starts consuming (its start latency can
        // be 100-400ms on some devices). While we wait, the server keeps
        // streaming into the socket; the caller discards that backlog so the
        // phone clock aligns with the stream instead of lagging permanently.
        val deadline = android.os.SystemClock.uptimeMillis() + 2000
        while (t.playbackHeadPosition == 0 && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    /** Blocking write of one chunk. Returns false if the engine was released. */
    fun write(buf: ByteArray): Boolean {
        val pending = pendingDevice
        if (pending != null && pending !== currentDevice) {
            try {
                rebuild(pending)
            } catch (_: Exception) {
                return false
            }
        }
        val t = track ?: return false
        return try {
            framesWritten += buf.size / bytesPerFrame
            t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING) == buf.size
        } catch (_: Exception) {
            false
        }
    }

    /** null = system default routing. Safe from any thread; applied on write. */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        pendingDevice = device
    }

    fun fillFrames(): Int {
        val t = track ?: return 0
        return try {
            (framesWritten - t.playbackHeadPosition).toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun rebuild(device: AudioDeviceInfo?) {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
        track = null
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        android.util.Log.d("PlaybackEngine", "rebuild rate=$sampleRate ch=$channels dev=$device minBuf=$minBuf frames=${minBuf / bytesPerFrame}")
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBuf)
            .build()
        t.setPreferredDevice(device)
        t.play()
        val sz = t.setBufferSizeInFrames(512)
        android.util.Log.d("PlaybackEngine", "shrunk buffer $sz frames (${sz * 1000 / sampleRate}ms)")
        track = t
        currentDevice = device
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
