<p align="center">
  <img src="art/microphun-logo.png" alt="microphun mascot" width="200">
</p>

<h1 align="center">🎤 microphun 🌈</h1>

<p align="center">
  <em>A real-time psychedelic audio visualiser for Android.</em><br>
  Point it at the room, play something loud, and watch the sound melt. 🫠
</p>

---

## ✨ What it does

microphun listens through the microphone, runs an FFT on what it hears, and
feeds the spectrum to GLSL shaders drawn full-screen at display rate. Bass,
mids, treble, loudness and beats all drive the picture, so it moves with
whatever is playing: a speaker, a band, a party, a conversation.

**Tap** the screen to cycle to the next visual. 👆

## 🎨 The visuals

| | Visual | What you see |
| --- | --- | --- |
| 🔮 | **kaleidoscope** | Mirrored wedges that fold and spin with the music. The default. |
| 🌊 | **plasma** | A layered field of sine waves, rippling with every band. |
| 🌀 | **tunnel** | A polar tunnel rushing towards you. |
| 📊 | **spectrum** | Mirrored radial bars: the spectrum itself, made pretty. |
| 💬 | **words** | What people in the room say, as a word cloud. Repeated words grow and pulse; forgotten ones fade. |
| 🐚 | **spiral** | New phrases invented from what was said, spilling out of the centre along a spiral. |
| 🔥 | **fire** | An image burns in from the edges, faster on the beat, then poofs into the next. **Long-press** to pick your own photos. |
| 🪟 | **crack** | Glass over an image. Every noise cracks it, louder noises harder, until it shatters. 💥 |

## 🔒 Privacy

microphun needs the microphone, and it treats that seriously:

- 🎧 It only listens while it is on screen. Leave the app and it lets go of
  the microphone.
- 🧠 Speech recognition runs only while **words** or **spiral** is showing.
  What it hears is kept in memory for that session and never written anywhere.
- 🙅 Invented phrases are never allowed to repeat four words in a row of what
  someone actually said, so the spiral riffs on the conversation without
  quoting anyone.
- 🖼️ The fire visual reads only the photos you pick, through the system photo
  picker. No storage permission is requested.

## 📱 Requirements

- Android 8.0 (API 26) or newer, with OpenGL ES 3.0.
- Android 13 (API 33) or newer for the speech visuals to keep the microphone
  while they listen. Older versions still work, but the other visuals go quiet
  while speech recognition has the mic.
- Tested on a Galaxy S24 Ultra.

> 💡 **Why the microphone and not your music app directly?** Android lets apps
> capture other apps' audio only if those apps opt in, and Spotify, YouTube
> Music and most streaming apps don't. The microphone hears everything.

## 🛠️ Building

You need JDK 21 and the Android SDK (platform 35, NDK 27.3.13750724,
CMake 3.22.1). Put the SDK path in `local.properties`:

```properties
sdk.dir=/home/you/Android/Sdk
```

Then run `make` on its own to list the targets:

| Command | What it does |
| --- | --- |
| `make build` | 📦 Build the debug APK → `app/build/outputs/apk/debug/app-debug.apk` |
| `make install` | 📲 Install it on the connected device |
| `make run` | 🚀 Install and launch it |
| `make test` | 🧪 Run all host tests, Kotlin and native |
| `make test-kotlin` | ☕ Just the Kotlin unit tests |
| `make test-native` | ⚙️ Just the C++ host tests (ring buffer, speech tap) |
| `make test-tsan` | 🧵 The C++ tests under ThreadSanitizer |
| `make lint` | 🔍 Android lint |
| `make clean` | 🧹 Remove build outputs |
| `make ci` | ✅ Lint, test and build: everything that must pass before a commit |

`make run` expects `adb` at `~/Android/Sdk/platform-tools/adb`. Override it
with `make run ADB=/path/to/adb`.

The DSP and the native audio plumbing are plain C++, so their tests run on
your machine without a device.

## 🧬 How it works

```
mic ─► Oboe callback ─► ring buffer ─► [GL thread] FFT ─► uniforms ─► fragment shader
       (realtime)           │          (lock-free)
                            └─► speech tap ─► 16 kHz ─► SpeechRecognizer  (words & spiral only)
```

- 🎙️ **Capture** uses [Oboe](https://github.com/google/oboe) with the
  unprocessed input preset, so no automatic gain control flattens the
  dynamics the visuals exist to show.
- ⚡ **The audio callback never waits.** It downmixes and appends to a
  lock-free ring, nothing more. The FFT runs on the GL thread, once per frame.
- 🎛️ **Every shader gets the same uniforms:** 24 log-spaced bands from 40 Hz
  to 16 kHz, overall level, bass/mid/treble, a beat onset and time, all
  normalised to 0..1.
- 🗣️ **Speech recognition is fed from microphun's own capture** through a
  pipe, rather than letting the recogniser take the microphone and silence
  the visuals.

### ➕ Adding a visual

1. Drop `yourname.frag` in `app/src/main/assets/shaders/`, copying the
   uniform block from `plasma.frag`.
2. Add `"yourname"` to `VisualizerRenderer.SHADERS`.
3. Build, install, and tap until it shows up. 🎉

Developer notes, design decisions and the verification log live in
[`CLAUDE.md`](CLAUDE.md).

## 🐛 Known rough edges

- 🌑 `kaleidoscope` is dimmer than `plasma` under the same audio.
- ⏱️ Latency follows the beat by eye but has not been measured.
- ✏️ A word the recogniser revises after moving past it can stay wrong in
  the word cloud.
