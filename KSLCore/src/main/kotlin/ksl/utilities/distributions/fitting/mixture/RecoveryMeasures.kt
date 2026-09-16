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

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import kotlin.math.abs
import kotlin.math.sqrt

/**
 *  How well a fitted mixture recovered the mixture that generated the data.
 *
 *  @param hellinger the Hellinger distance between the fitted and true densities, from zero to one
 *  @param l1Distance the integrated absolute difference of the densities, from zero to two
 *  @param kolmogorov the largest absolute difference between the distribution functions
 *  @param numComponentsCorrect whether the fitted number of components equals the true number
 *  @param familyAccuracy the fraction of matched components whose family was recovered, under
 *  the equivalence rules; not defined when the component counts differ
 *  @param weightMeanAbsoluteError the mean absolute difference of matched mixing weights; not
 *  defined when the component counts differ
 *  @param matching for each true component, the index of the fitted component matched to it
 *  @param quadratureError the larger of how far the true and the fitted density integrated from
 *  the mass the grid spans; the measure's own admissibility check, since a grid that cannot
 *  represent both densities cannot measure the distance between them
 */
data class RecoveryResult(
    val hellinger: Double,
    val l1Distance: Double,
    val kolmogorov: Double,
    val numComponentsCorrect: Boolean,
    val familyAccuracy: Double?,
    val weightMeanAbsoluteError: Double?,
    val matching: IntArray?,
    val quadratureError: Double = 0.0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecoveryResult) return false
        return hellinger == other.hellinger && l1Distance == other.l1Distance
    }

    override fun hashCode(): Int = hellinger.hashCode() * 31 + l1Distance.hashCode()
}

/**
 *  Computes how close a fitted mixture is to the truth.
 *
 *  Density distance is the primary measure, not family labels. A mixture of heterogeneous
 *  families need not have identifiable family labels at all: an exponential is a gamma of shape
 *  one and a Weibull of shape one, and a uniform is a beta with both shapes one, so a recovery
 *  measure keyed on the label would report failures that are not failures. Distance between
 *  densities is invariant to that relabeling, which is why it leads.
 *
 *  Family recovery is still reported, but against equivalence classes rather than exact labels,
 *  and only when the component counts agree so that a matching exists.
 */
object RecoveryMeasures {

    /**
     *  Families that describe the same distribution at particular parameter values and therefore
     *  cannot be distinguished from data alone. Two families in the same class count as
     *  recovering one another.
     */
    private val equivalenceClasses: List<Set<RVParametersTypeIfc>> = listOf(
        setOf(RVType.Exponential, RVType.Gamma, RVType.Weibull),
        setOf(RVType.Uniform, RVType.GeneralizedBeta),
        setOf(RVType.Triangular, RVType.GeneralizedBeta)
    )

    /**
     *  Indicates whether the fitted family counts as recovering the true one, allowing for the
     *  families that coincide at some parameter values.
     *
     *  @param trueType the family that generated the component
     *  @param fittedType the family that was selected
     */
    fun familiesEquivalent(
        trueType: RVParametersTypeIfc,
        fittedType: RVParametersTypeIfc
    ): Boolean {
        if (trueType == fittedType) return true
        return equivalenceClasses.any { it.contains(trueType) && it.contains(fittedType) }
    }

    /**
     *  Indicates whether two family names count as recovering one another.
     *
     *  Provided for analysis that reads family names back from a database, where the recorded
     *  value is the family's name rather than its type. Defined here so that the equivalence
     *  classes have one definition: a caller that restated them would drift from what this
     *  comparison actually scores, and the drift would be invisible.
     *
     *  @param trueName the name of the family that generated the component
     *  @param fittedName the name of the family that was selected
     */
    fun familiesEquivalent(trueName: String, fittedName: String): Boolean {
        if (trueName == fittedName) return true
        return equivalenceClasses.any { classes ->
            classes.any { it.toString() == trueName } && classes.any { it.toString() == fittedName }
        }
    }

    /**
     *  Compares a fitted mixture against the truth.
     *
     *  @param trueWeights the true mixing weights
     *  @param trueComponents the true components
     *  @param trueTypes the true component families
     *  @param fittedWeights the fitted mixing weights
     *  @param fittedComponents the fitted components
     *  @param fittedTypes the fitted component families
     *  @param numPoints the number of quadrature points
     */
    fun compare(
        trueWeights: DoubleArray,
        trueComponents: List<ContinuousDistributionIfc>,
        trueTypes: List<RVParametersTypeIfc>,
        fittedWeights: DoubleArray,
        fittedComponents: List<ContinuousDistributionIfc>,
        fittedTypes: List<RVParametersTypeIfc>,
        numPoints: Int = DensityQuadrature.defaultNumQuadraturePoints
    ): RecoveryResult {
        val grid = quadratureGrid(trueComponents + fittedComponents, numPoints)

        var hellingerIntegral = 0.0
        var l1Integral = 0.0
        var trueMass = 0.0
        var fittedMass = 0.0
        var maxCdfGap = 0.0
        var previousHellinger = 0.0
        var previousL1 = 0.0
        var previousTruth = 0.0
        var previousFitted = 0.0

        for (i in grid.indices) {
            val x = grid[i]
            val truth = density(trueWeights, trueComponents, x)
            val fitted = density(fittedWeights, fittedComponents, x)
            val h = run { val d = sqrt(truth) - sqrt(fitted); d * d }
            val l1 = abs(truth - fitted)
            if (i > 0) {
                val step = x - grid[i - 1]
                hellingerIntegral += 0.5 * (previousHellinger + h) * step
                l1Integral += 0.5 * (previousL1 + l1) * step
                trueMass += 0.5 * (previousTruth + truth) * step
                fittedMass += 0.5 * (previousFitted + fitted) * step
            }
            previousHellinger = h
            previousL1 = l1
            previousTruth = truth
            previousFitted = fitted
            val gap = abs(
                cumulative(trueWeights, trueComponents, x) -
                        cumulative(fittedWeights, fittedComponents, x)
            )
            if (gap > maxCdfGap) maxCdfGap = gap
        }
        // Against the mass the grid actually spans, not against one. The grid truncates both tails
        // deliberately, at the quadrature's tail probability, so charging that truncation to the
        // quadrature would set a floor under the error and blunt the check.
        //
        // Both densities are checked, because the distance is between the two of them. Checking
        // only the truth is not enough: a fitted mixture that has collapsed onto a near-degenerate
        // spike is unrepresentable on any grid built for the truth, so the truth integrates
        // cleanly, the reported distance is enormous, and the admissibility check says nothing.
        val spannedTrue = cumulative(trueWeights, trueComponents, grid.last()) -
                cumulative(trueWeights, trueComponents, grid.first())
        val spannedFitted = cumulative(fittedWeights, fittedComponents, grid.last()) -
                cumulative(fittedWeights, fittedComponents, grid.first())
        val quadratureError = maxOf(abs(trueMass - spannedTrue), abs(fittedMass - spannedFitted))
        // Clamped only once the grid is known to represent the truth. A value above one is
        // arithmetically impossible for two densities, so leaving it unclamped when the check
        // fails preserves the evidence; clamping unconditionally, as this did before, made a
        // broken measurement indistinguishable from a legitimately terrible fit.
        val raw = sqrt((0.5 * hellingerIntegral).coerceAtLeast(0.0))
        val hellinger = if (quadratureError <= maxQuadratureError) raw.coerceIn(0.0, 1.0) else raw

        val countsAgree = trueComponents.size == fittedComponents.size
        var familyAccuracy: Double? = null
        var weightError: Double? = null
        var matching: IntArray? = null
        if (countsAgree) {
            val assignment = matchComponents(trueComponents, fittedComponents, numPoints)
            matching = assignment
            var correct = 0
            var weightSum = 0.0
            for (t in trueComponents.indices) {
                val f = assignment[t]
                if (familiesEquivalent(trueTypes[t], fittedTypes[f])) correct++
                weightSum += abs(trueWeights[t] - fittedWeights[f])
            }
            familyAccuracy = correct.toDouble() / trueComponents.size
            weightError = weightSum / trueComponents.size
        }
        return RecoveryResult(
            hellinger, l1Integral, maxCdfGap, countsAgree, familyAccuracy, weightError, matching,
            quadratureError
        )
    }

    /**
     *  The quadrature abscissae for a set of components: one sub-grid per component over its own
     *  effective range, merged and de-duplicated.
     *
     *  A single uniform grid over the union of the ranges is unusable when the components differ by
     *  orders of magnitude in scale. The step is then set by the widest component, so a narrow one
     *  falls between abscissae and contributes nothing but its peak multiplied by that step. The
     *  result is a wrong distance rather than an obviously broken one, and clamping the Hellinger
     *  distance into its valid range afterwards hides even that.
     *
     *  @param components the components whose supports must be covered
     *  @param numPoints the total number of abscissae to distribute among them
     */
    fun quadratureGrid(
        components: List<ContinuousDistributionIfc>,
        numPoints: Int = DensityQuadrature.defaultNumQuadraturePoints
    ): DoubleArray = DensityQuadrature.grid(components, numPoints)

    /**
     *  Matches fitted components to true components so as to minimize the total component-wise
     *  Hellinger distance.
     *
     *  Matching is on distance between the component densities, not on their means. A mean is a
     *  poor summary of a skewed component, so matching on means pairs the wrong components
     *  precisely in the heterogeneous cases this work exists to handle.
     *
     *  The assignment is exact by enumeration up to `exactMatchingLimit` components, and greedy
     *  beyond that.
     *
     *  @param trueComponents the true components
     *  @param fittedComponents the fitted components, equal in number
     *  @param numPoints the number of quadrature points
     */
    fun matchComponents(
        trueComponents: List<ContinuousDistributionIfc>,
        fittedComponents: List<ContinuousDistributionIfc>,
        numPoints: Int = 2001
    ): IntArray {
        require(trueComponents.size == fittedComponents.size) {
            "Matching requires an equal number of components"
        }
        val k = trueComponents.size
        val cost = Array(k) { t ->
            DoubleArray(k) { f -> componentHellinger(trueComponents[t], fittedComponents[f], numPoints) }
        }
        if (k <= exactMatchingLimit) {
            val order = IntArray(k) { it }
            val used = BooleanArray(k)
            val best = IntArray(k)
            var bestCost = Double.MAX_VALUE
            fun search(depth: Int, running: Double) {
                if (running >= bestCost) return
                if (depth == k) {
                    bestCost = running
                    order.copyInto(best)
                    return
                }
                for (f in 0 until k) {
                    if (used[f]) continue
                    used[f] = true
                    order[depth] = f
                    search(depth + 1, running + cost[depth][f])
                    used[f] = false
                }
            }
            search(0, 0.0)
            return best
        }
        // greedy fallback for larger problems
        val assignment = IntArray(k) { -1 }
        val used = BooleanArray(k)
        val pairs = mutableListOf<Triple<Double, Int, Int>>()
        for (t in 0 until k) for (f in 0 until k) pairs.add(Triple(cost[t][f], t, f))
        pairs.sortBy { it.first }
        for ((_, t, f) in pairs) {
            if (assignment[t] < 0 && !used[f]) {
                assignment[t] = f
                used[f] = true
            }
        }
        return assignment
    }

    /**
     *  The Hellinger distance between two single distributions.
     *
     *  On the same scale-aware grid as `compare`, and for the same reason: this drives the
     *  component matching, so a grid that cannot resolve a narrow component pairs the wrong
     *  components with each other and every family and weight result downstream is then wrong.
     */
    fun componentHellinger(
        a: ContinuousDistributionIfc,
        b: ContinuousDistributionIfc,
        numPoints: Int = 2001
    ): Double {
        val grid = quadratureGrid(listOf(a, b), numPoints)
        if (grid.size < 2) return 0.0
        var integral = 0.0
        var previous = run { val d = sqrt(a.pdf(grid[0])) - sqrt(b.pdf(grid[0])); d * d }
        for (i in 1 until grid.size) {
            val x = grid[i]
            val current = run { val d = sqrt(a.pdf(x)) - sqrt(b.pdf(x)); d * d }
            integral += 0.5 * (previous + current) * (x - grid[i - 1])
            previous = current
        }
        return sqrt((0.5 * integral).coerceAtLeast(0.0)).coerceIn(0.0, 1.0)
    }

    private fun density(
        weights: DoubleArray,
        components: List<ContinuousDistributionIfc>,
        x: Double
    ): Double {
        var sum = 0.0
        for (j in components.indices) sum += weights[j] * components[j].pdf(x)
        return sum
    }

    private fun cumulative(
        weights: DoubleArray,
        components: List<ContinuousDistributionIfc>,
        x: Double
    ): Double {
        var sum = 0.0
        for (j in components.indices) sum += weights[j] * components[j].cdf(x)
        return sum
    }

    /**
     *  How far the true density may integrate from one on the quadrature grid before the density
     *  distances computed on that grid are treated as meaningless.
     *
     *  The check is the general one rather than a guard against the particular case that exposed
     *  it: whatever the reason a grid fails to represent the truth, the truth will not integrate
     *  to one on it, and no distance measured there means anything.
     */
    var maxQuadratureError: Double
        get() = DensityQuadrature.maxQuadratureError
        set(value) {
            DensityQuadrature.maxQuadratureError = value
        }

    /**
     *  The largest number of components for which the matching is solved exactly by enumeration.
     */
    var exactMatchingLimit: Int = 7
        set(value) {
            require(value >= 1) { "The exact matching limit must be >= 1" }
            field = value
        }
}
