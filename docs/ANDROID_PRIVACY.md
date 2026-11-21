# Ragtime for Android: privacy

Effective 21 November 2026.

## The short version

- Ragtime's developers receive nothing from this app. It has no accounts, no analytics, no advertising and no crash reporting, and it talks to no server of ours.
- What you send with Ragtime goes on the air. Amateur radio is public: anyone in range can receive what you transmit, keep it and pass it on, and nobody, including us, can take it back.

## What the app sends, and to whom

Ragtime connects only to the Data2G modem you name in Settings, which is often an app on the same phone. While you are connected, the modem puts on the air:

- Your callsign, with everything you send. Identifying your station is a condition of an amateur licence. In many countries anyone can look a callsign up in a public register and find the licensee's name and postal address.
- Your locator, if you enter one. It is a Maidenhead grid square that you type yourself: four characters place you within about 100 by 200 km, six within about 5 by 10 km. Ragtime never reads this phone's location.
- The messages you type and the files you choose to send.
- Small records that make the net work: check-ins, acknowledgements, whether you are in a ragchew or free, the checks and repair requests that go with files, and the name and version of this app.

All of it is sent in the clear. Amateur radio rules do not allow messages to be hidden by encryption, so Ragtime has none. A message addressed to one station is still heard by every station on the net, and a ragchew, though it is between two stations, can be received by anyone listening.

Other stations may store what they hear. Another operator's Ragtime keeps a log of the net just as yours does. We have no control over who receives your transmissions or what they do with them.

## What stays on this phone

Your settings, your conversations, the stations you have heard and the files you have sent and received are kept in the app's private storage. They are not part of Android's backups. Clearing the app's storage or uninstalling it removes them. A copy you make with Save a copy goes where you put it and is yours to look after.

## Permissions

- Network access, to reach the Data2G modem. Ragtime makes no other connection.
- Notifications and a foreground service, to keep the connection while you use other apps. An ongoing notification shows while it runs and can end it. While Ragtime is in the background, a notification also tells you when a station calls you, writes to you or offers you a file; it shows the station's callsign and what it sent, as your phone's notification settings allow.

Ragtime asks for no location, microphone, camera, contacts or storage permission.

## Demo mode

Demo mode's stations are simulated inside the app. Nothing leaves the phone.

## Children

Ragtime is not directed at children. Transmitting needs an amateur radio licence.

## Changes and questions

If this text changes, the date at the top changes with it. Ragtime for Android is open source; questions about it or about this text go to the project's issue tracker at https://github.com/finitor/ragtime/issues.
