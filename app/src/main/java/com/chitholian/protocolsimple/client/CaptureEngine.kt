package com.chitholian.protocolsimple.client

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-latency mic capture engine. Captures mono S16 PCM, optionally with
 * acoustic echo cancellation + noise suppression (ANC) attached to the
 * recording session. mono -> N-channel expansion happens in [toWire] so the
 * wire format always matches the server config (default stereo).
 */
class CaptureEngine(
    private val sampleRate: Int,
    private val outChannels: Int,
    private val anc: Boolean,
) {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var wireBuf: ByteArray? = null

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

        val rec: AudioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
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
                    AcousticEchoCanceler.create(rec.audioSessionId)
                        ?.also { it.enabled = true }
                        ?.let { aec = it }
                }
            } catch (_: Exception) {
            }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(rec.audioSessionId)
                        ?.also { it.enabled = true }
                        ?.let { ns = it }
                }
            } catch (_: Exception) {
            }
        }

        record = rec
        rec.startRecording()
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
