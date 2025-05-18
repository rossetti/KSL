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

import ksl.utilities.distributions.PDFIfc
import ksl.utilities.random.rng.RNStreamProviderIfc


/**
 * Provides a framework for generating random variates using the
 * ratio of uniforms method.
 * Specifies the pair (u, v), with ratio v/u
 *
 * @param umax the maximum bound in the "u" variate
 * @param vmin the minimum bound for the "v" variate
 * @param vmax the maximum bound in the "v" variate
 * @param f the desired PDF
 * @param rnStream the random number stream to use
 */
class RatioOfUniformsRV (
    umax: Double,
    vmin: Double,
    vmax: Double,
    f: PDFIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamNum, streamProvider, name) {

    private val uCDF: UniformRV = UniformRV(0.0, umax, streamNum, streamProvider, name)
    private val vCDF: UniformRV = UniformRV(vmin, vmax, streamNum, streamProvider,name)
    private val pdf: PDFIfc = f

    override fun generate(): Double {
        while (true) {
            val u = uCDF.value
            val v = vCDF.value
            val z = v / u
            if (u * u < pdf.pdf(z)) {
                return z
            }
        }
    }

    override fun instance(streamNum: Int, rnStreamProvider: RNStreamProviderIfc): RatioOfUniformsRV {
        return RatioOfUniformsRV(uCDF.max, vCDF.min, vCDF.max, pdf, streamNum, rnStreamProvider, name)
    }
}