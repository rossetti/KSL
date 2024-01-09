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
import ksl.utilities.random.rng.RNStreamIfc

class MVGaussianCopulaRV(
    val marginals: List<InverseCDFIfc>,
    correlation: Array<DoubleArray>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVRVariable(stream) {

    init {
        require(marginals.size > 1) { "The number of supplied marginals must be at least 2" }
        require(marginals.size == correlation.size) {" The number of supplied marginals must be equal to ${correlation.size}"}
    }

    private val myCopula = MVGaussianCopula(correlation, stream)

    override val dimension: Int
        get() = myCopula.dimension

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        // generate the uniforms
        val u = myCopula.sample()
        // apply the inverse transform for each of the marginals
        for (i in u.indices) {
            array[i] = marginals[i].invCDF(u[i])
        }
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return MVGaussianCopulaRV(marginals, myCopula.correlations, stream)
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return MVGaussianCopulaRV(marginals, myCopula.correlations, rnStream.antitheticInstance())
    }

}