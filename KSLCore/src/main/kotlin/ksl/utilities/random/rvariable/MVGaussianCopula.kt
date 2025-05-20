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
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 * Generations d-dimensional Gaussian copula, where the supplied
 * correlation matrix is the correlation for the underlying multi-variate normal.
 * Copulas generate correlated uniform random variates which can then be transformed.
 * This defines the dependence in terms of an underlying Gaussian distribution
 * (multi-variate normal distribution with the supplied correlation structure)
 *
 * @param correlation the correlation between the marginals
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class MVGaussianCopula @JvmOverloads constructor(
    correlation: Array<DoubleArray>,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : MVRVariable(streamNum, streamProvider, name){

    private val mvNormalRV: MVNormalRV = MVNormalRV.createStandardMVN(correlation, streamNum, streamProvider)
    private val myCorrelation = correlation

    val correlations
        get() =  myCorrelation.copyOf()

    override val dimension: Int
        get() = mvNormalRV.dimension

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): MVGaussianCopula {
        return MVGaussianCopula(myCorrelation, streamNumber, rnStreamProvider)
    }

    override fun generate(array: DoubleArray) {
        require(array.size == dimension) { "The length of the array was not the proper dimension" }
        // generate the normals
        val x = mvNormalRV.sample()
        for(i in array.indices){
            array[i] = Normal.stdNormalCDF(x[i])
        }
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