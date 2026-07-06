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

package ksl.animation.replay

import ksl.animation.AnimationEvent

/**
 * A single-pass accumulator over a trace event stream. One streaming traversal updates many of
 * these at once (observed extent, location positions, the flow graph), so a large trace is never
 * fully buffered. Implementations keep only bounded, running state.
 */
interface TraceAccumulator<out R> {
    /** Fold one event into the running state. Must be O(1)-ish and hold no per-event references. */
    fun accept(event: AnimationEvent)

    /** The accumulated result after the pass (or after an early stop). */
    fun result(): R
}

/**
 * Stops a streaming pass once it stabilizes: after [window] consecutive events with no observed
 * change (e.g. the flow graph added no new edge), [saturated] becomes true. An accumulator feeds it
 * via [observe] so the miner can break early — a large trace then processes only a prefix.
 */
class SaturationStop(private val window: Int = 2000) {
    private var quietRun = 0

    /** Record whether the just-processed event changed the structure being watched. */
    fun observe(changed: Boolean) {
        quietRun = if (changed) 0 else quietRun + 1
    }

    val saturated: Boolean get() = quietRun >= window
}

/**
 * Runs [accumulators] over an event [Sequence] in a single pass, breaking when [stopWhen] returns
 * true (so a saturated flow graph or an over-long trace processes only a prefix). The sequence is
 * consumed lazily; pair this with `TraceFileReader.readStreaming` so the underlying trace is never
 * held in memory and is closed even on an early stop.
 */
class StreamingTraceMiner(
    private val accumulators: List<TraceAccumulator<*>>,
    private val stopWhen: () -> Boolean = { false },
) {
    fun run(events: Sequence<AnimationEvent>) {
        for (event in events) {
            for (acc in accumulators) acc.accept(event)
            if (stopWhen()) break
        }
    }
}
