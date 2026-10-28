# S Player

An Android video player for [Stremio](https://www.stremio.com/) with Netflix/YouTube-style
**picture-in-picture**. Stremio hands a stream to S Player as an external player. When you leave
the app, the video keeps playing in a floating window you can move around, resize, control and
expand back to full screen.

## Features

- **Automatic PiP.** Going home (button or gesture) while a video plays shrinks it into a
  floating window. Android 12+ handles this through auto-enter, which gives the smooth system
  animation. Android 8–11 enter PiP when you leave the app.
- **PiP button** in the player's top bar to float the video on demand.
- **Controls in the window**: back 10 s, play/pause and forward 10 s. The window keeps the video's
  aspect ratio and animates from the video's position on screen.
- **Netflix/YouTube behaviour**: expanding the window returns to full screen. Closing it stops
  playback. Opening another stream from Stremio replaces the current one instead of stacking a
  second player.
- **Built for Stremio streams**: HTTP(S), HLS and DASH, plus MKV/MP4/WebM files from Stremio's
  local torrent server (with generous timeouts for slow-starting torrents). Also custom HTTP
  headers and side-loaded subtitles via the common MX Player / VLC intent extras.
- Full-screen landscape playback with Media3 (ExoPlayer) controls, including audio, subtitle and
  speed selection, plus headset button support through a media session.

## Using it with Stremio

1. Install S Player (see below).
2. In Stremio, open **Settings → Player** and choose **S Player** as the external player. If
   Stremio asks which app to open a stream with, pick S Player.
3. Play anything. Press or swipe home and the video floats in a PiP window.

You can also open the S Player app and paste any direct stream URL to try it out.

## Getting the APK

Every push runs the **Android** GitHub Actions workflow, which builds, unit-tests and lints the
app and uploads a debug APK as the `s-player-debug` artifact. Download it from the workflow run,
then install it on the phone (allow installs from unknown sources).

CI builds are signed with a throwaway debug key. To update from one CI build to another you may
need to uninstall the old one first.

## Building locally

Requirements: JDK 17+ and the Android SDK (API 35). Android Studio provides both.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew installDebug         # install on a connected device
```

## How it works

| Piece | Where |
| --- | --- |
| Intent filter that makes S Player show up as an external video player | `app/src/main/AndroidManifest.xml` |
| Parsing the stream URL, title, start position, headers and subtitles from the launching intent | `PlaybackRequest.kt` |
| Player, PiP enter/exit, PiP params (aspect ratio, source rect, auto-enter, actions) | `PlayerActivity.kt` |
| Aspect-ratio clamping and video-bounds maths | `VideoGeometry.kt` |
| Launcher screen with setup steps and a URL box | `MainActivity.kt` |

The player activity runs in its own task (`singleTask`, separate `taskAffinity`), so it can float
above Stremio or any other app. It declares `supportsPictureInPicture` and handles configuration
changes itself, so entering and leaving PiP never recreates it.

## Limitations

- **Android only.** On iOS, Stremio only hands off to a fixed list of players, so a third-party
  player can't plug in the same way.
- Audio codecs are whatever the phone supports. Many phones lack DTS/TrueHD decoders, so those
  tracks need Media3's FFmpeg extension, which isn't bundled.
- Watch progress isn't reported back to Stremio.
