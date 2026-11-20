# OpenX6X00-Android

An open, community companion app for the Xiegu X6100 running the
[OpenX6X00](https://github.com/theMadCatter/OpenX6X00) firmware. **Android only** for now.

By KD0CQ ([theMadCatter](https://github.com/theMadCatter)).

## What it is

- A phone remote for the radio over the network the firmware already exposes:
  mDNS discovery (`x6100.local`), the Remote/Panel WebSocket (port 50002: pairing,
  control, PTT lease, state, spectrum, FT8, CW, spots, logging) and TCI (port 50001:
  RX/TX audio and PTT).
- Kotlin and Jetpack Compose, minSdk 26.
- No firmware changes are needed for the basics.

## Spirit and license

The ham community is about sharing information with the community, not profiting off
the backs of others' work. This app is built in that non-commercial, give-it-back spirit.

It is licensed under the **GNU General Public License v3.0 or later** (see `LICENSE`).
Anyone who distributes this app or anything built from it must publish their source
under the same terms, so it can never be closed up.

## Status

Early work. Not yet usable on the air.

## Project layout

```
app/src/main/kotlin/radio/kd0cq/x6100/
  net/        panel (50002) and TCI (50001) WebSocket clients, with the
              pure codecs (PanelProtocol, TciProtocol) beside them
  audio/      the RX/TX audio path over TCI (interfaces first, audio later)
  ptt/        PTT lease control with a fail-safe unkey
  discovery/  NSD browse for _tci._tcp / _http._tcp, manual host+port fallback
  service/    the foreground service that owns the connection
  ui/         Compose screens: Connect, Pairing, Radio (RF.Guru face),
              Screen, FT8, Spots, Log, Settings
app/src/test/ codec unit tests, built from the firmware's real lines and frames
```

The protocol shapes come from the firmware sources (`src/remote_server.c`,
`src/tci_server.c`, `src/tci_audio.c`) in the [firmware
repo](https://github.com/theMadCatter/OpenX6X00); nothing on the wire is invented.

## Building

    ./gradlew assembleDebug testDebugUnitTest

Testing without a radio: build OpenX6X00's PC version with the stub radio
(`host/build.sh` in the firmware repo) and point the app at that PC's address.
