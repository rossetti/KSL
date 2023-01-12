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
package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

/**
 * NegativeBinomial(probability of success, number of trials until rth success)
 * @param probOfSuccess       the probability of success, must be in (0,1)
 * @param numSuccess number of trials until rth success
 * @param stream     the stream from the stream provider to use
 */
class NegativeBinomialRV(
    val probOfSuccess: Double,
    val numSuccess: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : ParameterizedRV(stream, name) {
    init {
        require(!(probOfSuccess <= 0.0 || probOfSuccess >= 1.0)) { "Success Probability must be (0,1)" }
        require(numSuccess > 0) { "Number of trials until rth success must be > 0" }
    }

    /**
     * @param prob       the probability of success, must be in (0,1)
     * @param numSuccess number of trials until rth success
     * @param streamNum  the stream number from the stream provider to use
     */
    constructor(prob: Double, numSuccess: Double, streamNum: Int) : this(
        prob, numSuccess, KSLRandom.rnStream(streamNum)
    )

    override fun instance(stream: RNStreamIfc): NegativeBinomialRV {
        return NegativeBinomialRV(probOfSuccess, numSuccess, stream)
    }

    override fun generate(): Double {
        return KSLRandom.rNegBinomial(probOfSuccess, numSuccess, rnStream).toDouble()
    }

    override fun toString(): String {
        return "NegativeBinomialRV(probOfSuccess=$probOfSuccess, numSuccess=$numSuccess)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = RVParameters.NegativeBinomialRVParameters()
            parameters.changeDoubleParameter("probOfSuccess", probOfSuccess)
            parameters.changeIntegerParameter("numSuccesses", numSuccess.toInt())
            return parameters
        }
}