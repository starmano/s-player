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
- **Audio & subtitles panel** (button in the top bar), Netflix style: pick the audio track and
  subtitle track (or turn subtitles off), and choose the subtitle size and style (outlined text or
  a dark box), which are remembered. Audio tracks show their format (Dolby Digital, DTS, ...),
  and tracks the phone can't decode are listed as not supported instead of silently missing.
- Full-screen landscape playback with Media3 (ExoPlayer) controls, including playback speed,
  plus headset button support through a media session.

## Using it with Stremio

1. Install S Player (see below).
2. In Stremio, open **Settings → Player** and choose **S Player** as the external player. If
   Stremio asks which app to open a stream with, pick S Player.
3. Play anything. Press or swipe home and the video floats in a PiP window.

You can also open the S Player app and paste any direct stream URL to try it out.

## Getting the APK

Every push runs the **Android** GitHub Actions workflow, which builds, unit-tests and lints the
app. Pushes to the default branch also publish a GitHub release with the APK attached as
`s-player.apk`. The newest one is always at
https://github.com/starmano/s-player/releases/latest/download/s-player.apk, and
https://s-player.bmk.si links to it.

## Updates

S Player updates itself. Its launcher screen checks https://s-player.bmk.si/version.json for a
newer build, then downloads it and opens Android's installer when you tap **Install update**.
The first time, Android asks you to allow S Player to install apps. When a video starts, the
player checks once a day and mentions an available update.

Every CI build is numbered after its workflow run. That number is both the app's `versionCode`
and the release tag (`build-N`) that the site reports.

### Signing key (one-time setup)

Android only installs an update over an app signed with the same key, so release builds are
signed with a permanent key instead of a throwaway debug key:

1. On GitHub, open **Settings → Secrets and variables → Actions → New repository secret**. Add
   `SIGNING_PASSWORD` with a long password, and keep a copy of it, e.g. in a password manager:
   without it no future build can update the app.
2. Under **Actions → Create signing key**, click **Run workflow**. It creates the key and commits
   it as `app/signing.p12`, encrypted with that password, which is useless without it.
3. Under **Actions → Android**, click **Run workflow** to publish a build signed with the new key.
   Uninstall the old S Player and install this build from the site once. From then on, updates
   install over the top.

Without the secret or the key file (local builds, forks), release builds are signed with the
debug key.

## Download site

The page at https://s-player.bmk.si lives in `website/`. `app.js` serves it as a small Node.js
app with no dependencies, the same way S-library is hosted. Its download button streams the APK
from the newest release, so phones download it from s-player.bmk.si without the server keeping a
copy. If GitHub can't be reached, the button redirects there instead.

### Hosting on cPanel

1. **Domain:** under **Domains**, create `s-player.bmk.si`. If `bmk.si`'s DNS is managed
   somewhere else, add an `A` record there pointing to your cPanel server's IP.
2. **HTTPS:** under **Security → SSL/TLS Status**, select the domain and click **Run AutoSSL**.
3. **Code:** in **Files → Git™ Version Control**, click **Create**, keep **Clone a Repository**
   on, and set **Clone URL** to `https://github.com/starmano/s-player.git` and **Repository
   Path** to `s-player-app`. The folder must be empty or not exist yet.
4. **App:** in **Software → Setup Node.js App**, click **Create Application** and set:
   - Node.js version: 18 or newer (the newest available is best)
   - Application mode: Production
   - Application root: `s-player-app`
   - Application URL: `s-player.bmk.si`
   - Application startup file: `app.js`

   Click **Create**. There is nothing to `npm install`, because the site has no dependencies.
5. **Updates:** under **Advanced → Cron Jobs**, add a job that runs every 15 minutes
   (`*/15 * * * *`) with this command. It pulls new commits and restarts the app only when
   something changed:

   ```sh
   cd ~/s-player-app && OLD=$(git rev-parse HEAD) && git pull -q --ff-only && [ "$(git rev-parse HEAD)" != "$OLD" ] && { mkdir -p tmp; touch tmp/restart.txt; cloudlinux-selector restart --json --interpreter nodejs --app-root s-player-app >/dev/null 2>&1 || pkill -u "$(id -un)" -f "lsnode|NodeApp|node app\.js"; }
   ```

6. **Check:** https://s-player.bmk.si shows the page, and **Download for Android** downloads the
   newest APK.

New app builds need no cPanel step at all: the button always points at the latest release.

Run the site's tests with `npm test`.

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
| Audio & subtitles panel: track lists, subtitle size and style | `TracksPanel.kt`, `TrackLabels.kt` |
| Aspect-ratio clamping and video-bounds maths | `VideoGeometry.kt` |
| Launcher screen with setup steps and a URL box | `MainActivity.kt` |
| Download site and its tiny Node.js server | `website/`, `app.js` |

The player activity runs in its own task (`singleTask`, separate `taskAffinity`), so it can float
above Stremio or any other app. It declares `supportsPictureInPicture` and handles configuration
changes itself, so entering and leaving PiP never recreates it.

## Limitations

- **Android only.** On iOS, Stremio only hands off to a fixed list of players, so a third-party
  player can't plug in the same way.
- Audio formats the phone can't decode itself (Dolby Digital, Dolby Digital Plus, DTS, TrueHD,
  ...) are decoded with the bundled FFmpeg decoder, Jellyfin's prebuilt
  `org.jellyfin.media3:media3-ffmpeg-decoder`. It is GPL-3.0 licensed, which also applies to the
  distributed APK.
- Watch progress isn't reported back to Stremio.
