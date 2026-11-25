# Reference — audio PTT review (revolta/audio-ptt-core)

Working notes for the review of `revolta/audio-ptt-core` at `f8f8b0c` (base `main` `2098e0b`) and the fixes on `cursor/explicit-ptt-unkey-abc7`. Owner: KD0CQ (theMadCatter). Firmware compared: Guru-RF/OpenX6X00 sources `remote_server.c`, `tci_server.c`, `tci_audio.h`, `control_owner.c`, `radio_session.js` (tree fetched for this review, not the app's DESIGN.md).

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

KD0CQ approved a fix pass against the simulated radio. Five regressions, each fixed at the cause:

1. **Frequency.** `FreqPart`'s underline was `fillMaxWidth()` inside the digit `Row`, so the first group (`14`) consumed the row and `.100.000` never laid out. A scrap of a glyph sat against the USB chip. The 7-segment drawing is gone. The readout is tabular monospace (`tnum`) in gold, and `frequencyTextSp` sizes it from the slot (0.62 em advance, plus room for the +/− column, height capped so a 64 dp landscape header is not overflowed). The whole `14.100.000` stays on one line, at least the old 20 sp on a wide slot.
2. **Waterfall.** Rows were painted 1:1 from the top, so a pane taller than the rows received so far left the bottom at palette index 0 (near black). After ~25 s that was about half the landscape pane. `historyRowAt` stretches the rows that exist across every pixel, and returns to one row per pixel once the history is as tall as the pane. Interpolation and the firmware palettes are unchanged, so a full history stays sharp.
3. **Landscape deck.** The page key was labelled `VOL` / `1:2` beside `Vol` / `30`, which read as a second volume. In the compact row it is now `Page` / `1:2`. The group strip scrolled, so `DFL` clipped to `DF` and `MEM` sat past the edge. Landscape gives the six names equal weight with no scroll. PTT is 72 dp and the mic is the icon plus the switch, which leaves the five keys wide enough that `TX pwr` is not clipped. Portrait still says `VOL`.
4. **Screen chips.** One horizontally scrolling row clipped `Full screen` to `Full`. The chips are two rows: picture mode, then keys and Full screen.
5. **Full-screen mirror.** The dialog stayed in the activity's portrait orientation, so the 800×480 frame was letterboxed. While it is open the activity locks to sensor landscape and both windows hide the system bars. The lock and the orientation to restore live in `FullscreenLock`, because the orientation change recreates the activity and a `remember` would close the picture. On close, and only when this is not a configuration change, the previous orientation and the system bars come back. `MainActivity.onStop` already ignores configuration changes, so the lock does not unkey.

PTT, the lease, and `PanelEventBus` are unchanged.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **177 tests, 0 failures, 0 errors** (the two new ones are `frequencyDigitsFitTheSlotTheyAreGiven` and `shortHistoryFillsThePaneAndAFullHistoryIsOneRowPerPixel`). `assembleDebug`: BUILD SUCCESSFUL. No emulator in this environment, so these five were not re-shot here.

## 3D spectrum (after b249501)

KD0CQ asked for the AetherSDR ridge plot: the last few dozen spectra drawn as stacked lines, newest in front, height from the signal, colour from the level.

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

KD0CQ wants the 3D surface, or the 2D trace, to be able to fill the scope with no waterfall under it.

The split always reserved a waterfall band, so the ridges could not use that height. `ScopeLayout` is now `both` (default), `spectrum`, or `waterfall`. It is stored in the same `radio_face` preferences as the 2D/3D choice (`spectrum_layout`). A short chip in the existing row cycles `S+W`, `Spec`, and `WF`, so it does not open a menu over the plot.

Spectrum only gives the trace or the ridges the room above the frequency scale and the band plan. The waterfall pixels are 0. Waterfall only does the reverse. The scale, the band plan, the VFO line, the passband, drag-to-tune, and pinch-zoom stay in every layout.

A taller spectrum pane does not blow the same 64 ridges up. `rowsForHeight` stays at 64 while the pane is the short combined strip, and climbs toward 128 (one row per about 3.5 px) when the spectrum has the whole scope. `amplitudeFor` keeps the peak fraction near 0.78 and adds at most 0.10, so the crest is taller in pixels and most of the new height is extra rows. The ring is allocated at 128 once.

`./gradlew testDebugUnitTest assembleDebug` after this pass: **188 tests, 0 failures, 0 errors** (`scopeLayoutGivesTheWholeRoomToOnePane`, `aFullScopePaneAddsRowsAndRaisesThePeak`). `assembleDebug`: BUILD SUCCESSFUL. No emulator here, so the full-scope 3D view was not re-shot.

## Background running (on by default)

KD0CQ wants the Panel socket, the TCI socket and the receiver to keep going with the screen off. What the phone did before: `RadioConnectionService` was already a `connectedDevice` foreground service, and it did not drop the sockets when the activity stopped. `MainActivity.onStop` always called `wentToBackground()`, which unkeys and, if a carrier was up, paints "this app went to the background".

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

## Aether grass field, after the shot10 check of b6011d4

Shot10 of `b6011d4` was warmer, and it was still a comb: wavy crest lines with a dark gap between them. AetherSDR is a dense field of short vertical blades, with no floor showing between the rows.

What changed is the Aether draw only. The FT-710 comb, the bake-once history, the carrier peak-hold, and the six features are the same.

- Aether draws all 128 ring slots at an even pitch. A noise blade is several times that pitch, and a translucent green sheet sits under the blades, so the dark floor does not show through.
- Each column is a vertical blade. The shaft is grass green. The top 40% uses the existing green-yellow-red ramp, so red is only a strong signal. There is no crest polyline in this style.
- The FT-710 path is still the dark veil and the cyan-to-white stroke, at 64 rows. Its grid stays. The Aether field does not draw that floor grid.
- Rows are still baked in `push`. Draw only places them. A Stroke is created once per frame, not once per row.

`aetherGrassIsADenseFieldOfBlades` requires the noise blade to cover more than four front pitches, the sheet to be translucent green, and a mid level to stay off red. `./gradlew testDebugUnitTest assembleDebug` after this pass: **210 tests, 0 failures, 0 errors**. No emulator here, so this pass was not re-shot.

## Tall spectrum pane, after the ab8ef51 check

Landscape spectrum-only left a bright lawn on the bottom and a dark void above it. The horizon was 0.92 and the far rows faded by 0.65, so on a tall pane the upper half went black. The Aether horizon is now 0.96 for every aspect, and the grass fade is 0.22 so the field stays green up to that horizon. The FT comb still uses 0.92 and 0.65. The carrier fraction still follows the same amplitude cap.

`theGrassFieldFillsWideAndTallPanesAndTheCarrierStaysProportional` checks a wide tall pane, a short strip, a portrait pane, and a square. The field covers at least 90% of each, and the carrier fractions stay within 0.12 of one another.

## 3D history across rotation

The ring lived in `remember` inside the spectrum pane. Portrait and landscape are different branches, so a rotation disposed the pane and allocated an empty ring. Draw still projected ages against all 128 rows, which parks a short history in a thin band at the bottom until the radio sends another full field. `RidgeHold` is on the view model. The pane projects that same ring into the new size. A repeated sequence does not scroll the rows. `theRidgeHistoryStaysFullWhenThePaneComesBack` fills the ring, collects the current frame again, and requires the oldest bake and the count to still be there.

The noise-blade grain is 0.90 of the blade instead of half of that. A signal is still unmoved. The shortest noise blade still covers more than four front pitches.

## Spectrum view across rotation

Portrait and landscape each kept the 2D / 3D chip in `rememberSaveable`. Those are different places in the tree, so the landscape pane restored the 2D it first composed, while `radio_face` `spectrum_view` already held `3d`. The chip then read 2D on a spectrum-only landscape face even though portrait was FT 3D. The layout preference could still say spectrum, which is why the waterfall was gone and the trace was 2D.

`SpectrumMemory` is the one choice. `SpectrumChoices` loads it from the three preference strings before the first frame and on configuration change. The layout chip writes only the layout. `theSelectedViewSurvivesRotationAndEveryLayout` reads the stored strings into a new memory and walks every layout without the view leaving FT 3D.

## Rotation layout

The side rail and the bottom bar were two navigation hosts, and the rail was chosen from `Configuration.orientation`. That flag changes before the window size does. On the way from landscape to portrait the new host was the portrait scaffold, while the face still measured a wide pane and kept the landscape deck. The keys ended up under the band plan, and the bottom bar and PTT were not on screen until the window finished resizing. There is one host now. `RadioFace.wideWindow` is the window width against its height, published as `LocalWideWindow`, and both the chrome and the face read it. A zero or unfinished size stays on the portrait face. `theWindowAspectPicksTheOperatingFace` covers a wide pane, a tall pane, a square, and an unfinished size.

## Frequency digit mark

Tapping a digit only swapped the gold glyph for the body colour. On the sunlight face both are black, so the chosen place disappeared, and at night the two colours are close. The chosen digit now sits on a gold chip with the bar colour for the glyph, and a 3 dp bar under that glyph only. The bar width is 0.62 em, the same advance the readout fitter uses, so it cannot take the row the way `fillMaxWidth` did. The selection is not cleared by a drag or a volume-key step. The line budget gives up 4 dp so the mark fits the header.

## QRZ limited flag

`QrzParse.limited` was true for any error, including the local "username and password are not set" result that has no callsign. The Log screen then added "QRZ did not include the full record" under the real error. `limited` is now true only when a callsign arrived with a blank name, grid, and QTH. A subscription reply that has the call and nothing else is still limited. `missingCredentialsIsTheErrorNotALimitedRecord` covers the empty-credentials path.

## Blade texture

Noise blades were one height, so the field looked flat. Each column now adds a fixed fraction of its own level, at most a quarter of that blade. A level above the grass is not moved, so the carrier stays a clean peak and a scrolled row does not change shape. The shortened blade is still several times the row pitch, so the floor stays covered.

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

## Field display

Setup can switch the chrome to a sunlight face: white page, black text, light system bars. The spectrum palettes stay the firmware colours, because those are signal level. The night face is still what the app starts in.

A tap selects one frequency digit. Drag, the volume keys, or +/− step that digit by its place (1 Hz through 10 MHz). A long-press opens a keypad and the result is still `set freq`. With no digit selected, +/− keeps stepping the MHz, kHz, or Hz group.

The S-meter can be the existing arc, a classic needle, an LED bar, or a lamp with a peak that decays. Transmit still shows power, SWR, and ALC.

`./gradlew testDebugUnitTest assembleDebug` after this feature includes `FieldDisplayTest`. Assemble succeeded.

## Notification quick keys and alerts

The background shade keeps Open and Stop, and adds a compact row: Band up/down, Mode, Vol +/−, and ATT/PRE/NB/NR. Mode, band, and the toggles are `set`. Volume is `knob vol`, which is the encoder the protocol already has. PTT is refused in the quick-key table and never appears on the shade.

A separate alert fires when `bat` is at or under the Setup percent, and when the `t` meter SWR is at or over the Setup ratio while the radio is transmitting. The default ratio is 2.5:1. Each alert waits 60 seconds before it can fire again.

`./gradlew testDebugUnitTest assembleDebug` after this feature includes `QuickKeysTest`. Assemble succeeded.

## What this pass found

`pos`, `clk`, and `js8` are not in `remote_server.c`. Those screens store the choice and do not write the socket. Spot labels, `log`/`logq`, `ft8 reply`, `set`/`key`/`knob`, `bat`, the `t` meter, and `spots` are real. The sunlight face and the meter styles are paint. Digit entry still ends in `set freq`.

## Spectrum and waterfall matched to the radio

Firmware studied: Guru-RF/OpenX6X00 `main` at `69f950291bafe8bbefad3e599d2d37abc5c28f3c` (2026-11-21). Release `v0.3.13` is 31 commits behind that, and `spectrum_view.c`, `waterfall_view.c`, and `dsp.cpp` are among the files that moved, so the match is to main, not to the tag. Drawing lives in those three plus `dsp_math.h`, `wf_palettes.c`, `theme.h`, `theme.c`, `freq_scale.c`, `gui_styles.c`, `smeter.h`, and the defaults in `settings_manager.h` / `params.c`.

What the RF.Guru defaults actually draw, and what the phone does now:

- Spectrum fill is on, the gradient and the fill-by-level are off, and the fill colour is the theme line `#94A3B8`. The trace is solid columns of that colour from the line to the bottom of the spectrum, one sample per screen pixel. The old white gradient and 1.5 px white stroke are gone.
- Peaks are on: 5 s hold, then 0.5 dB down per frame, colour `#475569`, drawn under the fill so only the part still above the trace shows. A tune slides them with the view. Transmit freezes them. The vertical scale in transmit is S4..S9+20.
- The spectrum the DSP smooths (0.6 up, 0.2 down) is a separate FFT, `SPECTRUM_NFFT` = the panel width. The socket sends the waterfall's 1024 int8 bins. Those bins get the same two coefficients, and they slide by whole bins when the centre moves.
- Colour window, shared by the 2D waterfall and the 2D trace: noise is the 25th percentile of 8-bin power groups, 4% dropped at each end; a tracker takes the first reading whole, follows quickly for a second, then rises with a 4 s constant and falls with a 2 s constant. Black starts 5 dB under that floor. The ramp is 48 dB. The floor is clamped to S_MIN..S8. A tune farther than the span resets the tracker. Gauss stays the default palette; the tables were already the firmware's.
- Passband: screen background `#08090C` at LV_OPA_50 over the spectrum, VFO red `#FF2020` at LV_OPA_20 over the waterfall. The VFO marker is 3 px in that red. The spectrum hides it in CW and CW-R. The waterfall keeps it.
- Frequency ticks already included both edges. Each interior tick now continues as a 4-on/4-off dash down the waterfall, in the text colour at LV_OPA_40.
- 3D Aether and FT-710 keep their height and their own colours. Their floor uses the same grouped noise estimate, so one carrier does not lift the lawn. The radio's 3D range is 40 dB and its default 3D colours are blue/green/yellow/red; using either would flatten the carrier the grass tests require and would replace the two styles. The history ring, the layouts, and the spot labels are unchanged.

`./gradlew testDebugUnitTest assembleDebug` after this: 221 tests, 0 failures. Assemble succeeded. This was not re-shot on a device.

Not on the wire, so not drawn: the separate spectrum FFT, waterfall speeds other than the default 15 lines/s, the Fixed-mode dimmed side trace, the notch, the RTTY/NAVTEX/WEFAX tone lines, a red marker while the recorder is on, and the DRM-plane Wu plot (Compose's stroke is the antialiasing). Auto level is the default; the manual −121..−73 grid is not sent as its own scale.

## Live check of a84e1f1

What already matched the radio, and stayed: the waterfall contrast (dark floor, signals pop), the solid fill, the marker and the passband, the 3D field, the spot labels, and the view surviving rotation.

What the live set showed, and the cause of each:

1. **Landscape 2D spectrum-only was a short ribbon with empty pane around it.** `scopeSplit` already gives spectrum-only the whole room above the scale and the plan, in both aspects. That is the same room the 3D field fills. The 2D trace was still on the waterfall's 48 dB colour window. The radio's spectrum widget is about 76 px (`p_spectrum_height`), so that window fills it. The phone's spectrum-only pane is the whole scope, and the same window left the trace on the floor. Spectrum-only now scales from the tracked noise (not the colour window's S8 clamp) to the loudest smoothed bin: the peak sits at 94% of the pane and the noise at 12%, with at least 12 dB of span so a quiet band does not become a solid block. The combined view keeps the 48 dB window. Transmit keeps S4..S9+20. Portrait spectrum-only uses that same full room, which is the scope column left after the keys, the meter and the sliders, not the 40% combined split and not past the column.

2. **The frequency dashes were invisible on a live Gauss waterfall.** `freq_scale.c` draws them in `TC(text)` `#F1F5F9` at `LV_OPA_40` (102), a 4 px dash and a 4 px gap, 1 px wide, down the waterfall only. The phone had that colour and that opacity, and a 1 px stroke. One phone pixel is antialiased away on Gauss, and the passband shade was drawn over the line. The stroke is now 1 dp, the pattern is 4 dp on and 4 dp off, the dashes are drawn after the shade, and the colour and the opacity are unchanged. They still stop at the waterfall. They do not cross the spectrum.

3. **A gold decoder line and the green SSB plan ate the bottom of every layout.** The line was the CW decoder (`T T T GG …`), painted on an 18 dp black bar across the bottom of the scope canvas, on top of the waterfall and the plan. The plan was 14 dp, and on a phone span it is often one green SSB segment. The decoder line is no longer painted on any scope layout. `RadioLink.cwLines` still keeps the text. The plan is 8 dp, with the label centred in that strip, so the colour stays and the waterfall gets the height back. The filter passband (the vertical shade) is unchanged.

4. **Landscape S+W put the right-hand frequency label under Zoom.** The inset ran only when `specH + 2 < zoomGuard`. The split already lifts that trace up to the guard, so the test was false and the label used the full width. The reserve now runs when the spectrum is no taller than the guard plus the scale, which is the landscape combined layout. A taller trace (portrait, or spectrum on its own) still uses the full width, because the scale is then below the chip.

`./gradlew testDebugUnitTest assembleDebug` after this: 225 tests, 0 failures. Assemble succeeded. This was not re-shot on a device.

## Waterfall resolution

What was limiting the picture, all of it on the phone. The socket was already delivering every bin it has.

- Frequency. `WaterfallDisplay.colorAt` called `sampleBin`, which linearly blends the two bins beside a pixel's centre. On a pane wider than 1024 bins each bin became a ramp. On a pane narrower than 1024 the sample at the centre dropped any bin that did not land there, so a one-bin carrier could vanish. Zoom spread the same bins across the width, so the ramp got wider.
- Time. `historyRowAt` stretched the lines received so far down the bitmap, so one line occupied several pixels while the history filled. `MAX_ROWS` was 480, the radio's screen buffer (`MAX_HEIGHT` / `BOARD_DISP_H`). The bitmap was already drawn 1:1, so a taller pane was capped at 480 lines and the rest of the pane was empty.
- Density. `Bitmap.createBitmap` stamps the display density. A draw that honours density turns one stored pixel into density/160 screen pixels. The canvas draw also left the destination size implicit.
- Colour. The palettes were already 256 entries (Gauss and Classic from `wf_palettes.c`; Grey, Fire, Ice and Rainbow from `theme_ramp_palette` with steps 0). `paletteIndex` truncates `(db - floor) / span * 255`. Over the 48 dB window that is about five table steps per decibel, and the truncation is under one step. The firmware dithers only the 1-bit mono theme (`wf_pixel` / `mono_dither`). Dither was not added. The bitmap stays `ARGB_8888`; `RGB_565` would crush the table.
- Averaging on the phone. The blend and the vertical stretch were the smear. The spectrum trace's rise 0.6 / fall 0.2 is the 2D line (`FirmwareLook.levelAt` still uses `sampleBin`) and was left. `WaterfallLeveler` still recolours every row from the live window; that is the radio's colour window and it stays. Nothing bakes a colour into a row.

What changed:

- `crispDb`. A pixel that covers one bin or less copies the bin under its centre, so a magnified bin is a flat run. A pixel that covers more than one bin keeps the loudest, so a carrier between centres survives. A pixel outside the row is the floor colour.
- `historyRowAt` returns the pixel's own line, or −1 once the lines run out. A short history is floor colour below the lines received, not a stretched copy. `MAX_ROWS` is 4096. `SpectrumHistory` is still resized to the waterfall's pixel height, so a taller pane keeps more time, up to that cap.
- The sheet bitmap is `ARGB_8888` with `Bitmap.DENSITY_NONE`. `drawImage` uses that bitmap's pixel size and `FilterQuality.None`.
- The view is the frame's own `widthHz`. A base with `HW_CAP_ZOOM` already puts the zoomed span in that field (`wf_span()` = `SPAN_HZ / zoom`, `SPAN_HZ` = 100000), still 1024 bins. Dividing by `state.zoom` again would zoom those bases twice. A base without the cap does not decimate the waterfall IQ (`feed` pushes the full-rate samples into the waterfall; the local decimator is only on the spectrum FFT) and the snapshot width stays 100 kHz. The radio's zoomed picture on that base is a display crop in `row_pixels` / `shown_span`. The phone shows the frame's span and does not crop a second time.

Before and after, for 1024 incoming bins:

| | Before | After |
|---|---|---|
| ~2400 px wide (landscape) | ~2.3 px/bin, each bin a ramp into the next | ~2.3 px/bin, each bin a flat run of 2 or 3 px |
| ~1080 px wide | ~1.05 px/bin, blended | ~1.05 px/bin, nearest; a pixel that covers two bins keeps the louder |
| under 1024 px | the centre sample drops bins | max-hold of the bins in the pixel |
| rows | cap 480 (~32 s at 15 fps); stretched while filling | the pane's pixel height, cap 4096. A 900 px waterfall is 900 lines (~60 s at 15 fps) once filled; the rest is floor colour until then |
| zoom 8, `HW_CAP_ZOOM` | 12.5 kHz span, ~12.2 Hz/bin, blended across the pane | the same bins, flat |
| colour | 256-entry LUT, `ARGB_8888` | the same. No dither |

`./gradlew testDebugUnitTest assembleDebug` after this: 227 tests, 0 failures. Assemble succeeded. This was not re-shot on a device.

## Firmware findings (waterfall bins and rate)

Guru-RF/OpenX6X00 `main` at `9c11903215b7c0794cd6d263d917cdf24d970524`. No upstream pull request and no issue. The Panel socket cannot be asked for more bins, wider samples, or a higher rate, so the app does not send a new command.

`src/radio.h` `#define RADIO_SAMPLES (512)`. `src/dsp.h` `#define WATERFALL_NFFT (RADIO_SAMPLES * 2)`, so 1024. `SPECTRUM_NFFT` is `BOARD_DISP_W` and is the on-screen spectrum FFT, not the socket.

`dsp.cpp` `Panorama` is built at `WATERFALL_NFFT`. `waterfall_frame` calls `take`, converts to dB, and `frame_out` copies `wf_db` into `snap_db` before `waterfall_data`. `dsp_spectrum_snapshot` copies `min(n, WATERFALL_NFFT)` floats and returns false when `n` is larger, so asking for fewer bins copies the first bins, not a bigger FFT.

`remote_server.c` `panel_thread` (the spectrum block) always calls `dsp_spectrum_snapshot(db, WATERFALL_NFFT, ...)`, quantises with `lroundf` into `int8_t` clamped to −128..127, and sends the 16-byte header `{u16 0xFFFF, u16 n, u32 centre, u32 width, u32 seq}` plus `n` bytes. `n` is `WATERFALL_NFFT`. A frame is skipped while `out_len >= PANEL_ROOM` (16 KB). No panel command sets the size or the rate.

`wf_frame_ms()`: speeds 1–3 are `FRAME_MS` (`1000/15`), speed 4 is 20 Hz, speed 5 is 30 Hz, from `p_waterfall_speed` on the radio, not from a panel command. `panel_thread` sends every new snapshot, so the socket already follows that rate. `waterfall_data` then averages 3 or 2 of those frames into one on-screen line at speeds 1 and 2. That average is after the snapshot copy, so the phone already gets the pre-average frames (15 Hz at the default), not the radio's 5 lines/s slow scroll.

`row_pixels` in `waterfall_view.c` blends neighbouring bins at 1/8-bin steps for the radio's screen. The phone does not copy that blend.

Bandwidth of the payload (16-byte header + bins), before WebSocket framing:

- 1024 int8 at 15 fps: 1040 bytes × 15 ≈ 15.6 KB/s (~125 kbit/s).
- 2048 int8 at 15 fps: ≈ 31 KB/s (~248 kbit/s).
- 4096 int8 at 15 fps: ≈ 62 KB/s (~493 kbit/s).
- Speed 5 is 30 fps, so those figures double. 4096 int8 at 30 fps is ≈ 123 KB/s.
- int16 doubles the sample bytes. 1024 int16 at 15 fps is about the same as 2048 int8 (~31 KB/s) and adds no frequency bins.

`PANEL_ROOM` is 16 KB. A 4096-byte frame still fits, and about three can sit in the queue before one is skipped.

A firmware change for 2048 or 4096 bins, which was not made:

- Raise `WATERFALL_NFFT` in `dsp.h` (it is `RADIO_SAMPLES * 2`; `RADIO_SAMPLES` is also the IQ block). `Panorama`, `snap_db`, `wf_lin`, `wf_db` in `dsp.cpp`, the `db[]` and `bins[]` arrays in `panel_thread`, and `ROW_BINS` (`WATERFALL_NFFT * 2`) in `waterfall_view.c` all follow that size.
- The header's `u16 n` already holds 2048 and 4096. The app paints `db.size`, so a future 2048 int8 frame would display with no app protocol change.
- CPU: once the window is full, `Panorama::push` runs one FFT of the last N complex samples on every IQ push. Cost is about N log N, so 2048 is a little over twice the current FFT and 4096 a little over four times, and the window has to be that many samples.

int16, which was not added: the float dB already exists in `snap_db`. `panel_thread` is what narrows it to int8. The header has no sample-size field, so int16 bytes would be read as twice as many int8 bins. It needs a new mark or a width field, and a parser change. The 256-entry table already has about five steps per decibel, so the visible limit is the bin count, not the 1 dB step.

Time smear the app cannot undo: `Panorama::take` returns the mean of every overlapping FFT since the last line. At a 100 kHz span and 512-sample pushes that is on the order of one FFT per push, many of them averaged into each 66 ms line. Sending every FFT would be on the order of 200 KB/s. Keeping only the latest FFT in `take()` would sharpen each line without raising the byte rate, and the line would be noisier.

X6200 (`HW_CAP_PSD`): `dsp_psd` linearly stretches the base's bins onto `WATERFALL_NFFT` before the snapshot, so the socket is still 1024 int8 even when the base packet is wider. Passing the base's own count through would be a change in `dsp_psd` / `frame_out` and in the fixed `snap_db` size, not only in `panel_thread`.

Zoom on the wire: with `HW_CAP_ZOOM`, `wf_span()` is `SPAN_HZ / zoom` because the base decimates the IQ, and the snapshot width is that span at 1024 bins (zoom 8 is 12.5 kHz, about 12.2 Hz/bin). Without the cap, `wf_span()` stays `SPAN_HZ` (100 kHz) and the waterfall FFT is not decimated. The radio crops that picture in `shown_span`. The phone shows `frame.widthHz`.

## Live check of a3c1afa: history across a taller pane

The 1 line = 1 px time axis stayed. Growing the waterfall (rotation to landscape, or a layout that gives the waterfall more pixels) showed a thin band of new lines and an empty floor until the pane filled again, about a minute at 15 fps.

Cause: the deque lived inside the spectrum pane (`remember`) and `resize` trimmed it to that pane's pixel height. A short portrait waterfall dropped every line past its height. A rotation disposed the pane, so the new one started empty even for the lines that had fit. The bitmap was then rebuilt at the new height from that short or empty deque.

The ring is now `WaterfallHold` on `RadioViewModel`, the same place as the 3D `RidgeHold`. It keeps [WaterfallDisplay.MAX_ROWS] raw lines (4096) whatever the pane measures. `linesFor(pixelRows)` returns the newest lines the bitmap can show and does not drop the rest, so shrinking and growing both paint from the lines already stored. A repeated sequence (the frame still current when the pane comes back) does not scroll. The colour window travels with the ring, so the stored lines are not recoloured from the leveler's default until the tracker catches up. The bitmap remember keys include the pane width and the waterfall pixel height, so the new size repaints before the next frame.

`theWaterfallHistorySurvivesATallerPane` stores 300 lines, reads 80 of them, then 900, and requires the oldest to still be there. Collecting the current sequence again leaves the count at 300. Reading 40 and then 200 still finds the same lines.

`./gradlew testDebugUnitTest assembleDebug` after this: 228 tests, 0 failures. Assemble succeeded. This was not re-shot on a device.

## Portrait spectrum height and waterfall chip overlap

KD0CQ approved two layout fixes on the same branch.

**What was wrong**

1. Portrait Radio-tab spectrum-only, both the 2D trace and the 3D field, stayed a short strip. A large empty region sat between the SSB / band-plan bar and the needle meter. `scopeSplit` already gives spectrum-only the whole plot above the scale and the plan, in both aspects, and the 2D pane window and the 3D grass already fill whatever height they are given. The short strip was the portrait row. Landscape puts the scope in the weighted slot of the screen column, so it grows. Portrait nests the scope under the frequency strip, beside the rail, in the row above the meter. That row was not locked to the leftover height, so the band plan stopped and the rest of the space above the meter stayed empty.

2. Portrait waterfall-only draws the scale at the top of the plot, because there is no trace above it. The palette, 2D/3D, layout, and Zoom chips were overlaid on that same top edge, so the labels and the chips covered each other and the first lines of the waterfall. Zoom also covered the right-hand label. The 32 dp fade under the chips darkened that strip.

**What changed**

- `PortraitOperating` measures the space above the meter and sets the rail row to that height. The frequency strip keeps its own height. The scope takes the rest, for 2D and 3D, so spectrum-only ends on the band plan.
- Waterfall-only places the same chip row above the plot. The frequency labels stay in the scale strip. The band plan stays under the scale. The waterfall starts under the plan. The top fade is not drawn in that layout, and the zoom corner is not reserved on the scale, because Zoom is in the chip row.
- Combined and spectrum-only still overlay the chips on the trace, so a landscape waterfall does not lose that row. `scopeSplit` shares for S+W are unchanged. Spots, rotation, and `WaterfallHold` are unchanged. The ring is still on the view model. A taller pane still repaints the lines it already has.

`RadioFace.scopeBands` is the arrangement the tests lock. Spectrum-only reports no chip band and no waterfall, and the scale plus the plan land on the bottom of the pane, for both the trace and the ridge. Waterfall-only reports the chip band first, then the scale, and the labels start where the chips end.

`./gradlew testDebugUnitTest assembleDebug` after this: 229 tests, 0 failures. Assemble succeeded. This was not re-shot on a device.

## S24 landscape: LSB passband, spectrum share, scope height, meter readout

Live check on KD0CQ's S24 Ultra (SM-S928U1), landscape, about 2340×1080, radio in LSB at 3.844.000 on 80 m.

**What was wrong**

1. The passband shade on the waterfall and the spectrum sat above the VFO marker, on the USB side, while the radio was in LSB. `flo` and `fhi` on the state line are `cfg_cur_filter_low` and `cfg_cur_filter_high`: positive audio cuts (300 and 2700), the same numbers in LSB as in USB. `passbandHz` added both to the carrier, so LSB 300..2700 was drawn 300..2700 Hz above the dial.

2. Landscape spectrum-plus-waterfall gave the waterfall about 73% of the pane and then subtracted the scale and the band plan from the trace. On a phone-landscape scope that left the trace under the 24 dp zoom chip, so the spectrum was a ribbon about as tall as that chip.

3. A dark band sat between the bottom of the waterfall and the "Take control" row. The scope is the weighted child between the 64 dp header and that row. The stack was not locked to the measured leftover, the same class of gap the portrait spectrum had above the meter.

4. The arc meter's dBm line was centred on the bottom of the same canvas as the scale. A reading such as −72 covered "+40".

**What changed**

- The side is `spectrum_view.c` `on_filter_change`. USB and DATA-U (DIGU) are `f + low_cut .. f + high_cut`. LSB, DATA-L (DIGL) and RTTY (the radio has no RTTY index; it runs as LSB or DATA-L) are `f - high_cut .. f - low_cut`, wholly below the carrier. CW-R is always that lower side. CW is the lower side only when `cwrise` is set (the X6200); on the X6100 it stays above. AM, NFM, SAM and WFM are `f - high_cut .. f + high_cut`. The state line's `kt` is stored and not added: a 700 Hz tone inside a 300..2700 filter would put the high edge above the VFO. `cwrise` is stored with it.
- Landscape spectrum-plus-waterfall keeps the trace at 30–35% of the whole scope (the screen asks for 32%). The scale and the plan come out of the waterfall. The 3D field takes a little more and stays under half, so the waterfall is still the larger part. Portrait stays 40% of the room after the strips, 55% for the ridge.
- Landscape measures the slot between the header and "Take control" and sets the scope to that height, so the waterfall's bottom is that row.
- The arc meter's caption and dBm line are their own strip above the dial. The marks are drawn in the arc under that strip, from the same geometry the test checks, so no reading shares a y with S1..+40.

`eachModePutsTheFilterOnItsOwnSideOfTheCarrier` locks USB, LSB, CW, CW-R, AM, NFM, SAM, WFM, DIGU and DIGL, and a key tone that must not slide CW-R above the carrier. `landscapeSpectrumTakesAboutAThirdAndReachesTheBottom` locks the 30–35% trace and that the four bands add up to the pane. `meterReadoutSitsAboveEveryScaleMark` locks the readout band above every mark. The state-line test keeps `kt` and `cwrise`.

`./gradlew testDebugUnitTest assembleDebug` after this: 232 tests, 0 failures. Assemble succeeded. This was not re-shot on a device.

## JS8, GhostNet, and the connect field

KD0CQ approved a real JS8 screen on the same branch, on top of 5789a3e. The words are the ones OpenX6X00 v0.3.13 added in b2c837d.

**What was wrong**

1. `JS8_IN_FIRMWARE` was false, the parser read `text` / `freq` / `call` / `directed`, and Transmit built `js8 tx`. The radio's command is `js8 send TEXT` (spaces kept, 500 characters, the radio fills the macros). A message is `js8r` with `x`, `f`, `fr`, `to`, `me` and `grp`. The same `id` is rewritten while the message is still arriving. State is a `js8` JSON line. The app waterfall is an 8-byte header, magic `0xFFFD`, then one int8 per bin. None of that was parsed, and the screen never held the view model, so a tap could not reach the radio.

2. JS8 was still in the grey pending-modes list, next to PSK and RTTY.

3. The connect page's help, the phase line, the empty-state line and the found-radio rows took a text colour that sat as dark grey on the black page. The host box was created at `x6100.local` on every composition, so a restart threw away the address that had been typed.

**What changed**

- `js8r` and `js8` parse into rows and status. A repeated id replaces the row in place. The last 64 are kept. `0xFFFD` is parsed before the JPEG check, because the first bins can be `FF D8`. Those lines have their own one-deep lane. Sharing the spectrum's lane would have let a spectrum frame replace every JS8 line, and the other way around.
- The screen sends `js8 wf 1` when it opens and `js8 wf 0` when it leaves. It does not send `js8 open`. Open is a button, and it needs the operate role and Take control, as every command except the waterfall does. Send, CQ and auto CQ ask in a dialog. HB, HB now and Auto are a tap and nothing else, and none of them is sent on open or on connect. Auto and HB stay at whatever the radio last reported.
- Transmit is refused until this session holds control, may operate, may transmit, the radio has reported a JS8 offset, and the dial plus that offset plus the tone width sits inside one US Advanced data segment (47 CFR 97.301(c) intersected with the 97.305 data stretches). There was no privilege check elsewhere in the app. This is that check, used on the JS8 transmit path. 7.107 MHz is inside the 40 m data segment. 3.844 MHz is phone and is refused.
- An answer of `unknown command js8` raises a note that the radio needs OpenX6X00 v0.3.13 or newer. `err js8 send: ...` means the command exists and this one send was refused, so it does not raise that note.
- GhostNet is a filter: `@GHOSTNET`, `@GSTFLASH`, and a regional group that starts at `@GNUSAMO` and can be edited. `@GSTFLASH` is drawn in red. The North America reminder is Thursday 0100Z on a 7.107 MHz dial, Wednesday 7 PM Central, with heartbeats kept below a 1000 Hz offset. A heartbeat at or above that offset is left out of the GhostNet list, and HB / HB now stay disabled on that tab. There is no remote command for GhostNet mode. The screen says it is turned on in the radio's JS8 settings or its web config.
- The connect page uses the face's own text colours. The last host is written when it is typed and when a radio is chosen, with `commit`, and Stop does not clear it. Nothing saved yet is still `x6100.local`. Backup is on so a reinstall that restores app data can bring that host back. The pairing token, the QRZ password and the local log are excluded from that backup.

`js8LinesUseTheFieldsTheRadioWrites`, `appWaterfallHeaderIsTheJs8Frame`, `js8CommandsAreTheWordsTheRadioDispatches`, `js8RowKeepsItsPlaceWhileTheTextGrows`, `ghostNetFilterKeepsTheGroupsAndDropsHighHeartbeats`, `usAdvancedDataSegmentsCoverJs8AndRefuseThePhoneBand` and `theLastHostWinsAndABlankOneFallsBack` cover the parsers, the waterfall header, the group filter and the remembered host. The pending-catalog test no longer requires a JS8 placeholder.

`./gradlew testDebugUnitTest assembleDebug` after this: 238 tests, 0 failures. Assemble succeeded. This was not re-shot on a device.

## Digital screens matched to the radio

Credit: KD0CQ. Built on a436b89. The S24 Ultra showed two layout bugs, and the JS8 screen did not follow the radio's own window.

**What I found**

1. `Js8Screen` was one column: title, status, a 140 dp waterfall, several button rows, the GhostNet editor, then a `LazyColumn` with `weight(1)`, then the compose field. Portrait leftover for the list was almost nothing, because the chrome above it used the height. Landscape on that phone is about 300 dp tall, the column was taller than the screen, and the column did not scroll, so everything under the waterfall was clipped. Putting `weight` inside a vertical scroll does not fix it: the list is then measured with an infinite height.

2. The on-radio JS8 window is `src/js8_window.c`, not `webserver/.../views/js8.html`. Waterfall on top (a short strip, plus a lane of calls), the message list under it, status line above. A row is `hh:mm:ss`, SNR, offset, a speed letter when it is not Normal, then the text. Colours in `message_bg`, night column of `src/theme.h`: our TX `#0A1A33`, alert `#5B21B6`, to us `#991B1B`, CQ `#14532D`, heartbeat in muted text, group `#1E3A5F`. A selected station gets a green bar (`#34D399`). Show is All, no heartbeats, or directed. The finder marks the TX offset.

3. What the panel actually streams, from `remote_server.c` on Guru-RF/OpenX6X00 `main`: FT8 (`ft8r`, `ft8`, `ft8 wf`), JS8 (`js8r`, `js8`, `js8 wf`), CW text (`cw "..."`), WeFax (`wefax` and `0xFFFE` rows after `wefax rows 1`). FT4 and FT2 are not separate sockets. `src/ft8_window.c` is one window and `cfg_ft8_protocol` is 0 FT4, 1 FT8, 2 FT2 (anything else counts as FT2). `ft8 mode` cycles them. PSK, RTTY, Olivia, SSTV, WSPR and Navtex have LVGL screens and no panel or TCI stream.

**What changed**

- Portrait keeps the radio's order and caps the control block, so the message list is the piece that grows and the piece that scrolls. Landscape puts the waterfall on the left and the list plus the controls on the right. Both use the same wide-window test as the rest of the face.
- JS8 rows use `format_row` and those colours. `@GSTFLASH` uses the alert colour. Show cycles All, No HB, Directed and GhostNet. The GhostNet reminder and the regional group stay, inside the capped control block. A bad checksum is the radio's negative `chk`. It used to be stored as a boolean, and a negative code is non-zero, so a bad checksum was kept as a good one.
- The waterfall palette tops out at 30 dB over the floor, with tick marks and lines at the TX offset and the selected station. Calls sit on the lane. Opening the screen still sends only `js8 wf 1`. Send, CQ and auto CQ still ask. Take control, operate, transmit permission and the US Advanced check are unchanged.
- FT8 / FT4 / FT2 is the one screen, titled with the protocol the radio reports. The list is oldest first. A slot time is its own line. A call to us is the red row (`#EF4444`), our TX is blue (`#0082FC`), a CQ has a green edge and a CQ or "worked" tag, and other traffic is grey. SNR, kilometres and the country sit at the end of the row. Mode sends `ft8 mode`. CQ, Auto, TX Call and a reply ask, and they need Take control plus the same band-edge check, using 50 Hz for FT8, 83 Hz for FT4 and 167 Hz for FT2 (`ft8_window.c`). Turning CQ, Auto or TX Call off does not need the band check, so a bad offset cannot leave the transmitter on. The screen asks for `ft8 wf` only while it is open. The radio has one `appwf` flag for FT8 and JS8, so the phone keeps one picture and clears it when a screen starts watching.
- WeFax parses `wefax` and the `0xFFFE` scan line (its own lane, 32 deep, so a chart is not one stripe and a row cannot sit in front of `ctl`). The picture is the grey bytes. The buttons are open, close, auto, cont, save, clear, plan and unplan. None of those is a transmit command. Each needs operate and Take control. The screen asks for rows and does not send open by itself. The chart takes the leftover height.
- CW decode is the radio's strip (`src/cw_field.c`): the text the socket sends, filling the screen and scrolling. The spectrum still does not paint it. There is no command and no invented speed.
- PSK, RTTY, Olivia, SSTV, WSPR and Navtex stay in the pending list.

**What could not be matched**

- The radio's JS8 status line prints its UTC clock and the time-sync drift. The `js8` JSON does not carry either, so the phone's line uses the switches, the speed, the place and the decode count.
- Directed on the radio also keeps traffic near the selected offset, using the speed's receive threshold. That threshold is not in the JSON. The phone's Directed view is the "to us or to a group" half.
- Country flags are LVGL images. The socket sends the country name, which is what the row shows.
- FT8's "NEW" tag needs the radio's DXCC-worked bit. The `ft8r` line does not include it. Worked, CQ, POTA, SNR, distance and country are on the line and are shown.
- The CW field's WPM is the keyer speed on the radio. The `cw` line is text only.
- WeFax tilt, align, the night fix and the saved-chart browser are on the radio and are not panel commands.
- PSK, RTTY, Olivia, SSTV, WSPR and Navtex decode on the radio and are not sent to the phone. No screen was added for them.

`js8AndFt8RowsFollowTheRadioScreens` and `wefaxRowIsNotAJpegOrAWaterfall` cover the row text, the colours, the protocol names and widths, the slot split, the fax header and the band-edge refusal.

`ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest assembleDebug --offline` after this: **240 tests, 0 failures, 0 errors**. `assembleDebug`: BUILD SUCCESSFUL.

This pass was not re-shot on a device.

## Each digital mode uses its own radio window

Credit: KD0CQ. Clarification after 818e523: FT8, FT4 and the other non-JS8 modes must follow their own firmware screens. The JS8 split is not their layout.

**What I found**

1. `DigitalSplit` was written as the phone's waterfall-over-a-list, and FT8 and WeFax both called it. That split is `src/js8_window.c`: a short waterfall, then the list, then the controls, and in landscape the waterfall beside the list. `src/ft8_window.c` is a different picture. `src/wefax_window.c` is the common app frame. Reusing the JS8 composable made the other modes look like JS8.

2. The CW screen was matched to `src/cw_field.c`. That file is the keyboard field: one line being keyed, and the keyer speed beside it. The decoder's display is `src/text_panel.cpp` (declared in `src/text_panel.h`). `src/cw_decoder.c` writes the socket line and then `panel_add_text`. The panel keeps the last lines that fit, colours a confirmed call, an abbreviation and an RST, and puts the speed on an info line above the panel. The `cw` line is the text only.

3. FT4 and FT2 are still not their own sockets. `cfg_ft8_protocol` is 0 FT4, 1 FT8, 2 FT2, and `ft8 mode` cycles them inside the one FT8 window.

**What changed**

- JS8 still uses `DigitalSplit` and `AppWaterfallPicture`. FT8, WeFax and CW do not.
- FT8, in both orientations, is the radio's column. A 26 px lane of calls sits on the panel colour. The waterfall is the Gauss table, 0 to 30 dB, and the finder is a band of the signal width starting at the TX offset (`set_tx_freq_in_filter` keeps the whole signal in the filter). The list is see-through at opacity 115 over the waterfall, with 40 px of the waterfall left clear above it. A CQ keeps the green edge. A call to us is `#EF4444`. Our TX is `#0082FC`. A slot time is a centred line with a rule on each side. View swaps that list for the 3×3 tiles. A tile that is calling us uses `#991B1B` (`row_to_me`), five signal bars from −24 dB to −4 dB, and the tag. A sideways drag is the main knob: 10 Hz per 24 dp, `ft8 txfreq`, and the list fades for one second. It needs Take control and does not run the band-edge check. The five button pages follow `ft8_window.c` in order. CQ, TX Call and Auto still ask, and turning one on still needs the US Advanced check (83 Hz FT4, 50 Hz FT8, 167 Hz FT2). Turning one off uses the same command, still asks, and skips the band check so a bad offset cannot leave the transmitter on. Hold and Mode send. Show and View are local. Freq, Retries, Force QSO save, Hound, Power, CQ modifier, Time sync, Live Map, Tune, Log Map and For you are drawn and send nothing. Open, Close, Stop and Take control stay in a thin row because the panel requires them and they are not F-keys. Opening the screen still sends only `ft8 wf`. There is no JS8 button on this screen.
- WeFax is the app frame. The left names the station and the frequency. The right is RX Auto or RX Cont, TX percent while transmitting, or Plan plus the next slot's `HH:MM`. The hint under the frame is the radio's page hint. The chart is the grey rows, newest at the bottom. View cycles Big, Full, Small. Small is the 152 px strip; the radio shows its main waterfall above that strip, and this route has no waterfall, so the frame colour fills that space. The four pages of five buttons follow `wefax_window.c`. Plan, Close, RX mode, Clear and Save send `plan`/`unplan`, `close`, `auto`/`cont`, `clear` and `save`, each through operate and Take control. None of them is a carrier. Bulletin, All Charts, Freq, Align, Tilt, Power and Night are drawn and send nothing. View is local. Open stays off that bar. The screen still asks for `wefax rows 1` and does not send `wefax open`.
- CW is the text panel: the panel background, a border, the last lines that fit at 24 sp, and a word coloured the way `src/cw_parse.c` classifies it once the words are already separated. A confirmed call (beside DE, or the same call twice) is `#E5A823`. An abbreviation is `#94A3B8`. An RST is the header gold. The info line says the speed is not in the `cw` text, and no WPM is invented. There is still no command. Opening the screen does not start the decoder. The spectrum still does not paint the line.

**What could not be matched**

- Country flags are LVGL images. The FT8 "NEW" tag and "called you" need the radio's worked state, which `ft8r` does not carry. Worked, CQ, POTA, SNR, distance and country are on the line and are shown.
- The Directed view's offset threshold, the JS8 UTC clock and the drift are still absent from the `js8` JSON.
- CW WPM, the RTTY tuning scope in the corner of the same panel, and dimming the text while the dial is moving are not on the socket. `cw_parse_split` (a run of words with no spaces) and the radio's known-call table are not ported. A call is coloured only when the decoder already separated the words.
- WeFax tilt, align, night, the bulletin, the chart browser and the frequency presets are on the radio and are not panel commands, so those buttons do not send. The idle overlay's product name, "in N min" and the two following slots come from the radio's plan table. The JSON has one `next` string, and that string is what the overlay shows.
- PSK, RTTY, Olivia, SSTV, WSPR and Navtex still have no panel or TCI stream. No screen was added for them.

`eachDigitalModeKeepsItsOwnWindow` locks the FT8 and WeFax page order, which keys send, the CQ filter, the heard-list order, the signal bars, the finder band, the fax frame text and the CW word colours.

`ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest assembleDebug --offline` after this: **241 tests, 0 failures, 0 errors**. `assembleDebug`: BUILD SUCCESSFUL.

This pass was not re-shot on a device.

## JS8 edges, and the header clock and battery

Credit: KD0CQ. Two leftovers on top of 323ca78, from an S24.

**What I found**

1. The JS8 command row scrolls sideways. The last key was one line, `Show: GhostNet`. After the swipe that brings it to the right edge, the last letters were clipped. The offset row's text field has Material's 280 dp minimum. In the landscape column that minimum, and the field sitting flush to the edge, left `Set` slightly past the clip on the first landscape frame.

2. The GhostNet net reminder is the footer of that same control block. The block ended on the system navigation bar, and the last line was shaved by it.

3. The header clock was the literal `--:--:--`. The battery printed `bat` even when the value was -1, so the face read "-1%". I read `pstate_line` in Guru-RF/OpenX6X00 `src/remote_server.c` (main, `b09df068`) and `radio_get_battery` in `src/radio.h`, and the TCI command list in `src/tci_server.c`. The state line does carry battery: `"bat":%d`. `radio_get_battery` is the percent on the radio's screen, and -1 on external power or before the base has measured the pack. The state line has no time field. TCI has no time command and no battery command. `clk? MS` is answered `clk MS <radio unix ms>`, and that answer is step 1 of setting the radio's clock from the phone (`clock_from_phone`). It is not a status field the face is pushed, so the header does not poll it.

**What changed**

- The Show key is two lines, `Show` over the name (`GhostNet`, `All`, `No HB`, `Directed`). The command row's end padding is inside the horizontal scroll, so the last key can travel clear of the right edge.
- The offset field's minimum width is 0. `Set` keeps its own width. The JS8 window applies the navigation-bar inset, and the control block keeps 12 dp under the scroll and 8 dp at the end, so the net reminder and `Set` end above the bar and inside the column.
- A `bat` in 0..100 is shown, in portrait and in the landscape header. -1 is not shown. Nothing connected still shows an em dash, so a blank header is not read as external power. A low-battery alert still ignores -1. The header clock is the phone's UTC, `HH:mm:ssZ`, ticking once a second. The "~ —" remaining-time mark stays the pending placeholder: the state line does not carry a time-remaining field.

**What is still not the radio's clock**

The face clock is the phone, in UTC. It is not the radio's RTC, and it does not send `clk`. The radio's own clock is still not a field on the panel state line or on TCI.

`theHeaderClockIsPhoneUtcAndAMissingBatteryIsHidden`, the `bat: -1` parse, `lowBattery(-1)`, and `showButton` cover the sentinel, the UTC digits, and the two-line Show key.

`ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest assembleDebug` after this: **242 tests, 0 failures, 0 errors**. `assembleDebug`: BUILD SUCCESSFUL.

This pass was not re-shot on a device.

## Credit is the callsign, and the README matches main

**What I found**

The tree, the file headers, and the notes still named the operator as a personal name next to the callsign. The same personal name was in the QRZ sample and in one logged QSO name. `theMadCatter` was already the GitHub handle and had to stay. The README on this branch had put the personal name back into the byline. `main` (`0c7de1f`, "Update author attribution in README.md") already says `By KD0CQ` and keeps the handle. The path given for a newer draft, `/workspace/readme-draft/README.md`, is not on this machine. The clock and battery work does not change what the radio sends: `bat` is still on the state line, including -1, and there is still no time field, so the README Status line stays "Early work. Not yet usable on the air."

**What changed**

- Every credit that paired a personal name with the callsign is now `KD0CQ` (possessive sentences use `KD0CQ's`). Headers read `Copyright (C) 2026 KD0CQ (theMadCatter)`. The handle is unchanged.
- `README.md` is the file from `main`, byte for byte. Status was not edited.
- The QRZ fixture and the sample QSO name carry the callsign. The parser still joins the two QRZ name fields.

Git history was not rewritten. Older commits still have the previous credit line.

`ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest assembleDebug --offline` after this: **242 tests, 0 failures, 0 errors**. `assembleDebug`: BUILD SUCCESSFUL.

This pass was not re-shot on a device.

## The approved README, with the Status line matched to the wire

**What I found**

The README placeholder in the previous request never expanded, so that pass used the file already on `main`. The approved text arrived in the follow-up. It already credits `KD0CQ` and keeps `theMadCatter`. Its Status line said the radio does not yet send its clock or its battery. That is true of the clock: the panel state line and TCI have no time field, and the header clock is the phone's UTC. It is not true of the battery. `pstate_line` already writes `bat`. A value in 0..100 is the percent on the face. -1 is external power or no reading yet, and the face hides it. The clock and battery work did not add a field and does not send `clk`.

**What changed**

`README.md` is the approved text. The Status line is the only edit. It keeps "Beta. Tested receive-only against a real X6100." and then says `bat` is sent, 0..100 is shown, -1 is hidden, and the clock is the phone's UTC because the radio does not send one.

`ANDROID_HOME=$HOME/android-sdk ./gradlew testDebugUnitTest assembleDebug --offline` after this: the tasks were up to date, and the last executed run is still **242 tests, 0 failures, 0 errors**. `assembleDebug`: BUILD SUCCESSFUL.

This pass was not re-shot on a device.
