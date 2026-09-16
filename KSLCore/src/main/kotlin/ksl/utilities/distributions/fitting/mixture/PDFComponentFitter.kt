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
package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc

/**
 *  Fits continuous families to a group by delegating to KSL's continuous distribution modeler.
 *
 *  Failure is detected using KSL's own signal rather than a re-derived list of preconditions.
 *  Every parameter estimator returns an estimation result carrying a success flag and a
 *  diagnostic message, and the modeler's own scoring stage discards a result when either the
 *  flag is false or no parameters were produced. This class applies the same predicate, for two
 *  reasons: a hand-maintained list of conditions drifts from what the estimators actually check,
 *  and reimplementing a library idiom works against the goal of lifting this package into the
 *  library.
 *
 *  The test is a disjunction because an estimation result is explicitly permitted to report
 *  failure while still carrying parameters; checking only for the absence of parameters would
 *  admit a fit that the estimator itself disowns.
 *
 *  @param estimators the candidate families to fit, defaulting to the modeler's full catalog
 *  @param automaticShifting whether the modeler may estimate a left shift for a group. A shift
 *  costs one additional estimated parameter, which is counted in the candidate.
 */
class PDFComponentFitter(
    estimators: Set<ParameterEstimatorIfc> = PDFModeler.allEstimators,
    val automaticShifting: Boolean = defaultAutomaticShifting
) : ComponentFitterIfc {

    private val myEstimators: Set<ParameterEstimatorIfc> = estimators.toSet()

    init {
        require(myEstimators.isNotEmpty()) { "There must be at least one estimator" }
    }

    /**
     *  The candidate families this fitter will attempt.
     */
    val estimators: Set<ParameterEstimatorIfc>
        get() = myEstimators.toSet()

    override fun fitGroup(sortedData: DoubleArray, startIndex: Int, endIndex: Int): GroupFitResult {
        require(startIndex >= 0) { "The start index must be >= 0. It was $startIndex" }
        require(endIndex <= sortedData.size) {
            "The end index must be <= the data size (${sortedData.size}). It was $endIndex"
        }
        require(startIndex < endIndex) {
            "The start index ($startIndex) must be < the end index ($endIndex)"
        }
        val groupData = sortedData.copyOfRange(startIndex, endIndex)
        val candidates = mutableListOf<ComponentCandidate>()
        val rejections = mutableListOf<ComponentRejection>()

        val results: List<EstimationResult> = try {
            PDFModeler(groupData).estimateParameters(myEstimators, automaticShifting)
        } catch (e: Exception) {
            // Deliberately broad, and deliberately not narrowed. This wraps the whole estimation
            // subsystem for one group rather than a single call, so the set of exceptions it can
            // see is not enumerable from here. What makes it safe is that nothing is swallowed:
            // whatever went wrong is reported as a rejection carrying its message, so a fault
            // shows up in the result rather than as a group that quietly fitted nothing.
            return GroupFitResult(
                startIndex, endIndex, emptyList(),
                listOf(ComponentRejection(null, "PDFModeler failed for the group: ${e.message}"))
            )
        }

        if (results.isEmpty()) {
            rejections.add(
                ComponentRejection(null, "The modeler produced no estimation results for the group")
            )
        }
        for (result in results) {
            // An estimator that reported failure, or produced no parameters, has nothing to
            // score. PDFModeler.scoringResults skips the same two cases; the wording here states
            // the rule rather than deferring to that function for it, because the two are
            // independent implementations that happen to agree and neither is the other's
            // specification.
            if (!result.success || (result.parameters == null)) {
                rejections.add(
                    ComponentRejection(
                        result.parameters?.rvType,
                        result.message ?: "The estimator reported failure without a message"
                    )
                )
                continue
            }
            // The success flag is necessary but not sufficient. An estimator may report
            // success with parameters from which no distribution can be constructed: a normal
            // estimator on constant data returns a zero variance, which the distribution
            // constructor rejects by throwing rather than by returning null. Constructing the
            // distribution is therefore part of validating the fit, not a step that follows it.
            val distribution = try {
                PDFModeler.createDistribution(result)
            } catch (e: IllegalArgumentException) {
                // Narrow deliberately: this is the distribution constructor's own require()
                // failing on parameter values the estimator was happy with. A broad catch would
                // also swallow a genuine fault in the modeler and turn it into a quietly rejected
                // component.
                rejections.add(
                    ComponentRejection(
                        result.parameters?.rvType,
                        "The estimator reported success but the distribution could not be " +
                                "constructed: ${e.message}"
                    )
                )
                continue
            }
            if (distribution == null) {
                rejections.add(
                    ComponentRejection(
                        result.parameters?.rvType,
                        "No continuous distribution is defined for the estimated parameters"
                    )
                )
                continue
            }
            val parameters = result.parameters!!
            // A shift is an estimated parameter and must be counted; the shifted wrapper
            // prepends it to the parameter array, so the count comes from the distribution.
            val numParameters = distribution.parameters().size
            val name = if (result.shiftedData != null) {
                "${result.shiftedData!!.shift} + $distribution"
            } else {
                distribution.toString()
            }
            candidates.add(
                ComponentCandidate(distribution, result, parameters.rvType, numParameters, name)
            )
        }
        return GroupFitResult(startIndex, endIndex, candidates, rejections)
    }

    override fun toString(): String {
        return "PDFComponentFitter(numEstimators=${myEstimators.size}, " +
                "automaticShifting=$automaticShifting)"
    }

    companion object {

        /**
         *  Whether a left shift is estimated for a group by default.
         *
         *  Shift estimation uses bootstrapping and is by a wide margin the most expensive part
         *  of fitting a group, so it is off by default and turned on deliberately. It is also an
         *  experimental factor: an estimated shift is an estimated support endpoint, which
         *  interacts with the support behaviour discussed in the specification.
         */
        var defaultAutomaticShifting: Boolean = false
    }
}
