# OpenX6X00-Android

An open, community companion app for the Xiegu X6100 running the
[OpenX6X00](https://github.com/Guru-RF/OpenX6X00) firmware. **Android only** for now.

By KD0CQ ([theMadCatter](https://github.com/theMadCatter)).

## What it does

- **Radio face.** One screen laid out like the radio's RF.Guru UI: VFO A/B, mode, AGC,
  PRE, ATT, ATU, a needle S-meter (TX watts, SWR and ALC while transmitting), the
  frequency strip, and a live spectrum and waterfall. Landscape follows the radio's
  800×480 main screen, and portrait follows the walkie layout. There is an optional 3D
  spectrum view and a high-contrast sunlight theme.
- **Screen.** A mirror of the radio's own display.
- **Digital modes.** Each one is drawn like that mode's own window on the radio.
  - **FT8 / FT4 / FT2:** call lane, waterfall, a see-through decode list or 3×3 tiles,
    and the radio's button pages.
  - **JS8 (needs firmware v0.3.13 or later):** drives the radio's built-in JS8 engine,
    with a waterfall, band activity and directed messages, and a **GhostNet** filter
    for @GHOSTNET, @GSTFLASH and your regional group.
  - **WeFax:** the chart, station and frequency, and the RX Auto/Cont status.
  - **CW:** decoded text with the radio's word colours.
  - PSK, RTTY, Olivia, SSTV, WSPR and Navtex aren't streamed by the firmware yet, so
    they show as pending.
- **Spots and Log.** Live spots on the spectrum, and a logbook with backup and export.
  Backups never include the pairing token, the QRZ password or the log itself.
- **Audio and PTT.** RX/TX audio over TCI, and hold-to-talk with a lease that always
  unkeys on release.
- **Background running.** A foreground service keeps the link up when the screen is off.

## Safety

Every transmit action, including PTT, CQ, replies, JS8 send and TX offset, requires
**Take control** on the radio and asks first. Digital TX is checked against **US
Advanced class** band edges (FCC 97.301(c) and 97.305). The app never stores QRZ
credentials in backups.

## How it talks to the radio

- mDNS discovery (`x6100.local`) or a manual host. The last host is remembered.
- The Remote/Panel WebSocket on port 50002 handles pairing, control, the PTT lease,
  state, spectrum, FT8, JS8, CW, WeFax, spots and logging.
- TCI on port 50001 handles RX/TX audio and PTT.

The protocol shapes come from the firmware sources in the
[firmware repo](https://github.com/Guru-RF/OpenX6X00). Nothing on the wire is
invented. If something isn't on the socket (country flags, CW speed, the RTTY scope),
the app doesn't fake it.

## Requirements

- Android 8.0 (API 26) or newer.
- A Xiegu X6100 on OpenX6X00 firmware. JS8 needs v0.3.13 or later.

## Building

    ./gradlew testDebugUnitTest assembleDebug

Kotlin and Jetpack Compose. Testing without a radio: build OpenX6X00's PC version with
the stub radio (`host/build.sh` in the firmware repo) and point the app at that PC.

## Project layout

```
app/src/main/kotlin/radio/kd0cq/x6100/
  net/        panel (50002) and TCI (50001) clients and their pure codecs
  audio/      RX/TX audio over TCI
  ptt/        PTT lease control with a fail-safe unkey
  discovery/  NSD browse, manual host+port fallback
  service/    the foreground service that owns the connection
  ui/         Connect, Pairing, Radio, Screen, FT8, JS8, WeFax, CW, Spots, Log, Settings
app/src/test/ codec unit tests built from the firmware's real lines and frames
```

## Status

Beta. Tested receive-only against a real X6100. The panel state line sends
battery (`bat`). A reading of 0..100 is shown, and -1 (no pack reading) is
hidden. The state line does not send a clock, so the header clock is the
phone's UTC.

## Spirit and license

The ham community is about sharing information with the community, not profiting off
the backs of others' work. This app is built in that non-commercial, give-it-back spirit.

Licensed under the **GNU General Public License v3.0 or later** (see `LICENSE`).
Anyone who distributes this app or anything built from it must publish their source
under the same terms, so it can never be closed up.

73 de KD0CQ
