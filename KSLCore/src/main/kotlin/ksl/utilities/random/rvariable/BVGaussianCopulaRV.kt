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

import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.random.rng.RNStreamIfc

/**
 *  Uses a bivariate Gaussian copula to produce (u_1, u_2) and uses the generated
 *  (u_1, u_2) to generate (x_1, x_2) where x_1 is from [marginal1] and x_2 is
 *  from [marginal2]. The joint distribution of (x_1, x_2) will have a correlation
 *  structure implied by the underlying bivariate Gaussian copula.
 *
 *  @param bvnCorrelation is the correlation of the bivariate Gaussian copula.
 *  The resulting correlation for (x_1, x_2) may not match this supplied correlation.
 */

class BVGaussianCopulaRV(
    val marginal1: InverseCDFIfc,
    val marginal2: InverseCDFIfc,
    val bvnCorrelation: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVRVariable(stream) {

    private val bvGaussianCopula = BVGaussianCopula(bvnCorrelation, stream)

    override val dimension: Int
        get() = bvGaussianCopula.dimension

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        // generate the uniforms
        val u = bvGaussianCopula.sample()
        // apply the inverse transform for each of the marginals
        array[0] = marginal1.invCDF(u[0])
        array[1] = marginal2.invCDF(u[1])
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return BVGaussianCopulaRV(marginal1, marginal2, bvnCorrelation, stream)
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return BVGaussianCopulaRV(marginal1, marginal2, bvnCorrelation, rnStream.antitheticInstance())
    }
}