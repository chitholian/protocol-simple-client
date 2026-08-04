# Protocol Simple Client (Android)

Wireless audio bridge between an Android phone and a PC running **PipeWire's
`protocol simple` module** — no apps, no daemons, no cloud on the PC side. Raw
S16LE PCM over a single full-duplex TCP socket.

The app is a **sink + source**:

- **Sink**: plays the PC's audio (anything the PC plays) through the phone's
  speaker / earpiece / Bluetooth, with an output-device switcher.
- **Source**: sends the phone's microphone (with echo cancellation + noise
  suppression) to the PC as a virtual mic.

## Wire protocol

PipeWire `module-protocol-simple` (`libpipewire-module-protocol-simple`):

- Raw PCM, **no framing, no handshake, no magic bytes**.
- Default **S16LE, 48000 Hz, 2 channels (FL FR)**, interleaved. Frame =
  channels × 2 bytes.
- One TCP connection is **full duplex**:
  - PC → phone: `capture` stream (`<peer-ip> capture`, monitors the default sink)
  - phone → PC: `playback` stream (`<peer-ip> playback`, an `Audio/Source`)
- Default port **4711**.

Verified live against PipeWire 1.6.8 (full-duplex, byte-exact).

## PC setup (PipeWire host)

`~/.config/pipewire/pipewire.conf.d/my-protocol-simple.conf`:

```conf
context.properties = {
    default.clock.quantum = 256
    default.clock.min-quantum = 128
}
context.modules = [
    {   name = libpipewire-module-protocol-simple
        args = {
            capture = true
            playback = true
            server.address = [ "tcp:4711" ]
            audio.rate = 48000
            audio.format = S16LE
            audio.channels = 2
            audio.position = [ FL FR ]
            capture.props = {
                # Monitor the default sink: phone hears what PC plays
                stream.capture.sink = true
                node.latency = "256/48000"
            }
            playback.props = {
                # Phone mic appears on PC as a virtual source
                media.class = "Audio/Source"
                # Drain the socket even with no consumer linked, so the
                # phone's TCP write never blocks behind a full buffer
                node.always-process = true
                node.latency = "256/48000"
            }
        }
    }
]
```

Then:

```sh
systemctl --user restart pipewire
ss -tlnp | grep 4711   # verify listener
```

The phone-mic source will show up in the graph as `<phone-ip> playback` — pick
it as the input device in any app (OBS, Discord, browser, …).

> `default.clock.quantum = 256` (~5ms @48k) keeps the graph granular for low
> latency; raise it to 512/1024 if you hear dropouts elsewhere on the desktop
> (Bluetooth, …). Keep the app's sample rate matching `audio.rate` above.

### Sample rate: match the phone

The app defaults the sample-rate field to the **phone's native output rate**
(`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`), because a rate the phone's audio
HAL must resample adds latency and quality loss. Set the PC side to the same
value:

```sh
adb shell getprop ro.audio.sample_rate   # e.g. 48000 — use this as audio.rate
```

If the field ever shows something else, set it back to the native rate.

> Note: `capture.props` must **not** set `media.class = Audio/Sink` for the
> phone-to-hear-PC use case — that makes the capture stream a sink that nothing
> routes into, and the phone receives silence. Use `stream.capture.sink = true`
> (a monitor stream) instead.

## Build

Requires JDK 21 (Android Studio's JBR works) and the Android SDK.

```sh
JAVA_HOME=/opt/apps/android-studio/jbr ./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

minSdk 26 (Android 8+), targetSdk 36.

## Install & use

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Grant microphone permission when prompted (needed for the source half; the
   sink half works without it).
2. Enter the PC's IP (port defaults to `4711`), sample rate/channels matching
   the server config (defaults 48000/2), tap **Connect**. Connect takes
   ~0.2s on the phone side.
3. Tap **Refresh devices** and pick an output device (earpiece, speaker, wired,
   Bluetooth…). "Default (auto)" follows the system routing. Switching devices
   while connected rebuilds the `AudioTrack` on the streaming thread — takes a
   few ms.
4. The connection runs in a foreground service. **Quit** (⋮ menu, top-right)
   stops the stream and the service completely and exits the app.

Settings (host, port, rate, channels, ANC, output device) persist across
launches.

## Latency

- `AudioTrack` in `MODE_STREAM` + `PERFORMANCE_MODE_LOW_LATENCY`, 5 ms chunks,
  blocking writes, buffer shrunk to 512 frames after start.
- `AudioRecord` mono 16-bit, 5 ms chunks, blocking reads.
- Server sets `TCP_NODELAY` + `IP_TOS LOWDELAY` per connection.
- Phone↔PC audio clocks drift (~0.3%); an app-side resampler compensates the
  backlog continuously (the fast AudioTrack path rejects `setPlaybackRate`).
- Connect-time drain clears the backlog the server accumulated during the
  phone's audio-start latency, so playback starts within a few ms of the click.

### Known behavior

- `ss -tnp` may show the server's Send-Q parked at the TCP window size
  (~85–90KB ≈ 450ms) — the server buffers what it streams; the phone-side
  pipeline stays at ~10–15ms (no underruns, no skips). Harmless; the two sides
  consume at matched rates and the queue never grows past the window.
- Sample-rate mismatch between app and server is fine — the server resamples to
  `audio.rate`; keep the app's rate equal to `audio.rate` to avoid that hop.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| No sound, but connected | `capture.props` set `media.class = Audio/Sink` → use `stream.capture.sink = true` (see above). Or nothing is playing on the PC / the default sink is muted. |
| Mic silent on PC | Pick `<phone-ip> playback` as the input device in the target app; check mic permission was granted. |
| Dropouts elsewhere on desktop | Raise `default.clock.quantum` to 512/1024. |
| Audio dull / slightly laggy | App rate ≠ phone native rate → HAL resamples. Check `adb shell getprop ro.audio.sample_rate` and match it (app pre-fills this). |
| Connect hangs for seconds | Older APK with the timeout-based drain — rebuild (drain now exits when the socket is empty). |
