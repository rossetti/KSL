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

package ksl.utilities.random.mcmc

import ksl.utilities.random.rvariable.UniformRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MetropolisHastingsMVTest {

    /** A constant target on two dimensions. */
    private object Unit2D : FunctionMVIfc {
        override val dimension: Int get() = 2
        override fun f(x: DoubleArray): Double = 1.0
    }

    /** Independent uniform proposals on two dimensions. */
    private object Uniform2D : ProposalFunctionMVIfc {
        private val u = UniformRV(0.0, 1.0)
        override val dimension: Int get() = 2
        override fun proposalRatio(currentX: DoubleArray, proposedY: DoubleArray): Double = 1.0
        override fun generateProposedGivenCurrent(currentX: DoubleArray): DoubleArray =
            doubleArrayOf(u.value, u.value)
    }

    /** Before R1.8.1 the header named the one-dimensional class, MetropolisHastings1D. */
    @Test
    @DisplayName("Printing a multivariate sampler names MetropolisHastingsMV")
    fun headerNamesTheClass() {
        val m = MetropolisHastingsMV(doubleArrayOf(0.5, 0.5), Unit2D, Uniform2D)
        m.runAll(10)
        assertEquals("MetropolisHastingsMV", m.toString().lines().first())
    }
}
