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

package ksl.utilities.random.rvariable

import ksl.utilities.distributions.Normal
import ksl.utilities.random.rng.RNStreamIfc

/**
 * Uses the autoregressive to anything algorithm
 * to generate correlated uniform variates.
 * The user supplies the correlation of the underlying
 * AR(1) process.  The resulting correlation in the u's
 * may not necessarily meet this correlation, due to
 * the correlation matching problem.
 */
class AR1CorrelatedRNStream @JvmOverloads constructor(
    val lag1Corr: Double,
    private val stream: RNStreamIfc = KSLRandom.nextRNStream(),
) : RNStreamIfc by stream {
    private var myX: Double = 0.0
    private val errorVariance: Double

    init {
        require( (-1.0 < lag1Corr) && (lag1Corr < 1.0)){ "The correlation must be (-1,1)" }
        // generate the first value for the process N(mean, variance)
        myX = KSLRandom.rNormal(stream)
        errorVariance = (1.0 - lag1Corr * lag1Corr)
    }

    private var myPrevU: Double = Double.NaN

    override val previousU: Double
        get() = myPrevU

    override fun randU01(): Double {
        val e = KSLRandom.rNormal(0.0, errorVariance, stream)
        myX = lag1Corr * myX + e
        val u = Normal.stdNormalCDF(myX)
        myPrevU = u
        return u
    }
}