# Changelog

All notable changes to microphun are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Real-time visualiser driven by the microphone: an FFT of what the room hears
  feeds full-screen shaders at display rate. Tap to cycle between visuals.
- Visuals `kaleidoscope`, `plasma`, `tunnel` and `spectrum`.
- `words`: speech in the room becomes a word cloud. Repeated words grow and
  pulse, and words nobody says again fade after nine seconds.
- `spiral`: phrases invented from what the room has said spill out of the
  centre along a spiral. While it is still listening, a heart throbs to each
  word.
- `fire`: an image burns in from the edges, faster on the beat, then poofs into
  the next. Rounds alternate between a frame of another visual and your own
  photos, which you pick with a long-press.
- `crack`: glass over an image, cracked by every noise. Louder noises crack it
  harder, until it shatters and fresh glass goes in.
- Speech recognition is fed from microphun's own capture on Android 13+, so the
  visuals keep moving while it listens.
- Invented phrases never repeat four words in a row of what someone said.
- Launcher icon with the microphun mascot.
- README, Makefile and this changelog.

### Fixed

- Words in the cloud appeared as their first, partial guess ("vol" for
  "voltage"); only words the recogniser has settled on are shown now.
- The `words` backdrop was so dark it did not read as a visual.
- Hesitations ("uh", "um") and stutters no longer show up in the word cloud or
  in invented phrases.
- The spiral's arm no longer stalls and runs backwards on strong beats, and
  phrases stream steadily instead of leaving the spiral empty between
  utterances.
- A rare race could splice two moments of audio into one FFT window.

[Unreleased]: https://github.com/ronstr8/microphun/commits/main
