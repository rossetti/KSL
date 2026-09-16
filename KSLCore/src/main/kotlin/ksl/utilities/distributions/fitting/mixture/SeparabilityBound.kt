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
import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Triangular
import ksl.utilities.distributions.Uniform
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 *  How far a k-component mixture is from the nearest mixture with one component fewer, and the
 *  sample size that distance implies.
 *
 *  **Why this is worth computing.** Every measured statement about component recovery so far has
 *  been about what one procedure achieves. This is about what *any* procedure could achieve. If
 *  the true mixture is very close to some mixture with fewer components, then no amount of
 *  cleverness in the fitting recovers the count from a small sample: the two models are nearly
 *  indistinguishable in the data, and the question is information-theoretic rather than
 *  algorithmic.
 *
 *  The governing quantity is
 *
 *  <pre>
 *      eta(k) = inf over all (k-1)-component mixtures g of Hellinger( f(k), g )
 *  </pre>
 *
 *  and classical testing theory gives a **necessary** condition on the sample size, of the order
 *  of one over eta squared, to tell the two apart with bounded error. It is a lower bound: no
 *  procedure does better, and a real procedure that must also estimate parameters does worse.
 *
 *  **The infimum is approximated from above.** The candidate set here is every mixture obtained by
 *  merging one adjacent pair of true components into a single distribution, over a catalog of
 *  families and a refinement of that family's moments. That is a subset of all (k-1)-component
 *  mixtures, so the value returned is at least the true infimum. Since the implied sample size
 *  varies as one over the square of the distance, over-stating the distance **under**-states the
 *  sample size: every figure this produces is a floor, not an estimate.
 *
 *  Only adjacent pairs are merged. For components ordered on the line and separated in the way the
 *  design separates them, a non-adjacent merge is further away, so it cannot supply the infimum.
 */
object SeparabilityBound {

    /**
     *  The mixture a bound is computed for: weights, components and their families.
     *
     *  A local carrier rather than a reference to the experiment's test case. The bound is a
     *  property of any mixture, so tying it to a designed-experiment type would put production
     *  code behind an experiment, which is the wrong way round.
     */
    private class Mixture(
        val label: String,
        val weights: DoubleArray,
        val components: List<ContinuousDistributionIfc>,
        val types: List<RVParametersTypeIfc>
    )

    /**
     *  What the search found for one case.
     *
     *  @param label the case label
     *  @param hellinger the smallest Hellinger distance to a merged (k-1)-component mixture
     *  @param kolmogorov the largest gap between the two distribution functions at that merge
     *  @param mergedPair the index of the lower component of the pair whose merge was closest
     *  @param mergedFamily the family the merged component took
     *  @param mergedComponent the single distribution that replaced the pair. Carried so that the
     *  approximating mixture can be drawn, not merely scored: a figure of the gap has to plot the
     *  density that produced the number
     */
    data class Bound(
        val label: String,
        val hellinger: Double,
        val kolmogorov: Double,
        val mergedPair: Int,
        val mergedFamily: String,
        val mergedComponent: ContinuousDistributionIfc
    ) {

        /**
         *  The sample size below which no procedure can separate the two models with bounded
         *  error, of the order of one over the squared Hellinger distance.
         *
         *  Reported as an order of magnitude rather than a threshold. The constant in the testing
         *  bound depends on the error probabilities one insists on; what carries meaning is how
         *  the requirement scales as components merge, which is quadratically.
         */
        val impliedSampleSize: Double
            get() = if (hellinger <= 0.0) Double.POSITIVE_INFINITY else 1.0 / (hellinger * hellinger)
    }

    /**
     *  The families a merged component may take.
     *
     *  Wider than the composition being merged, because the infimum is over the whole model class:
     *  a pair of gammas may be better absorbed by a lognormal than by a gamma, and restricting the
     *  merge to the parent family would overstate the distance.
     */
    private val mergeFamilies: List<RVParametersTypeIfc> = listOf(
        RVType.Normal, RVType.Gamma, RVType.Lognormal, RVType.Uniform, RVType.Triangular
    )

    /**
     *  Builds a distribution of the requested family with the requested mean and variance, or null
     *  where those moments are not attainable by that family.
     *
     *  @param type the family
     *  @param mean the target mean
     *  @param variance the target variance
     */
    fun momentMatched(
        type: RVParametersTypeIfc,
        mean: Double,
        variance: Double
    ): ContinuousDistributionIfc? {
        if (!mean.isFinite() || !variance.isFinite() || variance <= 0.0) return null
        return when (type) {
            RVType.Normal -> Normal(mean, variance)
            RVType.Gamma -> if (mean > 0.0) {
                Gamma(mean * mean / variance, variance / mean)
            } else null
            RVType.Lognormal -> if (mean > 0.0) Lognormal(mean, variance) else null
            RVType.Uniform -> {
                val half = sqrt(3.0 * variance)
                Uniform(mean - half, mean + half)
            }
            RVType.Triangular -> {
                // symmetric triangular with the requested moments: variance = h^2 / 6
                val half = sqrt(6.0 * variance)
                Triangular(mean - half, mean, mean + half)
            }
            else -> null
        }
    }

    /**
     *  The mean and variance of the sub-mixture formed by two weighted components.
     *
     *  @param weight1 the first weight
     *  @param component1 the first component
     *  @param weight2 the second weight
     *  @param component2 the second component
     */
    fun mergedMoments(
        weight1: Double,
        component1: ContinuousDistributionIfc,
        weight2: Double,
        component2: ContinuousDistributionIfc
    ): Pair<Double, Double> {
        val total = weight1 + weight2
        val p1 = weight1 / total
        val p2 = weight2 / total
        val m1 = component1.mean()
        val m2 = component2.mean()
        val mean = p1 * m1 + p2 * m2
        val second = p1 * (component1.variance() + m1 * m1) + p2 * (component2.variance() + m2 * m2)
        return mean to (second - mean * mean)
    }

    /**
     *  The closest (k-1)-component mixture reachable by merging one adjacent pair.
     *
     *  The grid, the true density on it, and each true component's density on it are computed
     *  **once per case**. A candidate then costs one density evaluation for the merged component
     *  rather than a rebuilt grid and a re-evaluated mixture, which is the difference between
     *  this running in a minute and running for hours: the grid construction alone bisects for an
     *  effective range per component, and the search visits on the order of a quarter of a million
     *  candidates.
     *
     *  @param case the design case
     *  @param numRefinements how many coordinate-search passes to run on the merged moments
     *  @param numPoints the quadrature resolution
     */
    fun boundFor(
        label: String,
        weights: DoubleArray,
        components: List<ContinuousDistributionIfc>,
        types: List<RVParametersTypeIfc>,
        numRefinements: Int = 6,
        numPoints: Int = 8001
    ): Bound? {
        require(weights.size == components.size) { "There must be one weight per component" }
        require(components.size == types.size) { "There must be one family per component" }
        val case = Mixture(label, weights, components, types)
        val k = case.components.size
        if (k < 2) return null
        val grid = DensityQuadrature.grid(case.components, numPoints)
        // each true component's density on the grid, so a candidate only has to evaluate the one
        // distribution that actually changed
        val componentDensity = Array(k) { j -> DoubleArray(grid.size) { case.components[j].pdf(grid[it]) } }
        val truth = DoubleArray(grid.size)
        for (i in grid.indices) {
            var sum = 0.0
            for (j in 0 until k) sum += case.weights[j] * componentDensity[j][i]
            truth[i] = sum
        }
        val trueCdf = DoubleArray(grid.size) { i ->
            var sum = 0.0
            for (j in 0 until k) sum += case.weights[j] * case.components[j].cdf(grid[i])
            sum
        }

        var best: Bound? = null
        for (j in 0 until k - 1) {
            val (mean0, variance0) = mergedMoments(
                case.weights[j], case.components[j], case.weights[j + 1], case.components[j + 1]
            )
            if (variance0 <= 0.0) continue
            val mergedWeight = case.weights[j] + case.weights[j + 1]
            // the part of the candidate that does not change as the merged component moves
            val fixed = DoubleArray(grid.size)
            for (i in grid.indices) {
                var sum = 0.0
                for (m in 0 until k) if (m != j && m != j + 1) sum += case.weights[m] * componentDensity[m][i]
                fixed[i] = sum
            }
            // the mass the unchanged components place inside the grid's span, so the candidate's
            // own integral can be checked against what it should be rather than against one
            var fixedSpanned = 0.0
            for (m in 0 until k) {
                if (m == j || m == j + 1) continue
                fixedSpanned += case.weights[m] *
                        (case.components[m].cdf(grid.last()) - case.components[m].cdf(grid.first()))
            }
            for (family in mergeFamilies) {
                // moment matching first, then a shrinking coordinate search on (mean, variance).
                // The Hellinger distance is not convex in these, so this is a local refinement of
                // a sensible start rather than a global optimum -- which keeps the result an
                // upper bound on the infimum, as documented.
                var mean = mean0
                var variance = variance0
                var value = hellingerOf(
                    grid, truth, fixed, fixedSpanned, mergedWeight, family, mean, variance
                ) ?: continue
                var stepMean = 0.25 * sqrt(variance0)
                var stepVariance = 0.25 * variance0
                repeat(numRefinements) {
                    var improved = false
                    for (dm in doubleArrayOf(-stepMean, 0.0, stepMean)) {
                        for (dv in doubleArrayOf(-stepVariance, 0.0, stepVariance)) {
                            if (dm == 0.0 && dv == 0.0) continue
                            val candidate = hellingerOf(
                                grid, truth, fixed, fixedSpanned, mergedWeight, family,
                                mean + dm, variance + dv
                            ) ?: continue
                            if (candidate < value) {
                                value = candidate
                                mean += dm
                                variance += dv
                                improved = true
                            }
                        }
                    }
                    if (!improved) {
                        stepMean *= 0.5
                        stepVariance *= 0.5
                    }
                }
                if (best != null && value >= best!!.hellinger) continue
                val merged = momentMatched(family, mean, variance) ?: continue
                var gap = 0.0
                for (i in grid.indices) {
                    var sum = mergedWeight * merged.cdf(grid[i])
                    for (m in 0 until k) if (m != j && m != j + 1) sum += case.weights[m] * case.components[m].cdf(grid[i])
                    val d = abs(trueCdf[i] - sum)
                    if (d > gap) gap = d
                }
                best = Bound(case.label, value, gap, j, family.toString(), merged)
            }
        }
        return best
    }

    /**
     *  The Hellinger distance between the truth and a candidate, both already sampled on a shared
     *  grid, with only the merged component evaluated here.
     *
     *  Returns null when the candidate cannot be represented on this grid — the same admissibility
     *  rule the recovery measures apply, since the two use the same trapezoid.
     */
    private fun hellingerOf(
        grid: DoubleArray,
        truth: DoubleArray,
        fixed: DoubleArray,
        fixedSpanned: Double,
        mergedWeight: Double,
        family: RVParametersTypeIfc,
        mean: Double,
        variance: Double
    ): Double? {
        if (variance <= 0.0) return null
        val merged = momentMatched(family, mean, variance) ?: return null
        var integral = 0.0
        var mass = 0.0
        var previous = 0.0
        var previousMass = 0.0
        for (i in grid.indices) {
            val candidate = fixed[i] + mergedWeight * merged.pdf(grid[i])
            if (!candidate.isFinite()) return null
            val d = sqrt(truth[i]) - sqrt(candidate)
            val h = d * d
            if (i > 0) {
                val step = grid[i] - grid[i - 1]
                integral += 0.5 * (previous + h) * step
                mass += 0.5 * (previousMass + candidate) * step
            }
            previous = h
            previousMass = candidate
        }
        val expected = fixedSpanned +
                mergedWeight * (merged.cdf(grid.last()) - merged.cdf(grid.first()))
        if (abs(mass - expected) > 10.0 * DensityQuadrature.maxQuadratureError) return null
        return sqrt((0.5 * integral).coerceAtLeast(0.0)).coerceIn(0.0, 1.0)
    }

    private fun mergedComponents(
        case: Mixture,
        pair: Int,
        merged: ContinuousDistributionIfc
    ): List<ContinuousDistributionIfc> {
        val out = mutableListOf<ContinuousDistributionIfc>()
        for (i in case.components.indices) {
            if (i == pair) out.add(merged) else if (i != pair + 1) out.add(case.components[i])
        }
        return out
    }

    private fun mergedTypes(
        case: Mixture,
        pair: Int,
        family: RVParametersTypeIfc
    ): List<RVParametersTypeIfc> {
        val out = mutableListOf<RVParametersTypeIfc>()
        for (i in case.types.indices) {
            if (i == pair) out.add(family) else if (i != pair + 1) out.add(case.types[i])
        }
        return out
    }

    /**
     *  The Dvoretzky–Kiefer–Wolfowitz sample size: how many observations are needed for the
     *  empirical distribution function to lie within a given distance of the truth, uniformly,
     *  with the requested confidence.
     *
     *  Present for comparison rather than as part of the bound. It answers a different and easier
     *  question — how much data represents the distribution — and the contrast between the two is
     *  the point being made.
     *
     *  @param eta the uniform accuracy required of the empirical distribution function
     *  @param alpha one minus the confidence
     */
    fun dkwSampleSize(eta: Double, alpha: Double = 0.05): Double {
        require(eta > 0.0) { "The accuracy must be > 0.0" }
        require(alpha > 0.0 && alpha < 1.0) { "The confidence level must be within (0, 1)" }
        return ln(2.0 / alpha) / (2.0 * eta * eta)
    }

    /**
     *  Indicates whether two moment values are close enough to treat as the same.
     */
    fun near(a: Double, b: Double, tolerance: Double = 1.0e-9): Boolean = abs(a - b) <= tolerance
}
