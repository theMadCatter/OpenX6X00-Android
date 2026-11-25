# TX checklist

On-air checks for a dummy load or the radio's lowest power. This pass does not
key a radio. KD0CQ runs this list.

Do not merge on the strength of these boxes alone. A box is a thing to try on
the air, not a result.

## Before any carrier

- [ ] Dummy load fitted, or power at the lowest step the radio will take.
- [ ] Phone paired. The session role is operate and the transmit flag is on.
- [ ] Take control. The face says the controls are ours.
- [ ] Setup: country United States, and the license class this operator holds.
- [ ] With no class chosen, PTT, Tune, FT8 CQ and JS8 Send do not key, and the
      face says to choose a class.
- [ ] A frequency outside that class and emission is refused. A frequency inside
      it is not.
- [ ] Turning the US check off shows the warning. Take control is still required.
      Turn the check back on when the test is done.

## Voice

- [ ] Radio microphone: hold PTT, speak, release. The radio returns to receive.
      The line is `key PTT 1 HOLD` while held and `key PTT 0` on release.
- [ ] Phone microphone: hold PTT, speak, release. The line is `trx:0,true,tci;`
      and then `trx:0,false;`.
- [ ] Release with the finger still down and the app backgrounded. The carrier
      drops. With background running on, the link stays and the key is up.
- [ ] Disconnect while holding PTT. The carrier drops.
- [ ] Pull the transmit flag, or give control back, while holding PTT. The
      carrier drops.

## FT8, FT4, FT2

- [ ] CQ asks, then sends `ft8 cq`. Cancel does not key.
- [ ] TX Call asks, then sends `ft8 txcall`.
- [ ] A reply asks, then sends `ft8 reply`.
- [ ] Auto asks, then sends `ft8 auto`.
- [ ] Turning CQ, TX Call or Auto off sends the same word and does not need the
      offset to be inside the band.
- [ ] Change mode among FT4, FT8 and FT2. The band check uses 83 Hz, 50 Hz and
      167 Hz.
- [ ] Drag the TX offset across a band edge. The offset stops at the edge and
      the radio is not told to use the illegal one. Drag it back inside. That
      one is sent.
- [ ] Leave the screen, disconnect, or give control back while CQ is running.
      The phone sends `ft8 stop`.

## JS8

- [ ] Send, CQ and Auto CQ each ask, then send `js8 send`, `js8 cq` and
      `js8 autocq`.
- [ ] Heartbeat, HB now and Auto each ask before they start. HB now sends
      `js8 hbnow`. Turning Heartbeat or Auto off sends `js8 hb` or `js8 auto`
      without the band check.
- [ ] TX offset, the −10 and +10 keys and Set, send `js8 txfreq` for an offset
      inside the band and refuse one that is not. They do not key by themselves.
- [ ] Leave the screen or disconnect while a JS8 transmission is up. The phone
      sends `js8 stop`.
- [ ] Watch the power. A radio set above 7 W drops to 7 W for the JS8 (and FT8)
      transmission and restores the previous power afterwards. The phone does
      not send a separate cap command. The radio does this.

## Tune, power, WeFax

- [ ] Hold Tune. The radio transmits a carrier (`tune 1 HOLD`) and stops on
      release (`tune 0`). This is not the ATU button.
- [ ] Background the app, disconnect, or give control back while Tune is held.
      The carrier drops.
- [ ] Change power only while this session holds control and may transmit.
      Above 7.0 W the face reminds you of the digital cap and still sends
      `set pwr`.
- [ ] WeFax: confirm the phone cannot start a fax transmission. The radio's own
      screen can. There is no panel word for it.

## If the phone loses the radio on its own

A disconnect the phone starts sends the stop and unkey lines first. A link that
dies before those lines can be written is different: PTT and Tune fall over on
the radio's own lease, a few seconds after the renewals stop. An FT8 or JS8
transmission the radio has already started can keep going until someone stops
it on the radio. Confirm that case once, on a dummy load, and do not treat the
phone as able to reach a radio it can no longer see.
