# Android port

Started 2026-11-20. The shared C++ engines are the reuse boundary; the
phone UI is a separate frontend.

## Agreed UI direction

The phone has four primary destinations:

- **Stations:** stations heard on the net, recent contacts, and entry
  points for calling a station or returning to the current ragchew.
- **Conversations:** Broadcast and the active one-to-one ragchew, with
  message history, a composer, attachments, and session Leave controls.
- **Field desk:** the same selected conversation with the reported dial,
  channel status, gear, and bandwidth always visible in a denser layout.
- **Files:** active transfers, repairs, kept parts, and completed files.

Stations, Conversations, and Field desk are complementary views in one
app. They share one host connection, one active ragchew, the same message
history and transfer state, and a draft per conversation. Switching views
does not connect, call, leave, or transmit.

The entire **Ragchewing with CALL** cell in Stations opens that session
in Conversations. Back returns to Stations without leaving the session.
Conversations and Field desk preserve the selected Broadcast/session
thread and its draft when switching between them. Broadcast and session
drafts remain separate so text cannot change its destination by switching
threads. Calling another station requires ending the current ragchew.

**Demo mode** (Tim, 2026-11-20) is a switch in Settings for trying
Ragtime with no modem: a coloured bar across the very top says so, the
stations are simulated, and a guide's bubbles suggest what to type next.

Radio and host controls are accessible from every destination. Field desk
keeps operational details visible; the other views keep more space for
stations and messages. File cards in a conversation link to Files and
the same transfer's details.

## Current implementation status

The Kotlin/Compose app and JNI bridge are in `android/` and
`platform/android/`; this is Android build 0.1.0 (build 2). The ARM64
debug APK builds against SDK 35, with API 26 as the provisional minimum.
It uses the existing Broadcast and Session engines, logs, station model
and seals. Build and test instructions and runtime limits are in
[android/README.md](../android/README.md).

All four destinations work. Files (2026-11-20) sends and receives session
files (offer, Receive, Decline, Cancel, progress, kept parts, Send rest)
and broadcast files (digest check, repairs asked for), through Android's
document picker in and out, up to 300 KB, with the history kept in
app-private storage. A finished session file has its line in the ragchew.
The Hello now says the phone takes session and broadcast files; it still
leaves out reactions, pictures and voice, which arrive as plain files.

Connect starts a foreground service with an ongoing notification (open,
Disconnect), so the connection and a ragchew outlive switching apps. A
red TX badge in the top bar follows data2g-host's PTT reports in every
view. Settings shows the version and build, with About and release
notes; About has Privacy and Open source licences, the notices the
licences and Google Play ask to be in the app
([the audit](ANDROID_LICENSES.md), [the privacy text](ANDROID_PRIVACY.md)). Field desk shows the real mode, bandwidth and channel reports.
Dial and rig integration, voice, inline pictures, reactions, automatic
Hand answers and recent-contact prediction are not implemented.

**Alerts** (2026-11-21). While no Ragtime window is in sight, a
notification on its own channel, "Calls and messages", tells the
operator that a station has called (a ragchew is answered by the modem,
so this is the first they hear of it), has written to them with `@` on
D2G, has said something in their ragchew, or offers them a file. A tap
lands on that ragchew, on Broadcast or on Files, and coming back to
Ragtime takes the alerts down. `AlertTracker` (`Alerts.kt`) finds them
by comparing each snapshot with what it has already seen: what was there
when the connection started, and what a new ragchew reads back from its
log (the snapshot marks those lines `past`), alerts nobody. One
notification a station and kind, its newest line replacing the last. In
demo mode the title says "Demo:". Settings has the switch, "Notify me of
calls and messages", on by default and remembered; when Android itself
is blocking Ragtime's notifications the switch says so and offers
Android's settings. There is no way yet to refuse calls. Checked by `AlertTrackerTest` (JVM) and `AlertsTest` on the S26,
on demo mode's net.

What has been checked, and where:

- Desktop CTest, no device: `ragtime_android_client_test` (fragmented
  command input, the Hello masks, UTF-8 JSON, durable numbering) and
  `ragtime_android_files_test` (two adapters on fake hosts: a session
  file offered, received and checked byte for byte, one declined, a
  broadcast file received and checked, rows kept across a restart and
  read back once, paths outside the station's folder refused).
- The tethered S26 (SM-S948U1, Android 17/API 37), 0.1.0 build 2, against
  the loopback fake-host bench over USB (2026-11-21, 121 s with demo
  mode's, the notices' and the alerts' tests, 8 passed, the two
  real-modem tests skipped; the bench's tests run twice on one bench with no restart):
  the packaged-JNI interop test (UTF-8 and emoji broadcast, addressed
  acknowledgements, session text, a session file saved byte for byte
  with its line in the ragchew, Leave, a broadcast file taken with
  Receive and whole by its digest, a path outside the station's folder
  refused, saved history, reconnect); the document test (import into the
  station's folder with its name kept, the 300 KB limit, empty files,
  export, nothing outside the app's files exported); and the Compose
  tests (all four destinations, shared and separate drafts, activity
  recreation, a picked document broadcast and shown in Files and
  Broadcast, Stations → ragchew → Back, the ragchew kept by the
  connection service while the activity is stopped, and Disconnect
  ending the ragchew at the far end). The phone's own Data2G TNC stayed
  up on 8100/8300 throughout: the bench was forwarded on ports 10000
  higher (`tools/android-smoke.sh SERIAL 10000`), and the saved MYCALL-1
  settings were put back after.
- The same phone and build over real modems, see
  [0.1.0 over real modems](#010-over-real-modems-2026-11-20): the
  addressed Unicode message and its ACK, a session file and a broadcast
  file with a repair round.
- The chat-only build earlier passed its tests on an ARM64/API 35
  emulator. 0.1.0 has not run on an emulator: a new google_apis AVD at
  the default 1.5 GB killed the app for slow startup while its first
  boot settled (no app exception), and the phone was used instead. Give
  an AVD 4 GB and let it go idle first.

Android lint: 0 errors, 11 warnings (the provisional SDK and dependency
pins, ARM64-only packaging). The launcher icon is the desktop's leaf
going digital on the cover's cream, as an adaptive icon with a themed
(monochrome) layer; `tools/icon/make_android_icon.py` makes its layers
and the notifications' small leaf from `app/res/ragtime.svg`. A launcher
cuts icons to its own shape, so the desktop's rounded square and red rim
are not in it. The UI test
dependency pins Espresso 3.7.0 for API 37.

Still to do: the system document picker and Save a copy by hand on the
phone (the tests call what the picker's result calls, not the picker),
the notification's own Disconnect action, screen-off and process-death
behaviour of the service, kept parts and Send rest after a dropped link,
a file from the desktop to the phone over the real modems, and larger
files.

### Demo mode

Demo mode runs the real engines against a net inside the app, so nothing
on the screen is faked: `platform/android/DemoAir` puts the bench's fake
data2g-host (`tests/FakeHost.*`, loopback sockets) on free ports for the
operator and four made-up stations beside it (W1QSO, K7QTH, DL3QRM and
F5QRN), each a real HostLink, BroadcastChat, Seals,
BroadcastFiles and SessionChat on a thread of the demo's own. The app
connects to it exactly as it does to data2g-host. Nothing of Data2G's is
in it, no audio is made and nothing reaches a modem or a radio.

- The stations check in one after another within ten seconds, each with
  a spoken line its Hello rides on. They never call the roll themselves:
  a roll call holds the channel for a window of Hands, and the net would
  take half a minute to come up. They do answer the operator's roll call.
- A line to everyone is answered by one station, in turn; a line to
  `@CALL` is acknowledged and answered by that station.
- Three of them take a call, greet, answer what is typed, offer their
  bulletin when a file is mentioned, save a file sent to them and say so,
  and leave after 73. W1QSO broadcasts a bulletin file 40 s in and every
  6 minutes, for Files to take with Receive.
- Nobody holds the stations' callsigns (Tim, 2026-11-21). The prefixes
  are real, two American and two European, and the suffixes are Q
  signals: the FCC assigns no suffix from QRA to QUZ, so the American
  two cannot exist. For DL3QRM and F5QRN no such rule was found; look
  them up in the German and French registers before the app is
  published. The operators' names are made up as well.
- A burst keys the pretend transmitter for 1.5 s, so the TX badge shows,
  and session bytes cross at 400 a second, so a file shows its progress.

On the screen: the amber bar ("DEMO MODE  Simulated stations; nothing is
sent.") covers the status bar's strip too and opens Settings. The Demo mode
switch in Settings is the demo (Tim, 2026-11-21): turned on it starts the
demo and the sheet makes way, turned off it ends it at once, and Connect
and Disconnect, which are for the operator's own modem, are not shown
while it is on. It cannot be turned on from a ragchew on the air. The
guide's bubble (`DemoGuide.kt`, `demoTip`) works out the next step from
the snapshot alone, so it survives rotation and an operator who wanders
off, and a tap on a bubble with a suggested line puts that line in the
right entry field (Broadcast or the ragchew) for the operator to send.
Hide tips puts the bubbles away until the next demo, and they step aside
by themselves while the keyboard is up, when there is room for the
conversation and the entry field and little else.

The demo keeps to itself. It uses the callsign in Settings, or DEMO
when there is none; its logs and files live in `files/demo/`, wiped at
each start, never in the operator's `files/stations/`; the saved host and
ports are not touched; and when it ends the engine is closed and the
screen cleared, so nothing a simulated station said is left to be taken
for the air. A demo station with the operator's own callsign is left out.
While the demo runs the TX badge lights as it does on a modem; its place
in the bar is always kept, so the bar never changes height.

Checked: `ragtime_android_demo_test` on the desktop (the whole net with
an adapter on it: check-ins and locators, both replies, a call, a
ragchew, a file each way, 73, the broadcast bulletin whole, the
callsign clash, the runner's thread); `DemoGuideTest` (JVM, the guide's
steps in order); and `DemoModeTest` on the S26, through the real sheet
(the switch, the bar, the stations, bubbles filling the entry field,
both replies, a call and a ragchew, the saved host untouched, the screen
cleared after). Not yet: the file steps of the tour on the device by
hand, and a demo left running for hours.

## Initial milestones

1. Link the shared core into a JNI library and exercise protocol checks
   on an Android emulator and an ARM64 device. Choose and document the
   initial SDK, NDK, and ABI matrix with the build.
2. Build the connected phone shell and a foreground-only network client
   for an external Data2G host. Reuse the existing Broadcast and Session
   engines. Verify chat against fake hosts and desktop Ragtime.
3. Add file transfer through app-private paths and Android document
   import/export, then verify repairs, persistence, reconnect, activity
   recreation, and network loss. Keep media codecs, audio, and local USB
   radio access as separately validated follow-up work.

## No-radio acceptance pilot

The intended acceptance setup is a real Data2G Android TNC alongside
Ragtime on the S26, with a second Data2G/Ragtime station on the Mac.
Synthetic callsigns, messages and illustrative UI data are acceptable;
delivery and acknowledgement results must come from actual modem decoding.

Each Ragtime connects to its own local Data2G KISS, command and session
ports. A virtual audio cable carries samples between the two Data2G
instances over TCP. Thus the phone's modem is exercised as well as its
client. The proposed audio transport carries continuous 8 kHz mono
float32 samples (32 kB/s each direction, including silence), with bounded
buffering and explicit transport/counter diagnostics. No microphone,
speaker, CAT or radio is needed for this path. Transport disconnects and
sample loss must be reported rather than silently counted as successful
delivery.

On 2026-11-20, a single-use fixed-token TCP exchange succeeded from the
S26's Wi-Fi address to the Mac's Wi-Fi address, without ADB reverse. This
checks network reachability from Android's shell; Ragtime/Data2G app-level
LAN access remains a separate check. The temporary listener was closed.

Existing building blocks found in the local Data2G project:

- `tests/test_native_host_e2e.py`: two real host processes, crossed named
  audio pipes, command/data/KISS traffic, exact payload checks, and audio
  error detection. Ragtime also has `tools/bench/real_modem_smoke.py` for
  an isolated release-host session check.
- `native/core/audio/pipe.{hpp,cpp}` and `app::Station`: an existing
  device-free `--audio-io pipe:IN,OUT` path, paced continuously in real
  time. FIFO input follows the sender's clock.
- `native/android-app/`: the actual Android TNC, reusing `app::Station`,
  the real host listeners, and a foreground service. Its current UI/start
  path does not expose the pipe-audio option. Selecting test audio and
  connecting that audio to TCP are new integration work, not existing
  working Android features.
- `native/lab/`: actual paired-engine audio with clean and seeded noisy/
  fading channels, byte checks and recording/replay. The current Lab runner
  has no external host sockets; its Android activity cancels a run when
  stopped. It cannot serve Ragtime unchanged while hidden behind it.

Recommended stages:

1. Run two pinned release Data2G hosts on the Mac using crossed audio
   FIFOs. Attach the phone's Ragtime to one over LAN and a separate desktop
   Ragtime/profile to the other. Reuse the existing pipe-host bench and
   allow real waveform/session deadlines rather than the fake host's
   15-second test assumptions. This proves Ragtime/real-modem protocol and
   LAN interoperability; it does not yet test an Android modem.
2. Add an explicitly selected virtual-audio test mode to the Android TNC.
   Reuse the existing pipe/FIFO boundary and bridge samples over TCP to
   the Mac's modem. Keep the TNC's service and ports alive when Ragtime is
   foreground. Review the service/permission path for a mode that does not
   capture or play device audio. No ordinary radio settings should be
   changed by selecting the bench.
3. Verify exact UTF-8 messages both ways, addressed acknowledgements,
   explicit Call/Leave, host reconnect, history and app switching. Retain
   source/APK identities, transcripts, audio recordings and transport/audio
   counters. Add file acceptance once Android file transfer exists.
4. After a clean channel passes, adapt the Lab's existing channel model
   for bounded, seeded impairment cases. Keep these correctness cases
   separate from profiling, training, reliability campaigns and RF/audio-
   hardware qualification.

The repeatable first-stage bench is `tools/bench/android_air_bench.py`;
setup and opt-in phone test commands are in [android/README.md](../android/README.md#no-radio-real-modem-pilot).
It launches existing release binaries without modifying Data2G source,
and keeps an isolated desktop Ragtime profile and recordings. The pinned
local Data2G 0.5.3 source is `ede34e7c15627b09a55c3f6bbae71511cfbb6882`;
the binary's SHA-256 is recorded per run. Only Ragtime is installed on the
S26; the phone's own modem is not exercised in this stage.

### Remote collaborator over Tailscale

The same virtual-audio design can span two hosts over Tailscale, with a
network bridge in place of the local crossed FIFOs. That bridge is new
work: use one bidirectional connection, bounded buffering, explicit
disconnect/sample counters, and handling of jitter and independent clocks.
Raw 8 kHz float32 mono audio needs 256 kbit/s in each direction before
transport overhead. Both Ragtime clients can stay local to their own modems.

Separate tailnets work through machine sharing: the collaborator shares
his modem host with your account, and your bridge initiates the connection
to that shared host. Tailscale's default shared-device quarantine permits
replies on an incoming connection but prevents that shared host initiating
connections back into the recipient's tailnet. Access remains subject to
both tailnets' policies. See [Tailscale machine sharing](https://tailscale.com/docs/features/sharing)
and [connection types](https://tailscale.com/docs/reference/connection-types).
An initial Mac-only raw FIFO/TCP relay is now implemented as
`tools/bench/tcp_audio_bridge.py`, launched by the bench helper's
`--remote-audio HOST:PORT` option. It preserves bytes, bounds its queues,
reports counters and stops on disconnect; it does not yet handle jitter
or clock drift. Its local fragmented bidirectional byte/EOF check passes.
The Android implementation is described below. See the
[Mac trial commands](../android/README.md#mac-to-remote-audio-trial).

### First-stage result, 2026-11-20

The S26 opt-in `RealModemPilotTest` passed against the pinned release
hosts and desktop Ragtime. With no ADB reverse, the app connected from
the phone's Wi-Fi address to the Mac's, sent an addressed Broadcast
containing `café 🚀`, and reached Acknowledged about nine seconds after
Send. Desktop Ragtime's isolated broadcast log contains the exact marker
and text. Both modem recordings contain the corresponding TX/RX bursts.
An initial attempt was interrupted by the phone app moving into the
background before the ACK arrived; the foreground repeat passed.

Local evidence is under `build-android-air-bench/run-bz62jpzi/`, including
`phone-result.json`, `desktop.conf.broadcast.log`, `status.json` and both
recording trees. Instrumentation output is
`build-android-results/s26-real-modem-pilot-repeat.log` (one test passed).
This verifies the app-level LAN path, desktop receipt and returned ACK;
session Call/Leave and phone-side Data2G remain separate acceptance steps.

### S26 modem integration, 2026-11-20

Data2G 0.5.3 now has an isolated development worktree at
`build-data2g-android-source`, branch `codex/android-network-audio`, adding
an explicitly selected TCP test-audio transport and Android configuration.
It uses the same station and modem core, opens no rig or device audio,
shows transport byte counters and stops on transport failure. Network mode
runs with a special-use foreground service; microphone permission remains
ungranted. Ordinary radio settings are retained. The transport has no
jitter correction or clock-drift compensation yet.

The ARM64 TNC APK builds with Qt 6.11.1, NDK 27.2.12479018, JDK 21 and
target SDK 36. The S26 runs both actual applications as MYCALL-1, with
Ragtime's locator MYGRID and local ports 8100/8300/8301. A temporary Mac
peer TESTMAC on LAN audio port 19977 decoded the phone's addressed Unicode
message, and the phone received its real-modem ACK in 9.377 seconds while
Data2G ran behind Ragtime. The opt-in phone test passed in 10.799 seconds.

Evidence is in `build-phone-modem-bench/run-vdifq5q6/phone-result.json`,
the peer broadcast log and recordings, plus
`build-android-results/s26-own-modem-pilot.log`. The targeted native
fragmentation/disconnect check and source include check also pass. Session
Call/Leave, files, process death, screen-off behavior and impaired-network
acceptance remain separate work.

The subsequent phone connection to the remote collaborator's host (over Tailscale, TCP 9977) was refused.
The Mac relay's previous audio connection also closed at the remote side.
The phone retains MYCALL-1 / MYGRID, local Ragtime host 127.0.0.1 and the
remote audio endpoint at that stage; external phone delivery awaits a reopened listener.
The development APK and portable Data2G patch are linked in the
[phone setup guide](../android/README.md#data2g-and-ragtime-together-on-the-s26).

After the remote operator went offline, the active manual setup was changed to Mac
MYCALL and phone MYCALL-1 / MYGRID over LAN audio at MAC_LAN_IPV4:19977.
The opt-in addressed Unicode/ACK check passed between these actual station
identities, and normal Android Ragtime was left connected for manual use.
Mac Ragtime uses an isolated profile in
`build-phone-local-bench/run-6no_hj0v/`; evidence includes its broadcast log,
audio recordings and the phone result. Android Ragtime continues to use
its local modem at 127.0.0.1:8100/8300/8301. This setup does not require
the remote host or Tailscale.

### 0.1.0 over real modems, 2026-11-20

The S26 ran Ragtime 0.1.0 build 2 as MYCALL-1 on its own Data2G TNC
(network audio to the Mac at MAC_LAN_IPV4:19977); the Mac ran the pinned
Data2G 0.5.3 host and desktop Ragtime as MYCALL, started with
`android_air_bench.py ... --listen-audio MAC_LAN_IPV4:19977 --auto-files
MYCALL-1`, which has the desktop save MYCALL-1's session files and every
broadcast file with no click.

- `RealModemPilotTest`: the addressed message with `café 🚀` reached
  Acknowledged 7.4 s after Send, and the Mac's broadcast log has it.
- `RealModemFileTest` (new, opt-in like the pilot): the phone called
  MYCALL and was connected in 4.5 s; a 1,200-byte session file was Sent
  and checked 32 s later, and the Mac saved it in 7.7 s of transfer; the
  phone left; a 1,200-byte broadcast file's pieces had gone 89 s after
  that, the Mac asked for a repair ("Repairing, 1 station asked" on the
  phone) and saved it whole. Both files on the Mac have the SHA-256 the
  phone recorded.

Evidence: `build-phone-local-bench/run-3mtfel0r/` (`received/`,
`desktop.conf.files`, the logs and recordings) and
`build-android-results/s26-0.1.0-real-modem-pilot.log`,
`s26-real-modem-file.log` and `s26-real-modem-file.json`. The first run
sent random bytes named `.bin`, which nothing can open; the test now
sends readable `.txt`, and a second run with those passed with the same
timings (the log and JSON are that run's; `received/` has all four
files). Not covered: a file from the desktop to the
phone, files over a few kilobytes, an impaired channel, and the phone
screen off.
