# Ragtime for Android: release notes

## 0.2.0, build 3

21 November 2026. An insider build for testing, not a store release.

Ragtime is chat and file transfer for amateur radio through a Data2G modem. The modem is a separate program that you run yourself, on this phone or on another machine; Ragtime never transmits by itself. To look around with no modem at all, turn on Demo mode in Settings.

These notes gather everything since release notes were first kept, with build 0.1.0. That build was the first with a version number; what it brought is under Files and Connection below.

## Conversations

- Broadcast to everyone on the D2G net and ragchew with one station, each with its own history and its own draft.
- Address a station with @CALL on Broadcast and see when it acknowledges.
- Call a station from Stations, and leave when you choose: Finish and leave, or Leave now.
- Stations lists who has been heard, where they are, how strongly, and whether they are free.
- Roll call, on Stations beside Call station, asks every station to check in.
- A red TX badge shows in every view while the modem is transmitting.

## Pictures

- A picture another station sends shows among the messages, blurred at first and sharper as more of it arrives. Up to 64 KB it comes without asking.
- Tap a picture to see it alone; pinch to zoom.
- Send one from the + beside the entry line. Pick any picture Android can read (JPEG, PNG, WebP, HEIF, GIF, BMP). A sheet shows it as the other station will see it, with its longest side and its size on the air to choose, and for a broadcast about how long it will take.
- Pictures go on the air as JPEG 2000. Ragtime also shows the Cool-chic pictures a desktop Ragtime can send.

## Files

- Send and receive files in a ragchew: accept, decline, cancel, progress, and the part that arrived kept if the link drops.
- Broadcast a file to the net, and receive one: it is checked whole, and pieces that were missed are asked for again.
- Choose files with Android's file picker and save a copy of any finished file where you like. A file may be up to 300 KB.
- Files keeps the history of what was sent and received. A file you broadcast says Sent once all of it has gone, and stays open for a while for stations to ask for pieces they missed.

## Gearshift

- On Field desk: the gear your broadcasts, files and pictures on D2G go in. Lower gears get through on weaker paths; higher ones are faster.
- Auto follows the weakest station heard lately. Until one has been heard, Ragtime sends in the mode the modem is set to. Tap a gear to stay in it.
- Each gear shows its speed, the signal it needs, its time per KB, and how far short the weakest path is for it.
- It needs a modem that gives thresholds with its modes: Data2G 0.5.1 or later. With an older modem there are no gears and Ragtime sends in the mode the modem is set to.

## Notifications

- While Ragtime is in the background it tells you when a station calls you, writes to you with @, says something in your ragchew, or offers you a file. Tap the notification to land on it.
- Settings has the switch: Notify me of calls and messages.

## Connection

- Connect in Settings. Ragtime keeps running when you switch to other apps; an ongoing notification brings you back or disconnects.
- Disconnect leaves an active ragchew at the modem first, so the next Connect can call again.
- If the modem goes away Ragtime retries by itself.

## Demo mode

- A switch at the bottom of Settings. Simulated stations check in, answer you, take a call, ragchew, trade files and broadcast a bulletin, behind a bar across the top that says it is a demo. Nothing is transmitted.
- A guide suggests what to try next; tap a suggestion to put it in the entry line.
- The demo keeps its own history, apart from your station's, and starts fresh each time.

## Settings and About

- Callsign and locator first, then the modem's address with a localhost button for Data2G on this phone, and Connect under them.
- About has these notes, Privacy, and Open source licences.
- The app has Ragtime's icon: the Maple Leaf Rag's leaf going digital.

## Not in this build

- Voice messages: they arrive as files.
- Reactions, editing a line already sent, and pings.
- Refusing a call: Ragtime always answers a ragchew call.
- Rig control and the dial frequency.
- Choosing a mode off the gear ladder, and the width chips.
- Recent work, the watch list and the globe.

## Known limits

- Tested on one phone, a Samsung S26 on Android 17. ARM64 phones only, Android 8 or later.
- This build is signed with a development key. A later build signed with a different key will not install over it: uninstall this one first.
- Over real modems, text, files and a broadcast repair have been checked between this phone and one desktop. Pictures and receiving a file from a desktop have been checked on the test bench and in Demo mode, not yet on the air.
- A picture that is one flat colour cannot be sent.
