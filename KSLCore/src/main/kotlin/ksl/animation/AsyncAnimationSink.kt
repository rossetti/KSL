/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.animation

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * An [AnimationSink] that hands events to a dedicated background writer thread
 * through a bounded queue, so serialization and disk I/O happen off the
 * simulation thread. This is the sink for *watching a long run as it executes*,
 * where buffering the whole run in memory (as `MemoryBufferedAnimationSink` does)
 * is not viable.
 *
 * The simulation thread (producer) calls [emit]; a single writer thread (consumer)
 * takes events in order and passes each to [consume] (which serializes/writes it).
 * A bounded [BlockingQueue] sits between them and absorbs bursts. What happens when
 * the producer outruns the writer and the queue fills is governed by
 * [overflowPolicy]:
 *  - [OverflowPolicy.BLOCK] (default) — the producer waits for space. *Lossless*;
 *    may briefly slow the simulation under a sustained burst. Preferred for
 *    animation because events are state deltas: dropping one corrupts the
 *    renderer's reconstructed state downstream.
 *  - [OverflowPolicy.DROP_NEWEST] — discard the incoming event; never slows the sim.
 *  - [OverflowPolicy.DROP_OLDEST] — evict the oldest queued event to make room.
 *
 * Shutdown is via [onExperimentEnd], which enqueues a sentinel and joins the writer
 * thread, guaranteeing every already-emitted event is written before it returns
 * (no tail loss).
 *
 * @param capacity the bounded queue size (number of events); must be >= 1
 * @param overflowPolicy what [emit] does when the queue is full
 * @param consume the write action invoked on the writer thread for each event,
 *        in emission order (e.g. `{ event -> output.write(event) }`)
 */
class AsyncAnimationSink(
    capacity: Int = DEFAULT_CAPACITY,
    private val overflowPolicy: OverflowPolicy = OverflowPolicy.BLOCK,
    private val consume: (AnimationEvent) -> Unit
) : AnimationSink {

    /** Policy applied by [emit] when the bounded queue is full. */
    enum class OverflowPolicy { BLOCK, DROP_NEWEST, DROP_OLDEST }

    init {
        require(capacity >= 1) { "capacity ($capacity) must be >= 1" }
    }

    // The queue holds events plus, at shutdown, the [poison] sentinel; hence Any.
    private val queue: BlockingQueue<Any> = ArrayBlockingQueue(capacity)
    private val poison = Any()

    private val closed = AtomicBoolean(false)
    private val dropped = AtomicLong(0)
    private val writeErrors = AtomicLong(0)

    /** Number of events discarded under a drop policy (0 under BLOCK). */
    val droppedCount: Long
        get() = dropped.get()

    /** Number of events whose [consume] call threw and was swallowed. */
    val writeErrorCount: Long
        get() = writeErrors.get()

    override val isActive: Boolean
        get() = true

    override fun emit(event: AnimationEvent) {
        if (closed.get()) return // refuse events after shutdown so emit can never block forever
        when (overflowPolicy) {
            OverflowPolicy.BLOCK -> queue.put(event) // waits for space; lossless
            OverflowPolicy.DROP_NEWEST -> if (!queue.offer(event)) dropped.incrementAndGet()
            OverflowPolicy.DROP_OLDEST -> while (!queue.offer(event)) {
                if (queue.poll() != null) dropped.incrementAndGet()
            }
        }
    }

    /** Drains all emitted events and stops the writer thread. Idempotent. */
    override fun onExperimentEnd() {
        if (closed.getAndSet(true)) return
        queue.put(poison)      // enqueued after every prior event; FIFO drains them first
        writerThread.join()    // block until the writer consumes the sentinel and exits
    }

    private fun runWriter() {
        while (true) {
            val item = queue.take() // blocks until an item is available
            if (item === poison) break
            try {
                consume(item as AnimationEvent)
            } catch (e: Throwable) {
                writeErrors.incrementAndGet() // fail soft: one bad write must not kill the writer
            }
        }
    }

    // Declared last so every field above is initialized before the thread starts.
    private val writerThread: Thread = Thread({ runWriter() }, "ksl-animation-writer").apply {
        isDaemon = true
        start()
    }

    companion object {
        /** Default bounded-queue capacity (number of events). */
        const val DEFAULT_CAPACITY: Int = 65_536
    }
}
