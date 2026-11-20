# Design of the audio path and the PTT

This is how the transmit side of this app works and, more importantly, how it
guarantees it does not leave a radio holding the key. It covers the TCI audio
pipeline, the Panel PTT lease, and the fail-safe path through every layer.
The wire formats themselves are documented by the firmware sources they came
from — `src/remote_server.c`, `src/tci_server.c`, `src/tci_audio.h` in the
[firmware repo](https://github.com/theMadCatter/OpenX6X00) — and this app
invents nothing on the wire.

## The safety model

There is one failure that matters more than all the others: **the app believes
it is done transmitting while the radio is still on the air.** Everything else
— latency, dropouts, a clumsy UI — is recoverable from; a radio transmitting
into a dead microphone, unattended, is not. The scenarios this design defends
against:

1. **A stale timer keys the radio after the user let go.** Renewals are
   periodic, releases are event-driven, and they run on different threads. If
   a queued renewal can fire after the release, the radio re-keys from a press
   that ended.
2. **The app and the radio disagree about who holds the key.** The radio took
   it away (its own limit, no renewal, someone with controls), or it was never
   given (refusal, no `tx 1`), and the screen still says *Keyed* — or worse,
   says *Idle* while the carrier is up.
3. **The only exit is gone.** The socket died while keyed, the app was
   backgrounded, the service was killed, the Activity was stopped. Whatever is
   still reachable has to be used to unkey, and the app must not pretend a
   lost write succeeded.
4. **A malformed frame makes the radio unkey mid-transmission.**
   `handle_tx_audio` unkeys on any frame whose shape it does not recognize.
   Sending garbage is not just a dropout; it is an uncontrolled release, and
   the app then disagrees with the radio about state.
5. **Silence that keeps a key.** A microphone that reports *running* but
   delivers nothing (another app owns the input) produces perfect silent
   frames, which satisfy the radio's silence watchdog. The transmitter stays
   keyed to the three-minute limit with nobody knowing.

The defense is the same shape everywhere: **a release is final.** Every hold
(Panel lease) and every session (TCI audio) carries a *generation* counter.
Releasing retires the generation and cancels every timer asked for on its
behalf. Any timer or in-flight callback that finds its generation retired
gives up without touching the wire. So it is not merely true that releases
stop renewals — it is structurally impossible for anything scheduled before a
release to send after it. The unit tests are built around exactly this
property, with a fake clock and scheduler.

Where the app cannot be sure, it chooses *unkey*. If a write fails it assumes
the radio may have seen it, and the next press takes a higher hold number
(a number the radio has never seen cannot key anything — `ptt_down()` in
`remote_server.c` refuses a hold that is not above the last one it honored).
A renewal the socket will not take is a release, and that release sends
`key PTT 0` (or `trx:0,false;` when the lost write was a TX frame). The
up-command is retried for about one firmware lease (`PTT_LEASE_MS`, 3 s) or
one silence window (`TX_SILENCE_MS`, 500 ms). A retry is tied to the hold's
generation, so it cannot unkey a press that started after it was asked for,
and the next press sends any owed up-command before its down-command.
`err ptt released` does not send another `key PTT 0`: `ptt_lease()` already
took the key up. If the link drops while keyed, or while an up-command is
still owed, the app still attempts the unkey before giving up: a
politely-closed socket takes it, a dead one loses nothing. `reset()` — the
last thing a dying service does — sends the unkey if a hold is still live
or an earlier up-command never left, rather than walking away from one.

## Layers and the fail-safe path through them

```
HoldToTalkButton on the radio screen (press-and-hold)
    └─ RadioViewModel.pressPtt() / releasePtt()
        └─ LeasePttController            ptt/LeasePttController.kt
            ├─ PttLeaseStateMachine      core/PttLeaseStateMachine.kt   (Panel path)
            ├─ TciTxStateMachine         core/TciTxStateMachine.kt      (phone-mic path)
            └─ AndroidAudioPipeline      audio/AndroidAudioPipeline.kt
Rig / RadioRigs                          service/RadioRig.kt   (owns them over RadioLinks)
RadioLink / PanelClient / TciClient      net/                  (the wire)
```

The fail-safe reaches in from every layer that can observe a problem:

| Layer | Event | What runs |
|---|---|---|
| Activity | `onStop()` | `ptt.wentToBackground()`, which fail-safes both paths |
| Service | `onDestroy()`, `onTaskRemoved()` | same, in the documented shutdown order below, and `onDestroy` disconnects only after that |
| Rig | Panel or TCI socket closed / failed | `failSafe(...)` if on air, or if an up-command is still owed |
| Client | a write the socket would not take (`sendChecked` false) | the state machine releases and sends the up-command; a lost renewal is a release on the wire, not only in memory |
| State machine | its own timers: key-confirmation, hard cap, silence, mic stall | releases with a reason, and sends the up-command |
| Radio | `tx 0`, `err ptt released`, any PTT refusal | releases; renewals stop. `err ptt released` does not send a second `key PTT 0` |
| Radio | controls taken by someone else | both paths unkey. `ctl tci 0` is this app's own phone-mic holder (`ctl_tell` sets MINE only for this panel slot) and does not unkey it. `ctl local` does, because `ctl_local_input()` moves the holder while the radio is still transmitting |
| UI | thumb leaves the button | release, in a `finally`, even if the press threw |

The shutdown order is fixed (`Rig.shutdown`): `ptt.failSafe(reason)` first,
while the socket is still alive enough to carry `key PTT 0`; then
`audio.release()`; then `scheduler.clear()`; then `ptt.reset()`. Audio stops
before timers are flushed because the TCI machine may answer a chrono while
the scheduler drains, and the PTT resets last so it sees a quiesced world.

The Activity hook is `onStop`, not `onPause`: split-screen and notification
shades pause but do not stop, and dropping the key there would make the app
unusable in a shack where the phone is doing two things.

**There are deliberately no no-op implementations.** The skeleton's
`NoOpPttController` and `NoOpAudioPipeline` were deleted: a key that silently
does nothing looks exactly like a key that works, which is the disagreement
this whole design is trying to prevent. If the parts are not wired, the
button is not there.

## The two transmit paths

`LeasePttController` offers two ways to key, and exactly one is live at a
time (`usePhoneMicrophone`):

**Panel lease (default).** `key PTT 1 HOLD` over the Panel socket (port
50002) presses the radio's *own* microphone. No microphone permission, no
audio pipeline, and the radio keeps working if this app dies — the firmware
leases the key and lets go by itself. This is the path the radio's web page
uses and the one the app defaults to.

**Phone microphone.** TCI `trx:0,true,tci;` (port 50001) routes *this phone's*
microphone through the radio, answered with TX_AUDIO frames. It is gated on
the microphone actually running: `TciTxStateMachine.press()` starts capture
and checks it is really running before the key is ever sent, stopping it
again and refusing with a visible reason if it is not — the page's
*Microphone not ready: no transmission* rule. The radio's own mic is never
substituted silently; if the phone's mic cannot capture, this app does not
transmit. The permission (RECORD_AUDIO) is requested from the switch that
turns the path on, and turning the path off unkeys first.

### Lease numbers

`PttLeaseStateMachine` keeps a monotonic counter; every press takes the next
number and the number is *spent even if the write failed*. The radio refuses
any hold not strictly above the last one it honored, so a reused number is a
press that cannot key; a re-sent number is a press that can key *late*.
Neither is acceptable, so numbers only go up for the life of the machine.

## Timing

All intervals are chosen against firmware values, and every one of them is a
constructor argument so the tests can shrink the world.

| Interval | Value | Why |
|---|---|---|
| `PTT_LEASE_MS` (radio) | 3 000 ms | the firmware's lease (`remote_server.c`) |
| renew cadence (app) | 1 000 ms | the page's `RENEW_MS`; two missed renewals of margin |
| key confirm (lease) | 1 500 ms | the page's `KEY_MS`: no `tx 1` by then means never |
| key confirm (TCI) | 1 000 ms | `trx` answers are immediate on a live socket |
| hard cap (app) | 170 000 ms | under the radio's `PTT_LIMIT_MS`/`TX_LIMIT_MS` of 180 s — *we stop, we are not stopped* |
| release settle | 1 500 ms | how long *Unkeying…* shows before the button is live again |
| TX chrono | every 21.3 ms | the radio's pace: 512 samples at 24 kHz (`tci_server.c`) |
| TX silence | 500 ms | the radio's `TX_SILENCE_MS`; the TCI machine watches the same gap toward *itself* |
| mic stall | 500 ms | capture "running" with zero samples delivered for this long is a dead microphone: unkey |

The asymmetry is the point: every app-side watchdog fires *before* the
radio's, so the app is always the one that ends a transmission, and the
radio's limits are a backstop rather than the mechanism.

## The audio pipeline

`TciAudioFrameCodec` is a frame-shaped front over the skeleton's
`net/TciProtocol` header codec — one codec, not two. The TCI audio header is
64 little-endian bytes (`receiver, sample_rate, format, codec, crc, length,
type, channels, reserved`); `length` counts samples *across all channels*.
Audio starts with the exact string the firmware expects:

```
audio_samplerate:24000;audio_stream_sample_type:int16;audio_stream_channels:1;audio_stream_samples:512;audio_start:0;
```

**RX**: one `AudioTrack`, 24 kHz mono PCM16, low-latency `MODE_STREAM`;
decoded RX_AUDIO frames are queued and written; underruns are counted, not
hidden.

**TX**: one `AudioRecord`, same format, 512-sample reads fed to TX_AUDIO.
Each `TX_CHRONO` is answered with exactly one frame of 512 samples (the
payload shape `handle_tx_audio` accepts, `length = 512`); if the microphone
has not filled a full frame yet, the gap is zero-filled — a short answer is
not an option, but silence is, and silence is what the radio's own page sends
when the mic is behind. Every outbound frame passes `isSensibleTxAudio`
first: type, sample count, and exact payload length are checked against what
`handle_tx_audio` accepts, and a frame that would make the radio unkey is
never built — the machine ends the session with a reason instead of waiting
for the radio to notice it.

What is deliberately *not* done: no `MODE_IN_COMMUNICATION`, no acoustic
echo cancellation, no noise suppression. The radio is the far end of a
transmitter, not a phone call; voice processing on a SDR audio path mangles
the signal. The honest cost: on speakerphone, the speaker feeds back into
the microphone. Use headphones. And no Oboe/NDK: AudioTrack/AudioRecord in
`MODE_STREAM` hold their own low-latency callbacks and keep the app a pure
JVM + framework build; 24 kHz mono at 21.3 ms per frame is not a budget that
needs a native path (see below).

### Latency budget

One chrono period is 21.3 ms of wall time. Inside it the app must: read the
WebSocket binary message, decode a 64-byte header, read up to 512 samples
from the AudioRecord ring buffer (already filling at sample rate), encode one
frame, and hand it to OkHttp. That is a `System.arraycopy`-sized amount of
work per period — sub-millisecond on any phone from the last decade. The
AudioRecord buffer is 4 frames (≈85 ms) deep, so the capture side can be up
to four periods behind without a glitch, and the mic-stall watchdog is set at
500 ms, well past any scheduling jitter and well inside the radio's own 500 ms
silence unkey.

### Threading

- **Chronos are not answered on the OkHttp thread.** `OkHttpTciClient` posts
  each binary frame into a `SharedFlow` (`tryEmit`, `DROP_OLDEST`, 128 slots).
  `RadioLink` collects that flow on `Dispatchers.IO` and the audio pipeline
  calls `TciTxStateMachine.onFrame` from there. A chrono the flow drops is a
  gap; the silence watchdog and the radio's `TX_SILENCE_MS` both unkey.
- **Panel text and panel pictures do not share a queue.** `ctl`, `tx`, `role`,
  `err`, `state`, and every other text line go through `PanelEventBus` into an
  unlimited queue and are emitted in order. Spectrum and screen frames are a
  separate one-deep replay: a slow collector keeps the latest picture and
  misses the ones in between, and that miss cannot evict a control line.
  A socket generation retires lines still queued when the socket dies, so a
  late `tx 0` cannot release the next press. The new socket is the resync:
  the firmware sends `role` and `ctl` on the handshake, `auth` asks again, and
  `panel 1` sets `panel_fresh` (only on the false-to-true edge) which pushes
  the whole `state` line, including `tx`. There is no other state-query
  command, and a live socket is not toggled off and on to ask again.
- **Slow timers run on the main looper** (`HandlerPttScheduler`): renewals,
  caps, settles, and unkey retries. Renewals are 1 s-class; an unkey retry is
  100 ms (TCI) or 250 ms (Panel).
- **Both state machines hold one monitor around every mutation.** Releases
  arrive from the UI thread, the main-looper timers, and the IO collectors
  that read the sockets. Generation checks make the ordering not matter.
  `send` runs under that lock. OkHttp's `WebSocket.send` queues; it does not
  re-enter the listener before returning.
- **Audio callbacks are the framework's** (AudioTrack/AudioRecord internal
  threads); the app's threads only fill and drain their buffers, never block
  on the network.

## Pairing and control

`PairingController` speaks the Panel's pair/auth/control handshake in
`core/` with no Android types: a client connects unpaired, the radio offers
pairing, the user confirms on the radio's screen, `pair CODE NAME` answers
with a token, role and transmit permission, and from then on `auth TOKEN`
reconnects *this* session without asking again. A `paired` answer without a
usable session token is a refusal, not a pairing; the token is cleared on
`unpair` and never logged. Losing the controls while keyed is a fail-safe
event on both transmit paths, not a notification. `ctl tci` is the phone-mic
path's own holder and is not that event.

## Testing

150 JUnit4 tests, plain JVM, no Robolectric, no device — driven by
`ManualClock`/`ManualScheduler`, which make time an argument. They cover:
renewal cadence and exact wire text; every fail-safe entry point (release,
link loss, control loss, `err ptt released`, background, hard cap, lost
renewal write, key-confirm timeout); *no renewal or audio frame after any
release, ever*; strictly increasing hold numbers including the write-failed
case; press-while-releasing honored, press-while-live refused; TX_AUDIO only
between key and unkey, only in the accepted shape, zero-filled to length;
unkey on socket drop; chrono answering and the silence/mic-stall watchdogs;
frame round-trip against the raw `TciProtocol` codec; pairing parsing and
token handling; a flood of spectrum and screen frames leaving `ctl`, `tx`,
`role` and `err` in order, with only the latest picture retained. Tests were
written from the firmware's real lines and frame
layouts — when a test and the firmware disagree, the test is wrong, but every
disagreement so far was a bug.

`./gradlew assembleDebug testDebugUnitTest` must be green before anything is
committed.

## Known limitations

- **Speaker feedback** on the phone-mic path (above). Headphones.
- **Half-duplex only**, because the radio is: TCI audio stops when the
  transmitter keys.
- **170 s cap** per press, on purpose. There is no auto-key/CW-keyer mode,
  and there will not be one without a much louder failsafe conversation.
- **The Panel path uses the radio's microphone.** The phone-mic path exists
  for completeness and for radios whose mic element is the thing you are
  avoiding; it is not the default and the UI says so.
- **AudioTrack latency is whatever the device grants**; on a phone with a
  long output pipeline, RX audio lags more than the low-latency claim. The
  app counts underruns and shows them rather than pretending.
- No Oboe. If a device ever fails its 21.3 ms chrono budget in the field,
  the fix is a native callback on the TX side only — the state machines would
  not change, which is why they were kept free of Android types.
