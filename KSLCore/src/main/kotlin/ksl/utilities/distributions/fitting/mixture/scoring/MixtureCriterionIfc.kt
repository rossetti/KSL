/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.distributions.fitting.mixture.scoring

import ksl.utilities.distributions.fitting.mixture.MixtureCandidate
import ksl.utilities.moda.MetricIfc

/**
 *  The value of a criterion for one candidate, together with whether that value may be compared
 *  with another candidate's.
 *
 *  A criterion computed from a log-likelihood that is not finite, or from a candidate that
 *  assigns zero density to some observation, is not on the same footing as one computed
 *  normally. Reporting that alongside the number, rather than substituting a bounded worst
 *  value and moving on, is what keeps a ranking honest.
 *
 *  @param value the criterion value
 *  @param isComparable whether the value may be ranked against other candidates
 *  @param numZeroDensity the number of observations at which the candidate's density is zero
 *  @param message an explanation when the value is not comparable, otherwise null
 */
data class MixtureCriterionValue(
    val value: Double,
    val isComparable: Boolean,
    val numZeroDensity: Int,
    val message: String? = null
)

/**
 *  A criterion by which fitted mixtures are ranked.
 *
 *  Implementations must obtain the number of free parameters from the candidate rather than from
 *  an assembled mixture distribution. The library's mixture distribution reports one parameter
 *  per mixing weight, but only one fewer than that number are free, so reading the count from
 *  the assembled distribution overstates the dimension.
 *
 *  Implementations must also screen the log-likelihood for finiteness before using it. The
 *  library's information criterion helpers reject a non-finite log-likelihood by throwing, and a
 *  mixture produces one routinely: whenever a component assigns zero density to an observation
 *  it was not fitted to. Turning an expected modelling outcome into an exception mid-experiment
 *  is not acceptable, so the value is bounded and marked incomparable instead.
 */
interface MixtureCriterionIfc {

    /**
     *  A short name for the criterion, used as a column heading and a factor level.
     */
    val name: String

    /**
     *  Whether smaller values indicate a better fit. Information criteria are smaller-is-better;
     *  a held-out mean log-likelihood is not. Ranking machinery must consult this rather than
     *  assuming a direction.
     */
    val smallerIsBetter: Boolean
        get() = true

    /**
     *  A metric describing this criterion, for use with the library's multi-objective decision
     *  analysis machinery. The direction is taken from this interface.
     *
     *  Note that metric domains are rescaled to the observed range of alternatives during a
     *  decision analysis, which mutates the metric, so a fresh instance is required per
     *  evaluation and per thread.
     */
    fun metric(): MetricIfc

    /**
     *  Evaluates the criterion for the supplied candidate against the supplied observations.
     *
     *  @param candidate the assembled mixture to evaluate
     *  @param data the observations the candidate was fitted to
     */
    fun evaluate(candidate: MixtureCandidate, data: DoubleArray): MixtureCriterionValue
}
