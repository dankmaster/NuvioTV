# Nuvio TV debug CLI

This fork adds a debug-only Activity using the project's ExoPlayer engine,
`PlayerMediaSourceFactory`, image loader and `ContentCard`. Its separate package
is `com.dankmaster.nuvio.tv.cli`, signed with the local Android debug key. It does
not replace the installed Nuvio TV app. Release builds contain neither the Activity
nor the command server. The diagnostic Activity runs in its own process, where
account sync and telemetry startup are skipped.

Requirements: PowerShell 7, JDK 17 or newer, Android SDK 36 and an ADB-connected
Android TV device or emulator. Build the full debug variant:

```powershell
./gradlew.bat :app:assembleFullDebug
./scripts/debug-tv.ps1 -Action start -Serial emulator-5554 -Apk ./app/build/outputs/apk/full/debug/app-full-universal-debug.apk
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command status
```

The serial is required to avoid operating the wrong device. The script forwards
host localhost port 8798 to the app's random localhost port and retrieves its
private session token with `run-as`. Use `-Port` and `-SessionDirectory` for
independent devices. The API is not bound to the LAN. Do not network-forward it.

Use a private request file for media URLs and optional headers:

```json
{"url":"http://10.0.2.2:8799/test-video.mp4","headers":{}}
```

```powershell
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command load -RequestFile ./media-request.json
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command pause
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command seek -PositionMs 10000
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command speed -Speed 1.5
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command play
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command audio -Index 0
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command subtitle -Index -1
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command mute -Muted $false
```

The default is muted. `status` returns real playback/buffering/end state,
position, duration, dimensions, first-frame status, error codes and supported
audio/subtitle tracks. Commands are asynchronous: inspect status to verify their
effect. `stop` clears media and `shutdown` closes the Activity and removes the
host ADB forwarding. No remote watch history or scrobbling is written by this harness.

`history` accepts a private JSON file with up to 100 `entries` containing
`contentId`, `contentType`, nullable `season`/`episode` and `watchedAtMs`.
Supplying entries replaces only this debug package's local watched preferences,
preserving supplied dates. Up to 10 `queries` use the same identity fields without
the date. The response contains real `WatchedItemsPreferences.isWatched` matches
and loaded timestamps. This replays client matching, not cloud delivery or
series completion. `preview` may use a `lookup` query instead of the explicit
`watched` flag to render the badge from that real matcher.
It uses the actual engine and media factory, but does not exercise every setting,
automatic source choice or lifecycle path of the ordinary player screen.

Poster fixture:

```json
{"url":"http://10.0.2.2:8799/test-poster.png","shape":"poster","width":320,"watched":true}
```

```powershell
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command preview -RequestFile ./poster-request.json
./scripts/debug-tv.ps1 -Action capture -Serial emulator-5554 -Output ./preview.png
./scripts/debug-tv.ps1 -Serial emulator-5554 -Command shutdown
```

Shapes are `poster`, `landscape` and `square`; card sizing follows the client's
real component. Network images are asynchronous. Capture copies the player's
SurfaceView during playback, exporting the decoded video without subtitle or
control overlays. In preview mode it copies the Activity's own window. It does
not use computer control (capture requires Android API 26 or newer).
`watched` is an explicit fixture input; a visible fixture badge does not
verify imported account history. Real TV codec/HDR support must be checked on
that TV; emulator success does not establish device playback acceptance.

For background tests, start a dedicated emulator using Android's documented
`-no-window -no-audio` options. Software emulation can be too slow or fail without
an emulator hypervisor. This fork does not change Windows virtualization settings.

Protocol: one bounded newline-delimited JSON command per localhost TCP connection,
with a random token from the app-private session file. Session and request files
are private, untracked artifacts. The CLI returns no media URLs, headers or tokens
in its normal status output.
