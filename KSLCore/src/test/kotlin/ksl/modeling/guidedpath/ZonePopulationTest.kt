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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  A zone carries an exclusive holder and a count of occupants, and between them they are the whole
 *  exclusion mechanism.
 *
 *  Two rules, and nothing else:
 *
 *  - an exclusive claim succeeds only when the zone has no holder **and no occupants**;
 *  - an occupant enters only when the zone has no holder.
 *
 *  Out of those two fall a vehicle refused while somebody is on a crossing, pedestrians refused
 *  while a vehicle is on it, and an admission policy that can ask how crowded a zone is -- with no
 *  shared/exclusive modes in the space and no object holding a crossing on anyone's behalf.
 *
 *  The smallest model that can show it: one aisle, one occupant, one vehicle.
 */
class ZonePopulationTest {

    /** A straight path of four zones, one cart, and an occupant that stands in the cart's way. */
    private class Aisle(parent: ModelElement) : ModelElement(parent, "Aisle") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )

        /** The zone the occupant stands in: the third along, so the cart is under way before it. */
        val crossing: Zone get() = network.zone("L1.Zone3")!!

        /** When the occupant arrives and when it leaves, or NaN for neither. */
        var occupantArrives: Double = Double.NaN
        var occupantLeaves: Double = Double.NaN

        var admitted: Boolean? = null
        val cartArrivedAt = mutableListOf<Double>()

        init {
            cart.attachArrivalListener { cartArrivedAt.add(time) }
        }

        override fun initialize() {
            admitted = null
            cartArrivedAt.clear()
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, 0.0)
            if (occupantArrives.isFinite()) {
                schedule({ _: KSLEvent<Nothing> ->
                    admitted = system.admitToZone(crossing)
                }, occupantArrives)
            }
            if (occupantLeaves.isFinite()) {
                schedule({ _: KSLEvent<Nothing> -> system.departFromZone(crossing) }, occupantLeaves)
            }
        }
    }

    private fun run(
        occupantArrives: Double = Double.NaN,
        occupantLeaves: Double = Double.NaN,
        length: Double = 40.0,
        replications: Int = 1
    ): Aisle {
        val m = Model("ZonePopulation")
        val a = Aisle(m)
        a.occupantArrives = occupantArrives
        a.occupantLeaves = occupantLeaves
        a.system.checkInvariants = true
        m.numberOfReplications = replications
        m.lengthOfReplication = length
        m.simulate()
        return a
    }

    // ---- the baseline the rest is measured against ---------------------------------------------

    @Test
    fun `with nobody in the way the cart crosses four zones at one minute each`() {
        // Twelve feet at twelve feet a minute. The cart starts on the junction A and enters Zone1
        // through Zone4 and then the junction B -- but a junction is dimensionless, so only the
        // four link zones cost anything. Four minutes, and the arrival is what the tests below are
        // compared against.
        val a = run()
        assertEquals(listOf(4.0), a.cartArrivedAt.map { it })
        assertEquals(0.0, a.cart.numTimesBlocked.value, 0.0)
    }

    // ---- rule one: a claim needs an empty zone -------------------------------------------------

    @Test
    fun `an occupant in the way stops the cart, and letting it go releases the cart`() {
        // The occupant steps into Zone3 at 0.5, while the cart is still crossing Zone1, and leaves
        // at 10.0. The cart reaches the end of Zone2 at 2.0, is refused Zone3, and waits 8 minutes.
        val a = run(occupantArrives = 0.5, occupantLeaves = 10.0)
        assertEquals(true, a.admitted, "the zone had no holder, so the occupant must have got in")
        assertEquals(1.0, a.cart.numTimesBlocked.value, 0.0, "the cart must have been stopped once")
        // The cart settles at the end of Zone2 at 2.0 and is refused Zone3, so it waits eight
        // minutes and then has Zone3 and Zone4 left: 10.0 + 2.0.
        assertEquals(listOf(12.0), a.cartArrivedAt, "arrival is the clear run plus the 8 minutes waited")
    }

    @Test
    fun `the cart is woken by the last occupant leaving, not by anything holding the zone`() {
        // The point of the funnel. Nothing ever held Zone3, so no release can fire for it; the only
        // thing that can start the cart again is the departure. Two occupants, so the wake-up must
        // come from the second one leaving and not the first.
        val m = Model("ZonePopulationTwo")
        val a = Aisle(m)
        a.system.checkInvariants = true
        object : ModelElement(a, "Crowd") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> a.system.admitToZone(a.crossing) }, 0.5)
                schedule({ _: KSLEvent<Nothing> -> a.system.admitToZone(a.crossing) }, 0.6)
                schedule({ _: KSLEvent<Nothing> -> a.system.departFromZone(a.crossing) }, 6.0)
                schedule({ _: KSLEvent<Nothing> -> a.system.departFromZone(a.crossing) }, 9.0)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        assertEquals(0, a.crossing.numPresent, "both occupants left")
        // Blocked at 2.0 until the *second* departure at 9.0, so seven minutes and not four, and
        // then Zone3 and Zone4 at a minute each.
        assertEquals(listOf(11.0), a.cartArrivedAt, "the first departure must not have woken the cart")
        assertEquals(1.0, a.cart.numTimesBlocked.value, 0.0)
    }

    // ---- rule two: an occupant needs a zone with no holder -------------------------------------

    @Test
    fun `an occupant is refused a zone a vehicle is standing in`() {
        // The pedestrians' half of the crossing: the same two facts about the zone, working in the
        // other direction. The zone is chosen at run time as whatever the cart is covering at that
        // instant, because which zone that is depends on the control rule -- the cart gives up the
        // zone behind as it arrives, so naming one in advance tests the wrong thing.
        val m = Model("ZonePopulationRefused")
        val a = Aisle(m)
        a.system.checkInvariants = true
        var admittedToHeld: Boolean? = null
        var attemptedOn: Zone? = null
        object : ModelElement(a, "LateComer") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    val underTheCart = a.cart.frontZone!!
                    attemptedOn = underTheCart
                    admittedToHeld = a.system.admitToZone(underTheCart)
                }, 2.5)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        assertTrue(attemptedOn != null, "the cart was never anywhere, so nothing was tested")
        assertEquals(false, admittedToHeld, "a zone a vehicle covers must refuse an occupant")
        assertEquals(0, attemptedOn!!.numPresent, "and must be left empty")
    }

    // ---- the state itself ----------------------------------------------------------------------

    @Test
    fun `a zone with occupants is available to nobody and holds nobody`() {
        val a = run(occupantArrives = 0.5, occupantLeaves = 10.0, length = 3.0)
        // Stopped at 2.0 and the run ends at 3.0, so the occupant is still standing there.
        val z = a.crossing
        assertEquals(1, z.numPresent)
        assertTrue(z.hasOccupants)
        assertFalse(z.hasHolder, "an occupied zone is held by nobody")
        assertTrue(z.isAvailable, "it has no holder, so its state is FREE")
        assertFalse(z.isCovered)
    }

    @Test
    fun `the population is cleared between replications`() {
        // The third instance of a defect family this subsystem has already met twice -- a manifest
        // and a position, both left behind by a reset. A test that ran one replication would prove
        // nothing about it, which is why this one runs three and requires the same answer each time.
        val a = run(occupantArrives = 0.5, occupantLeaves = 10.0, replications = 3)
        assertEquals(0, a.crossing.numPresent)
        val blocked = a.cart.numTimesBlocked.acrossReplicationStatistic
        assertEquals(3.0, blocked.count, 0.0, "one observation per replication")
        assertEquals(1.0, blocked.average, 1e-12, "the cart was stopped once in every replication")
        assertEquals(
            0.0, blocked.variance, 1e-12,
            "a deterministic model must block identically every replication; any spread means a " +
                    "count was carried over"
        )
    }
}
