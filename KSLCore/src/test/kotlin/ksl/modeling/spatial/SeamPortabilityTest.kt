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
package ksl.modeling.spatial

import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.MovableAgentResource
import ksl.modeling.agent.Point2D
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **What the walking-a-tour code looks like when it knows nothing about the substrate.**
 *
 *  The conformance suite proves each substrate keeps the contract. This proves the other half of
 *  the claim: that code written *against* the contract runs over either one unchanged. [driveTour]
 *  below is the shape of `AgvSystem`'s control loop with everything substrate-specific removed —
 *  issue a leg, wait, re-issue if something stopped it short, advance — and it does not mention a
 *  zone, an interpolation step, a network or a projection. The two fixtures below hand it different
 *  worlds and it does not notice.
 *
 *  **What this is not.** A `Line`, a `Stop` and a `Dispatcher` still require an `AgvSystem`, which
 *  is built on a `GuidedPathNetwork` and reads its vehicles' manifests off `GuidedTransporter`.
 *  Running a *declared service* on a projection therefore waits on the fleet layer being lifted
 *  onto the seam, which is Phase 7. What is demonstrated here is the property that lift depends on:
 *  the tour-walking itself has no substrate in it.
 */
class SeamPortabilityTest {

    /** What one run recorded, in terms no substrate owns. */
    private class Round {
        val arrivals = mutableListOf<String>()
        val legLengths = mutableListOf<Double>()
        val odometer = mutableListOf<Double>()
        var totalDistance: Double = Double.NaN
    }

    /**
     *  Walks an itinerary using nothing but [VehicleMovementIfc].
     *
     *  A top-level extension on the process builder because `@RestrictsSuspension` permits no other
     *  form, and it is written here rather than in the library because the library's own version of
     *  it is `AgvSystem`'s control loop, which does considerably more.
     */
    private suspend fun KSLProcessBuilder.driveTour(
        movement: VehicleMovementIfc,
        itinerary: List<LocationIfc>,
        waiter: ProcessModel.Entity,
        round: Round,
        release: (VehicleMovementIfc) -> Unit
    ) {
        for (stop in itinerary) {
            round.legLengths.add(movement.pathDistanceTo(stop))
            // A leg is re-issued until the vehicle actually gets there. Under an ordinary journey
            // this runs once; it runs again whenever something stopped the vehicle short.
            while (true) {
                val q = movement.beginTravelTo(stop, MovePurpose.SERVICE, waiter) ?: break
                hold(q, suspensionName = "travellingTo:${stop.name}")
                if (!movement.isHalted) break
                release(movement)
                movement.resumeHalted()
            }
            round.arrivals.add(stop.name)
            round.odometer.add(movement.distanceTravelled)
        }
        round.totalDistance = movement.distanceTravelled
    }

    // ---- substrate one: a guide path -----------------------------------------------------------

    private inner class Aisles(parent: ModelElement, val round: Round) : ProcessModel(parent, "Aisles") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            // Deliberately longer than the straight line between the same two corners: a guide
            // path's distance is a declared path length, and the plane's is a separation. The two
            // are different questions and the seam asks each substrate its own.
            .link("CD", "C", "D", length = 150.0, zoneLength = 25.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 25.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        val space = GuidedPathTransportSystem(this, network, name = "Space")
        val cart = GuidedTransporter(space, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")

        inner class Driver : Entity("Driver") {
            val p = process(isDefaultProcess = true) {
                driveTour(
                    cart,
                    listOf(
                        network.requireLocation("B"),
                        network.requireLocation("C"),
                        network.requireLocation("D")
                    ),
                    this@Driver, round
                ) { }
            }
        }

        override fun initialize() {
            activate(Driver().p)
        }
    }

    // ---- substrate two: a continuous projection ------------------------------------------------

    private inner class Floor(parent: ModelElement, val round: Round) : AgentModel(parent, "Floor") {

        val world: Context<AgentLike> = Context("world")
        val space: ContinuousProjection<AgentLike> =
            ContinuousProjection(world, 0.0..200.0, 0.0..200.0)

        val cart = MovableAgentResource(
            this, space, initPosition = Point2D(0.0, 0.0), name = "Cart",
            velocity = 10.0, stepSize = 5.0
        )

        // The same four corners, so the round is recognisably the same round. The distances are not
        // the guide path's -- one is along links, the other across a plane -- and that is the point:
        // each substrate answers with its own metric and the driver above never compares them.
        val b = space.spatialModel.location(100.0, 0.0, "B")
        val c = space.spatialModel.location(100.0, 100.0, "C")
        val d = space.spatialModel.location(0.0, 100.0, "D")

        inner class Driver : Entity("Driver") {
            val p = process(isDefaultProcess = true) {
                driveTour(cart, listOf(b, c, d), this@Driver, round) { }
            }
        }

        override fun initialize() {
            activate(Driver().p)
        }
    }

    private fun runGuidePath(): Round {
        val round = Round()
        val m = Model("SeamGuidePath")
        Aisles(m, round)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return round
    }

    private fun runProjection(): Round {
        val round = Round()
        val m = Model("SeamProjection")
        Floor(m, round)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return round
    }

    @Test
    @DisplayName("One tour-walking loop drives a guide path and a projection to the same itinerary")
    fun oneLoopTwoSubstrates() {
        val guidePath = runGuidePath()
        val projection = runProjection()

        assertEquals(listOf("B", "C", "D"), guidePath.arrivals, "the guide path went somewhere else")
        assertEquals(listOf("B", "C", "D"), projection.arrivals, "the projection went somewhere else")
        assertEquals(
            guidePath.arrivals, projection.arrivals,
            "the same driver produced different itineraries on two substrates, which is exactly " +
                    "what the seam exists to prevent"
        )
    }

    @Test
    @DisplayName("Each substrate accounts for the round in its own metric, and neither goes backwards")
    fun eachSubstrateAccountsForItself() {
        for ((what, round) in listOf("guide path" to runGuidePath(), "projection" to runProjection())) {
            assertEquals(3, round.legLengths.size, "$what: the driver did not measure three legs")
            assertTrue(round.legLengths.all { it > 0.0 }, "$what: a leg measured zero before setting out")
            for (i in 1 until round.odometer.size) {
                assertTrue(
                    round.odometer[i] >= round.odometer[i - 1],
                    "$what: the odometer went backwards between stops"
                )
            }
            // Every leg was asked for before it was made, so the sum of what was asked is what was
            // travelled. That the two substrates disagree about the *number* is the whole point of
            // asking the substrate rather than computing a distance above it.
            assertEquals(
                round.legLengths.sum(), round.totalDistance, 1e-6,
                "$what: the distance travelled does not account for the legs that were planned"
            )
        }
    }

    @Test
    @DisplayName("The two substrates give different distances for the same corners, and should")
    fun theMetricsAreTheSubstrates() {
        val guidePath = runGuidePath()
        val projection = runProjection()
        // The same three corners in the same order, and two different answers: 100 + 100 + 150
        // along declared links, 100 + 100 + 100 across a plane. Neither is wrong, and a fleet that
        // computed distances above the seam instead of asking would have got one of them wrong
        // wherever it ran.
        assertEquals(350.0, guidePath.totalDistance, 1e-6, "two 100-unit links and one of 150")
        assertEquals(300.0, projection.totalDistance, 1e-6, "three 100-unit sides of a square")
        assertTrue(
            guidePath.totalDistance != projection.totalDistance,
            "the fixture no longer distinguishes the two metrics, so this test proves nothing"
        )
    }
}
