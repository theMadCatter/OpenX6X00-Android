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
