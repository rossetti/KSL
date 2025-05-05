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

import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.parameters.BinomialRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * BinomialRV(probability of success, number of trials)
 * @param pSuccess  the probability of success, must be in (0,1)
 * @param numTrials the number of trials, must be greater than 0
 * @param stream    the stream from the stream provider to use
 * @param name an optional name
 */
class BinomialRV constructor(
    val pSuccess: Double,
    val numTrials: Int,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : ParameterizedRV(streamProvider, name) {
    init {
        require(!(pSuccess < 0.0 || pSuccess > 1.0)) { "Success Probability must be [0,1]" }
        require(numTrials > 0) { "Number of trials must be >= 1" }
    }

    /**
     * @param pSuccess  the probability of success, must be in (0,1)
     * @param numTrials the number of trials, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     */
    constructor(
        pSuccess: Double,
        numTrials: Int,
        streamNum: Int,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(
        pSuccess,
        numTrials,
        streamProvider,
        name
    ) {
        rnStream = streamProvider.rnStream(streamNum)
    }

    override fun instance(streamNum: Int): BinomialRV {
        return BinomialRV(pSuccess, numTrials, streamNum, streamProvider, name)
    }

    override fun generate(): Double {
        return KSLRandom.rBinomial(pSuccess, numTrials, rnStream).toDouble()
    }

    override fun toString(): String {
        return "BinomialRV(pSuccess=$pSuccess, numTrials=$numTrials)"
    }

    override val parameters: RVParameters
        get() {
            val parameters: RVParameters = BinomialRVParameters()
            parameters.changeDoubleParameter("probOfSuccess", pSuccess)
            parameters.changeDoubleParameter("numTrials", numTrials.toDouble())
            return parameters
        }

}