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

import ksl.examples.general.agent.PedestrianCrowdExample
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  Phase 5.2 smoke test for [PedestrianCrowdExample]: a Helbing
 *  social-force crowd evacuating a one-room layout through a
 *  three-cell doorway. Uses a larger `dt` than the canonical 0.05
 *  to keep CI time bounded.
 */
class PedestrianCrowdTest {

    @Test
    fun pedestriansEvacuateThroughTheDoorway() {
        val model = Model("PedestrianCrowdSmokeTest")
        val sys = PedestrianCrowdExample(model, "crowd")
        // Smaller population + larger dt → faster test, same qualitative
        // behavior. dt = 0.1 is on the looser end of stable for these
        // force constants but works for the smoke test horizon.
        sys.population = 25
        sys.dt = 0.1
        model.lengthOfReplication = 300.0
        model.numberOfReplications = 1
        model.simulate()

        // Most pedestrians should have evacuated. With 25 in a room of
        // ~360 passable cells and a 3-cell doorway, the bottleneck
        // throughput is the limit. We expect well over half out in 300s.
        val evacuated = sys.numEvacuated.acrossReplicationStatistic.average
        assertTrue(
            evacuated >= 15.0,
            "expected at least 15 of 25 pedestrians evacuated; got $evacuated",
        )

        // Average evacuation time should be positive and bounded. With
        // ~25m of travel at ~1.3 m/s plus arching delays, expect 20–120s.
        val avgTime = sys.evacuationTime.acrossReplicationStatistic.average
        assertTrue(avgTime > 0.0, "average evacuation time should be positive; got $avgTime")
        assertTrue(
            avgTime < 300.0,
            "average evacuation time should be bounded by replication length; got $avgTime",
        )

        // Sanity: the pedestrians that DID evacuate had to pass through
        // a doorway cell, so the exits set has done work. (We don't
        // have per-cell traversal counts; instead, check that any
        // remaining pedestrians are still tracked in the projection.)
        for (p in sys.pedestrians) {
            // p might have evacuated (removed from context) or not.
            // We don't assert; just verify no crash querying.
            sys.space.positionOf(p)  // either Point2D or null is fine
        }
    }
}
