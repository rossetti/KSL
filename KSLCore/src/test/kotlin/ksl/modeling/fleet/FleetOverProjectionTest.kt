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
package ksl.modeling.fleet

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.ProjectionSpatialModel
import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **What "there is no fleet binding for the projection" does and does not mean.**
 *
 *  It is easy to read that sentence as saying a dispatcher cannot be run over an agent-based
 *  projection at all. It says less than that, and the difference is worth a test rather than a
 *  paragraph, because the two halves are separately true and a modeller only cares about one of
 *  them at a time.
 *
 *  **A fleet runs over a projection's geometry today.** `ProjectionSpatialModel` is an ordinary
 *  `SpatialModel` wrapping a `ContinuousProjection`, and `FreePathFleet` asks a spatial model for
 *  named places and distances and nothing else. So the dispatcher, tours, consolidation and every
 *  statistic work over projection coordinates with no new class, which this test runs to make sure
 *  the claim is not merely plausible.
 *
 *  **What is missing is a vehicle that is itself an agent in that projection.** The vehicle here is
 *  a `FreePathVehicle`, whose body is a `MovableResource`. It is *at* projection coordinates but it
 *  is not *in* the projection: it has no `Agent` identity, so it is invisible to neighbour queries,
 *  force dynamics and statecharts, and pedestrians will walk through it. A binding built on
 *  `MovableAgentResource` is what would close that, and nothing ships one.
 *
 *  So the honest statement is: **geometry yes, agency no.** If you want a dispatcher over
 *  projection coordinates, this works now. If you want the vehicles to be agents that the crowd can
 *  see, that is unbuilt.
 */
class FleetOverProjectionTest {

    private class Floor(parent: ModelElement) : AgentModel(parent, "Floor") {

        val context: Context<Agent> = Context("movers")
        val projection: ContinuousProjection<Agent> =
            ContinuousProjection(context, xRange = 0.0..200.0, yRange = 0.0..200.0)
    }

    private class PorterShop(parent: ModelElement) : ProcessModel(parent, "PorterShop") {

        val floor = Floor(this)

        /** The projection, seen as an ordinary spatial model. No new class is involved. */
        val space = ProjectionSpatialModel(floor.projection)

        val places = listOf(
            space.location(0.0, 0.0, "Store"),
            space.location(100.0, 0.0, "WardA"),
            space.location(100.0, 100.0, "WardB")
        )

        init {
            spatialModel = space
        }

        val fleet = FreePathFleet(this, space, places, name = "Porters")

        val porter = FreePathVehicle(
            fleet, "Store", ConstantRV(10.0), name = "Porter", loadCapacity = 2, stepSize = 5.0
        ).apply { homeBase = "Store" }

        inner class Delivery(private val to: String) : Entity() {
            val run = process {
                currentLocation = fleet.space.requireLocation("Store")
                transportByFleet(fleet, destination = to, origin = "Store")
            }
        }

        override fun initialize() {
            activate(Delivery("WardA").run)
            activate(Delivery("WardB").run)
        }
    }

    @Test
    @DisplayName("a fleet runs over a projection's geometry with no new class")
    fun aFleetRunsOverAProjection() {
        val m = Model("FleetOverProjection")
        val shop = PorterShop(m)
        m.numberOfReplications = 2
        m.lengthOfReplication = 500.0
        m.simulate()

        assertEquals(
            2.0, shop.fleet.dispatcher.numTasksCompleted.acrossReplicationStatistic.average, 1e-12,
            "both deliveries should complete over projection coordinates"
        )
        assertTrue(
            shop.porter.distanceTravelled > 0.0,
            "the porter should have covered ground measured by the projection"
        )
        // The projection's own metric is what measured it: Store to WardA is 100 across the floor.
        assertEquals(
            100.0,
            shop.space.distance(shop.places[0], shop.places[1]),
            1e-12,
            "distance came from the projection, not from a table the fleet kept"
        )
    }

    @Test
    @DisplayName("the vehicle is at projection coordinates but is not an agent in the projection")
    fun theVehicleIsNotAnAgent() {
        val m = Model("FleetOverProjection2")
        val shop = PorterShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        // This is the half that is missing, stated as a measurement rather than as a caveat: the
        // projection holds no agents, so nothing in a crowd model can see the porter.
        assertEquals(
            0, shop.floor.projection.size,
            "a FreePathVehicle occupies projection coordinates without being an agent in it; " +
                    "closing that would need a binding built on MovableAgentResource"
        )
    }
}
