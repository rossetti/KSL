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

package ksl.utilities.distributions.fitting.estimators

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.distributions.MetalogBoundedness
import ksl.utilities.distributions.MetalogFeasibilityChecker
import ksl.utilities.distributions.MetalogFunctions
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  Estimates the parameters of a metalog with a fixed number of terms and a fixed boundedness.
 *
 *  Estimation is least squares on cumulative distribution function data, which is what makes the
 *  metalog unusual among the families the KSL fits: the quantile function is linear in its
 *  coefficients, so no nonlinear optimization is involved.
 *
 *  Two things distinguish this from the other estimators here. Least squares can return
 *  coefficients whose quantile function is not strictly increasing, which is not a distribution;
 *  when that happens and the fallback is enabled, the fit is retried as a linear program with the
 *  monotonicity imposed as constraints, which is valid by construction. And a bound, where the
 *  chosen boundedness needs one, is profiled from the data unless supplied, by fitting at each
 *  candidate and keeping whichever fits the observations best.
 *
 *  No shift of the data is performed, which is what `checkRange` being false means here. A metalog
 *  represents a lower bound natively: the lower-bounded member is `bl + exp(M(y))`, so its bound
 *  already *is* the threshold parameter a shift would estimate, and the two are not jointly
 *  identifiable. The unbounded member is supported on the whole real line and has no origin to
 *  anchor. This is the same treatment the normal, uniform, triangular, and generalized beta
 *  estimators receive, and for the same reason — each of those carries its own location or limits.
 *
 *  Nothing about automatic shifting changes what these estimators produce. When the shift applies,
 *  `PDFModeler.estimateParameters` hands the shifted data only to estimators whose `checkRange` is
 *  true, so a metalog fit is identical either way.
 *
 *  The remaining consideration is only wasted work, and it is not specific to this family: the
 *  bootstrap for the minimum runs once per call whenever `automaticShifting` is true, whether or
 *  not any estimator in the set can use the result. Fitting only estimators that decline the shift
 *  therefore pays for a bootstrap nobody reads. Whether that is worth avoiding depends on the
 *  sample size — measured against the twenty metalog fits, the bootstrap is roughly a sixth of
 *  their cost at a thousand observations, comparable at ten thousand, and several times their cost
 *  beyond about a hundred thousand, since the fits work from a fixed-size resampling grid while the
 *  bootstrap grows with the sample. Passing `automaticShifting = false` is worth it for a
 *  metalog-only run on a large sample and is close to irrelevant on a small one. Leave it on when
 *  the classical families are in the same set, since they do use it.
 *
 *  ## What to trust in the result
 *
 *  Trust the fitted distribution. Do not read much into which arity and boundedness produced it.
 *
 *  Fitting the whole family and taking the top-ranked result recovers the underlying distribution
 *  well: measured against data generated from known metalogs, the fitted distribution sits a
 *  median Kolmogorov distance of about 0.02 to 0.03 from the generating one at a thousand
 *  observations, which is inside the sampling noise of the data itself, and the distance falls
 *  steadily as the sample grows.
 *
 *  Which member of the family carries that fit is another matter, and is close to arbitrary. The
 *  arities nest: a four-term metalog whose fourth coefficient is small is nearly a three-term
 *  metalog, and no quantity of data separates them. Boundedness has the same difficulty, since an
 *  unbounded metalog and a lower-bounded one whose bound sits far from the data agree everywhere
 *  the data lives. In measurements against known generating distributions, the exact generating
 *  arity and boundedness came back roughly one time in five, and raising the sample size by a
 *  factor of twenty did not systematically improve it.
 *
 *  This is the correct statistical outcome rather than a defect: the candidates really are
 *  indistinguishable, and the ranking is choosing among fits that describe the data equally well.
 *  It does mean that "the data came from a four-term metalog" is not a conclusion this estimator
 *  can support, and that a report naming the winning arity should not be read as identifying one.
 *
 *  See `MetalogRecoveryTest` for the measurements behind these statements.
 *
 *  @param numTerms how many metalog terms to fit, between two and six
 *  @param boundedness which member of the family to fit
 *  @param lowerBound the lower bound to use, or null to profile it from the data
 *  @param upperBound the upper bound to use, or null to profile it from the data
 *  @param useLPFallback whether to retry an invalid least squares fit as a linear program
 *  @param ridge the ridge parameter for the least squares solve
 *  @param name an optional name, defaulting to one naming the terms and the boundedness
 */
class MetalogParameterEstimator(
    val numTerms: Int,
    val boundedness: MetalogBoundedness,
    val lowerBound: Double? = null,
    val upperBound: Double? = null,
    val useLPFallback: Boolean = true,
    val ridge: Double = 0.0,
    name: String? = null
) : ParameterEstimatorIfc, MVBSEstimatorIfc,
    IdentityIfc by Identity(name ?: defaultName(numTerms, boundedness)) {

    private val myOLSSolver = MetalogOLSSolver(ridge)
    private val myProfiler = MetalogBoundProfiler()

    init {
        require(numTerms >= MIN_TERM_COUNT) {
            "The number of terms $numTerms must be at least $MIN_TERM_COUNT"
        }
        require(numTerms <= MAX_TERM_COUNT) {
            "The number of terms $numTerms must be at most $MAX_TERM_COUNT"
        }
        if (lowerBound != null) {
            require(lowerBound.isFinite()) { "A supplied lower bound must be finite" }
            require(boundedness.hasLowerBound) {
                "A lower bound was supplied but $boundedness does not use one"
            }
        }
        if (upperBound != null) {
            require(upperBound.isFinite()) { "A supplied upper bound must be finite" }
            require(boundedness.hasUpperBound) {
                "An upper bound was supplied but $boundedness does not use one"
            }
        }
    }

    override val rvType: RVParametersTypeIfc
        get() = typeFor(numTerms)

    /**
     *  A metalog carries any lower bound natively, so shifting the data would be redundant and
     *  would work against the bound profiling.
     */
    override val checkRange: Boolean = false

    /**
     *  The coefficient names followed by whichever bounds this boundedness actually uses. Only
     *  bounds that are finite appear, so bootstrapping never has to average an infinity.
     */
    override val names: List<String> = buildList {
        for (i in 1..numTerms) {
            add("a$i")
        }
        if (boundedness.hasLowerBound) {
            add("lowerBound")
        }
        if (boundedness.hasUpperBound) {
            add("upperBound")
        }
    }

    /**
     *  The estimated values in the order given by the names, or an empty array when the estimation
     *  did not succeed.
     */
    override fun estimate(data: DoubleArray): DoubleArray {
        val result = estimateParameters(data, Statistic(data))
        if (!result.success || (result.parameters == null)) {
            return doubleArrayOf()
        }
        val parameters = result.parameters
        return DoubleArray(names.size) { parameters.doubleParameter(names[it]) }
    }

    override fun estimateParameters(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
        if (data.size < numTerms) {
            return failure(data, statistics, "There must be at least $numTerms observations to fit $numTerms terms")
        }
        if (data.any { !it.isFinite() }) {
            return failure(data, statistics, "Every observation must be finite")
        }
        // Scanned rather than run through distinct(), which boxes every observation into a set.
        // Only the existence of a second distinct value matters, and the scan leaves as soon as
        // it finds one.
        if (data.all { it == data[0] }) {
            return failure(data, statistics, "The observations must not all be identical")
        }
        val (values, probabilities) = MetalogPlottingPositions.cdfData(data)
        val search = searchBounds(values, probabilities)
        var best = search.bestFeasible
        if (best == null) {
            if (!useLPFallback) {
                return failure(
                    data, statistics,
                    "No valid $numTerms term $boundedness metalog was found: the least squares fit " +
                            "produced a quantile function that was not strictly increasing, and the " +
                            "linear program fallback was disabled"
                )
            }
            val candidate = search.fallbackCandidate
                ?: return failure(data, statistics, "No usable bounds were available for $boundedness")
            best = fitByLinearProgram(values, probabilities, candidate.first, candidate.second)
        }
        if (best == null) {
            return failure(
                data, statistics,
                "No valid $numTerms term $boundedness metalog was found: the least squares fit " +
                        "produced a quantile function that was not strictly increasing, and the " +
                        "constrained linear program found no fit either"
            )
        }
        val parameters = buildParameters(best)
        val method = if (best.usedLinearProgram) "a constrained linear program" else "least squares"
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The $numTerms term $boundedness metalog parameters were estimated using " +
                    "$method, with ${boundDescription(best)}",
            success = true,
            estimator = this
        )
    }

    /**
     *  Searches the candidate bounds using least squares only.
     *
     *  The linear program is deliberately not used here. It is an order of magnitude more expensive
     *  than a least squares solve, and running one per candidate would multiply that cost by the
     *  length of the candidate ladder for no benefit, since a candidate whose least squares fit is
     *  invalid is rarely the one worth keeping. It is applied once afterward, and only when no
     *  candidate produced a valid fit at all.
     *
     *  For the bounded member the two bounds are searched one after the other rather than as a
     *  cross product. Sweeping both together would square the length of the ladder, which for the
     *  default ladder is over a hundred fits per estimation.
     */
    private fun searchBounds(values: DoubleArray, probabilities: DoubleArray): Search {
        val lowers = lowerCandidates(values)
        val uppers = upperCandidates(values)
        return when (boundedness) {
            MetalogBoundedness.Unbounded ->
                bestOver(values, probabilities, listOf(Pair(lowers[0], uppers[0])))

            MetalogBoundedness.LowerBounded ->
                bestOver(values, probabilities, lowers.map { Pair(it, Double.POSITIVE_INFINITY) })

            MetalogBoundedness.UpperBounded ->
                bestOver(values, probabilities, uppers.map { Pair(Double.NEGATIVE_INFINITY, it) })

            MetalogBoundedness.Bounded -> {
                // Hold the upper bound at its tightest candidate while choosing the lower, then
                // hold that lower while choosing the upper.
                val referenceUpper = uppers.first()
                val firstPass = bestOver(
                    values, probabilities,
                    lowers.filter { it < referenceUpper }.map { Pair(it, referenceUpper) }
                )
                val chosenLower = firstPass.bestFeasible?.lowerBound
                    ?: firstPass.fallbackCandidate?.first
                    ?: lowers.last()
                val secondPass = bestOver(
                    values, probabilities,
                    uppers.filter { it > chosenLower }.map { Pair(chosenLower, it) }
                )
                secondPass.betterOf(firstPass)
            }
        }
    }

    /**
     *  Fits at each supplied pair of bounds by least squares, keeping the best valid fit and, for
     *  the fallback, the candidate whose fit came closest in the fitting space whether or not it
     *  was valid.
     */
    private fun bestOver(
        values: DoubleArray,
        probabilities: DoubleArray,
        candidates: List<Pair<Double, Double>>
    ): Search {
        val design = MetalogFunctions.designMatrix(probabilities, numTerms)
        val checker = MetalogFeasibilityChecker.defaultChecker
        var bestFeasible: Fit? = null
        var fallbackCandidate: Pair<Double, Double>? = null
        var fallbackBadness = Double.POSITIVE_INFINITY
        for ((candidateLower, candidateUpper) in candidates) {
            val z = fittingSpace(values, candidateLower, candidateUpper) ?: continue
            val coefficients = myOLSSolver.solveOrNull(design, z) ?: continue
            val residual = fittingSpaceResidual(design, coefficients, z)
            if (residual < fallbackBadness) {
                fallbackBadness = residual
                fallbackCandidate = Pair(candidateLower, candidateUpper)
            }
            if (!checker.isFeasible(coefficients)) {
                continue
            }
            val fit = scoreFit(
                coefficients, values, probabilities, candidateLower, candidateUpper, false
            ) ?: continue
            if ((bestFeasible == null) || (fit.badness < bestFeasible.badness)) {
                bestFeasible = fit
            }
        }
        return Search(bestFeasible, fallbackCandidate)
    }

    /**
     *  One constrained solve at a fixed pair of bounds, used only after the least squares search
     *  has failed everywhere.
     */
    private fun fitByLinearProgram(
        values: DoubleArray,
        probabilities: DoubleArray,
        candidateLower: Double,
        candidateUpper: Double
    ): Fit? {
        val z = fittingSpace(values, candidateLower, candidateUpper) ?: return null
        val coefficients = MetalogLPSolver().solveOrNull(
            probabilities, z, numTerms, MetalogFeasibilityChecker.defaultChecker
        ) ?: return null
        return scoreFit(coefficients, values, probabilities, candidateLower, candidateUpper, true)
    }

    /**
     *  Maps the observations into the metalog's fitting space for a candidate pair of bounds, or
     *  null when any observation falls outside them.
     */
    private fun fittingSpace(
        values: DoubleArray,
        candidateLower: Double,
        candidateUpper: Double
    ): DoubleArray? {
        val z = DoubleArray(values.size)
        for (i in values.indices) {
            z[i] = try {
                boundedness.toFittingSpace(values[i], candidateLower, candidateUpper)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (!z[i].isFinite()) {
                return null
            }
        }
        return z
    }

    /**
     *  The sum of squared residuals in the fitting space, used only to rank candidates for the
     *  single fallback solve.
     */
    private fun fittingSpaceResidual(
        design: Array<DoubleArray>,
        coefficients: DoubleArray,
        z: DoubleArray
    ): Double {
        var total = 0.0
        for (i in z.indices) {
            var fitted = 0.0
            for (j in coefficients.indices) {
                fitted += design[i][j] * coefficients[j]
            }
            val residual = fitted - z[i]
            total += residual * residual
        }
        return if (total.isFinite()) total else Double.POSITIVE_INFINITY
    }

    private fun lowerCandidates(values: DoubleArray): DoubleArray {
        return when {
            !boundedness.hasLowerBound -> doubleArrayOf(Double.NEGATIVE_INFINITY)
            lowerBound != null -> doubleArrayOf(lowerBound)
            else -> myProfiler.lowerBoundCandidates(values)
        }
    }

    private fun upperCandidates(values: DoubleArray): DoubleArray {
        return when {
            !boundedness.hasUpperBound -> doubleArrayOf(Double.POSITIVE_INFINITY)
            upperBound != null -> doubleArrayOf(upperBound)
            else -> myProfiler.upperBoundCandidates(values)
        }
    }

    /**
     *  Scores a candidate fit by how far its quantile function sits from the observations.
     *
     *  The comparison is made in the units of the data rather than in the fitting space, because
     *  each candidate bound induces a different fitting space and residuals measured there are not
     *  comparable from one candidate to the next.
     */
    private fun scoreFit(
        coefficients: DoubleArray,
        values: DoubleArray,
        probabilities: DoubleArray,
        candidateLower: Double,
        candidateUpper: Double,
        usedLinearProgram: Boolean
    ): Fit? {
        var total = 0.0
        for (i in probabilities.indices) {
            val fitted = boundedness.fromFittingSpace(
                MetalogFunctions.quantile(coefficients, probabilities[i]),
                candidateLower, candidateUpper
            )
            if (!fitted.isFinite()) {
                return null
            }
            val residual = fitted - values[i]
            total += residual * residual
        }
        if (!total.isFinite()) {
            return null
        }
        return Fit(coefficients, candidateLower, candidateUpper, total, usedLinearProgram)
    }

    private fun buildParameters(fit: Fit): RVParameters {
        val parameters = typeFor(numTerms).rvParameters
        for (i in 1..numTerms) {
            parameters.changeDoubleParameter("a$i", fit.coefficients[i - 1])
        }
        parameters.changeDoubleParameter("lowerBound", fit.lowerBound)
        parameters.changeDoubleParameter("upperBound", fit.upperBound)
        return parameters
    }

    private fun boundDescription(fit: Fit): String {
        return when (boundedness) {
            MetalogBoundedness.Unbounded -> "no bounds"
            MetalogBoundedness.LowerBounded -> "a lower bound of ${fit.lowerBound}"
            MetalogBoundedness.UpperBounded -> "an upper bound of ${fit.upperBound}"
            MetalogBoundedness.Bounded -> "bounds of ${fit.lowerBound} and ${fit.upperBound}"
        }
    }

    private fun failure(
        data: DoubleArray,
        statistics: StatisticIfc,
        message: String
    ): EstimationResult {
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            message = message,
            success = false,
            estimator = this
        )
    }

    /**
     *  The outcome of a candidate search: the best valid fit if there was one, and the candidate to
     *  hand the fallback solve if there was not.
     */
    private class Search(
        val bestFeasible: Fit?,
        val fallbackCandidate: Pair<Double, Double>?
    ) {
        /** Whichever of two searches found the better valid fit. */
        fun betterOf(other: Search): Search {
            val mine = bestFeasible
            val theirs = other.bestFeasible
            val winner = when {
                mine == null -> theirs
                theirs == null -> mine
                theirs.badness < mine.badness -> theirs
                else -> mine
            }
            return Search(winner, fallbackCandidate ?: other.fallbackCandidate)
        }
    }

    /**
     *  One candidate fit and how badly it matched the observations.
     */
    private class Fit(
        val coefficients: DoubleArray,
        val lowerBound: Double,
        val upperBound: Double,
        val badness: Double,
        val usedLinearProgram: Boolean
    )

    companion object {

        /**
         *  The fewest terms an estimator may fit.
         */
        const val MIN_TERM_COUNT: Int = 2

        /**
         *  The most terms an estimator may fit, matching the registered distribution classes.
         */
        const val MAX_TERM_COUNT: Int = 6

        /**
         *  The random variable type for a given number of terms.
         */
        fun typeFor(numTerms: Int): RVType {
            return when (numTerms) {
                2 -> RVType.Metalog2P
                3 -> RVType.Metalog3P
                4 -> RVType.Metalog4P
                5 -> RVType.Metalog5P
                6 -> RVType.Metalog6P
                else -> throw IllegalArgumentException(
                    "There is no metalog type for $numTerms terms; " +
                            "the registered types cover $MIN_TERM_COUNT through $MAX_TERM_COUNT"
                )
            }
        }

        /**
         *  The name an estimator takes when none is supplied.
         */
        fun defaultName(numTerms: Int, boundedness: MetalogBoundedness): String {
            return "Metalog${numTerms}P${boundedness.name}ParameterEstimator"
        }

        /**
         *  Estimators across a range of term counts for one member of the family.
         */
        fun estimators(
            boundedness: MetalogBoundedness,
            termCounts: IntRange = MIN_TERM_COUNT..MAX_TERM_COUNT,
            useLPFallback: Boolean = true
        ): Set<MetalogParameterEstimator> {
            return termCounts.map {
                MetalogParameterEstimator(it, boundedness, useLPFallback = useLPFallback)
            }.toSet()
        }

        /**
         *  Estimators across every term count and every member of the family.
         */
        fun allEstimators(
            termCounts: IntRange = MIN_TERM_COUNT..MAX_TERM_COUNT
        ): Set<MetalogParameterEstimator> {
            return MetalogBoundedness.entries
                .flatMap { estimators(it, termCounts) }
                .toSet()
        }
    }
}
