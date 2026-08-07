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

package ksl.examples.general.agent

import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase C11 — `FlockingExample`'s update regime.
 *
 *  The example previously stepped each boid in its own loop, applying immediately:
 *  a Gauss-Seidel update, where boids visited later in a tick steer against
 *  neighbours that have already moved. `Dynamics.stepAll` documents the synchronous
 *  alternative as the right choice "when reproducibility / order independence
 *  matters, **e.g. flocking**", so the flagship flocking example was demonstrating
 *  the idiom its own library warns against for this exact case.
 *
 *  It now supports both behind `useJacobiUpdate`, defaulting to the synchronous
 *  form. These tests assert the switch is real — that both regimes run, that they
 *  produce genuinely different trajectories, and that each is reproducible — which
 *  is what makes the example usable as a demonstration rather than an assertion.
 */
class FlockingUpdateRegimeTest {

    /** Runs the example and returns the final boid positions, in creation order. */
    private fun runFlock(jacobi: Boolean, length: Double = 8.0): List<Pair<Double, Double>> {
        val model = Model("flock-${if (jacobi) "jacobi" else "gaussSeidel"}")
        val sys = FlockingExample(model, "flock")
        sys.useJacobiUpdate = jacobi
        sys.population = 12
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        model.simulate()
        return sys.boids.map { b ->
            val p = sys.space.positionOf(b)!!
            p.x to p.y
        }
    }

    @Test
    @DisplayName("C11: both update regimes run and move the flock")
    fun bothRegimesRun() {
        for (jacobi in listOf(true, false)) {
            val positions = runFlock(jacobi)
            assertEquals(12, positions.size, "every boid should still have a position (jacobi=$jacobi)")
            assertTrue(
                positions.any { (x, y) -> x != 0.0 || y != 0.0 },
                "the flock should have moved (jacobi=$jacobi)",
            )
        }
    }

    /**
     *  The switch has to actually change the model, or it would be decoration. Same
     *  streams, same initial conditions, different update regime — the trajectories
     *  must diverge, which is the whole point Huberman and Glance made.
     */
    @Test
    @DisplayName("C11: the two regimes produce different trajectories")
    fun regimesDiverge() {
        val jacobi = runFlock(jacobi = true)
        val gaussSeidel = runFlock(jacobi = false)
        val differing = jacobi.zip(gaussSeidel).count { (a, b) ->
            kotlin.math.abs(a.first - b.first) > 1e-9 || kotlin.math.abs(a.second - b.second) > 1e-9
        }
        assertTrue(
            differing > 0,
            "update order should change the outcome; all $differing of ${jacobi.size} boids matched",
        )
    }

    /**
     *  Each regime must be reproducible on its own terms — the divergence above is
     *  the regime, not run-to-run noise.
     */
    @Test
    @DisplayName("C11: each regime is reproducible across identical runs")
    fun eachRegimeIsReproducible() {
        for (jacobi in listOf(true, false)) {
            val first = runFlock(jacobi)
            val second = runFlock(jacobi)
            assertEquals(first, second, "identical runs should agree (jacobi=$jacobi)")
        }
    }

    /**
     *  The default is the synchronous regime, because that is what the library
     *  documents as correct for flocking. Pinned so a later edit cannot quietly
     *  revert the example to demonstrating the discouraged idiom.
     */
    @Test
    @DisplayName("C11: the synchronous regime is the default")
    fun jacobiIsTheDefault() {
        assertTrue(FlockingExample.Defaults.useJacobiUpdate, "default should select Jacobi")
        val model = Model("flock-default")
        val sys = FlockingExample(model, "flock")
        assertTrue(sys.useJacobiUpdate, "a fresh instance should inherit the default")
    }
}
