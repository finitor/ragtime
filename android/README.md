# Ragtime for Android — development slice

Native Kotlin/Compose frontend over Ragtime's existing C++17 engines.
Stations, Conversations, Field desk and Files share one connection owner.
The agreed navigation is in [../docs/ANDROID.md](../docs/ANDROID.md).

Build 0.2.0 supports external-host broadcast and session text, addressed
acknowledgements, station discovery, explicit Call/Leave, pause, narrow
modes, reconnect, native chat history, and session and broadcast files up
to 300 KB through Android's document picker. Connect starts a foreground
service, so the connection outlives switching apps. Pictures show inline
and are sent from the + beside the entry line; voice messages arrive as
plain files; rig control, reactions and recent-contact prediction are
not implemented. Field desk shows actual host
mode/channel reports and the gearshift, which picks the mode broadcasts
go in where the modem gives thresholds (Data2G 0.5.1 on); dial frequency
needs a separate rig adapter.
The Hello capability mask says text, status, seals, pings and files, not
reactions, and automatic Hand answers are off until this client supports
the full record set.

To try it with no modem at all, turn on **Demo mode** in Settings:
simulated stations on a net inside the app, behind an amber bar that says
so ([how it works](../docs/ANDROID.md#demo-mode)).

## Build

Pinned development toolchain: JDK 17, Gradle 8.9, AGP 8.7.2, Kotlin 2.0.0,
Compose BOM 2024.10.01, SDK/target 36, NDK 28.2.13676358, CMake 3.22.1.
The provisional minimum is Android 8/API 26; this APK contains ARM64 only.
These pins use the available local toolchain and are not a Play release
or a final OS support policy.

Tests explicitly pin Espresso 3.7.0 because the older Compose test library
otherwise brings in Espresso 3.5.0, whose reflective input lookup fails on
API 37. The fix is documented in the
[AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0).

Install the SDK components and accept their licences through Android's
SDK manager. Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your SDK
(or create an ignored `local.properties` with `sdk.dir=...`). Then:

```sh
cd android
./gradlew :app:assembleDebug :app:lintDebug
```

The APK is `app/build/outputs/apk/debug/ragtime-android-<version>-b<build>-debug.apk`,
named from `versionName` and `versionCode` in `app/build.gradle.kts`; raise
the build number with every APK handed to someone. Open `android/`
in Android Studio to run it. Configure your separately running
`data2g-host` address, callsign and KISS/command ports; session data uses
command + 1. The app does not bundle, launch, or replace the modem.

The Gradle project builds the existing core with PIC and desktop/audio
dependencies disabled. For pictures it clones OpenJPEG 2.5.3 from GitHub
the first time it configures (`cmake/FetchOpenJpeg.cmake`) and builds it
into `libragtime_jni.so`, so that build needs the network once. JNI uses byte arrays for standard UTF-8, opaque
registered handles, and exception boundaries. All engine calls and three
nonblocking TCP streams belong to one worker. Partial writes remain queued;
KISS/command failure reconnects both with 2/4/8/15/30-second backoff.
The session data stream retries independently every five seconds.

## Runtime policy

Settings (the gear) has the callsign and locator first, then the host and
ports with Connect and Disconnect right under them: Connect checks and
saves those fields and connects to that modem, Disconnect ends the
connection. The host field has a
**localhost** button that fills in 127.0.0.1 and ports 8100 and 8300 for a
Data2G TNC on the same phone. Not connected, the tip on Stations opens the
sheet. Under the callsign it says that the callsign and locator go out
with everything sent, with Privacy beside it.

About has **Privacy** and **Open source licences**. The privacy text is
`docs/ANDROID_PRIVACY.md` and Ragtime's licence is the repository's
`LICENSE`, both copied into the APK when it is built, so the app cannot
drift from them; the Apache License is the asset
`app/src/main/assets/licenses/apache-2.0.txt`. Which notices are owed,
and why, is in [docs/ANDROID_LICENSES.md](../docs/ANDROID_LICENSES.md).

Connect is explicit, and it starts `RadioService`, a foreground service
(special use) with an ongoing notification that opens Ragtime or
disconnects. The connection, the engines and any ragchew belong to that
service's process-wide model, not to the activity: rotating, switching
apps or closing the task's window does not disconnect. Disconnect, the
notification's action, a failed connection or the process dying ends it.
Disconnect and a settings change first leave any ragchew at the modem
(Leave now, sent while the command port is still open), as the desktop
does on quit: closing the sockets alone would leave the modem's ARQ link
up and the next call refused. A process killed outright cannot do that;
a relaunched process requires Connect again and never calls the partner
back by itself. A partial wake lock is held while connected. Android 13
and later ask for the notification permission after the first Connect;
refused, the service still runs. Settings and core chat logs are
app-private; UI drafts and the selected view survive activity recreation
through saved state.

While Ragtime is out of sight, notifications on the channel "Calls and
messages" say that a station called, wrote to the operator with `@`,
spoke in the ragchew or offers a file; a tap lands there, and returning
to the app clears them. "Notify me of calls and messages" in Settings
turns them off and on. The activity is `singleTop`, so a tap never
opens a second Ragtime.

Broadcast numbering is saved before output is handed to a socket. Data is
separated by callsign. A settings change rebuilds the connection; switching
views does not. Broadcast and session drafts remain separate.

## Files

A picked document is copied into the station's own folder
(`files/stations/<call>/outgoing/`), at most 300 KB, and only a file
under that folder is ever sent. Received files go to `received/` beside
it, broadcast parts to `broadcast-parts/`, and the Files rows and kept
parts to `files.log` in the desktop's format. Save a copy writes a
finished file wherever Android's picker says. Session offers wait for
Receive or Decline; a broadcast file waits for Receive, then asks for
the pieces it missed. Forget removes a row, not the file.

## Fake-host tests

Never point the interoperability tests at a real radio. Start the existing
`ragtime_fake_air` bench with both ports free, for example from a dependency-
minimal desktop build:

```sh
./build-portability/tests/ragtime_fake_air --bots 0 --airtime 0
```

Then, with a device or an ARM64 API 35 emulator attached:

```sh
./tools/android-smoke.sh emulator-5554
./tools/android-smoke.sh PHONE_SERIAL 10000
```

The script builds and installs both debug APKs on **only the named device**,
forwards the six loopback fake-host ports, runs the tests, removes those
forwards and puts the app's saved Settings settings back. The second
argument moves the bench's ports on the device (18100, 18300 and so on):
use it on a phone whose own Data2G TNC holds 8100/8300, so no test meets
the real modem. It grants the notification permission, which Connect
would otherwise ask for over the screen the UI tests drive. A new
emulator needs 4 GB (`hw.ramSize`) and a few idle minutes after its first
boot, or Android kills the app for starting too slowly.

Native interop tests exercise UTF-8/emoji broadcasts, acknowledgements,
explicit sessions and delivered text, a session file offered, received and
compared byte for byte, Leave, saved history, and reconnect. Compose tests
exercise the shared conversation draft, separate Broadcast/ragchew drafts,
activity recreation, the active ragchew's Stations → Conversations → Back
flow, and the ragchew kept by the connection service while the activity
is stopped.

The dependency-free `ragtime_android_client_test` also runs under desktop
CTest. It checks fragmented command input, the advertised Hello masks,
UTF-8 JSON escaping, session opening and durable broadcast numbering.
`ragtime_android_files_test` runs two adapters on in-process fake hosts
through session and broadcast file transfers, with no device, and
`ragtime_android_demo_test` runs demo mode's whole net with an adapter on
it. `./gradlew :app:testDebugUnitTest` runs the demo guide's steps on the
JVM, and `AlertTrackerTest` what is worth a notification. `DemoModeTest`,
`AboutTest` (the notices) and `AlertsTest` (a notification while out of
sight) run on the device with the others and need no bench.

Verified on 2026-11-21 with 0.2.0 build 3: both desktop adapter tests
pass with the eight HostLink, Broadcast, Session and file CTest targets
beside them; debug APK assembly and Android lint pass (0 errors, 11
warnings for the provisional toolchain/ABI choices); and the tethered S26 (SM-S948U1, Android 17/API 37) passes the
five fake-host tests, demo mode's, the notices' and the alerts' in 121 s (the broadcast file waits
about 45 s for its seal and repair), over USB on offset ports with its
own TNC left running. The system document picker, the
notification's actions, screen lock, process death and performance are
not yet checked on the phone. Results over the real modems are in
[docs/ANDROID.md](../docs/ANDROID.md#010-over-real-modems-2026-11-20).

## No-radio real-modem pilot

From the repository root, start two existing Data2G release hosts with
crossed audio FIFOs and an isolated desktop Ragtime profile:

```sh
python3 tools/bench/android_air_bench.py start \
  --host /path/to/data2g-host --bind YOUR_MAC_LAN_IPV4
python3 tools/bench/android_air_bench.py status
```

The desktop window is W1BBB at localhost, KISS 18110, command 18310.
On the phone, use W1AAA, locator CN87, the Mac's LAN address, KISS 18100
and command 18300. Session data uses command + 1. Keep Ragtime foreground;
normal process launch requires an explicit Connect. Send `@W1BBB hello`
in Broadcast and wait for Acknowledged, or call W1BBB for a ragchew.
The bench uses real modem waveforms and no sound devices, rig or RF.
Both modems currently run on the Mac; Android Data2G is a later stage.

The helper keeps only its own three child processes alive and retains
the isolated profile, host logs, binary hash, chat history and waveform
recordings beneath ignored `build-android-air-bench/run-*` directories.
Closing this desktop window stops the bench. Alternatively:

```sh
python3 tools/bench/android_air_bench.py stop
```

The opt-in instrumentation check sends a unique addressed UTF-8 message
from the phone UI and requires the desktop's real-modem ACK. Build/install
the test APK, then run on the explicitly selected device, without ADB reverse:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class org.ragtime.radio.RealModemPilotTest \
  -e pilotHost YOUR_MAC_LAN_IPV4 -e pilotMarker UNIQUE_TOKEN \
  org.ragtime.radio.test/androidx.test.runner.AndroidJUnitRunner
adb -s DEVICE_SERIAL exec-out run-as org.ragtime.radio \
  cat files/real-modem-pilot.json
```

`RealModemFileTest` takes the same arguments (and `pilotBytes`, 1200 by
default): it calls the peer, sends a session file and waits for Sent,
which the far end gives only after checking the digest, leaves, then
broadcasts a second file. Start the bench with `--auto-files PHONE_CALL`
so the desktop saves both with no click, and compare the copies in the
run's `received/` with the SHA-256s in `files/real-modem-file.json`. It
takes about four minutes, or longer with `-e pilotSettle SECONDS`, the
time it stays on the air after the broadcast file has gone, for its seal
and any repairs (90 by default; a file that was talked over needs
minutes). The bench's desktop is put on gearshift Auto.

An APK handed to anyone is the debug build as tested, copied to
`dist/android/` with its SHA-256 and the release notes; `dist/` is not
in the repository.

The real-modem tests skip unless `pilotHost` is explicitly supplied. Its
deadline allows actual waveform transmission; keep the phone app open
throughout. The success report includes the marker, peer and ACK time.

On 2026-11-20 this passed on the S26 over Wi-Fi with two pinned Data2G
0.5.3 macOS hosts and desktop Ragtime: the addressed Unicode message
arrived intact and its real-modem acknowledgement returned in about nine
seconds. See the [pilot result](../docs/ANDROID.md#first-stage-result-2026-11-20)
for evidence and remaining acceptance steps.

### Mac-to-remote audio trial

The same helper can replace its second local modem with a remote raw-audio
TCP endpoint. Both Ragtime and Data2G then run on this Mac. For example:

```sh
python3 tools/bench/android_air_bench.py start \
  --output build-remote-audio-bench \
  --host /path/to/data2g-host --bind 127.0.0.1 \
  --call MYCALL --locator '' --kiss-port 18120 --command-port 18320 \
  --remote-audio REMOTE_TAILSCALE_IP:9977
python3 tools/bench/android_air_bench.py status --output build-remote-audio-bench
python3 tools/bench/android_air_bench.py stop --output build-remote-audio-bench
```

This uses an isolated desktop profile and `tcp_audio_bridge.py`, a
dependency-free relay equivalent to socat between two FIFOs and one TCP
socket. The modem reads `b-to-a` and writes `a-to-b`; the relay reads
`a-to-b` and writes `b-to-a`. The remote listener must supply and accept
unframed 8 kHz mono float32 audio, including continuous silence. There is
no sample conversion, impairment model, jitter correction or clock-drift
compensation. Each user-space queue is capped at 32768 bytes; backpressure
stalls instead of discarding bytes. This is an initial clean-channel trial.

`audio-bridge.json` records byte counts and queue high-water marks in both
directions. A closed remote socket stops the bench with an error; it is
not silently replaced with silence or retried. Restart both endpoints as
needed. Continuous byte flow proves transport only: require decoded text
and an ACK before claiming modem delivery. The relay's local correctness
check covers fragmented binary streams in both directions and remote EOF:

```sh
python3 tools/bench/test_tcp_audio_bridge.py -v
```

### Data2G and Ragtime together on the S26

The development TNC APK is
`build-data2g-android-arm64/data2g-tnc-network-audio.apk` from the repository
root. Its Data2G 0.5.3 changes live on branch `codex/android-network-audio`
in `build-data2g-android-source`; the portable source patch is
[`tools/bench/data2g-android-network-audio.patch`](../tools/bench/data2g-android-network-audio.patch),
against commit `ede34e7c15627b09a55c3f6bbae71511cfbb6882`.
It adds test audio without changing the modem core or radio waveform.

Saved phone configuration:

| Application | Setting | Value |
|---|---|---|
| Data2G TNC | Callsign | MYCALL-1 |
| Data2G TNC | Network audio test | Enabled |
| Data2G TNC | Audio peer | the Mac's LAN address, TCP 19977 |
| Ragtime | Callsign / locator | MYCALL-1 / MYGRID |
| Ragtime | Host | 127.0.0.1 |
| Ragtime | KISS / command / data | 8100 / 8300 / 8301 |

When the peer listener is ready, open Data2G TNC and tap Start. Confirm
**TCP audio connected** with RX/TX counts increasing, then open Ragtime
and use Settings → Connect. Leave Data2G's task in Recents: it runs
behind Ragtime; swiping it away stops the modem. A failed audio connection
stops the modem and requires an explicit Start after the listener is fixed.
The network mode does not request microphone permission or use device audio.

On 2026-11-20 this APK passed the S26's opt-in real-modem check with an
isolated Mac TESTMAC peer over LAN TCP audio. Both applications ran on the
phone; the Unicode message was decoded on the Mac and its ACK returned in
9.377 seconds. Its foreground service remained active while Ragtime was
foreground, and microphone permission remained denied. Test command:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class org.ragtime.radio.RealModemPilotTest \
  -e pilotHost 127.0.0.1 -e pilotCall MYCALL-1 -e pilotLocator MYGRID \
  -e pilotPeer TESTMAC -e pilotKiss 8100 -e pilotCommand 8300 \
  -e pilotMarker UNIQUE_TOKEN \
  org.ragtime.radio.test/androidx.test.runner.AndroidJUnitRunner
```

This requires Data2G already connected to the temporary TESTMAC audio peer.
The remote trial returned **Connection refused** after its remote listener
closed. The active manual bench now connects phone MYCALL-1 to Mac MYCALL
over local Wi-Fi audio, with both real modems running on their respective
devices. Start the Mac side from the repository root with:

```sh
python3 tools/bench/android_air_bench.py start \
  --output build-phone-local-bench \
  --host /path/to/data2g-host --bind 127.0.0.1 \
  --call MYCALL --locator '' --kiss-port 18120 --command-port 18320 \
  --listen-audio MAC_LAN_IPV4:19977
```

Then start the phone TNC with the saved LAN endpoint and connect Ragtime
to 127.0.0.1. The MYCALL-1 → MYCALL addressed Unicode/ACK check passed on
2026-11-20. The Mac profile accepts ragchew calls. Keep its Ragtime window
open and leave the phone TNC running behind Android Ragtime. Stopping
either audio endpoint requires restarting the bench and phone TNC.
The remote endpoint (a collaborator's host over Tailscale, TCP 9977) remains for a future trial.
