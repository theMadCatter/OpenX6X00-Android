// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.
package radio.kd0cq.x6100.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The panel socket's two lanes.
 *
 * Before this split, every panel line and every spectrum or screen frame went into one
 * `SharedFlow` of 512 slots with `DROP_OLDEST`. A waterfall fills that in well under a second
 * when the collector is behind, and the oldest thing in it is whichever of `ctl`, `tx`, `role`
 * or `err` arrived first. Those lines are what take the carrier down. Losing one left the app
 * believing it was still the holder.
 *
 * Text now goes into an unlimited queue and is emitted in order. Nothing on that lane is dropped
 * to make room for a frame. Spectrum and screen frames go to a one-deep replay: a collector that
 * is behind sees the latest frame and not the ones it missed. The two lanes do not share a buffer,
 * so a flood of frames cannot push a `ctl` or a `tx 0` out.
 *
 * OkHttp delivers both kinds on its reader thread and that thread must not suspend: a blocked
 * reader is a client the radio will idle out. [post] only ever `trySend`s. The unlimited channel
 * is what keeps order without a giant preallocated `SharedFlow` buffer (`MutableSharedFlow`
 * allocates `replay + extraBufferCapacity` up front).
 *
 * Each socket gets a generation from [openConnection]. [closeConnection] retires it. A line that
 * was still queued when the socket died is not delivered afterwards: the link-lost fail-safe has
 * already unkeyed, and a late `tx 0` from the dead socket would release the next press. The new
 * socket's handshake sends `role` and `ctl` again, and `panel 1` asks for the whole state.
 */
class PanelEventBus(scope: CoroutineScope) {

    private data class Item(val generation: Int, val event: PanelEvent)

    private val epoch = AtomicInteger(0)
    private val inbox = Channel<Item>(Channel.UNLIMITED)
    private val text = MutableSharedFlow<PanelEvent>(
        extraBufferCapacity = TEXT_BUFFER,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val pictures = MutableSharedFlow<PanelEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val appPictures = MutableSharedFlow<PanelEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * WeFax scan lines. A one-deep slot would keep only the newest line and the
     * chart would be a single stripe. Thirty-two is enough for the UI to fall
     * a moment behind. Past that, the oldest line is dropped, which is what
     * the radio already does when a client has no room.
     */
    private val faxPictures = MutableSharedFlow<PanelEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Control, state, role, errors, and every other text line. Never dropped for a frame. */
    val events: Flow<PanelEvent> = text.asSharedFlow()

    /** The latest spectrum or screen frame. Older frames are replaced, not queued. */
    val frames: Flow<PanelEvent> = pictures.asSharedFlow()

    /**
     * The latest JS8 or FT8 app-waterfall line. Its own slot, so it neither evicts a
     * spectrum frame nor waits behind one, and neither of them can push `ctl` out.
     */
    val appFrames: Flow<PanelEvent> = appPictures.asSharedFlow()

    /** WeFax scan lines, in order, dropped only when this buffer is already full. */
    val faxFrames: Flow<PanelEvent> = faxPictures.asSharedFlow()

    private val queued = AtomicLong(0)

    /** Text lines waiting in the unlimited queue. Cheap to read; not logged per line. */
    val textQueued: Long get() = queued.get()

    private val highWater = AtomicLong(0)

    /** The most text lines that have been waiting at once since this bus was built. */
    val textHighWater: Long get() = highWater.get()

    private val rejected = AtomicLong(0)

    /**
     * Text lines that could not be queued because the bus was closed. A live bus does not reject
     * a line to make room. Frames are not counted here: replacing the latest frame is the point
     * of that lane, and counting every waterfall frame would be noise.
     */
    val textRejected: Long get() = rejected.get()

    init {
        scope.launch {
            for (item in inbox) {
                queued.decrementAndGet()
                // Retired with the socket. Delivering it now would apply a dead connection's
                // `tx 0` or `ctl` to whatever press the new socket has started.
                if (item.generation != epoch.get()) continue
                // extraBufferCapacity does not replay to a collector that was not subscribed
                // yet, so emitting into an empty SharedFlow would swallow the line. Wait until
                // someone is listening, then check the generation again: the socket may have
                // died while this line sat here.
                text.subscriptionCount.first { it > 0 }
                if (item.generation != epoch.get()) continue
                text.emit(item.event)
            }
        }
    }

    /** The generation a new socket's listener stamps on every line it reads. */
    fun openConnection(): Int = epoch.incrementAndGet()

    /**
     * That socket is gone. Queued lines stamped with [generation] are skipped, and a listener that
     * still holds this generation can no longer enqueue.
     */
    fun closeConnection(generation: Int) {
        epoch.compareAndSet(generation, generation + 1)
    }

    /**
     * One line from the socket [generation]. Text is queued. Spectrum and screen replace the
     * latest frame. A generation that [closeConnection] has already retired is ignored.
     */
    fun post(generation: Int, event: PanelEvent) {
        if (generation != epoch.get()) return
        when (event) {
            is PanelEvent.Spectrum, is PanelEvent.Screen -> pictures.tryEmit(event)
            is PanelEvent.AppWaterfall -> appPictures.tryEmit(event)
            is PanelEvent.WeFaxRow -> faxPictures.tryEmit(event)
            else -> enqueue(generation, event)
        }
    }

    /** Stop the bridge. Queued text is still delivered; further [post] calls are rejected. */
    fun close() {
        inbox.close()
    }

    private fun enqueue(generation: Int, event: PanelEvent) {
        val waiting = queued.incrementAndGet()
        highWater.updateAndGet { max -> maxOf(max, waiting) }
        val result = inbox.trySend(Item(generation, event))
        if (result.isFailure) {
            queued.decrementAndGet()
            rejected.incrementAndGet()
        }
    }

    private companion object {
        /** Room in front of a slow collector before the bridge coroutine suspends. The unlimited
         *  inbox holds the rest, in order, so this bound never becomes a drop. */
        const val TEXT_BUFFER = 64
    }
}
