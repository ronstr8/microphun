# microphun

Real-time psychedelic audio visualiser for Android. The microphone feeds an
FFT in C++; the resulting spectrum drives GLSL fragment shaders rendered
full-screen at display rate.

Status: working on hardware. See [Verification status](#verification-status).

## Platform constraints (read before proposing features)

These are hard limits, not preferences. They shaped the architecture.

- **System audio capture is mostly unavailable.** Android's
  `AudioPlaybackCapture` (API 29+) only captures apps that opt in. Spotify,
  YouTube Music and most streaming apps set `ALLOW_CAPTURE_BY_NONE`, and DRM
  audio is excluded unconditionally. It also requires a `MediaProjection`
  consent dialog. It is a bonus path, never the foundation.
- **The microphone is the one reliable input** and is what v1 uses. It hears
  anything audible in the room, including a speaker playing the audio you
  actually want to visualise.
- **Only one capture gets the microphone at a time.** Anything else that wants
  to listen — notably a speech recogniser, which is a separate app — will take
  it and leave our stream silent. Nothing in microphun may hand the microphone
  over; anything that needs audio gets fed from our capture instead. See
  `SpeechTap` and [The `words` visual](#the-words-visual).
- **The legacy `Visualizer` API is not an option.** 8-bit, 1024 bytes, and
  increasingly restricted on modern Android. The FFT here exists to avoid it.
- **Casting the visual to a TV was tried and dropped.** The Presentation API
  works — Android hands the app a real display for a Miracast receiver or an
  HDMI adapter, and the visual renders natively on it. What does not work is
  keeping it running: any shift of attention on the phone (the cast panel, the
  notification shade, the screen locking) pauses the activity, which stops
  capture and dismisses the presentation. Surviving that needs a foreground
  service with `microphone` type, so the app would hold the microphone while it
  is not on screen. That trade was declined on 2026-09-22 — the feature is not
  worth the privacy posture. The work is parked in a git stash, not deleted.
- **Xbox is not a near-term target.** Retail consoles sandbox apps with no
  loopback or system-audio capture API at all, and shipping requires ID@Xbox
  or a GDK partner licence. A console port would have to play its own audio.
  The shaders would carry over; nothing else would.

## Architecture

```
mic ─► Oboe callback ─► ring buffer ─► [GL thread] FFT ─► Frame ─► uniforms ─► fragment shader
       (realtime thread)   │           (lock-free)                              (one fullscreen triangle)
                           │
                           └─► tap ring ─► [writer thread] 16 kHz S16 ─► pipe ─► SpeechRecognizer
                               (lock-free)                                        (words visual only)
```

The audio callback does nothing but downmix and append — to the FFT ring, and
to the speech tap's ring when a recogniser is listening. No allocation, locking,
logging or syscall happens on the realtime thread, and nothing downstream is
ever allowed to make it wait. The FFT runs on the GL
thread, pulled once per rendered frame — a 2048-point transform at 60 fps is
cheap, and this keeps the audio path clean. The tap's resampling and its
`write(2)` happen on their own thread for the same reason.

| Layer | Files |
| --- | --- |
| Capture | `app/src/main/cpp/audio_engine.{h,cpp}` — Oboe input stream, device-disconnect recovery |
| Ring | `app/src/main/cpp/sample_ring.{h,cpp}` — lock-free single-producer ring with snapshot reads; `sample_ring_test.cpp` is a host test, not in the CMake target |
| Speech feed | `app/src/main/cpp/speech_tap.{h,cpp}` — resample to 16 kHz mono S16, write to a pipe; `speech_tap_test.cpp` is a host test, not in the CMake target |
| DSP | `app/src/main/cpp/fft.{h,cpp}` — Hann-windowed radix-2 FFT; `analysis.{h,cpp}` — log-spaced bands, RMS level, spectral-flux onsets |
| Bridge | `app/src/main/cpp/jni_bridge.cpp` ↔ `app/src/main/java/com/microphun/audio/AudioEngine.kt` |
| Render | `app/src/main/java/com/microphun/gl/` — `VisualizerRenderer`, `ShaderProgram`, `VisualizerSurfaceView`, `GlyphCache`, `WordLayer` |
| Speech | `app/src/main/java/com/microphun/speech/` — `WordListener` (continuous `SpeechRecognizer`, fed from our capture), `WordStream` (hand-off queue) |
| Visuals | `app/src/main/assets/shaders/*.frag` |
| Shell | `app/src/main/java/com/microphun/MainActivity.kt` — permission flow, immersive mode, lifecycle |

### The `Frame` contract

Every shader receives the same uniforms, all normalised to 0..1:

| Uniform | Meaning |
| --- | --- |
| `uBands[24]` | Log-spaced band magnitudes, 40 Hz → 16 kHz, low first. Fast attack, slow decay. |
| `uLevel` | Broadband RMS. |
| `uBass` / `uMid` / `uTreble` | 40–250 Hz / 250–2000 Hz / 2–16 kHz. |
| `uOnset` | Spikes to 1 on a detected beat, then decays. Use for punches and flashes. |
| `uTime` | Seconds since the surface was created. |
| `uResolution` | Viewport pixels. |

Magnitudes are mapped through a −70 dB floor before normalising, so quiet
detail stays visible instead of crushing to black.

## Adding a visual

1. Drop `yourname.frag` in `app/src/main/assets/shaders/`. Copy the uniform
   block from `plasma.frag` verbatim.
2. Add `"yourname"` to `VisualizerRenderer.SHADERS`.
3. Tap the screen at runtime to cycle to it.

Write `$BAND_COUNT` wherever the band count is needed — the renderer
substitutes the real value at load time so shaders never hardcode it.

Existing shaders: `kaleidoscope` (mirrored wedges, the default), `plasma`
(layered sine field), `tunnel` (polar perspective), `spectrum` (mirrored
radial bars), `words` (a dark backdrop under the word cloud — see below).

## The `words` visual

The one visual that is not just a fragment shader. `words.frag` draws a
deliberately dark, low-contrast backdrop, and `WordLayer` then draws recognised
speech over it: one textured quad per live word.

Rasterising lives in `GlyphCache`, not in the layout — the `Paint` and its baked
halo, the bitmap, the texture upload, and the hue derived from the text. Layouts
share one cache, so a word costs one rasterisation however many times it appears
or however many visuals show it. **Its one rule: ask for a glyph on every frame
you draw it, rather than holding the `Glyph`.** Entries are evicted
least-recently-used past 48, and the lookup is what marks one as still in use;
hold a reference instead and a long-lived word can have its texture deleted out
from under it. The renderer owns the cache and clears it on the way out of the
visual.

- A new word lands at a random position — best of ten candidates by distance to
  the words already up, which is cheap and enough to stop the cloud piling into
  one corner.
- A repeat does not move the word. It scores a hit, which enlarges it a step
  (capped at 12 hits) and kicks a pulse that flashes and overshoots its size.
  A word fades out after nine seconds of not being heard, so the screen ends up
  showing what the room keeps saying.
- The glyph textures carry a baked black halo. `text.frag` uses their rgb as the
  ink mask and their alpha as coverage, so the letters take a colour while the
  halo only darkens what is behind them. That is what keeps a word readable
  without dimming the visual — and it is why `words.frag` does not need to be
  dark. Legibility is handled locally, per word, wherever a word lands; the
  backdrop only has to stay low contrast so the eye settles on the text. It was
  first written near-black for legibility's sake, which was doing that job
  twice, and the result did not read as a visual at all on the device.
- Blending is premultiplied (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`) because `Canvas`
  bitmaps are premultiplied. `text.frag` keeps its output premultiplied to match.

Speech recognition only runs while this visual is on screen —
`MainActivity.syncListener` starts and stops it on every shader change, and
`onPause` stops it like capture.

### Keeping the microphone

The recogniser is a separate app, and Android hands the microphone to one
capture at a time, so a recogniser left to open its own input silences ours and
the visuals go still. microphun keeps the microphone and feeds the recogniser
instead:

```
SpeechTap (native)  ──► pipe write end
                          │
WordListener        ──►  read end handed to the service as EXTRA_AUDIO_SOURCE
```

- `WordListener.openTap` creates a pipe per session, hands the write end's
  detached fd to native (which owns and closes it from then on), and keeps the
  read end alive until that session ends. A fresh pipe per session matters: a
  reused one would start the next session on audio left in the buffer.
- `SpeechTap` resamples the captured audio to 16 kHz mono 16-bit — a 33-tap
  windowed-sinc low pass just under the output Nyquist, then linear
  interpolation at a fractional step, so a device that negotiates 44.1 kHz works
  as well as one at 48 kHz. The intent declares that format in
  `EXTRA_AUDIO_SOURCE_{CHANNEL_COUNT,ENCODING,SAMPLING_RATE}`.
- The pipe's write end is `O_NONBLOCK`, and the tap drops audio rather than
  stalling anything when the recogniser stops reading: first at the pipe, on
  `EAGAIN`, and then at the ring, where `push` refuses to pass `read_` instead of
  overwriting a slot the writer thread is mid-way through. That second rule is
  what keeps the single-producer/single-consumer ring free of an actual data
  race — ThreadSanitizer catches it if the drop is moved back to the consumer.
  Dropping is the right trade either way: that audio is stale by the time
  anybody wants it.
- `EXTRA_SEGMENTED_SESSION` asks for one session that lives as long as the
  descriptor, reporting each utterance through `onSegmentResults`. The docs are
  explicit that a recogniser may ignore it, so `onResults` still restarts the
  session the ordinary way; `onSegmentResults` resets the emitted tail because
  each segment starts a fresh hypothesis.
- `EXTRA_AUDIO_SOURCE` needs API 33. Below that, and if the recogniser reports
  nothing at all for `FALLBACK_AFTER_SILENT_SESSIONS` sessions,
  `WordListener.fallBackToMicrophone` gives up and lets it open the microphone.
  The visuals then lose their input while it listens, which is worse but better
  than a word cloud that never fills, and the UI says so once.

`SpeechRecognizer` is one-shot, so `WordListener` restarts it after every
utterance and backs off exponentially on real errors while treating no-match and
timeout as ordinary silence. Restarts go through a single pending `Runnable`,
because a session can report both a result and an error, and `cancel` can report
one of its own.

### Which words are settled

Partial results are requested so a word can appear while it is still being said,
but **only tokens the recogniser has moved past are emitted** — every token but
the last, plus the last one on a final result. That rule is not fussiness. The
recogniser refines the word in progress one guess at a time, and each guess has
the same token count as the one before it:

```
partial: ... problems with vol
partial: ... problems with volt
partial: ... problems with voltage
```

Emitting the whole hypothesis therefore strands every word at its earliest,
shortest guess — one syllable each, never corrected, because a word already
drawn cannot be taken back. The cost of the rule is that a word appears when the
next one begins, or at the end of the utterance if it is the last.

Set `emittedCount` back to zero whenever a new hypothesis starts: a new session,
a new segment, or the end of a segmented session. It never decreases within a
hypothesis, so a hypothesis that shrinks cannot re-emit what is already up.

`WordStream` is the only state shared between the recogniser (main thread) and
`WordLayer` (GL thread): a bounded queue that drops words rather than blocking
or growing, same rule as the audio path.

## App icon

The mascot is an adaptive icon, generated from the art rather than hand-drawn:

| Piece | Where |
| --- | --- |
| Source art | `art/microphun-logo-source.png` — the mascot as drawn, on flat white |
| Cut-out | `art/microphun-logo.png` — same art with the background lifted off |
| Generator | `art/make_icons.py` — rebuilds both of the bitmap layers |
| Layers | `res/mipmap-*/ic_launcher_{foreground,monochrome}.png` over `@color/ic_launcher_background`, assembled by `res/mipmap-anydpi-v26/ic_launcher.xml` |

```bash
python3 art/make_icons.py     # after changing the art; nothing in the Gradle build runs it
```

Three things about it are deliberate:

- **The background is lifted with a flood fill from the borders, not a colour
  key.** The mascot's eyes, teeth and highlights are the same white as the
  paper; they survive because its black outline walls them off from the edge.
- **The mascot sits at 70dp of the 108dp canvas.** Only the middle 72dp
  survives every launcher mask and a circular mask clips even that, so 70dp is
  as large as it goes — at 74dp a shoe crosses the arc.
- **The monochrome layer is ink, not a silhouette.** It is built from how dark
  the drawing is, so the face and the mesh survive; a flat silhouette of this
  character is an unreadable blob.

## Conventions

- **`Frame::kBands` in `analysis.h` and `AudioEngine.BAND_COUNT` in Kotlin
  must match.** The constructor checks this at runtime and throws if they
  drift.
- **The FFT ring is read as a snapshot, and a stale snapshot is detected, not
  prevented.** `SampleRing` hands back the newest `kFftSize` samples while the
  callback keeps appending; the two collide once the callback wraps around onto
  the window. The slots are therefore relaxed atomics — the same instructions on
  arm64, minus the compiler's licence to assume they cannot change — and
  `snapshot` re-reads the write cursor afterwards, reporting `Lapped` when the
  window was spliced from two moments. `AudioEngine::frame` retries and then
  repeats the last good frame. Do not "fix" this by making the producer wait for
  the consumer: that stalls live audio to protect stale samples. The speech tap
  drops at the producer precisely because its consumer wants every sample in
  order, and the FFT's does not.
- Shaders are ES 3.0 (`#version 300 es`); the manifest requires ES 3.0 and the
  surface requests context version 3.
- `AudioEngine` is `AutoCloseable` and owns a native pointer. Failing to
  `close()` leaks the Oboe stream.
- Capture starts in `onResume` and stops in `onPause` — microphun never holds the
  mic while backgrounded.
- Oboe is asked for `InputPreset::Unprocessed` deliberately: AGC and noise
  suppression are tuned for speech and would flatten exactly the dynamics the
  visuals exist to show.
- The device may refuse the requested sample rate. `AudioEngine::start`
  rebuilds the `Analyzer` against the rate actually granted, otherwise every
  frequency would be mislabelled. It also calls `SpeechTap::setInputRate` for
  the same reason: resampling from the wrong input rate hands the recogniser
  pitch-shifted speech.
- **`SpeechTap::kOutputRate` in `speech_tap.h` and
  `AudioEngine.SPEECH_SAMPLE_RATE` in Kotlin must match.** Kotlin declares that
  rate to the recogniser while native resamples to it; the constructor checks
  the pair the same way it checks the band count.
- A file descriptor passed to `AudioEngine.startSpeechTap` is owned by native
  code, which closes it — including when the call fails. Pass a detached fd,
  never one a `ParcelFileDescriptor` still owns, or it gets closed twice.
- The speech tap is deliberately independent of `AudioEngine::stop`, so a device
  disconnect can reopen the stream without interrupting recognition. It is
  stopped by the destructor, and by `WordListener`.
- **Native libraries must be 16 KB page aligned.** Android 15+ uses 16 KB
  pages on newer arm64 hardware and refuses to load 4 KB aligned libraries.
  `CMakeLists.txt` passes `-Wl,-z,max-page-size=16384`; Oboe must stay recent
  enough to ship aligned binaries (1.9.0 was not, 1.11.0 is). Verify with:
  `llvm-readelf -l libmicrophun.so | grep LOAD` — every LOAD must show `0x4000`.
- **The launcher icon stays in `mipmap-anydpi-v26`.** Lint's `ObsoleteSdkInt`
  says to merge it into plain `mipmap-anydpi` because minSdk is 26, and that
  advice does not work: AGP 8.7's resource merger silently drops a bare
  `anydpi` mipmap folder and the build fails with `resource mipmap/ic_launcher
  not found`. `aapt2 compile` accepts the same file on its own, so the problem
  is the merger, not the qualifier.
- `VisualizerSurfaceView` sets `isClickable = true`. A `GLSurfaceView` is not
  clickable by default, so it returns false from `ACTION_DOWN`, Android stops
  delivering the rest of the gesture, and `performClick` never fires. Do not
  hand-roll `onTouchEvent` here.

## Build

Toolchain (all user-local, nothing installed system-wide, no sudo):

| Piece | Version | Location |
| --- | --- | --- |
| JDK | 21 | system |
| Gradle | 8.11.1 | `~/.local/opt/gradle-8.11.1` (only needed to regenerate the wrapper) |
| Android SDK | platform 35, build-tools 35.0.0 | `~/Android/Sdk` |
| NDK | 27.3.13750724 (pinned in `app/build.gradle.kts`) | `~/Android/Sdk/ndk` |
| CMake | 3.22.1 | `~/Android/Sdk/cmake` |

```bash
./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug      # needs adb + a connected device
```

`local.properties` (gitignored) holds `sdk.dir`. SDK packages are managed with
the new CLI, which replaced the deprecated `sdkmanager`:

```bash
~/Android/Sdk/cmdline-tools/latest/bin/android sdk list --all "ndk*"
~/Android/Sdk/cmdline-tools/latest/bin/android sdk install --no-metrics <pkg>
```

Builds print `This version only understands SDK XML versions up to 3 but ... 4
was encountered`. It is benign — AGP 8.7 is older than the cmdline-tools that
wrote the repository metadata.

The DSP is plain C++ with no Android dependencies, so it can be tested on the
host without a device:

```bash
cd app/src/main/cpp
g++ -std=c++17 -O2 -I. your_test.cpp fft.cpp analysis.cpp -o /tmp/t && /tmp/t
```

`SpeechTap` and `SampleRing` are host-testable too — logging is the only
Android-specific part of either, and it falls back to `stderr` off device:

```bash
cd app/src/main/cpp
# the resampler, the PCM conversion and the full-pipe behaviour
g++ -std=c++17 -O2 -I. speech_tap_test.cpp speech_tap.cpp -o /tmp/t -pthread && /tmp/t
# the ring's contract, and the race its lap check exists to catch
g++ -std=c++17 -O2 -I. sample_ring_test.cpp sample_ring.cpp -o /tmp/t -pthread && /tmp/t
```

Both are worth running under ThreadSanitizer as well, which is what shows the
concurrent accesses are defined rather than merely lucky. In this environment it
needs ASLR disabled:

```bash
g++ -std=c++17 -O1 -g -fsanitize=thread -I. sample_ring_test.cpp sample_ring.cpp -o /tmp/t -pthread
setarch $(uname -m) -R /tmp/t
```

Native crashes symbolise with `ndk-stack`; debug builds are `isJniDebuggable`.

## Verification status

Verified on a Galaxy S24 Ultra (SM-S928U1, Adreno, wireless adb), most recently
on 2026-09-20:

- End to end: the microphone drives the visuals and they track the beat.
- All five shaders compile on the Adreno driver and tap-to-cycle switches
  between them.
- The rename from `earwig` is verified on device: `com.microphun` installs and
  runs, `libmicrophun.so` loads, and its LOAD segments are still 16 KB aligned.
- **The `words` visual works end to end.** Speech is recognised and lands on
  screen, placed, tinted and legible over the backdrop — whole words, confirmed
  by ear on device after the settled-token rule went in.
- **`EXTRA_AUDIO_SOURCE` is honoured, and we keep the microphone.** Over a
  session where the recogniser (Samsung's on-device SODA) returned eight final
  results, `dumpsys`-visible audio policy shows exactly one input ever opened:
  ours, `AUDIO_SOURCE_UNPROCESSED` at 48 kHz, from app start. The recogniser
  never opened one. Check it the same way after any change to the tap:

  ```bash
  adb logcat -d | grep "getInputForAttr() source"   # source 9 is ours, 6 would be the recogniser
  adb logcat -d | grep -c "speech tap: 48000"       # one line per recogniser session
  ```

- **`EXTRA_SEGMENTED_SESSION` is honoured too.** One `speech tap:` line covered
  all eight results: the session stayed open and delivered each utterance
  through `onSegmentResults`, so the restart path never ran. The restart path is
  therefore *not* covered by this run — it is still only reasoned about.
- `InputPreset::Unprocessed` is granted, not silently downgraded: the audio
  policy log shows `Source: AUDIO_SOURCE_UNPROCESSED`. The recogniser also has
  no trouble with audio that has had no AGC or noise suppression.
- Native libraries load, so Oboe capture and the JNI bridge work on device.
- The DSP is independently correct. Compiled and tested on the host: a 1 kHz
  sine peaks at bin 43 (1007 Hz), lands in band 13 of 24, reports `bass` and
  `treble` at zero, and silence produces zero across the board.

Known rough edges:

- `kaleidoscope` renders noticeably dimmer than `plasma` under identical
  audio. Its brightness floor (`0.3`) and radial vignette
  (`smoothstep(2.2, 0.15, r) + 0.08`) are both too aggressive.
- Latency has not been measured, only eyeballed as "follows the beat".
- A word the recogniser revises *after* moving past it still goes wrong. The
  device log shows `Voltage work` becoming `Voltage that works` becoming
  `Voltage works`: "that" reaches the screen and "works" never does. Only a
  word cloud that can revise what it has already drawn would fix this, which
  means `WordStream` carrying replacements rather than plain words.
- Untested on hardware in the `words` visual: the one-shot restart path (the
  device never needed it), the fallback to giving the recogniser the microphone,
  and whether the default word size and nine-second lifetime feel right in a
  long conversation. Only three words were ever on screen at once.
- The FFT ring's race is fixed and the fix is covered. `sample_ring_test.cpp`
  runs a writer that never waits against a reader taking a million snapshots:
  none of the accepted windows is ever spliced, while a control group built from
  the old non-atomic ring tears dozens to hundreds of times in the same run, so
  the test demonstrably can see the bug. Squeezing the headroom provokes ~10,000
  detected laps per run, and the whole thing is clean under ThreadSanitizer.
- The speech tap's DSP *is* verified, on the host, via `speech_tap_test.cpp`,
  including a clean run under ThreadSanitizer
  (`g++ -O1 -g -fsanitize=thread …`, which needs ASLR off: `setarch $(uname -m) -R`):
  one second of 48 kHz input comes out as 16001 samples, a 1 kHz tone keeps its
  amplitude (0.498 of 0.5) and its level (0.352 RMS of 0.354 expected), a 12 kHz
  tone that would alias to 4 kHz is rejected to 0.0005, a 44.1 kHz input
  resamples to the exactly expected count, and a pipe nobody reads never blocks
  the writer thread.
