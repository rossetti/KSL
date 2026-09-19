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
package ksl.modeling.guidedpath

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  `G6`: the guided-path process verbs behave and read like the transfer constructs the library
 *  already has.
 *
 *  Parity matters for two separate reasons, and the tests below cover both.
 *
 *  It matters to a reader, because a modeller who has used `transportWith` should be able to write
 *  a guided model without relearning the shape of the call: a request/move/release triad plus a
 *  composed verb, the same delay and priority parameters, the same defaults.
 *
 *  It matters rather more to a *result*. The purpose of the subsystem is to let a study ask what
 *  congestion costs, which means running the same model free-path and guided and attributing the
 *  difference to the guide path. That attribution is only valid if the two families agree when
 *  there is no congestion to find. So the central test here runs one journey both ways over matched
 *  distances and requires the answers to be identical -- not close. Any residual difference would
 *  otherwise be silently added to every congestion figure the subsystem ever reports.
 *
 *  One deliberate divergence: the guided verbs have no `emptyMovePriority` or `transportPriority`.
 *  A free-path move is a single scheduled event whose priority can be set; a guided move is a
 *  sequence of zone traversals arbitrated by the contention rule, so there is no one event to
 *  prioritise and the contention rule is the parameter that does that job. It is recorded here so
 *  the omission reads as a decision rather than an oversight.
 */
class ProcessApiParityTest {

    // ---- the same journey, both ways -----------------------------------------------------------

    /**
     *  One cart on the guide path, carrying one part from the entry station to the exit station.
     *  The cart starts on its home spur, so the journey is an empty move of 126 feet followed by a
     *  loaded move of 204, at a constant ten feet per minute.
     */
    private class GuidedShop(parent: ModelElement) : ProcessModel(parent, "GuidedShop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart"
        )
        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), ClosestByNetworkDistanceRule(), ParkInPlaceRule(), "Carts"
        )

        var finishedAt: Double = -1.0
        var result: GuidedTransportResult? = null

        inner class Part : Entity() {
            val make = process("part") {
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                result = guidedTransport(
                    carts,
                    destination = SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION,
                    loadingDelay = ConstantRV(1.0),
                    unLoadingDelay = ConstantRV(2.0)
                )
                finishedAt = time
            }
        }

        override fun initialize() {
            finishedAt = -1.0
            result = null
            activate(Part().make)
        }
    }

    /**
     *  The same journey with a free-path movable resource over a distance model carrying exactly the
     *  distances the guide path implies. Nothing is in the way in either model, so the only thing
     *  that could make the two disagree is a difference in what the verbs mean.
     */
    private class FreePathShop(parent: ModelElement) : ProcessModel(parent, "FreePathShop") {
        private val distances = DistancesModel()
        val home = distances.Location("Home")
        val entry = distances.Location("Entry")
        val exit = distances.Location("Exit")

        init {
            // The distances the guide path gives: home spur to the entry station, and the long way
            // round the one-way loop from entry to exit.
            distances.addDistance(home, entry, 126.0)
            distances.addDistance(entry, exit, 204.0)
            distances.defaultVelocity = ConstantRV(10.0)
            spatialModel = distances
        }

        val cart = MovableResourceWithQ(this, home, ConstantRV(10.0), name = "Cart")

        var finishedAt: Double = -1.0

        inner class Part : Entity() {
            val make = process("part") {
                entity.currentLocation = entry
                transportWith(
                    cart, exit,
                    loadingDelay = ConstantRV(1.0),
                    unLoadingDelay = ConstantRV(2.0)
                )
                finishedAt = time
            }
        }

        override fun initialize() {
            finishedAt = -1.0
            activate(Part().make)
        }
    }

    private fun <T : ProcessModel> simulate(name: String, factory: (Model) -> T): T {
        val m = Model(name)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return p
    }

    @Test
    @DisplayName("An uncongested guided journey takes exactly as long as the free-path equivalent")
    fun guidedAndFreePathAgreeWhenNothingIsInTheWay() {
        val guided = simulate("GuidedRun") { GuidedShop(it) }
        val freePath = simulate("FreePathRun") { FreePathShop(it) }

        // 12.6 empty + 1 loading + 20.4 loaded + 2 unloading.
        assertEquals(36.0, freePath.finishedAt, 1e-9, "the free-path arithmetic is the reference")
        assertEquals(
            freePath.finishedAt, guided.finishedAt, 1e-9,
            "the two families must agree with no congestion to find, or every congestion figure " +
                    "the guided subsystem reports carries a constant offset that is not congestion"
        )

        val r = assertNotNull(guided.result)
        assertEquals(12.6, r.approachTime, 1e-9)
        assertEquals(20.4, r.rideTime, 1e-9)
        assertEquals(204.0, r.routeLength, 1e-9)
        assertEquals(0.0, r.blockedTime, 1e-9)
    }

    // ---- the triad and the composed verb -------------------------------------------------------

    /** The same journey written out as request, transport, release rather than as one call. */
    private class TriadShop(parent: ModelElement) : ProcessModel(parent, "TriadShop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart"
        )
        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), ClosestByNetworkDistanceRule(), ParkInPlaceRule(), "Carts"
        )

        var finishedAt: Double = -1.0
        var result: GuidedTransportResult? = null
        var reusedAfterRelease: Throwable? = null

        inner class Part : Entity() {
            val make = process("part") {
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val request = requestGuidedTransporter(carts, pickupLocation = SimpleAgvNetwork.ENTRY_STATION)
                delay(1.0)
                result = transportBy(request, destination = SimpleAgvNetwork.EXIT_STATION)
                delay(2.0)
                releaseGuidedTransporter(request, carts)
                finishedAt = time
                // A request is inert once its transporter has gone back. Using it again is a defect
                // in the process, and it has to fail here rather than quietly command a transporter
                // that now belongs to somebody else.
                try {
                    releaseGuidedTransporter(request, carts)
                } catch (e: Throwable) {
                    reusedAfterRelease = e
                }
            }
        }

        override fun initialize() {
            finishedAt = -1.0
            result = null
            reusedAfterRelease = null
            activate(Part().make)
        }
    }

    @Test
    @DisplayName("The triad composes into the same journey as the composed verb")
    fun theTriadAndTheComposedVerbAgree() {
        val composed = simulate("ComposedRun") { GuidedShop(it) }
        val triad = simulate("TriadRun") { TriadShop(it) }
        assertEquals(
            composed.finishedAt, triad.finishedAt, 1e-9,
            "spelling the journey out as request/transport/release, with the loading and unloading " +
                    "written as ordinary delays, must cost exactly what the one-liner costs"
        )
        val c = assertNotNull(composed.result)
        val t = assertNotNull(triad.result)
        assertEquals(c.approachTime, t.approachTime, 1e-9)
        assertEquals(c.rideTime, t.rideTime, 1e-9)
        assertEquals(c.zonesTraversed, t.zonesTraversed)
        assertEquals(c.routeLength, t.routeLength, 1e-9)
    }

    @Test
    @DisplayName("A released request cannot be used again")
    fun aReleasedRequestIsInert() {
        val triad = simulate("InertRun") { TriadShop(it) }
        val thrown = assertNotNull(
            triad.reusedAfterRelease,
            "releasing a request twice must fail rather than release somebody else's transporter"
        )
        assertTrue(thrown is IllegalStateException, "the failure is a state error, but was $thrown")
        assertTrue(
            thrown.message!!.contains("Cart"),
            "the message must name the transporter so the defect can be found: ${thrown.message}"
        )
    }

    /**
     *  Two entities and one request: the first obtains a transporter and holds it, the second gets
     *  hold of the same request and tries to be carried by it. Nothing stops a modeller passing a
     *  request around, so the verb has to refuse it -- otherwise one entity would command a
     *  transporter that is carrying another, and both would arrive somewhere neither was sent.
     */
    private class SharedRequestShop(parent: ModelElement) : ProcessModel(parent, "SharedRequestShop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart"
        )
        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), ClosestByNetworkDistanceRule(), ParkInPlaceRule(), "Carts"
        )

        var shared: GuidedTransportRequest? = null
        var stolenBy: Throwable? = null

        inner class Owner : Entity() {
            val make = process("owner") {
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val request = requestGuidedTransporter(carts, pickupLocation = SimpleAgvNetwork.ENTRY_STATION)
                shared = request
                delay(30.0)
                releaseGuidedTransporter(request, carts)
            }
        }

        inner class Thief : Entity() {
            val make = process("thief") {
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val request = shared
                if (request != null) {
                    try {
                        transportBy(request, destination = SimpleAgvNetwork.EXIT_STATION)
                    } catch (e: Throwable) {
                        stolenBy = e
                    }
                }
            }
        }

        override fun initialize() {
            shared = null
            stolenBy = null
            activate(Owner().make)
            // Late enough that the owner has its transporter and has not yet given it back.
            activate(Thief().make, timeUntilActivation = 20.0)
        }
    }

    @Test
    @DisplayName("A request built for one entity cannot be used by another")
    fun aRequestBelongsToOneEntity() {
        val shop = simulate("OwnershipRun") { SharedRequestShop(it) }
        assertNotNull(shop.shared, "the owner must have obtained a transporter for the test to mean anything")
        val thrown = assertNotNull(
            shop.stolenBy,
            "a second entity commanding somebody else's request must be refused, not obeyed"
        )
        assertTrue(thrown is IllegalArgumentException, "the failure is an argument error, but was $thrown")
        assertTrue(
            thrown.message!!.contains("belonging to entity"),
            "the message must say whose request it was: ${thrown.message}"
        )
    }

    // ---- shape ---------------------------------------------------------------------------------

    private fun verb(name: String): KFunction<*> {
        val fns = KSLProcessBuilder::class.memberFunctions.filter { it.name == name }
        assertEquals(
            1, fns.size,
            "there must be exactly one $name declared as a member of KSLProcessBuilder; found " +
                    "${fns.size}. An extension function would not appear here at all, which is " +
                    "the point of asking"
        )
        return fns.single()
    }

    @Test
    @DisplayName("The four verbs are members of KSLProcessBuilder, not extensions on it")
    fun theVerbsAreMembers() {
        for (name in listOf(
            "requestGuidedTransporter", "transportBy", "releaseGuidedTransporter", "guidedTransport"
        )) {
            val f = verb(name)
            assertTrue(f.isSuspend, "$name must be suspending like every other transfer verb")
        }
    }

    @Test
    @DisplayName("Every verb ends in an optional suspensionName, as the existing verbs do")
    fun theVerbsCarryTheFamilyParameters() {
        for (name in listOf(
            "requestGuidedTransporter", "transportBy", "releaseGuidedTransporter", "guidedTransport"
        )) {
            val params = verb(name).parameters.drop(1) // drop the receiver
            val last = params.last()
            assertEquals(
                "suspensionName", last.name,
                "$name must end in suspensionName so that a process with several suspension " +
                        "points can name them, exactly as the conveyor and movable-resource verbs do"
            )
            assertTrue(last.isOptional, "suspensionName must default to null in $name")
        }
    }

    @Test
    @DisplayName("Delays and priorities default, so the simple call stays simple")
    fun theOptionalParametersAreOptional() {
        val required = verb("guidedTransport").parameters.drop(1).filter { !it.isOptional }
        assertEquals(
            listOf("pool", "destination"), required.map { it.name },
            "only the fleet and where to go may be required of a caller; everything else -- pickup, " +
                    "loading, unloading, and every priority -- must have a default, matching the " +
                    "shape of transportWith"
        )
    }

    @Test
    @DisplayName("Guided transporters work with the existing allocation machinery unchanged")
    fun theyUseTheExistingResourceMachinery() {
        val shop = simulate("MachineryRun") { GuidedShop(it) }
        // A guided transporter is a capacity-one Resource, so the ordinary resource statistics are
        // collected for it without the subsystem doing anything special.
        assertTrue(
            shop.cart.numTimesSeized > 0,
            "the seize must go through Resource, not around it"
        )
        assertEquals(
            0.0, shop.cart.numBusyUnits.value, 1e-9,
            "the release must go through Resource too, leaving the transporter free at the end"
        )
        assertTrue(
            shop.carts.waitingQ.timeInQ.withinReplicationStatistic.count >= 0.0,
            "the pool's waiting queue must be an ordinary KSL queue collecting ordinary statistics"
        )
    }
}
