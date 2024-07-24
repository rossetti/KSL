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

package ksl.utilities.random.robj

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 *  Allows the picking between two alternatives according to a Bernoulli process.
 *  @param firstProbability the probability associated with the first choice
 *  @param first the first choice
 *  @param second the second choice
 *  @param stream the associated random number stream
 */
class BernoulliPicker<T>(
    private val firstProbability: Double,
    private val first: T,
    private val second: T,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RElementIfc<T> {

    constructor(
        firstProbability: Double,
        first: T,
        second: T,
        streamNum: Int,
    ) : this(firstProbability, first, second, KSLRandom.rnStream(streamNum))

    init {
        require(!(firstProbability <= 0.0 || firstProbability >= 1.0)) { "Probability must be (0,1)" }
    }

    override var rnStream: RNStreamIfc = stream

    override val randomElement: T
        get() = if (rnStream.randU01() <= firstProbability) first else second

}