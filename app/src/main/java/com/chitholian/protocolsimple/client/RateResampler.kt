package com.chitholian.protocolsimple.client

/**
 * Linear-interpolation resampler that drains or pads the incoming stream to
 * compensate the phone-vs-PC audio clock drift. The AudioTrack (fast path)
 * rejects setPlaybackRate, so the drift compensation happens here: targetRate
 * above sampleRate shrinks the output (phone consumes input faster than it
 * arrives), below expands it. Stateful across chunks.
 */
class RateResampler(private val sampleRate: Int, private val channels: Int) {
    private val bytesPerFrame = 2 * channels
    private var ratio = 1.0
    private var frac = 0.0
    private var prev = ShortArray(channels)

    var targetRate: Int = sampleRate
        set(value) {
            field = value
            ratio = sampleRate.toDouble() / value
        }

    fun process(input: ByteArray, count: Int): ByteArray {
        val inFrames = count / bytesPerFrame
        if (frac < 0.0) frac = 0.0
        val outFrames = (inFrames * ratio).toInt()
        val out = ByteArray(outFrames * bytesPerFrame)
        var fo = 0
        var fi = 0
        while (fo < outFrames) {
            fi = frac.toInt()
            if (fi >= inFrames) break
            val t = frac - fi
            for (c in 0 until channels) {
                val a: Int = if (fi == 0) prev[c].toInt()
                else readSample(input, fi - 1, c).toInt()
                val b = readSample(input, fi, c)
                val s = (a + ((b - a) * t).toInt()).coerceIn(-32768, 32767)
                writeSample(out, fo, c, s.toShort())
            }
            frac += 1.0 / ratio
            fo++
        }
        if (inFrames > 0) {
            frac -= inFrames
            for (c in 0 until channels) prev[c] = readSample(input, inFrames - 1, c)
        }
        return out
    }

    private fun readSample(buf: ByteArray, frame: Int, channel: Int): Short {
        val o = frame * bytesPerFrame + channel * 2
        return ((buf[o].toInt() and 0xff) or (buf[o + 1].toInt() shl 8)).toShort()
    }

    private fun writeSample(buf: ByteArray, frame: Int, channel: Int, v: Short) {
        val o = frame * bytesPerFrame + channel * 2
        buf[o] = (v.toInt() and 0xff).toByte()
        buf[o + 1] = (v.toInt() shr 8).toByte()
    }
}
