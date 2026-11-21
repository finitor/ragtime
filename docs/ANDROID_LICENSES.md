# Android: open source licence audit

Audited 2026-11-20, Android build 0.1.0 (build 2), from the debug APK
`ragtime-android-0.1.0-b2-debug.apk` and the `releaseRuntimeClasspath`
Gradle resolves. Redo it when a dependency, the NDK or a native feature
(pictures, voice, rig control) changes. This is an engineering audit, not
legal advice.

## What is in the APK

| Component | Where | Licence | What it asks of us |
| --- | --- | --- | --- |
| Ragtime: the C++ core, the Android adapter, demo mode's stations and the bench's fake host (`tests/FakeHost.*`), the Kotlin app | `libragtime_jni.so`, `classes.dex` | MIT, © 2026 Jeffrey Baitis | The copyright line and the MIT text in every copy. In About > Open source licences since 2026-11-21, read from the repository's `LICENSE` when the APK is built. |
| Files ported from Glissando (Data2GLink, SessionFiles, Sha256, HamText, Globe, Airtime's packing) | in the core | MIT here | Their headers say Jeff wrote them for Glissando and relicensed them. Nothing more than Ragtime's own notice. |
| AndroidX and Jetpack Compose, 74 modules (activity, compose ui/foundation/material3/material-icons-core, lifecycle, core, emoji2, profileinstaller and what they bring) | `classes.dex`, `libandroidx.graphics.path.so` | Apache-2.0 | A copy of the licence. None ships a NOTICE file (every jar and aar was scanned), so there is no NOTICE text to carry. |
| Kotlin standard library 2.0.0, kotlinx.coroutines 1.7.3, JetBrains annotations 23.0.0 | `classes.dex`, `kotlin/*.kotlin_builtins` | Apache-2.0 | The same. |
| Guava `listenablefuture` 1.0 (an empty placeholder AndroidX brings) | `classes.dex` | Apache-2.0 | The same. |
| LLVM libc++, libc++abi, libunwind and compiler-rt builtins, linked statically from NDK 28.2 | inside `libragtime_jni.so` | Apache-2.0 with LLVM exceptions | Nothing: the exception lifts the notice conditions for runtime code a compiler embeds. Listing it is a courtesy. |
| OpenJPEG 2.5.3, for JPEG 2000 pictures (since 2026-11-21) | inside `libragtime_jni.so` | BSD-2-Clause, © Université catholique de Louvain and others | Its copyright notices, conditions and disclaimer with the binary: in Open source licences, from `assets/licenses/openjpeg.txt`. |
| Ragtime's port of Cool-chic, for pictures a desktop sends that way (`image/src/coolchic/`) | inside `libragtime_jni.so` | BSD-3-Clause, © Orange; its fitting code follows SOAP, MIT, © Nikhil Vyas | Both notices with the binary: in Open source licences, read from `image/src/coolchic/` when the APK is built. |
| Natural Earth coast, border, lake and admin-1 lines | tables in the core (`core/src/globus/`) | Public domain | Nothing. A credit is welcome. |
| HamText table 0 | a table in the core | Ragtime's own | Built from Ragtime's and Glissando's own chat lines and docs (`tools/ham_table/`). |

83 Java and Kotlin modules resolve, every one Apache-2.0. The native
library needs only `libm`, `libdl` and `libc` from the system.

## What is not in it

Checked by the library's strings and its build flags
(`android/app/src/main/cpp/CMakeLists.txt` turns each off): Hamlib
(LGPL), sotto and Codec 2 (LGPL-2.1), libheif and libde265 (LGPL-3),
RNNoise and libwebp (BSD), wxWidgets, and anything of Data2G's
(Artistic-2.0 with its interface exception). So the APK carries **no
copyleft code** and owes nobody source. Pictures did not change that:
the phone reads WebP and HEIF with Android's own decoders, so neither
libwebp nor the LGPL libheif came with them.

Bringing voice to Android changes that. sotto and Codec 2 are
LGPL-2.1: the user must be able to replace the library, which a signed
APK makes awkward (ship it as its own `.so`, publish the build recipe and
offer the object files, or get another licence from Jeff, who owns sotto).
Decide that before the code, not after.

## What has to appear in the app

All three are in the app since 2026-11-21 (`Notices.kt`, checked on the
S26 by `AboutTest`).

1. **An "Open source licences" page**, reached from About Ragtime, with:
   - Ragtime, `Copyright (c) 2026 Jeffrey Baitis`, and the MIT licence in
     full. This is the one notice a licence requires that the APK went
     without until now. If the Android port's authors claim copyright in their
     part, their line goes beside Jeff's; MIT asks only that his stays.
   - The Apache License 2.0 in full, once, and under it the names it
     covers: AndroidX and Jetpack Compose (The Android Open Source
     Project), Kotlin and kotlinx.coroutines (JetBrains), Guava (Google).
   - OpenJPEG's notice and the Cool-chic port's (with SOAP's), each in
     full: BSD and MIT ask for them with every binary.
   - As courtesies: the LLVM runtime libraries (Apache-2.0 with LLVM
     exceptions) and Natural Earth (public domain).

   Write it by hand or generate it at build time from the resolved
   dependencies. Google's `oss-licenses-plugin` would do it but pulls in
   Google Play services, which a ham radio app that may also go to
   F-Droid does not want; a checked-in text asset, checked against
   `./gradlew :app:dependencies` when dependencies change, is enough for
   one licence family.

2. **A privacy policy**, as a link or as text inside the app, and its
   URL in Play Console. The text is [ANDROID_PRIVACY.md](ANDROID_PRIVACY.md),
   shown in About and from Settings, beside a line under the callsign
   that says it goes out with everything sent; its page on the public
   repository can be the URL Play asks for. Google asks this of
   every app, including ones that collect nothing. Ragtime's is short and
   should say what is true: the developer receives nothing; the callsign,
   locator, messages and files go only to the Data2G host the operator
   names and from there on the air, in the clear, to anyone listening
   (amateur radio is never encrypted); history and received files stay in
   the app's private storage; there are no accounts, ads or analytics.

3. **The ongoing notification** while connected, with its way to stop.
   It is there already. Play Console wants the foreground service
   declared (type `specialUse`, with the manifest's subtype text) and a
   short video of the operator pressing Connect and the notification
   appearing.

Nothing else is required on screen by the licences or by Play. Demo
mode's bar already says the stations are simulated.

## Also needed before a Play submission

Not licence matters, but they stop a submission or should be settled:

- **Target API 36.** The build targets API 35. Since 2026-08-31 Play
  takes new apps and updates only if they target Android 16 (API 36).
- A release build signed with an upload key, and an Android App Bundle.
  (The launcher icon is in since 2026-11-21.) 64-bit and 16 KB page
  support are already in place.
- **The demo's callsigns** are invented since 2026-11-21: real prefixes
  with Q-signal suffixes (W1QSO, K7QTH, DL3QRM, F5QRN) and made-up
  names. The FCC assigns no suffix from QRA to QUZ, so the American two
  cannot be anyone's. For the German and the French one no such rule was
  found: look them up in those countries' registers before publishing.
- **The name and the package.** MIT lets anyone ship this, but "Ragtime"
  and `org.ragtime.radio` are Jeff's project's name; settle with him who
  publishes it and under which package name before the first upload,
  since a package name on Play can never be changed.
- **The Data safety form** is a judgement, and worth settling before the
  listing is written. Nothing reaches the developer, which argues for
  "no data collected". But Google's form defines collecting as sending
  data off the device, whoever receives it, and Ragtime exists to send
  the operator's callsign, locator, messages and files off the device.
  The careful answer declares those (a user ID, approximate location,
  messages, files) as collected for app functionality, says truthfully
  that they are **not** encrypted in transit, and treats the sharing as
  the operator's own act, which the form exempts. The privacy text
  already says the same in plain words.

## Outside the APK, in this repository

`tools/bench/data2g-android-network-audio.patch` is a diff against
Data2G's source, with Data2G's lines in it, and the test TNC built from
it is a modified Data2G. Data2G is under the Artistic License 2.0, and
this project's own rule is that no Data2G code is copied in. Neither is
in the Ragtime APK. The patch belongs in a Data2G fork or with Andrew,
and the modified TNC should not be handed round as plain "Data2G".

## Sources

- [Target API level requirements for Google Play apps](https://developer.android.com/google/play/requirements/target-sdk)
- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Understanding foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
