# Reference — audio PTT review (revolta/audio-ptt-core)

Working notes for the review of `revolta/audio-ptt-core` at `f8f8b0c` (base `main` `2098e0b`) and the fixes on `cursor/explicit-ptt-unkey-abc7`. Owner: Chris Fink KD0CQ (theMadCatter). Firmware compared: Guru-RF/OpenX6X00 sources `remote_server.c`, `tci_server.c`, `tci_audio.h`, `control_owner.c`, `radio_session.js` (tree fetched for this review, not the app's DESIGN.md).

## What the question was

When a PTT lease renewal fails (send error, socket dying), does the code send an explicit unkey (`key PTT 0` and/or `trx:0,false;`), or does it only flip internal state and rely on the radio's lease timeout?

## What I found (before the fix)

A failed Panel renewal did **not** send `key PTT 0`. `PttLeaseStateMachine` called `letGoLocked(..., sendRelease = false)` at the lost-renewal site. The machine went Releasing, then Idle. `keyed` is false in Releasing, so `LeasePttController`'s panel-phase collector (gated on `lease.keyed`) did not try again. The unit test `aRenewalTheSocketWouldNotTakeEndsThePress` asserted that the wire stayed at the one down-command.

The radio then unkeys only if:

- the panel socket stays up and `ptt_lease()` fires after `PTT_LEASE_MS` (3000 ms) — `remote_server.c`, and
- or the socket actually closes and `client_close()` → `client_release_keys()` emits key-up.

OkHttp `WebSocket.send` returns false when the socket has failed, is closing, or the queue exceeds 16 MiB (which also closes the socket). A false renewal often means the following `key PTT 0` cannot be queued either. The explicit unkey still matters when one write fails and a later one can succeed (a dropped binary TX frame versus the text unkey, or a transient false). The firmware timeout is the backstop when the socket is dead, not a substitute for the up-command while it is alive.

The same pattern existed for a lost TCI audio frame: `letGoLocked(..., sendUnkey = false)`. The radio's backstop is `TX_SILENCE_MS` (500) or `tx_session_end` on socket close. `handle_cmd` never refuses `trx:0,false` from the client that keyed (audit B01 in `tci_server.c`: refusing left the radio on the air until the 180 s limit).

A second stuck-carrier bug, on a healthy socket: `onPanelEvent` called `lease.controlLost` only when the lease was keyed and `mine` was false. A phone-mic session (`trx:0,true,tci;`) ignored `ctl local` and `ctl browser …`. `ctl_local_input()` in `control_owner.c` forces `CTL_LOCAL` even while transmitting, and the TCI server does not unkey when that happens. `ctl tci 0` is **this** session: `ctl_tell()` sets MINE only when the holder is this panel slot (`CTL_BROWSER`). Treating every `mine=0` as "someone else" would unkey every phone-mic transmission.

Also on a healthy socket:

- `role operate 0` (`tx_ok` clear) was ignored for the phone-mic path. The UI gated the button on `canOperate` only, and `pointerInput` is the real key, so `Button.enabled` alone did not stop a press.
- A panel-socket drop fail-safed only the lease. Phone-mic TX survived the loss of the only channel that carries `ctl`.
- Panel `tx 0` did not stop a Keyed TCI session. (`tx 0` during TCI `Starting` must not, or a stale line from the previous over cancels a key that has not been paced yet.)

Paths that already sent an unkey, and were left that way: button `finally` → `releasePtt`; `MainActivity.onStop` → `wentToBackground`; service `onDestroy` shutdown **before** `link.disconnect`; `onTaskRemoved` shutdown; 170 s cap; 1.5 s panel confirm; 1 s TCI confirm; 500 ms silence; 500 ms mic stall; malformed TX frame (`txAudioOrNull` null); `linkLost` / `failSafe` while still Requesting/Keyed/Starting; radio `tx 0` on the lease machine.

## What was changed, and why

Cause, not a second watchdog: any exit from a live hold attempts the up-command unless the radio has already said the carrier is down.

- `carrierMayBeUp` / `owesUnkey` on both machines. `owesUnkey` is false during a live press (link loss uses `onAir`) and true after the press if the up-command was not accepted.
- Lost renewal and lost TX frame pass `sendRelease` / `sendUnkey` true.
- A down-command the socket refuses is still followed by the up-command. `ptt_down()` records the hold before it keys; a write OkHttp did not queue can still be one the radio had. `key PTT 0` is a no-op when this client holds nothing (`remote_server.c` returns when the key is not in `held[]`).
- Retries: Panel 250 ms × 12 (about `PTT_LEASE_MS`); TCI 100 ms × 6 (about `TX_SILENCE_MS`). Generation-scoped. Cancelled with the other timers. Refused while Requesting/Keyed/Starting. The next `press()` calls `deliverUnkey` **before** the new down-command, under the same lock, because `key PTT 0` has no hold number and a late copy would release the new press.
- `err ptt released` clears the owe flag and does not send `key PTT 0`. TCI `onTransmitting(false)` sends one `trx:0,false;` (harmless if the server already ended the session; B01 says it is never refused for the owner) and then clears the owe flag so it is not retried.
- `reset()` unkeys when the session was live **or** an up-command is still owed. Previously Releasing after a lost renewal made `reset()` send nothing.
- TCI `letGo` / `reset` send the unkey **before** `mic.stop()`. `stop()` joins the capture thread for up to 500 ms, which is the whole of `TX_SILENCE_MS`.
- `LeasePttController`: panel phase loss fail-safes both paths when `onAir` or either machine `owesUnkey`. TCI phase loss calls `tx.linkLost` when keyed or owing. `tx 0` stops TCI only in `State.Keyed`. Control loss unkeys TCI unless `ControlInfo.isTci`. Role loss fail-safes both when `!canOperate` or `!txAllowed`. `RadioRig`'s TCI-phase watcher uses `onAir || needsUnkey`.
- `ControlsScreen` (later replaced; the same gate is now `HoldToTalk` on the radio screen — see the RF.Guru section): `mayKey = canOperate && txAllowed` gates `Button.enabled` and `pointerInput`. The microphone switch stays on `canOperate` only.

Why this does not re-key or unkey the wrong press: the generation check and the lock around "unkey owed, then down-command" are the same mechanism the branch already used to stop a renewal after release. Retries are bounded so a dead socket is not written forever. Firmware socket-close and the 3 s / 500 ms / 180 s limits remain the backstop when every write fails.

## Tests

`./gradlew testDebugUnitTest` on JDK 21: **146 tests, 0 failures** (133 on `f8f8b0c`, plus the cases below).

New or rewritten coverage:

- Renewal dropped, unkey accepted → exactly one `key PTT 0`, no second unkey, no second down-command.
- Unkey refused → `owesUnkey`, then one `key PTT 0` after 250 ms, and no further copies.
- Down-command refused → `key PTT 0` still sent; hold number still spent.
- TX frame refused → `trx:0,false;` sent; the refused frame is not on the wire.
- TCI key refused → unkey still sent and the microphone stopped.
- TCI unkey refused → retried after 100 ms, once.
- `ctl local` and `ctl browser other` unkey a phone-mic session. `ctl tci` does not.
- Lease control loss still sends `key PTT 0` and does not touch TCI.
- `role operate 0` and `role view` unkey whichever path is up.
- Panel `tx 0` does not cancel TCI `Starting`; it does unkey TCI `Keyed`.
- `err ptt released` stops the phone microphone.
- Panel phase `LOST` and TCI phase `LOST` each send `trx:0,false;` for a phone-mic session.

The original suite already drove button release, 1.5 s confirm, 170 s cap, 500 ms silence, 500 ms mic stall, malformed frames, link loss while still keyed, and "no frame after release". Those still pass. They did not catch the renewal bug, because the test asserted the missing unkey.

## What is still true, and not changed

- Hard process kill: no userspace unkey. Firmware `client_close` / TCI `fd < 0` → `tx_session_end` is the backstop.
- `WebSocket.send == false` usually means the unkey cannot be queued either. The retry covers the window where a later write works.
- Panel `SharedFlow` (`DROP_OLDEST`, 512) is shared with spectrum and screen JPEGs. A dropped `ctl` / `tx 0` / `err` is not repaired. Dropped chronos fail safe (radio silence-unkeys). Not changed: splitting that flow is a larger protocol-client change than this fix.
- Lease `tx 0` during `Requesting` still unkeys. The web page does not abort a requesting press on `tx 0`. Left as the more cautious behavior.
- Speaker-to-mic feedback and half-duplex are unchanged (DESIGN.md).
- This is a Jetpack Compose app. It was not rewritten.

## Issues log

| Problem | Cause | Change |
|---|---|---|
| Renewal failure left the carrier up to 3 s (or 180 s if the socket stayed half-open and the lease timer did not run) while the app showed Idle | `sendRelease = false`, and later paths trusted `keyed` | Send `key PTT 0`, remember `carrierMayBeUp`, retry, and teach link-loss / `reset` to look at `owesUnkey` |
| Lost TX frame waited on `TX_SILENCE_MS` even when the text socket could still take `trx:0,false;` | `sendUnkey = false` | Send the unkey and retry ~500 ms |
| Phone-mic TX ignored control loss, `tx_ok` clearing, panel drop, and panel `tx 0` | `onPanelEvent` only talked to the lease machine, and only when `mine` was false | Unkey TCI unless the holder is `tci`; fail-safe both paths on role and panel drop; `tx 0` only after `Keyed` |
| A session with operate but `tx_ok` 0 could still press PTT | Gesture checked `canOperate` only | `mayKey` includes `txAllowed` |
| DESIGN.md said chronos run on the OkHttp thread, and that a lost renewal is a release, while the renewal site skipped the up-command | Doc written ahead of the code | Doc updated to the code after the fix. Test count was 88 in the doc, 133 on the branch, 146 after these tests |
| Panel `ctl` / `tx` / `role` / `err` could be evicted by spectrum or screen frames | One 512-slot `DROP_OLDEST` flow carried both | `PanelEventBus`: text on an unlimited queue, pictures conflated to the latest, socket generation retires a dead socket's queue. Reconnect resync is `auth` + `panel 1` (`panel_fresh` only on the subscribe edge). See the section below |

## Panel lanes (after merge 5992031)

Problem: `OkHttpPanelClient` posted text and binary frames into one `MutableSharedFlow` (`extraBufferCapacity = 512`, `DROP_OLDEST`). A waterfall or a screen JPEG stream fills that when the collector is behind, and the oldest entry — often `ctl`, `tx 0`, `role`, or `err ptt released` — is the one that is dropped. The PTT machines then never see the line that should unkey.

What the firmware allows for a resync (`remote_server.c`):

- Every new WebSocket gets `hello`, `size`, `keys`, `role`, and `ctl` before any command.
- `auth` sends `role` and `ctl` again.
- `panel 1` sets `panel_fresh` only when `panel` was off. That pushes modes, bands, plan, and the full `state` line, including `tx`.
- A second `panel 1` on a socket that is already subscribed does nothing. There is no "send state" command. Toggling `panel 0` / `panel 1` would ask again and is not done: once pictures cannot evict text, a waterfall is not a gap in the control stream.

Change:

- `PanelEventBus` queues every text line on `Channel.UNLIMITED` and emits it in order. `MutableSharedFlow` was not given an enormous buffer: it allocates `replay + extraBufferCapacity` up front. The OkHttp thread only `trySend`s, so the reader does not block.
- Spectrum and screen use a one-deep replay (`DROP_OLDEST`). `RadioLink.collectFrames` is the only consumer of that lane.
- `openConnection` / `closeConnection` stamp a generation. `disconnect` retires it before cancelling the socket. Queued lines from the dead socket are skipped so a late `tx 0` cannot release the next press. Link-lost already fail-safed the press that was live.
- `textQueued`, `textHighWater`, and `textRejected` are atomics. Nothing is logged per frame.

Tests: a flood of spectrum and screen frames (well past 512) does not drop `ctl`, `tx 0`, `role`, `err`, or the text lines posted among the frames; the picture lane still holds only the latest frame; lines posted before any collector starts are kept; a retired socket's `tx` is not delivered on the next connection. `PanelCommands.reconnect` is `auth` (when a token exists) and `panel 1`.

## RF.Guru radio face (cursor/rfguru-radio-ui-96d1)

The operating UI was a waterfall page plus a controls page, with five letter tabs (W C F S L) and a theme that followed the phone's light/dark setting. The radio's own screen (theme.h RF.Guru column, button_bar.cpp F1–F5, the 800×480 main page, the walkie portrait, and the phone web panel) is one dark face. That is what this change draws.

What changed, and why:

- `RadioFace` is plain Kotlin (no Android). Frequency text, S-meter and TX-meter fractions, band label, band-plan colours, waterfall colour, spot hit-testing, filter nudge, softkey pages, and the "pending" catalog live there so unit tests can run on the JVM. The composables only paint what it returns.
- One radio screen replaces `WaterfallScreen` and `ControlsScreen`. Landscape is the 800×480 stack (status, frequency, spectrum, scale, scrolling waterfall, F1–F5). Portrait is the walkie rail plus the phone panel's sliders. PTT stays on screen in both. There is still one key path.
- `HoldToTalk` keeps the old gate: `mayKey = canOperate && txAllowed`, press and release in `finally`, and the release always calls `releasePtt`. The microphone switch is still `canOperate` only, and a refused `RECORD_AUDIO` leaves the radio microphone selected. `RadioViewModel.pressKey` still refuses a bare `key PTT`. The Screen tab's key list also drops `PTT` and `POWER`.
- Spectrum history is the last 48 frames, newest first. Every cluster spot in the span is drawn and tappable. A tap on the trace tunes `set freq` (the spot, when one is within slop). A swipe sends `knob main`, so the radio's own step moves the VFO.
- Softkey groups follow button_bar.cpp: GEN (VOL 1:2, VOL 2:2, MFK 1:3–3:3), APP 1:3–3:3, KEY 1:2–2:2 plus CW 1:2–2:2, DFN 1:3–3:3, DFL (one page, no page key), MEM 1:2–2:2. Tap steps forward in the group, hold steps back.
- Theme is forced: `#08090c` background, `#13161d` bars, `#262b36` borders, `#1b1f29` buttons, `#e5a823` / `#f2d886` gold, `#4f8bf5` markers, `#ff2020` VFO line. `OpenX6X00Theme` ignores the system dark flag. Connect, Pairing, FT8, Spots, Log, and Settings use the same colours. Tabs are stroke icons (Radio, Screen, FT8, Spots, Log) plus Pair, Setup, and Connect off the operating row.
- `Screen` asks for `screen 1` while it is composed and stops on dispose. Optional keys sit on the picture; knobs are main / vol / mfk.

Placeholders (dialog text `Pending firmware Panel support`, and the same list on Settings). These are firmware menus the Panel protocol does not carry. They are on the softkey pages and the status icons, not as a wall of text on the frequency strip:

- VFO: Split / TX shift
- Status icons and clock: clock, remaining time, Wi-Fi, Bluetooth, A2DP, CAT, GPS, DX cluster link
- DSP: DNF (and DNF freq / width / auto), NB level and width, NR level
- VFO shift and audio: RIT, XIT, COMP, IF shift, VOX, Monitor
- CW: keyer speed, volume, tone, mode, iambic, QSK, ratio, ZAP, CW peak, peak Q, trainer, decoder switch, tuner, decoder SNR, parse, CW keyboard
- Tools: SWR Scan, RX Recorder, Field Ops, GPS, Birdie Cal, Memories (sets 1–8), waterfall reference and mode, screen recorder
- Modes with no `set`: JS8, PSK, RTTY, Olivia, SSTV, WSPR, Navtex, WeFax. FT8 still opens the existing FT8 screen.

Wired `set` names, all reachable: freq, mode, agc, pre, att, nb, nr, sql, rfg, vol, pwr, step, zoom, band, vfo, filter, atu, lock. Plus `knob` and non-carrier `key`.

## What I found while compiling

- Kotlin will not resolve a package name after a member dot (`Modifier.something.androidx.compose...`). Those calls were rewritten as imports (`padding`, `clickable`, `RoundedCornerShape`, `remember`).
- `CompactSlider`'s trailing lambda was bound to `modifier` because `onChange` was not the last parameter. `onChange` is last now.
- `filterNudgeWillNotCrossItself` expected the sample pair `300–2700` when the inputs were `2650–2700`. A +50 Hz low-edge step would land on the high edge, so the pair stays `2650–2700`. The assertion matches that.

## Tests and what was not run

`ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest assembleDebug` on JDK 21, compileSdk 36:

- `testDebugUnitTest`: **162 tests, 0 failures, 0 errors** (150 existing plus 12 in `RadioFaceTest`).
- `assembleDebug`: **BUILD SUCCESSFUL**.

No emulator or device was available in this environment for the first pass. The crash that a `weight` inside an unbounded scroll would cause was checked in the source: portrait scroll is `Modifier.weight(1f).verticalScroll(...)` on a bounded parent column, and the only inner `weight` calls sit in a fixed-height row or a width-bounded row. That is not a substitute for a device pass.

## Device pass on 8fdae6a (1080×2400)

The emulator, connected to the simulated radio, showed six bugs. Cause and change:

1. **Connect dead-end.** `Open the radio` was drawn only when a token was already saved, and Pair / Radio chrome only exists on an operating route. A fresh install reached "Connected, view only" and could not leave Connect. Once `phase.online`, Connect shows both **Open the radio** and **Pair**. The radio opens view-only; pairing is the other button.
2. **Status bar through the title.** The portrait top bar was drawn at y = 0. It now applies `statusBarsPadding()`, so "OpenX6X00" sits under the clock.
3. **Portrait.** The scope was a fixed 300 dp inside a scrolling column, so F1–F5 ended as a sliver above PTT. The scope is now the weighted remainder and the softkeys stay on screen; the column does not scroll. The second "step 500" bar is gone; step stays next to LOCK. Scale labels are clamped inside the view and a label that would sit on the previous one is dropped. Waterfall bins are dB near −110; mapping them across −128..0 left noise and signals in one navy stop. `waterfallWindow` stretches the noise percentile across at least 36 dB, and a gamma below 1 opens that slice into the blue/cyan stops. The spectrum trace uses the same window.
4. **Landscape.** On a ~400 dp-tall phone the 78 dp meter left the scope with about the height of the scale row, and GEN sat on the band plan. Below 520 dp the meter row is 64 dp and PTT is 52 dp, and the scope is the only weighted child so it keeps the rest.
5. **Rotation banner.** `onStop` runs when the activity is recreated for a configuration change. That called `wentToBackground()` even though the rig lives on the application and the operator is still looking at it. `isChangingConfigurations` skips that call, so a live carrier is not unkeyed by turning the phone. Separately, an idle fail-safe published its reason through the machine listeners, which is why the red line appeared when PTT had never been down. Idle with nothing owed now notifies the listener with a null reason. A real background while keyed still unkeys and still says why.
6. **Screen tab.** The framebuffer card was `weight(1)`, so an 800×480 JPEG sat in a tall black box. The card is `aspectRatio(800/480)`. The key row was one line clipped at the edge ("MSG" → "MS"); the keys wrap, and the page scrolls if they take more than the screen.

`./gradlew testDebugUnitTest assembleDebug` after these fixes: **166 tests, 0 failures, 0 errors** (the previous 162, plus the waterfall window, the scale-label placement, and the idle versus keyed background cases). `assembleDebug`: BUILD SUCCESSFUL. The emulator was not available in this environment, so the six screens were not re-shot here.

## Second device pass (e4ee87b)

Portrait, Connect, the status-bar inset, F1–F5, the duplicate step, the waterfall, and rotation were confirmed on the emulator. Two leftovers:

- **Screen tab crash.** `FlowRow` was compiled to the `FlowRowOverflow` overload, which this Compose Foundation does not contain (`NoSuchMethodError` at `ScreenMirrorScreen`). The keys are packed into plain `Row`s by width (`packKeyRows`) so `MSG` stays whole and the tab does not call `FlowRow`.
- **Landscape zoom.** On a short scope the spectrum band was shorter than the Zoom chip, so `Zoom x1` covered the `14.150` tick. The scale now starts at or below that chip, and if the box cannot spare the height the last label is kept out of the chip's corner.
- **Landscape waterfall.** The header was two full-width rows and PTT was another full-width bar, so the waterfall was a thin strip. Landscape now uses one 52 dp header (pills, frequency, meter) and one 44 dp row (groups, F1–F5, PTT, mic). The scope takes the rest, and `scopeSplit` gives the waterfall about 73% of that pane. Portrait is unchanged.

`./gradlew testDebugUnitTest assembleDebug` after the Screen-tab and landscape rework: **168 tests, 0 failures, 0 errors**. `assembleDebug`: BUILD SUCCESSFUL.

## Display quality (bee8510 follow-up)

The native waterfall was one bitmap column per FFT bin, stretched to the phone. On a landscape screen that is about two pixels per bin, so it looked grainy and blocky next to the radio's own framebuffer. The colour window was also recomputed from scratch every frame, so the field shimmered.

- **Drawing.** Each waterfall row is now painted at the view's pixel width. A pixel samples the bins on either side of its frequency (the firmware's `row_pixels`), and a row is placed by the frequency it was taken at, so tuning slides the history instead of smearing it. The spectrum trace is the same interpolation, with a vertical gradient under the line. Colours are the firmware palettes: Classic and Gauss are the 256-entry tables in `wf_palettes.c`; Grey, Fire, Ice and Rainbow are the stops in `waterfall_view.c` run through `theme_ramp_palette`. Gauss is the default because the RF.Guru theme uses that table. A chip on the scope steps through them. The noise floor and the ceiling ease toward the newest row (`WaterfallLeveler`) instead of jumping.
- **What the socket can send.** `remote_server.c` always sends `WATERFALL_NFFT` bins, which is `RADIO_SAMPLES * 2` = 1024 int8 dB values, once per new DSP spectrum, and it skips a frame while 16 KB is still queued for that client. The comment there says "up to 15 a second". There is no Panel command for a larger FFT or a faster rate, so none was invented. The history depth is the waterfall's pixel height, capped at the firmware's 480 rows, instead of a fixed 48 that then got stretched.
- **Recomposition.** The radio screen no longer collects the spectrum flow. The scope does, and its canvas is on its own graphics layer, so a frame does not recompose the keys, the header or PTT. A drag still turns the main knob. A pinch steps the existing `zoom` set (fingers apart narrows the span).
- **Widgets.** The frequency is 7-segment digits. The S-meter arc has amber ticks and the labels S1, 3, 5, 7, 9, +20, +40. The landscape deck is 52 dp: shorter key titles (`TX pwr`, `RF`, `Vol`, `Sql`), padding so the value is not on the bottom edge, and a narrower PTT so the five keys have the width.
- **Screen tab.** It asks for `picture sharp` first. Four late gaps (not the once-a-second still-screen repeat, `STILL_MS`) fall back to `picture fast`. A tap, or the Full screen chip, opens the 800×480 framebuffer across the phone. Tap again to close.

PTT, the lease, `PanelEventBus` and pairing are unchanged.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **175 tests, 0 failures, 0 errors** (168, plus the palette, interpolation, level, screen-picture and dial tests). `assembleDebug`: BUILD SUCCESSFUL. No emulator in this environment, so the sharper waterfall was not re-shot here.

## Shot4 regressions (7edf2a6, 2400×1080 emulator)

Chris Fink (KD0CQ) approved a fix pass against the simulated radio. Five regressions, each fixed at the cause:

1. **Frequency.** `FreqPart`'s underline was `fillMaxWidth()` inside the digit `Row`, so the first group (`14`) consumed the row and `.100.000` never laid out. A scrap of a glyph sat against the USB chip. The 7-segment drawing is gone. The readout is tabular monospace (`tnum`) in gold, and `frequencyTextSp` sizes it from the slot (0.62 em advance, plus room for the +/− column, height capped so a 64 dp landscape header is not overflowed). The whole `14.100.000` stays on one line, at least the old 20 sp on a wide slot.
2. **Waterfall.** Rows were painted 1:1 from the top, so a pane taller than the rows received so far left the bottom at palette index 0 (near black). After ~25 s that was about half the landscape pane. `historyRowAt` stretches the rows that exist across every pixel, and returns to one row per pixel once the history is as tall as the pane. Interpolation and the firmware palettes are unchanged, so a full history stays sharp.
3. **Landscape deck.** The page key was labelled `VOL` / `1:2` beside `Vol` / `30`, which read as a second volume. In the compact row it is now `Page` / `1:2`. The group strip scrolled, so `DFL` clipped to `DF` and `MEM` sat past the edge. Landscape gives the six names equal weight with no scroll. PTT is 72 dp and the mic is the icon plus the switch, which leaves the five keys wide enough that `TX pwr` is not clipped. Portrait still says `VOL`.
4. **Screen chips.** One horizontally scrolling row clipped `Full screen` to `Full`. The chips are two rows: picture mode, then keys and Full screen.
5. **Full-screen mirror.** The dialog stayed in the activity's portrait orientation, so the 800×480 frame was letterboxed. While it is open the activity locks to sensor landscape and both windows hide the system bars. The lock and the orientation to restore live in `FullscreenLock`, because the orientation change recreates the activity and a `remember` would close the picture. On close, and only when this is not a configuration change, the previous orientation and the system bars come back. `MainActivity.onStop` already ignores configuration changes, so the lock does not unkey.

PTT, the lease, and `PanelEventBus` are unchanged.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **177 tests, 0 failures, 0 errors** (the two new ones are `frequencyDigitsFitTheSlotTheyAreGiven` and `shortHistoryFillsThePaneAndAFullHistoryIsOneRowPerPixel`). `assembleDebug`: BUILD SUCCESSFUL. No emulator in this environment, so these five were not re-shot here.

## 3D spectrum (after b249501)

Chris Fink (KD0CQ) asked for the AetherSDR ridge plot: the last few dozen spectra drawn as stacked lines, newest in front, height from the signal, colour from the level.

What I found:

- The scope already draws one 2D trace on its own graphics layer, with the same gestures (drag tunes, pinch zooms) and the waterfall bitmap underneath. There is no DataStore. The only preference file is the pairing token.
- A data-class point per sample would allocate thousands of objects a frame. The projection therefore returns floats into a scratch buffer, and the frame ring's slots are allocated once.

What changed:

- A `2D` / `3D` chip sits beside the palette chip. 2D is the default. The choice is written to SharedPreferences (`radio_face` / `spectrum_view`), the same mechanism as the pairing token, in its own file so forgetting a pairing does not clear it.
- 3D keeps 48 frames. Each is sampled with the 2D trace's interpolation and the same eased floor and ceiling, so it does not shimmer. Older ridges step up and inset; each is filled to its baseline in the pane's black (so the nearer ridge hides the one behind it) and then stroked blue-green → green → yellow → red. The waterfall palette stays on the waterfall. Gauss is a dark-blue table and would not match the reference ridges.
- The frequency scale, band plan, VFO line, passband, Zoom chip and palette chip are the same canvas and the same overlays. Drag and pinch are unchanged. The waterfall drawing is unchanged. In 3D the spectrum is given a little more of the pane (landscape waterfall share minus 0.12, portrait trace 55% of the room instead of 40%).
- PTT, the lease, and `PanelEventBus` were not touched. Closing full screen still leaves orientation alone.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **182 tests, 0 failures, 0 errors** (177, plus the five projection and ring tests in `SpectrumRidgeTest`). `assembleDebug`: BUILD SUCCESSFUL. No emulator here, so the ridges were not re-shot.

## Solid 3D surface (after 215bf36)

The emulator shot of `215bf36` is a thin teal mesh with the red VFO line through it. The cause is the fill: each ridge was painted black and only the crest was stroked, so the phone drew a wireframe. AetherSDR and the Yaesu FT-710 3DSS (the manual's "3-Dimension Spectrum Stream": X frequency, Y strength, Z time) are solid surfaces.

What changed, and the choices:

- Each column is now an opaque wall from that row's baseline up to the signal, coloured by level. Columns of one colour share a path (at most eight fills a row). No crest stroke: a stroke on the fill outline is what made the mesh. Nearer rows are drawn last, so they hide the rows behind them.
- Farther rows are darker (`fogArgb`, gain down to about 0.38) and stay opaque. Transparency would let the back rows show through and bring the mesh back.
- The trapezoid is stronger: inset 0.38, rise 0.66, amplitude 0.78, distance shrink 0.28. A carrier stays a ridge toward the back. Depth is 64 frames, 96 samples across.
- The noise floor is the eased floor already subtracted, then a soft knee (`KNEE` 0.36). Below the knee the level squares down into a sheet about a fifth of full scale, so the quiet band is a smooth lawn. A 3-tap spatial pass flattens that sheet and keeps a sample that stands above both neighbours. A light blend with the older row (same span only) drops one-frame flicker. Both passes are 3D only.
- Faint frequency lines run to the vanishing point, and a time line sits on the floor every sixteenth row.
- Colour stays off the waterfall palette. The default is the AetherSDR lawn (deep teal, green, yellow, orange, red). A long-press on the scope chip switches to the FT-710 cyan-to-white stream and is stored next to the 2D/3D choice. The chip then reads `FT`. Tap still cycles 2D and 3D. 2D is still the default.
- The red line in the shot is the VFO, drawn after the surface, and it stays. So do the scale, the band plan, the passband, drag-to-tune, and pinch-zoom. PTT, the lease, and `PanelEventBus` were not touched.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **186 tests, 0 failures, 0 errors** (`SpectrumRidgeTest` is 9: the knee, the peak-preserving smooth, the fog, and the FT-710 ramp). `assembleDebug`: BUILD SUCCESSFUL. No emulator here, so the solid surface was not re-shot.

## Scope layout (spectrum by itself)

Chris Fink (KD0CQ) wants the 3D surface, or the 2D trace, to be able to fill the scope with no waterfall under it.

The split always reserved a waterfall band, so the ridges could not use that height. `ScopeLayout` is now `both` (default), `spectrum`, or `waterfall`. It is stored in the same `radio_face` preferences as the 2D/3D choice (`spectrum_layout`). A short chip in the existing row cycles `S+W`, `Spec`, and `WF`, so it does not open a menu over the plot.

Spectrum only gives the trace or the ridges the room above the frequency scale and the band plan. The waterfall pixels are 0. Waterfall only does the reverse. The scale, the band plan, the VFO line, the passband, drag-to-tune, and pinch-zoom stay in every layout.

A taller spectrum pane does not blow the same 64 ridges up. `rowsForHeight` stays at 64 while the pane is the short combined strip, and climbs toward 128 (one row per about 3.5 px) when the spectrum has the whole scope. `amplitudeFor` keeps the peak fraction near 0.78 and adds at most 0.10, so the crest is taller in pixels and most of the new height is extra rows. The ring is allocated at 128 once.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **188 tests, 0 failures, 0 errors** (`scopeLayoutGivesTheWholeRoomToOnePane`, `aFullScopePaneAddsRowsAndRaisesThePeak`). `assembleDebug`: BUILD SUCCESSFUL. No emulator here, so the full-scope 3D view was not re-shot.

## Background running (on by default)

Chris Fink (KD0CQ) wants the Panel socket, the TCI socket and the receiver to keep going with the screen off. What the phone did before: `RadioConnectionService` was already a `connectedDevice` foreground service, and it did not drop the sockets when the activity stopped. `MainActivity.onStop` always called `wentToBackground()`, which unkeys and, if a carrier was up, paints "this app went to the background".

What changed:

- `Run in background` is a switch on Setup, stored as `run_in_background` in the `radio_face` preferences. Missing means on.
- On: leaving the screen releases an on-screen PTT with `release()` (up-command, no banner). The microphone stops because that is the transmit machine's release. The 3 second lease is untouched and still the radio's backstop if the write does not land. Nothing keys from the background. The scope stops collecting frames. If the screen tab had asked for the mirror, the radio is sent `screen 0`, and `screen 1` again when the activity returns. The service takes a partial wake lock and a high-performance Wi-Fi lock only while it is in that background state and a socket is online. The notification shows frequency, mode and the S-meter caption, with Open and Stop. A killed process reconnects to the remembered host. Stop forgets the host.
- Off: the old fail-safe (banner if a carrier was up) and the service stops, which closes both sockets. That is a disconnect. The previous onStop left the sockets up.
- The service type is `connectedDevice`, plus `mediaPlayback` only while receiver audio is playing. Android 15 stops a media-playback foreground service that is not playing. There is no microphone type.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **196 tests, 0 failures, 0 errors** (`BackgroundRunTest`, 8). `assembleDebug`: BUILD SUCCESSFUL. No emulator here, so background audio was not heard on a device. Android can still freeze the process under battery optimization; Setup opens the system list and does not request the exemption itself.

## 3D lawn, after the 77ec5cf emulator check

Images of `77ec5cf`: the carrier at 14.100 is a white line in the waterfall and is absent from the 3D surface. The surface is a dark flat sheet, and a band of black sits above the vanishing point.

Causes:

- A ridge was 96 samples across 1024 bins. The sample was the bin at the column centre, so a one-bin carrier between two centres was never read.
- `shapeLevel` squared everything below 0.36 of the window into a sheet. On a 36 dB window that is the first 13 dB, which is where a real signal lives.
- The floor colour was `#072E36`. Fog of 0.62 took the back row down to about 38% of that, which reads as black.
- Rise 0.66 left the oldest baseline a third of the way down the pane. Inset 0.38 pulled the sides in until the vanishing point sat in that black.

What changed, 3D only. The waterfall window, PTT, the lease, `PanelEventBus`, background mode, and the layout preference are the same.

- Each column keeps the loudest bin in its slice (`columnPeakDb`). 256 columns, so the carrier is a narrow ridge.
- `RidgeRange` is the 3D scale. The floor eases to the quiet end. The ceiling eases up quickly to the loud end and down slowly, and a quiet band stays at least 12 dB wide so the grass has texture. It does not use the waterfall's window.
- The first 8 dB above the floor are drawn in proportion (`GRASS_HEIGHT` 0.22). Past that the level runs to full scale at the ceiling. Spatial and temporal smoothing touch only that grass. A column above it is copied.
- The lawn colours start at a bright green-cyan and go yellow, orange, red. The FT-710 style starts at a bright cyan and goes to white. Fog is 0.50, so the oldest row is half as bright and still a colour.
- Inset is 0.10 and rise is 0.92. The oldest baseline is near the top of the pane. The sides meet above the pane.
- No second-finger gain. Pinch already uses two fingers, and a drag tunes. The auto-range is the vertical control.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **197 tests, 0 failures, 0 errors**. `aCarrierThirtyDbAboveTheFloorReachesMostOfThePane` requires a +30 dB bin to rise at least 60% of the pane on the first range update. No emulator here, so this look was not re-shot.

## 3D comb, after the 6a40873 emulator check

Images of `6a40873`: the carrier stands up as a red ridge and the back edge fills the pane. Both of those stay. The rest overshot. Each column was an opaque wall in a bright green-cyan ramp, the noise band was 22% of full scale, and a tall pane added rows until they touched. That reads as a lime carpet with teal holes. AetherSDR is a comb: many thin strokes, a dark floor, and the colour on the strokes and the tips of the noise.

What changed, 3D drawing only. PTT, the lease, `PanelEventBus`, background mode, and the layout preference are the same. The waterfall window is the same. The carrier test is the same.

- Each row is a dark veil (the background, about 90% opaque, a slight teal or navy tint) and then a 1.25 dp stroke coloured along the line. The veil is what a nearer row uses to hide the one behind it. The stroke is the picture.
- The noise band is the first 6 dB, drawn at 10% of a full-scale peak (`GRASS_HEIGHT` 0.10). On the default range that is about a tenth of the pane, under the new 15% ceiling. Peak-hold per column stays, so a one-bin carrier is still a ridge.
- Colour runs teal-green, green, yellow, orange-red. The FT-710 style runs dark cyan to white. The lime and chartreuse stops are gone.
- Fog is 0.65, and it follows the same perspective as the row position. The front stroke is full colour. The oldest keeps about 35%.
- Row spacing uses `depthT` (`u` to the 0.70). The front gaps are wider than the back gaps. Both panes draw 64 rows. The earlier "a tall pane adds rows up to 128" behaviour is what fused the comb; the ring still holds 128 and the draw ignores the extra slots.
- Smoothing still touches only the noise. A column more than 6 dB above the floor is copied, so a carrier does not boil.
- The canvas paints a short black fade under the top chips. It is inside the canvas, so drag and pinch still hit the plot.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **198 tests, 0 failures, 0 errors**. `theFloorSitsLowInThePane` requires the top of the noise, at the default, to stay at or under 15% of the pane. `aTallPaneKeepsSixtyFourRowsAndOpensTheFrontGaps` replaces the test that asked a tall pane for more than 64 rows. No emulator here, so this comb was not re-shot.

## Calmer 3D comb, after the 976ab5d emulator check

The emulator of `976ab5d` showed the right structure: a dark floor, separate strokes, and a tall carrier. Two things were still wrong. The strokes were wavy and teal, and about 39% of the pane changed between frames 0.4 s apart, against about 10% on the earlier surface.

The cause of the boil is that every draw rebuilt every row. `drawRidgePlot` read the raw bins, shaped them with the floor of that moment, ran the spatial pass, and blended each row with the row behind it. The floor glides, and the neighbour changes when a row scrolls, so a row that had already left the front was a different shape on the next frame. The history crawled.

What changed, 3D only. PTT, the lease, `PanelEventBus`, background mode, the layout preference, the carrier test, and the floor-height ceiling are the same.

- `push` bakes the levels once: shape with the floor and ceiling of that frame, a light 3-tap on the noise, then the mean of this frame and up to three earlier noise frames on the same span. A bin more than 6 dB above the floor is copied, so a carrier is immediate. Draw only places those levels. The next push does not write the slot again.
- The noise top is 8% of a full-scale peak, down from 10%. Columns are 512, so a full-span column is two bins and the floor is fine blades.
- Aether strokes start at `#3ccf5a`, rise through `#6fe36a` and yellow, and peak orange-red. The FT style is still cyan to white.
- `DEPTH_CURVE` is 0.80, so the front gaps are a little tighter and the back is still closer.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **199 tests, 0 failures, 0 errors**. `aRowIsFrozenOnceItLeavesTheFront` keeps a copy of the front row and requires the same samples two frames later, including after the floor has moved. No emulator here, so this pass was not re-shot.

## QRZ logger

`remote_server.c` writes a QSO with `log` / `logq ID CALL|RSTS|RSTR|NAME|QTH|GRID|COUNTRY` and answers `logged ID ok|no`. That line has no time column, so the time is only in the local Room table. QRZ is the public XML API. The username and password go in EncryptedSharedPreferences and are not logged. A session error or a call with no name and no grid is shown as a limited record. Distance and bearing use the operator's grid. The map is a canvas, with no tile server. ADIF leaves through the share sheet.

`./gradlew testDebugUnitTest assembleDebug` after this feature: the logger tests in `LoggerTest` (XML, distance, bearing, ADIF, worked-before). Assemble succeeded.

## Phone location and clock

`remote_server.c` has no `pos` and no `clk`. Both are answered `err unknown command`, so Setup stores the toggles and does not write the socket. The words are `pos LAT LON [ACC]` and `clk` / `clk?`. A phone fix more than 100 m from the last one updates the header grid. The radio's own grid is not reported by the protocol.

`./gradlew testDebugUnitTest assembleDebug` after this feature includes `StationSyncTest`. Assemble succeeded.

## FT8 polish and JS8

FT8 already had `ft8 reply ID`. The list now marks CQ, a call to the operator, and a station in the local log, and it shows the seconds left in the slot. Transmit stays that reply command, with a TX mark when the radio says it is sending.

`remote_server.c` has no `js8`, `js8r` or `js8 wf`. The JS8 screen parses those lines and builds the commands, and it does not send them. Heartbeat, CQ and compose say the firmware does not have them yet.

`./gradlew testDebugUnitTest assembleDebug` after this feature includes `DigitalTest`. Assemble succeeded.

## Spot labels and the hunter

Callsign labels were already drawn on the scope. Overlapping ones are now dropped, newest first, so a busy slice stays readable. A tap still tunes. The hunter list is exact calls plus prefixes that end in `*`, and the radio's own `alert` flag. A match raises one notification, including in the background, at most once per 30 seconds and once per call per 10 minutes. The first list after connect is the history the radio already held, so it does not buzz for that.

`./gradlew testDebugUnitTest assembleDebug` after this feature includes `HunterTest`. Assemble succeeded.
