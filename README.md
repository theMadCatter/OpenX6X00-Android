# OpenX6X00-Android

An open, community companion app for the Xiegu X6100 running the
[OpenX6X00](https://github.com/theMadCatter/OpenX6X00) firmware. **Android only** for now.

By Chris Fink KD0CQ ([theMadCatter](https://github.com/theMadCatter)).

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

## Building

    ./gradlew assembleDebug testDebugUnitTest

Testing without a radio: build OpenX6X00's PC version with the stub radio
(`host/build.sh` in the firmware repo) and point the app at that PC's address.
