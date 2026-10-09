# Changelog

Every release bumps `versionName` (semver) and `versionCode` in `app/build.gradle.kts`.
Each version is published as GitHub release `v<version>` with `ScrollReader-v<version>.apk`.

## v1.2.0 (2026-10-09)

### Added
- Audio diagnostics screen (setup screen → Troubleshooting → Audio diagnostics):
  - Five voice tests, one per audio channel: media (current), media with audio focus,
    accessibility, navigation voice, and phone-call channel over Bluetooth call audio.
  - Each test logs the full audio state and the exact device the voice played on.
  - Share, copy and clear the log.
- Audio logging while reading: audio state at start, the device each spoken block plays on,
  player changes, Bluetooth/output connects and disconnects, and voice errors.

### Unchanged
- How reading sounds. This version only observes and logs; no audio behaviour changed.

## v1.1.1 (2026-10-08)

### Changed
- New app icon: text lines with a sound wave, on a dark navy background.

## v1.1.0 (2026-10-07)

### Added
- Control bar while reading: Back, Pause/Resume, Skip, Slower (−), Faster (+), Stop.
- Pause resumes at the word where it stopped, not from the start of the block.
- Back restarts the current block; pressed near its start, it goes to the previous block.
- Skip jumps to the next block and scrolls for more when needed. You decide what to skip; nothing is auto-filtered.
- Live speed control from 0.5× to 3× in 0.25 steps, applied straight away and remembered.
- Highlight box around the text being read.
- Start where you want: long-press ▶ and tap the line to start from. A plain tap starts from the top of what's on screen now (it never scrolls up).
- App version shown on the setup screen.

### Changed
- The setup speed slider now covers 0.5×–3×, matching the bar buttons.

## v1.0.0 (2026-10-07)

First release (published as `build-1`).
- Reads the current app aloud, then scrolls down and keeps reading until the end of the page.
- Skips the status bar and navigation bar.
- Floating ▶/■ button and "Read screen" Quick Settings tile.
