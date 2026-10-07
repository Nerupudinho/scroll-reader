# Scroll Reader

An Android accessibility app that reads the app you're in out loud, scrolls down, reads what's new, and repeats until the end of the page.

- **Skips the status bar.** The time, battery, signal and notification icons live in a separate system window. Scroll Reader only reads the app's own window.
- **Actually scrolls.** It uses the app's own "scroll down" action when the app provides one. Otherwise it does a real swipe gesture. Horizontal carousels and tab pagers are ignored, so it won't flip you to another tab.
- **Doesn't repeat itself.** Text already read on the last two screens is skipped. That covers the overlap between scrolls and fixed headers or tab bars.
- **Knows when to stop.** It stops when two scrolls in a row bring nothing new ("End of page"), when you switch apps, or after 80 screens on endless feeds.

## Start and stop

- **Floating button** (▶ / ■). It sits on top of every app once the service is on. Tap to start or stop, drag to move.
- **Quick Settings tile** "Read screen". Pull down the shade and tap it. The shade closes and reading starts.

The home-screen icon opens the setup screen. A home-screen icon can't start reading because tapping it takes you to the home screen, which is the wrong thing to read.

## Build

**Option A: GitHub (no Android Studio needed).** Push this folder to a GitHub repo. The included workflow (`.github/workflows/build-apk.yml`) builds the APK on every push. Open the repo's **Actions** tab, open the latest run, and download **ScrollReader-apk**.

**Option B: Android Studio.** Open the folder, wait for Gradle sync, then run *Build → Build APK(s)*.

## Install on the phone

1. Copy the `.apk` to the phone and open it. Allow installing from that source when Android asks.
2. Open **Scroll Reader** and tap **Turn on Scroll Reader**. Find it in the list and switch it on.
3. **If the switch is greyed out** (Android 13+ "restricted setting" for apps installed outside the Play Store), open App info → ⋮ → **Allow restricted settings**, then switch it on again.

## Tuning

These constants are at the top of `ReaderService.kt`:

| Constant | Default | Meaning |
|---|---|---|
| `SETTLE_MS` | 900 | Wait after each scroll before reading |
| `STALE_LIMIT` | 2 | Scrolls with nothing new before "End of page" |
| `MAX_SCREENS` | 80 | Safety stop for infinite feeds |

The swipe distance is set in `swipeUp()`, from 72% to 30% of the screen height.
