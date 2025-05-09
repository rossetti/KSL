/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  Defines a multi-variate Gaussian copula random variable
 *
 * @param marginals the marginals for each dimension
 * @param correlation the correlation between the marginals
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class MVGaussianCopulaRV(
    val marginals: List<InverseCDFIfc>,
    correlation: Array<DoubleArray>,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : MVRVariable(streamNumber, streamProvider, name) {

    init {
        require(marginals.size > 1) { "The number of supplied marginals must be at least 2" }
        require(marginals.size == correlation.size) {" The number of supplied marginals must be equal to ${correlation.size}"}
    }

    private val myCopula = MVGaussianCopula(correlation, streamNumber, streamProvider)

    override val dimension: Int
        get() = myCopula.dimension

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): MVGaussianCopulaRV {
        return MVGaussianCopulaRV(marginals, myCopula.correlations, streamNumber, rnStreamProvider)
    }

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        // generate the uniforms
        val u = myCopula.sample()
        // apply the inverse transform for each of the marginals
        for (i in u.indices) {
            array[i] = marginals[i].invCDF(u[i])
        }
    }


}