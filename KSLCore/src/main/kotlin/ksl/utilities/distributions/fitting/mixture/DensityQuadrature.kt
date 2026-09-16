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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 *  Where to evaluate a mixture's density, and how far a quadrature may drift before its answer
 *  stops meaning anything.
 *
 *  Extracted from the experiment package when the separability bound became production code: a
 *  grid over a set of densities is not an experimental concern, and production code must not
 *  depend on an experiment to get one. `Overlap` and `RecoveryMeasures` delegate here, so the
 *  numerics have one definition rather than two that can drift.
 */
internal object DensityQuadrature {

    /**
     *  The number of quadrature points used when none is specified.
     */
    var defaultNumQuadraturePoints: Int = 20001
        set(value) {
            require(value > 2) { "There must be more than two quadrature points" }
            field = value
        }

    /**
     *  The tail probability excluded from each end of a distribution's effective range.
     */
    var defaultTailProbability: Double = 1.0e-6
        set(value) {
            require(value > 0.0 && value < 0.5) { "The tail probability must be within (0, 0.5)" }
            field = value
        }

    /**
     *  How far the density may integrate from the mass its own grid spans before the distances
     *  computed on that grid are treated as meaningless.
     *
     *  The check is the general one rather than a guard against a particular case: whatever the
     *  reason a grid fails to represent a density, that density will not integrate correctly on
     *  it, and no distance measured there means anything.
     */
    var maxQuadratureError: Double = 1.0e-3
        set(value) {
            require(value > 0.0) { "The quadrature error tolerance must be positive" }
            field = value
        }

    /**
     *  The interval holding essentially all of the mass of every supplied component.
     *
     *  @param components the components to cover
     *  @param tailProbability the probability excluded from each end
     */
    fun effectiveRange(
        components: List<ContinuousDistributionIfc>,
        tailProbability: Double = defaultTailProbability
    ): Pair<Double, Double> {
        require(components.isNotEmpty()) { "There must be at least one component" }
        var lower = Double.MAX_VALUE
        var upper = -Double.MAX_VALUE
        for (c in components) {
            val domain = c.domain()
            val low = if (domain.lowerLimit.isFinite()) {
                maxOf(domain.lowerLimit, safeQuantile(c, tailProbability, domain.lowerLimit))
            } else {
                safeQuantile(c, tailProbability, -1.0e6)
            }
            val high = if (domain.upperLimit.isFinite()) {
                minOf(domain.upperLimit, safeQuantile(c, 1.0 - tailProbability, domain.upperLimit))
            } else {
                safeQuantile(c, 1.0 - tailProbability, 1.0e6)
            }
            if (low < lower) lower = low
            if (high > upper) upper = high
        }
        return lower to upper
    }

    private fun safeQuantile(
        c: ContinuousDistributionIfc,
        p: Double,
        fallback: Double
    ): Double {
        return try {
            val q = c.invCDF(p)
            if (q.isFinite()) q else fallback
        } catch (e: IllegalArgumentException) {
            // invCDF rejecting the probability or the distribution's own parameters. Narrow so
            // that a fault inside a quantile routine surfaces rather than silently becoming a
            // fallback abscissa, which would shift a quadrature grid with nothing to show for it.
            fallback
        }
    }

    /**
     *  The quadrature abscissae for a set of components: one sub-grid per component over its own
     *  effective range, plus the overall span, merged and de-duplicated.
     *
     *  A single uniform grid over the union of the ranges is unusable when the components differ
     *  by orders of magnitude in scale. The step is then set by the widest component, so a narrow
     *  one falls between abscissae and contributes nothing but its peak multiplied by that step.
     *  This is not hypothetical: it corrupted 10.7% of the attempts in the W1 run, and it did so
     *  silently, because the Hellinger distance was clamped into its valid range afterwards.
     *
     *  The overall span matters as much as the per-component ranges. Without it the merged grid
     *  has wide gaps between the components, and the trapezoid rule bridges a gap with a straight
     *  line from the last point of one component's tail, enclosing spurious mass proportional to
     *  the width of the gap.
     *
     *  @param components the components whose supports must be covered
     *  @param numPoints the total number of abscissae to distribute among them
     */
    fun grid(
        components: List<ContinuousDistributionIfc>,
        numPoints: Int = defaultNumQuadraturePoints
    ): DoubleArray {
        require(numPoints > 2) { "There must be more than two quadrature points" }
        require(components.isNotEmpty()) { "There must be at least one component" }
        val ranges = components.map { effectiveRange(listOf(it)) } + listOf(effectiveRange(components))
        val per = maxOf(3, numPoints / ranges.size)
        // Two extra abscissae per component bracket a jump in the density. A uniform or a
        // triangular is discontinuous at its support boundary, and the trapezoid rule run from a
        // neighbouring point where the density is zero up to the boundary where it is not encloses
        // a spurious triangle whose area is proportional to the distance between them. Placing a
        // point immediately outside each finite limit collapses that triangle to nothing.
        val raw = DoubleArray(ranges.size * per + 2 * components.size)
        var index = 0
        for ((low, high) in ranges) {
            val step = if (high > low) (high - low) / (per - 1) else 0.0
            for (i in 0 until per) raw[index++] = low + i * step
        }
        for (c in components) {
            val domain = c.domain()
            val width = domain.upperLimit - domain.lowerLimit
            val nudge = if (width.isFinite() && width > 0.0) width * 1.0e-9 else 1.0e-9
            raw[index++] = if (domain.lowerLimit.isFinite()) domain.lowerLimit - nudge else raw[0]
            raw[index++] = if (domain.upperLimit.isFinite()) domain.upperLimit + nudge else raw[0]
        }
        raw.sort()
        // repeated abscissae contribute zero-width trapezoids; dropping them costs nothing and
        // keeps the grid honest about how many distinct points it actually has
        var kept = 0
        for (i in raw.indices) if (i == 0 || raw[i] > raw[kept - 1]) raw[kept++] = raw[i]
        return raw.copyOf(kept)
    }

    /**
     *  The density of a weighted mixture at a point.
     *
     *  @param weights the mixing weights
     *  @param components the components
     *  @param x where to evaluate
     */
    fun density(
        weights: DoubleArray,
        components: List<ContinuousDistributionIfc>,
        x: Double
    ): Double {
        var sum = 0.0
        for (j in components.indices) sum += weights[j] * components[j].pdf(x)
        return sum
    }

    /**
     *  The distribution function of a weighted mixture at a point.
     *
     *  @param weights the mixing weights
     *  @param components the components
     *  @param x where to evaluate
     */
    fun cumulative(
        weights: DoubleArray,
        components: List<ContinuousDistributionIfc>,
        x: Double
    ): Double {
        var sum = 0.0
        for (j in components.indices) sum += weights[j] * components[j].cdf(x)
        return sum
    }

    /**
     *  The Hellinger distance between two weighted mixtures, on a grid built to represent both.
     *
     *  **Null rather than a number when the grid cannot represent one of them.** Both densities are
     *  integrated as the distance is accumulated, and a mixture whose integrated mass differs from
     *  the mass its own distribution function says the grid spans has not been sampled adequately;
     *  no distance computed there means anything. Returning null says so. Clamping the answer into
     *  its valid range instead is what hid a defect in 10.7% of one experiment's attempts, and the
     *  clamp below is applied only after both checks have passed.
     *
     *  @param weightsA the mixing weights of the first mixture
     *  @param componentsA the components of the first mixture
     *  @param weightsB the mixing weights of the second mixture
     *  @param componentsB the components of the second mixture
     *  @param numPoints the total number of abscissae to distribute over the supports
     *  @return the distance within zero and one, or null when the grid cannot represent both
     */
    fun hellinger(
        weightsA: DoubleArray,
        componentsA: List<ContinuousDistributionIfc>,
        weightsB: DoubleArray,
        componentsB: List<ContinuousDistributionIfc>,
        numPoints: Int = defaultNumQuadraturePoints
    ): Double? {
        require(weightsA.size == componentsA.size) {
            "The first mixture has ${weightsA.size} weights for ${componentsA.size} components"
        }
        require(weightsB.size == componentsB.size) {
            "The second mixture has ${weightsB.size} weights for ${componentsB.size} components"
        }
        val points = grid(componentsA + componentsB, numPoints)
        var integral = 0.0
        var massA = 0.0
        var massB = 0.0
        var previousDistance = 0.0
        var previousA = 0.0
        var previousB = 0.0
        for (i in points.indices) {
            val a = density(weightsA, componentsA, points[i])
            val b = density(weightsB, componentsB, points[i])
            if (!a.isFinite() || !b.isFinite()) return null
            val gap = sqrt(a) - sqrt(b)
            val h = gap * gap
            if (i > 0) {
                val step = points[i] - points[i - 1]
                integral += 0.5 * (previousDistance + h) * step
                massA += 0.5 * (previousA + a) * step
                massB += 0.5 * (previousB + b) * step
            }
            previousDistance = h
            previousA = a
            previousB = b
        }
        val low = points.first()
        val high = points.last()
        val expectedA = cumulative(weightsA, componentsA, high) - cumulative(weightsA, componentsA, low)
        val expectedB = cumulative(weightsB, componentsB, high) - cumulative(weightsB, componentsB, low)
        if (abs(massA - expectedA) > maxQuadratureError) return null
        if (abs(massB - expectedB) > maxQuadratureError) return null
        return sqrt((0.5 * integral).coerceAtLeast(0.0)).coerceIn(0.0, 1.0)
    }
}
