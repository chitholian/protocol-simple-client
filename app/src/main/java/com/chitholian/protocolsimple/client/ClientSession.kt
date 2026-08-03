package com.chitholian.protocolsimple.client

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

enum class BridgeState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Wire client for PipeWire's "protocol simple" (module-protocol-simple,
 * PipeWire >= 0.3.80). The protocol is a single full-duplex TCP socket carrying
 * raw interleaved S16LE PCM in both directions: bytes read from the socket are
 * played back (server's capture stream), bytes written to the socket become the
 * server's playback stream (virtual mic). No framing, no handshake. Default
 * format S16LE @ 44100 Hz stereo; must match the server config.
 */
class ClientSession(
    private val context: Context,
    private val onState: (BridgeState, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var playback: PlaybackEngine? = null
    private var capture: CaptureEngine? = null

    @Volatile
    private var selectedOutputDevice: AudioDeviceInfo? = null

    val isActive: Boolean get() = running.get()

    fun connect(host: String, port: Int, rate: Int, channels: Int, anc: Boolean, disableMic: Boolean = false) {
        if (!running.compareAndSet(false, true)) return
        post(BridgeState.CONNECTING, "Connecting to $host:$port")
        Thread({
            var lastState = BridgeState.DISCONNECTED
            var errMsg = "Disconnected"
            try {
                val t0 = SystemClock.elapsedRealtime()
                android.util.Log.d("PWDBG", "connect t0")
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.sendBufferSize = 8192
                sock.connect(InetSocketAddress(host, port), 5000)
                android.util.Log.d("PWDBG", "connect t1 sock=${SystemClock.elapsedRealtime() - t0}ms")

                val pl = PlaybackEngine(rate, channels, useVoiceCallStream = !disableMic && anc)
                val targetDev = selectedOutputDevice
                pl.setPreferredDevice(targetDev)
                applyDeviceRouting(targetDev)
                pl.open()
                android.util.Log.d("PWDBG", "connect t2 open=${SystemClock.elapsedRealtime() - t0}ms")

                socket = sock
                playback = pl

                // Drain the audio that piled up on the socket while the
                // AudioTrack was starting (HAL start latency). Keep it fresh.
                sock.soTimeout = 30
                val sink = ByteArray(8192)
                var drained = 0L
                val drainDeadline = SystemClock.elapsedRealtime() + 2000
                while (SystemClock.elapsedRealtime() < drainDeadline && drained < 250_000) {
                    val n = try {
                        sock.getInputStream().read(sink)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                    if (n <= 0) break
                    drained += n
                    if (sock.getInputStream().available() == 0) break
                }
                sock.soTimeout = 0
                android.util.Log.d("PWDBG", "connect t3 drain=${SystemClock.elapsedRealtime() - t0}ms bytes=$drained")

                post(BridgeState.CONNECTED, if (disableMic) "Streaming (receive only): $host:$port" else "Streaming: $host:$port")

                val readT = Thread({ readLoop(sock, pl, RateResampler(rate, channels), rate) }, "pw-read")
                    .apply { priority = Thread.MAX_PRIORITY }
                readT.start()

                val writeT = if (!disableMic) {
                    val out: OutputStream = sock.getOutputStream()
                    val cap = CaptureEngine(context, rate, channels, anc)
                    capture = cap
                    Thread({ writeLoop(sock, cap, out) }, "pw-write")
                        .apply { priority = Thread.MAX_PRIORITY }
                        .also { it.start() }
                } else null

                android.util.Log.d("PWDBG", "connect t4 spawn=${SystemClock.elapsedRealtime() - t0}ms")
                readT.join()
                writeT?.join()
            } catch (e: Exception) {
                lastState = BridgeState.ERROR
                errMsg = e.message ?: e.javaClass.simpleName
            } finally {
                teardown()
                if (running.getAndSet(false)) post(lastState, errMsg)
            }
        }, "pw-session").also { it.priority = Thread.MAX_PRIORITY }.start()
    }

    private fun readLoop(sock: Socket, pl: PlaybackEngine, rs: RateResampler, rate: Int) {
        val chunk = ByteArray(pl.chunkBytes)
        val bytesPerFrame = pl.bytesPerFrame
        var startMs = 0L
        var written = 0L
        var drift = 0
        var healthy = 0
        var skips = 0L
        var lastLog = 0L
        val maxLagFrames = rate / 50 // 20ms max backlog threshold for zero lipsync lag
        try {
            val dis = DataInputStream(sock.getInputStream())
            while (running.get()) {
                val avail = dis.available()
                if (avail > pl.chunkBytes * 2) {
                    val skipFrames = (avail - pl.chunkBytes) / bytesPerFrame
                    if (skipFrames > 0) {
                        val skipped = dis.skipBytes(skipFrames * bytesPerFrame)
                        written += skipped / bytesPerFrame
                        skips++
                    }
                }
                dis.readFully(chunk)
                if (startMs == 0L) {
                    startMs = SystemClock.elapsedRealtime()
                }
                written += chunk.size / bytesPerFrame
                val expected = (SystemClock.elapsedRealtime() - startMs) * rate / 1000
                val behind = expected - written
                if (behind > maxLagFrames) {
                    val skipped = dis.skipBytes((behind * bytesPerFrame).toInt())
                    written += skipped / bytesPerFrame
                    drift = (drift + 4).coerceAtMost(500)
                    rs.targetRate = rate + drift
                    healthy = 0
                } else if (behind < -240) {
                    drift = (drift - 8).coerceAtLeast(0)
                    rs.targetRate = rate + drift
                    healthy = 0
                } else if (healthy++ >= 100 && drift > 0) {
                    drift = (drift - 1).coerceAtLeast(0)
                    rs.targetRate = rate + drift
                    healthy = 0
                }
                if (!pl.write(rs.process(chunk, chunk.size))) break
                val now = SystemClock.elapsedRealtime()
                if (now - lastLog > 5000) {
                    lastLog = now
                    android.util.Log.d(
                        "PWDBG",
                        "avail=${dis.available()} behind=$behind drift=$drift skips=$skips fill=${pl.fillFrames()} read=$written"
                    )
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("AudioBridge", "readLoop: ${e.message}")
        } finally {
            failIfRunning(sock)
        }
    }

    private fun writeLoop(sock: Socket, cap: CaptureEngine, out: OutputStream) {
        val mono = ByteArray(cap.monoChunkBytes)
        try {
            cap.open()
            while (running.get()) {
                val n = cap.read(mono)
                if (n <= 0) throw IOException("mic read failed: $n")
                out.write(cap.toWire(mono, n))
                out.flush()
            }
        } catch (e: Throwable) {
            android.util.Log.e("AudioBridge", "writeLoop: ${e.message}")
        } finally {
            failIfRunning(sock)
        }
    }

    /** If the session is still alive, close the socket to unblock the other loop. */
    private fun failIfRunning(sock: Socket) {
        if (running.get()) {
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun setOutputDevice(device: AudioDeviceInfo?) {
        selectedOutputDevice = device
        applyDeviceRouting(device)
        playback?.setPreferredDevice(device)
    }

    private fun applyDeviceRouting(device: AudioDeviceInfo?) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        android.util.Log.d("ClientSession", "applyDeviceRouting: $device (type=${device?.type})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device != null) {
                val commDevs = am.availableCommunicationDevices
                val targetComm = commDevs.firstOrNull {
                    it.id == device.id || (it.type == device.type && it.productName == device.productName)
                } ?: commDevs.firstOrNull { it.type == device.type }
                if (targetComm != null) {
                    val ok = am.setCommunicationDevice(targetComm)
                    android.util.Log.d("ClientSession", "setCommunicationDevice(${targetComm.productName}) -> $ok")
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                am.isSpeakerphoneOn = true
                if (am.isBluetoothScoOn) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
            } else if (device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            ) {
                am.isSpeakerphoneOn = false
                if (!am.isBluetoothScoOn && am.isBluetoothScoAvailableOffCall) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
            } else {
                am.isSpeakerphoneOn = false
                if (am.isBluetoothScoOn) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
            }
        }
    }

    fun setMicMuted(muted: Boolean) {
        capture?.muted = muted
    }

    fun disconnect() {
        if (!running.getAndSet(false)) return
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        post(BridgeState.DISCONNECTED, "Disconnected")
    }

    private fun teardown() {
        applyDeviceRouting(null)
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        playback?.release()
        capture?.release()
        playback = null
        capture = null
    }

    private fun post(state: BridgeState, msg: String) {
        main.post { onState(state, msg) }
    }
}
