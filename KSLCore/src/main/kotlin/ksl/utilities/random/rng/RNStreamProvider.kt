/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

import mu.KLoggable
import mu.KotlinLogging

//private val logger = KotlinLogging.logger {}

/**
 * A concrete implementation of RNStreamProviderIfc.  If more than streamNumberWarningLimit
 * streams are made a warning message is logged.  Generally, unless you know what you are doing
 * you should not need an immense number of streams.  Instead, use a small number of
 * streams many times. Conceptually this provider could have a possibly infinite number of streams,
 * which would have bad memory implications.  Thus, the reason for the warning.
 * The default stream if not set is the first stream.
 */
class RNStreamProvider(defaultStreamNum: Int = 1) : RNStreamProviderIfc {
    init {
        require(defaultStreamNum > 0) {
            "The default stream number must be > 0!"
        }
    }

    var streamNumberWarningLimit = 5000
        set(value) {
            require(value > 0) { "The stream number warning limit must be at least 1" }
            field = value
        }

    private val myStreamFactory: RNStreamFactory = RNStreamFactory()

    private val myStreams: MutableList<RNStreamIfc> = ArrayList()

    override val defaultStreamNumber: Int = defaultStreamNum

    init {
        defaultRNStream()
    }

    override fun nextRNStream(): RNStreamIfc {
        val stream = myStreamFactory.nextStream()
        myStreams.add(stream)
        if (myStreams.size > streamNumberWarningLimit) {
            logger.warn("The number of streams made is now = {}", myStreams.size)
            logger.warn("Increase the stream warning limit if you don't want to see this message")
        }
        logger.info { "Provided stream ${stream.id}, stream ${lastRNStreamNumber()} of ${myStreams.size} streams" }
        return stream
    }

    override fun lastRNStreamNumber() = myStreams.size

    override fun rnStream(i: Int): RNStreamIfc {
        require(i > 0) {
            "The stream number must be > 0!"
        }
        if (i > lastRNStreamNumber()) {
            var stream: RNStreamIfc? = null
            for (j in lastRNStreamNumber()..i) {
                stream = nextRNStream()
            }
            // this is safe because there must be at least one call to nextRNStream()
            return stream!!
        }
        return myStreams[i - 1]
    }

    override fun streamNumber(stream: RNStreamIfc): Int {
        return if (myStreams.indexOf(stream) == -1) {
            -1
        } else myStreams.indexOf(stream) + 1
    }

    override fun advanceStreamMechanism(n: Int) {
        myStreamFactory.advanceSeeds(n)
    }

    override fun resetRNStreamSequence() {
        myStreams.clear()
        myStreamFactory.resetFactorySeed()
    }

    /**
     * Gets the default initial seed: seed = {12345, 12345, 12345,
     * 12345, 12345, 12345};
     *
     * @return an array holding the initial seed values
     */
    fun defaultInitialSeed(): LongArray {
        return myStreamFactory.defaultInitialFactorySeed()
    }

    /**
     * Returns the current seed
     *
     * @return the array of seed values for the current state
     */
    fun currentSeed(): LongArray {
        return myStreamFactory.getFactorySeed()
    }

    /**
     * Sets the initial seed to the six integers in the vector seed[0..5]. This
     * will be the seed (initial state) of the first stream. By default, this
     * seed is (12345, 12345, 12345, 12345, 12345, 12345).
     *
     * If it is	called,	the first 3 values of the seed must all be less than m1
     * = 4294967087, and not all 0; and the last 3 values must all be less than
     * m2 = 4294944443, and not all 0.
     *
     * @param seed the seeds
     */
    fun initialSeed(seed: LongArray = longArrayOf(12345, 12345, 12345, 12345, 12345, 12345)) {
        myStreamFactory.setFactorySeed(seed)
    }

    companion object : KLoggable {
        override val logger = logger()
    }
}