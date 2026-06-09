/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.random.rng

/**
 * Controls the movement through a pseudo-random number stream
 *
 */
interface RNStreamControlIfc : StreamOptionIfc {

    /**
     * The resetStartStream method will position the RNG at the beginning of its
     * stream. This is the same location in the stream as assigned when the RNG
     * was created and initialized.
     */
    fun resetStartStream()

    /**
     * Resets the position of the RNG at the start of the current substream
     */
    fun resetStartSubStream()

    /**
     * Positions the RNG at the beginning of its next substream
     */
    fun advanceToNextSubStream()

    /**
     * Advances the stream by n sub-streams, leaving it at the start of the sub-stream that
     * n successive calls to advanceToNextSubStream would reach. A value of 0 leaves the stream
     * unchanged. The default implementation advances one sub-stream at a time; generators with
     * jump-ahead support should override with an O(log n) skip-ahead.
     *
     * @param n the number of sub-streams to advance; must be >= 0
     */
    fun advanceSubStreams(n: Long) {
        require(n >= 0) { "The number of sub-streams to advance must be >= 0; was $n" }
        var i = 0L
        while (i < n) {
            advanceToNextSubStream()
            i++
        }
    }

    /**
     * Tells the stream to start producing antithetic variates
     */
    var antithetic: Boolean
}