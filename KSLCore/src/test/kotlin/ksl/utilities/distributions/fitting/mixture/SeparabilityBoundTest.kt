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

import ksl.utilities.distributions.Normal
import ksl.utilities.random.rvariable.RVType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 *  The separability bound: how far a k-component truth is from the nearest mixture with one
 *  component fewer.
 *
 *  This quantity is what makes the component-count problem information-theoretic rather than
 *  algorithmic, so its qualitative behaviour has to be right before any sample-size statement is
 *  built on it. Two components that coincide must give a bound of zero; components pulled apart
 *  must give a larger one; and the value has to be a distance.
 */
class SeparabilityBoundTest {

    private fun twoNormals(separation: Double): SeparabilityBound.Bound? =
        SeparabilityBound.boundFor(
            "pair at $separation",
            doubleArrayOf(0.5, 0.5),
            listOf(Normal(0.0, 1.0), Normal(separation, 1.0)),
            listOf(RVType.Normal, RVType.Normal)
        )

    @Test
    fun `coincident components are indistinguishable from one component`() {
        // two identical components ARE a single component, so nothing separates the k and k-1
        // models and the bound must be zero. If this is non-zero the search is not finding the
        // merge that obviously exists.
        val bound = twoNormals(0.0)
        assertTrue(bound != null, "a two-component case must produce a bound")
        assertTrue(
            bound!!.hellinger < 1.0e-3,
            "coincident components gave a separability of ${bound.hellinger}"
        )
    }

    @Test
    fun `pulling the components apart makes them more separable`() {
        var previous = -1.0
        for (separation in doubleArrayOf(0.0, 0.5, 1.0, 2.0, 3.0, 4.0)) {
            val bound = twoNormals(separation)!!
            assertTrue(
                bound.hellinger >= previous - 1.0e-6,
                "separability fell from $previous to ${bound.hellinger} at spacing $separation"
            )
            previous = bound.hellinger
        }
        // Two unit normals four standard deviations apart measure 0.199 here. That is the scale
        // to expect rather than something near one: a clearly bimodal density is still only so
        // far from its best unimodal approximation, which is the whole reason the count is hard
        // to recover. The threshold is set below the measured value, not at a round number.
        assertTrue(previous > 0.15, "well separated normals should be clearly separable, got $previous")
    }

    @Test
    fun `the bound is a distance`() {
        for (separation in doubleArrayOf(0.0, 1.0, 3.0, 6.0)) {
            val bound = twoNormals(separation)!!
            assertTrue(bound.hellinger in 0.0..1.0, "hellinger ${bound.hellinger}")
            assertTrue(bound.kolmogorov in 0.0..1.0, "kolmogorov ${bound.kolmogorov}")
        }
    }

    @Test
    fun `the implied sample size falls as the components separate`() {
        val close = twoNormals(1.0)!!
        val far = twoNormals(4.0)!!
        assertTrue(
            close.impliedSampleSize > far.impliedSampleSize,
            "closer components must demand more data: ${close.impliedSampleSize} against " +
                    "${far.impliedSampleSize}"
        )
    }

    @Test
    fun `merged moments match the sub-mixture`() {
        val a = Normal(0.0, 1.0)
        val b = Normal(4.0, 9.0)
        val (mean, variance) = SeparabilityBound.mergedMoments(0.25, a, 0.75, b)
        // conditional on falling in the pair, the weights renormalize to 0.25 and 0.75
        assertEquals(0.25 * 0.0 + 0.75 * 4.0, mean, 1.0e-9)
        val second = 0.25 * (1.0 + 0.0) + 0.75 * (9.0 + 16.0)
        assertEquals(second - mean * mean, variance, 1.0e-9)
    }

    @Test
    fun `moment matching reproduces the requested moments`() {
        for (type in listOf(RVType.Normal, RVType.Gamma, RVType.Lognormal, RVType.Uniform,
            RVType.Triangular)) {
            val d = SeparabilityBound.momentMatched(type, 5.0, 2.0)
            assertTrue(d != null, "$type could not match the moments")
            assertEquals(5.0, d!!.mean(), 1.0e-6, "$type mean")
            assertEquals(2.0, d.variance(), 1.0e-6, "$type variance")
        }
    }

    @Test
    fun `the DKW sample size matches the closed form and the published figure`() {
        // n = ln(2/alpha) / (2 eps^2); the parent paper reports about 18,455 for eps = 0.01 at 95%
        assertEquals(
            ln(2.0 / 0.05) / (2.0 * 0.01 * 0.01),
            SeparabilityBound.dkwSampleSize(0.01), 1.0e-9
        )
        assertTrue(
            abs(SeparabilityBound.dkwSampleSize(0.01) - 18455.0) < 50.0,
            "expected about 18455, computed ${SeparabilityBound.dkwSampleSize(0.01)}"
        )
    }

}
