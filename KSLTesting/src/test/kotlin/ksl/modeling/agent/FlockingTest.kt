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

package ksl.modeling.agent

import ksl.examples.general.agent.FlockingExample
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  Phase 5.3 smoke test for [FlockingExample]: 40 boids in a toroidal
 *  world for 60 simulated seconds. Verifies the three Reynolds force
 *  terms produce emergent flocking — polarization rises well above
 *  the random baseline, boids find each other (positive average
 *  neighbor count), and population stays in the projection.
 */
class FlockingTest {

    @Test
    fun boidsFlockUnderReynoldsRules() {
        val model = Model("FlockingSmokeTest")
        val sys = FlockingExample(model, "flock")
        // Modest population + dt = 0.1 for fast CI; tunings unchanged.
        sys.population = 40
        sys.dt = 0.1
        model.lengthOfReplication = 60.0
        model.numberOfReplications = 1
        model.simulate()

        // Polarization at random initial conditions averages ~0.15 for
        // a small population; a flocking signature shows time-averaged
        // polarization well above that. We allow generous slack since
        // the order parameter wobbles as sub-flocks form and merge.
        val avgPolarization = sys.polarization.acrossReplicationStatistic.average
        assertTrue(
            avgPolarization > 0.35,
            "expected mean polarization > 0.35 indicating coherent flocking; got $avgPolarization",
        )

        // Boids should find neighbors — cohesion is doing its job.
        // 40 boids in a 100×100 world at cohesion radius 10 covers
        // pi * 100 / 10000 = 3.1% of the area on average; at uniform
        // density we'd expect ~1.2 neighbors per boid. Flocking should
        // raise this considerably as boids cluster.
        val avgNeighbors = sys.avgNeighborCount.acrossReplicationStatistic.average
        assertTrue(
            avgNeighbors > 2.0,
            "expected boids to cluster (avg neighbors > 2.0); got $avgNeighbors",
        )

        // Population stays bounded — no boid lost to integration blow-up.
        val avgSpeedVal = sys.avgSpeed.acrossReplicationStatistic.average
        assertTrue(avgSpeedVal in 0.5..5.0, "avg speed should stay reasonable; got $avgSpeedVal")

        // Every boid still tracked in the projection.
        for (b in sys.boids) {
            assertTrue(
                sys.space.positionOf(b) != null,
                "boid ${b.name} should still be tracked",
            )
        }
    }
}
