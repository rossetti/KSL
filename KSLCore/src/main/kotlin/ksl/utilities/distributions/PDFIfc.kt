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

import kotlin.math.exp
import kotlin.math.ln

/** Represents the probability density function for
 *  1-d continuous distributions
 *
 * @author rossetti
 */
interface PDFIfc : DomainIfc, LogLikelihoodIfc {

    /** Returns the f(x) where f represents the probability
     * density function for the distribution.  Note this is not
     * a probability.
     *
     * @param x a double representing the value to be evaluated
     * @return f(x)  This should be a strictly positive number
     */
    fun pdf(x: Double): Double

    /**
     *  Computes the natural log of the pdf function evaluated at [x].
     *  Implementations may want to specify computationally efficient
     *  formulas for this function.
     */
    override fun logLikelihood(x: Double): Double {
        return ln(pdf(x))
    }

    /**
     *  Assuming that the observations in the array [data]
     *  are from a random sample, this function computes
     *  the likelihood function. This is computed using
     *  as the sum of the log-likelihood function raised
     *  to e. Implementation may want to specify other computationally
     *  efficient formulas for this function.
     */
    fun likelihood(data: DoubleArray) : Double {
        return exp(sumLogLikelihood(data))
    }
}