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

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  A pool sends a transporter that can actually come.
 *
 *  A [TransporterMovementGateIfc] can stop a transporter at a zone boundary while nobody holds it:
 *  a transporter repositioning under an idle disposition rule belongs to no entity, so a gate that
 *  refuses it passage leaves a vehicle that is unallocated and immobile at the same time. An
 *  allocation rule ranks candidates on network distance and has no way to tell the two apart.
 *
 *  **The blindness is worse than it sounds, because the rule is not merely indifferent to the
 *  stopped vehicle -- it prefers it.** Distance is measured from where a candidate stands, and the
 *  stopped candidate is the one whose distance does not grow while everybody else's does. So the
 *  rule that is supposed to minimise empty running selects, out of the whole fleet, the one vehicle
 *  that will never arrive; the entity waits out the replication; and the run reports nothing,
 *  because from the pool's point of view a transporter was found and sent.
 *
 *  The first test below is the control that establishes the preference rather than assuming it: the
 *  same model with the gate never refusing sends the vehicle that the gated model must not send.
 *
 *  The fix has two halves and both are tested, because the first without the second would replace a
 *  silent wrong answer with a silent stall. A pool offers only [GuidedTransporter.isDispatchable]
 *  transporters, *and* a transporter that becomes dispatchable says so -- the library's ordinary
 *  wake path runs on release, and a transporter released from a halt is not released by anybody.
 */
class HaltedDispatchTest {

    /**
     *  Two one-way cycles that share a single junction, "P".
     *
     *  A to B to P and back to A is one; D to E to P and back to D is the other. The shape is chosen
     *  so that the two carts' journeys to the shared pickup do not overlap: on a single loop the
     *  nearest cart is by construction the one every other cart must drive through, so a halted cart
     *  would block the fleet as well as failing to come, and a test could not tell the two effects
     *  apart. Here the far cart's route to "P" never touches the link the near cart is stopped on,
     *  so anything that goes wrong is the pool's choice and nothing else.
     */
    private companion object {
        const val ZONE = 25.0
        const val SPEED = 10.0

        fun network(): GuidedPathNetwork = GuidedPathNetwork.builder("TwoLoops")
            .link("AB", "A", "B", length = 100.0, zoneLength = ZONE, beginDirection = 0.0)
            .link("BP", "B", "P", length = 100.0, zoneLength = ZONE, beginDirection = 90.0)
            .link("PA", "P", "A", length = 100.0, zoneLength = ZONE, beginDirection = 180.0)
            .link("DE", "D", "E", length = 150.0, zoneLength = ZONE, beginDirection = 270.0)
            .link("EP", "E", "P", length = 100.0, zoneLength = ZONE, beginDirection = 0.0)
            .link("PD", "P", "D", length = 100.0, zoneLength = ZONE, beginDirection = 90.0)
            .build()
    }

    /**
     *  A gate that refuses everything from the moment it is armed. Nothing subtler is needed: what
     *  is under test is what a pool does with a transporter a gate has stopped, not the gate's own
     *  reasoning.
     */
    private class ClosedGate(var closed: Boolean) : TransporterMovementGateIfc {
        override fun mayContinue(transporter: GuidedTransporter, zone: Zone): Boolean = !closed
    }

    /**
     *  Two carts and one part that wants collecting from "P".
     *
     *  "Near" starts at A and is sent to B at once, so with the gate closed it stops one zone along
     *  "AB" -- 200 units from the pickup by way of B. "Far" starts at D, 250 units from the pickup
     *  by way of E. Near is therefore the rule's choice on distance, whether or not it can move.
     */
    private class Shop(
        parent: ModelElement,
        gateClosed: Boolean,
        cartCount: Int = 2,
        val releaseHaltAt: Double? = null
    ) : ProcessModel(parent, "Shop") {

        val network: GuidedPathNetwork = network()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val gate = ClosedGate(gateClosed)

        val near = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(SPEED), name = "Near")
            .apply {
                homeBase = "A"
                attachMovementGate(gate)
            }

        val far: GuidedTransporter? =
            if (cartCount > 1) GuidedTransporter(
                system, TransporterPlacement.At("D"), ConstantRV(SPEED), name = "Far"
            ).apply { homeBase = "D" } else null

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOfNotNull(near, far),
            allocationRule = ClosestByNetworkDistanceRule(),
            idleDispositionRule = ReturnToHomeBaseRule(),
            name = "Carts"
        )

        /** Which cart actually collected the part, or null if none ever did. */
        var carriedBy: String? = null

        /** When the part finished, or NaN if it never did. */
        var finishedAt: Double = Double.NaN

        /** Whether the part was still queued for a cart at the moment the halt was released. */
        var waitingWhenReleased: Boolean = false

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("P")
                val request = requestGuidedTransporter(carts, "P")
                carriedBy = request.transporter.name
                transportBy(request, "E")
                releaseGuidedTransporter(request, carts)
                finishedAt = time
            }
        }

        override fun initialize() {
            // Send "Near" off on an errand of its own, so that it is moving and allocated to
            // nobody -- which is the only state in which a gate can stop an unheld transporter.
            schedule({ _: KSLEvent<Nothing> -> near.sendTo("B") }, 0.0)
            // The part arrives after "Near" has had time to reach the first zone boundary and be
            // refused, so the pool is asked while the halt is in force rather than before it.
            schedule({ _: KSLEvent<Nothing> -> activate(Part().p) }, 5.0)
            val release = releaseHaltAt
            if (release != null) {
                schedule({ _: KSLEvent<Nothing> ->
                    waitingWhenReleased = carts.waitingQ.isNotEmpty
                    gate.closed = false
                    system.resumeHaltedTransporter(near)
                }, release)
            }
        }
    }

    private fun run(
        gateClosed: Boolean,
        cartCount: Int = 2,
        releaseHaltAt: Double? = null,
        horizon: Double = 200.0
    ): Shop {
        val m = Model("HaltedDispatch")
        val shop = Shop(m, gateClosed, cartCount, releaseHaltAt)
        m.numberOfReplications = 1
        m.lengthOfReplication = horizon
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("The control: with nothing stopping it, the rule sends the near cart")
    fun theRulePrefersTheNearCart() {
        // Establishes the premise the rest of the class rests on. Without this, a test showing that
        // the far cart is sent when the near one is halted would prove nothing -- the far cart might
        // simply be the one the rule prefers.
        val shop = run(gateClosed = false)
        assertEquals(
            "Near", shop.carriedBy,
            "the near cart is 200 units from the pickup and the far one 300, so an ungated run " +
                    "must send the near one; if it does not, this fixture is not testing what it claims"
        )
    }

    @Test
    @DisplayName("A halted cart is not offered, so the pool sends one that can come")
    fun aHaltedCartIsNotSent() {
        val shop = run(gateClosed = true)
        assertTrue(shop.near.isHalted, "the fixture must actually halt the near cart")
        assertFalse(shop.near.isDispatchable, "and a halted cart is by definition not dispatchable")
        assertEquals(
            "Far", shop.carriedBy,
            "the rule prefers the near cart on distance and the near cart cannot move, so the " +
                    "pool must offer only the far one"
        )
        assertTrue(shop.finishedAt.isFinite(), "and the part must actually be delivered")
    }

    @Test
    @DisplayName("The pool reports what it can send, not what nobody holds")
    fun thePoolSeparatesTheTwoKinds() {
        val shop = run(gateClosed = true, cartCount = 1, horizon = 20.0)
        assertTrue(shop.near.isHalted)
        assertEquals(
            emptyList(), shop.carts.idleTransporters,
            "the one cart belongs to nobody but cannot move, so the pool has nothing to send"
        )
        assertEquals(
            listOf(shop.near), shop.carts.haltedTransporters,
            "and it must be visible as held rather than simply absent"
        )
        assertEquals(
            0, shop.carts.numAvailableUnits,
            "the count the library's own wake path reads has to agree, or an entity is woken to " +
                    "find there is nothing for it"
        )
        assertFalse(shop.carts.hasIdleTransporter)
    }

    @Test
    @DisplayName("A cart released from a halt offers itself, so nobody waits in front of a free cart")
    fun releasingAHaltWakesTheQueue() {
        // The other half of the fix. Declining to offer a halted cart is only safe if the moment it
        // becomes able to move is also the moment the queue is re-examined: the library's wake path
        // runs on release, and a cart released from a halt is not released by anybody.
        val shop = run(gateClosed = true, cartCount = 1, releaseHaltAt = 50.0)
        assertTrue(
            shop.waitingWhenReleased,
            "the part must genuinely have been queued, or this test proves nothing about waking"
        )
        assertEquals("Near", shop.carriedBy, "the only cart there is must be the one that comes")
        assertTrue(
            shop.finishedAt.isFinite(),
            "a part that is still waiting at the end of the run is the silent stall this half " +
                    "of the fix exists to prevent"
        )
        assertTrue(
            shop.finishedAt > 50.0,
            "and it must have been served after the halt lifted, not before"
        )
    }

    @Test
    @DisplayName("A halted cart is not sent home when the queue empties")
    fun aHaltedCartIsNotDisposed() {
        // An idle disposition rule issues a journey. Issuing one to a cart a gate is holding would
        // overrule the gate through the back door, and would do it at the moment the fleet went
        // quiet -- which is exactly when nobody is looking. Asked of the pool directly, because the
        // path that reaches it in a model is a release, and nobody holds a cart a gate has stopped.
        val shop = run(gateClosed = true, cartCount = 1, horizon = 20.0)
        val before = shop.near.frontZone
        shop.carts.disposeIfUnwanted(shop.near)
        assertTrue(shop.near.isHalted, "the disposition must not have restarted the cart")
        assertEquals(
            before, shop.near.frontZone,
            "and it must still be where the gate stopped it, rather than on its way home"
        )
        val front = shop.near.frontZone
        assertTrue(
            front is LinkZone && front.link.name == "AB",
            "which is one zone along the link it was refused on, but it is at ${front?.name}"
        )
    }
}
