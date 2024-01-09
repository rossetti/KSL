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

import ksl.utilities.KSLArrays
import ksl.utilities.distributions.Normal
import ksl.utilities.random.rng.RNStreamIfc

/**
 * Generations d-dimensional Gaussian copula, where the supplied
 * correlation matrix is the correlation for the underlying multi-variate normal
 *
 * @param correlation the covariance of the random variable
 * @param stream      the stream for sampling
 */
class MVGaussianCopula(
    correlation: Array<DoubleArray>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVRVariable(stream){

    private val mvNormalRV: MVNormalRV = MVNormalRV.createStandardMVN(correlation, stream)
    private val myCorrelation = correlation

    override val dimension: Int
        get() = mvNormalRV.dimension

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        // generate the normals
        val x = mvNormalRV.sample()
        for(i in array.indices){
            array[i] = Normal.stdNormalCDF(x[i])
        }
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return MVGaussianCopula(myCorrelation, stream)
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return MVGaussianCopula(myCorrelation, mvNormalRV.antitheticInstance().rnStream)
    }

}

fun main(){
    val cov = arrayOf(
        doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0),
        doubleArrayOf(1.0, 2.0, 2.0, 2.0, 2.0),
        doubleArrayOf(1.0, 2.0, 3.0, 3.0, 3.0),
        doubleArrayOf(1.0, 2.0, 3.0, 4.0, 4.0),
        doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
    )
    val rho = MVNormalRV.convertToCorrelation(cov)
    val rv = MVGaussianCopula(rho)
    for (i in 1..5) {
        val sample: DoubleArray = rv.sample()
        println(KSLArrays.toCSVString(sample))
    }
}