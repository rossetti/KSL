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
 *  Generates a bivariate Gaussian copula. (u_1, u_2)
 *  @param bvnCorrelation is the correlation of the bivariate normal random variable.
 *  The resulting correlation for (u_1, u_2) may not match this supplied correlation.
 */
class BVGaussianCopula(
    val bvnCorrelation: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVRVariable(stream) {

    private val bvn = BivariateNormalRV(corr = bvnCorrelation, stream = stream)

    override val dimension: Int
        get() = bvn.dimension

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        val x = bvn.sample()
        for(i in array.indices){
            array[i] = Normal.stdNormalCDF(x[i])
        }
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return BVGaussianCopula(bvn.corr, stream)
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return BVGaussianCopula(bvn.corr, bvn.rnStream.antitheticInstance())
    }

}