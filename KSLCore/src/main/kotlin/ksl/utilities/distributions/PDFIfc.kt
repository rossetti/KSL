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
     *
     *  Only a density that is exactly zero, because [x] is outside the support or because the
     *  density underflowed, is replaced by the floor ln(Double.MIN_VALUE), about -744.44. A small
     *  positive density is a legitimate tail value and keeps its true logarithm; treating densities
     *  below a numerical tolerance as zero added roughly 1,450 to BIC for each such observation and
     *  depended on the units the data were recorded in. The floor is the logarithm of the smallest
     *  positive double, so the result is continuous and increasing in the density.
     *
     *  A distribution that computes its log-density directly (Gamma, Lognormal, Weibull,
     *  PearsonType5) returns the exact value even where `pdf` underflows, which can lie below the
     *  floor. An observation astronomically far in a fitted tail then scores as badly as it really
     *  does, and can score worse than one outside the support; both mark a candidate that fits
     *  very poorly.
     *
     *  The floor keeps single-distribution BIC and AIC finite and rankable. The mixture code's
     *  `MixtureLogLikelihood` deliberately returns negative infinity instead and leaves the decision
     *  to its caller, since a mixture assigning no density to an observation is itself the finding.
     */
    override fun logLikelihood(x: Double): Double {
        val y = pdf(x)
        if (y.isNaN() || y <= 0.0) return ln(Double.MIN_VALUE)
        return ln(y)
    }

    /**
     *  Assuming that the observations in the array [data]
     *  are from a random sample, this function computes
     *  the likelihood function. This is computed using
     *  as the sum of the log-likelihood function raised
     *  to e. Implementation may want to specify other computationally
     *  efficient formulas for this function or (most likely)
     *  the sum of the log-likelihood function.
     */
    fun likelihood(data: DoubleArray) : Double {
        return exp(sumLogLikelihood(data))
    }
}