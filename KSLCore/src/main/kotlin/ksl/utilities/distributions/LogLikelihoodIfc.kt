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

package ksl.utilities.distributions

fun interface LogLikelihoodIfc {

    /**
     *  Computes the natural log of the likelihood function at
     *  the value [x]
     */
    fun logLikelihood(x: Double) : Double

    /**
     *  Computes the sum of the log-likelihood function
     *  evaluated at each observation in the [data].
     *  Implementations may want to specify computationally efficient
     *  formulas for this function.
     */
    fun sumLogLikelihood(data: DoubleArray) : Double {
        var sum = 0.0
        for(x in data){
            sum = sum + logLikelihood(x)
        }
        return sum
    }
}