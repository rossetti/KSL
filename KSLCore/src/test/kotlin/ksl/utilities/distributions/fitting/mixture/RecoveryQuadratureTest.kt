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
import ksl.utilities.distributions.Normal
import ksl.utilities.random.rvariable.RVType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 *  The quadrature underneath the recovery measures.
 *
 *  These exist because a single uniform grid over the union of the component ranges silently
 *  corrupts the measurement whenever the components differ widely in scale. The step is set by the
 *  widest component, a narrow one falls between abscissae, and the resulting Hellinger distance is
 *  then clamped into its valid range — so a perfect fit is reported as a maximal distance and
 *  looks exactly like a terrible one. The Kolmogorov distance, computed pointwise, reads 0.0000 on
 *  the very same comparison, which is the tell.
 *
 *  Two defences are pinned here: a grid that adapts to component scale, and the measure's own
 *  check that the true density integrates to one on whatever grid it used.
 */
class RecoveryQuadratureTest {

    /** Components spanning three orders of magnitude in scale — the shape that broke it. */
    private val wideSpread: List<ContinuousDistributionIfc> = listOf(
        Normal(0.0, 1.0), Normal(200.0, 100.0), Normal(20000.0, 1000000.0)
    )
    private val equalWeights = doubleArrayOf(1.0 / 3, 1.0 / 3, 1.0 / 3)
    private val normalTypes = listOf(RVType.Normal, RVType.Normal, RVType.Normal)

    @Test
    fun `a mixture whose scales span orders of magnitude measures zero against itself`() {
        // the decisive case: identical arguments, so every distance is zero by definition and any
        // departure is the quadrature failing rather than a fit being poor
        val result = RecoveryMeasures.compare(
            equalWeights, wideSpread, normalTypes, equalWeights, wideSpread, normalTypes
        )
        assertEquals(0.0, result.hellinger, 1.0e-9, "self-distance must be zero")
        assertEquals(0.0, result.l1Distance, 1.0e-9, "self-distance must be zero")
        assertTrue(
            result.quadratureError <= RecoveryMeasures.maxQuadratureError,
            "the true density integrated to ${1.0 + result.quadratureError} on the grid"
        )
    }

    @Test
    fun `the narrow component gets its own resolution`() {
        // the failure was structural, not numerical: the narrow component had no abscissae inside
        // it at all, so no tolerance on the answer would have caught it
        val grid = RecoveryMeasures.quadratureGrid(wideSpread, 20001)
        val inside = grid.count { it > -4.0 && it < 4.0 }
        assertTrue(
            inside >= 100,
            "only $inside abscissae landed on the narrowest component, out of ${grid.size}"
        )
        assertTrue(grid.size > 1, "the grid must have more than one point")
        for (i in 1 until grid.size) {
            assertTrue(grid[i] > grid[i - 1], "the grid must be sorted and free of duplicates")
        }
    }

    @Test
    fun `the true density integrates to one on the grid it is measured on`() {
        // the general check, and the one the status now keys on: whatever the reason a grid fails
        // to represent the truth, the truth will not integrate to one on it
        for (components in listOf(
            wideSpread,
            listOf(Normal(0.0, 1.0), Normal(4.0, 1.0)),
            // the shape the positive-support composition actually takes: a fixed-shape gamma
            // chain, so the scale -- and therefore the spread -- grows geometrically
            listOf(Gamma(2.5, 1.0), Gamma(2.5, 41.0), Gamma(2.5, 1681.0), Gamma(2.5, 68921.0))
        )) {
            val w = DoubleArray(components.size) { 1.0 / components.size }
            val types = List(components.size) { RVType.Normal }
            val result = RecoveryMeasures.compare(w, components, types, w, components, types)
            assertTrue(
                result.quadratureError <= RecoveryMeasures.maxQuadratureError,
                "quadrature error ${result.quadratureError} for $components"
            )
        }
    }

    @Test
    fun `a grid too coarse to represent the truth is reported rather than clamped`() {
        // starve it deliberately. The point is not that this configuration is used, but that when
        // the quadrature cannot work the result says so instead of returning a plausible number.
        val result = RecoveryMeasures.compare(
            equalWeights, wideSpread, normalTypes,
            equalWeights, listOf(Normal(0.0, 1.0), Normal(1.0, 1.0), Normal(2.0, 1.0)),
            normalTypes,
            numPoints = 9
        )
        assertTrue(
            result.quadratureError > RecoveryMeasures.maxQuadratureError,
            "a nine-point grid over three orders of magnitude must fail its own check, " +
                    "instead the error was ${result.quadratureError}"
        )
    }

    @Test
    fun `an unbounded density is refused rather than integrated`() {
        // A gamma of shape below one has a density that diverges at the origin. Such a mixture is
        // perfectly integrable in principle and not integrable by any grid that includes the
        // singularity, so the honest answer is to report that the measurement did not happen. The
        // design does not currently build such a component; the guard on the measure does not
        // depend on the design continuing not to.
        val components = listOf(Gamma(0.5, 0.01), Normal(500.0, 2500.0))
        val w = doubleArrayOf(0.5, 0.5)
        val types = listOf(RVType.Gamma, RVType.Normal)
        val result = RecoveryMeasures.compare(w, components, types, w, components, types)
        assertTrue(
            result.quadratureError > RecoveryMeasures.maxQuadratureError,
            "a divergent density must fail the check, the error was ${result.quadratureError}"
        )
    }

    @Test
    fun `it still agrees with the closed form for two normals`() {
        // the non-uniform grid must not have bought robustness at the cost of accuracy:
        // H(N(0,1), N(m,1)) = sqrt(1 - exp(-m^2/8))
        for (m in doubleArrayOf(0.5, 1.0, 2.0, 4.0)) {
            val expected = sqrt(1.0 - exp(-m * m / 8.0))
            val actual = RecoveryMeasures.componentHellinger(Normal(0.0, 1.0), Normal(m, 1.0), 20001)
            assertTrue(
                abs(actual - expected) < 1.0e-5,
                "mean $m: expected $expected, computed $actual"
            )
        }
    }
}
