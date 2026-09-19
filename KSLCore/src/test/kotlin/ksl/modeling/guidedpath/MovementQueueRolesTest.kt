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
import ksl.modeling.entity.ProcessModel
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
 *  What the movement queues are for, and what they are not.
 *
 *  A hold queue is how a suspended entity is found again. It is a mechanism, and the moment it also
 *  becomes the statistic, a row appears on the report that reads as a waiting line and is not one:
 *  `RidingHoldQ`'s time in queue is the mean length of a loaded move, and its number in queue is a
 *  count of moving carts. Both quantities are already reported properly, and separately, by
 *  `ApproachTime` and `RideTime`. So the queues report nothing, which is what `Conveyor`
 *  does with the same three-way split for the same reason.
 *
 *  The split itself is the other half. One queue holding three unrelated kinds of waiter can be told
 *  apart only by reading a suspension name out of a trace, and its size answers no question anybody
 *  asks. Three queues answer three: who is standing about waiting to be collected, who is aboard,
 *  and who is driving.
 */
class MovementQueueRolesTest {

    /**
     *  One cart, parked on a spur, fetching a part from the entry station and carrying it to the
     *  exit. Both legs are real journeys, so a sample taken while the model runs will find the part
     *  first in one queue and then in the other.
     */
    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network: GuidedPathNetwork = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val cart = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), idleDispositionRule = ReturnToHomeBaseRule(), name = "Carts"
        )

        /** Every queue that was ever seen holding somebody, and who. */
        val seenAwaitingPickup = mutableSetOf<String>()
        val seenRiding = mutableSetOf<String>()
        val seenDriving = mutableSetOf<String>()

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                guidedTransport(
                    carts, SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION
                )
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            fun namesIn(q: ksl.modeling.queue.QueueCIfc<ProcessModel.Entity>) =
                q.immutableList.map { it.name }
            seenAwaitingPickup.addAll(namesIn(system.awaitingPickupHoldQ))
            seenRiding.addAll(namesIn(system.ridingHoldQ))
            seenDriving.addAll(namesIn(system.drivingHoldQ))
        }

        override fun initialize() {
            activate(Part().p)
            // Sampled densely rather than at chosen instants, so the test does not depend on the
            // arithmetic of the layout: whichever moments the two legs occupy, some sample lands in
            // each of them.
            var t = 0.1
            while (t < 40.0) {
                schedule(::sample, t)
                t += 0.1
            }
        }
    }

    private fun run(reporting: Boolean? = null): Shop {
        val m = Model("MovementQueueRoles")
        val shop = Shop(m)
        if (reporting != null) shop.system.statisticalReportingForHoldQueues(reporting)
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("Each movement queue holds one kind of waiter, and says which")
    fun eachQueueHoldsOneKindOfWaiter() {
        val shop = run()

        assertEquals(
            setOf("Part"), shop.seenAwaitingPickup,
            "the part must be seen waiting to be collected while the cart drives out to it"
        )
        assertEquals(
            setOf("Part"), shop.seenRiding,
            "the part must be seen riding while the cart carries it"
        )
        // Nobody drives a transporter under the passive paradigm: the entity is fetched and carried
        // on its behalf. This queue exists for the active subsystem's vehicle agents, and staying
        // empty here is what says the split is by kind of wait rather than by phase of a journey.
        assertTrue(
            shop.seenDriving.isEmpty(),
            "nothing should ever drive under the passive paradigm, but found ${shop.seenDriving}"
        )

        // And nothing is left behind at the end.
        assertEquals(0, shop.system.awaitingPickupHoldQ.size)
        assertEquals(0, shop.system.ridingHoldQ.size)
        assertEquals(0, shop.system.drivingHoldQ.size)
    }

    @Test
    @DisplayName("The movement queues report nothing by default, and the switch works both ways")
    fun movementQueuesReportNothingByDefault() {
        val shop = run()
        val queues = listOf(
            shop.system.awaitingPickupHoldQ, shop.system.ridingHoldQ, shop.system.drivingHoldQ
        )
        for (q in queues) {
            assertFalse(q.waitTimeStatOption, "${q.name} is collecting waiting time statistics")
            assertFalse(q.defaultReportingOption, "${q.name} appears on the summary report")
        }

        // The pool's queue is the waiting line and does report: an entity genuinely waits there for
        // a cart to become free, and that wait is what the modeller asked about.
        assertTrue(shop.carts.waitingQ.defaultReportingOption, "the pool queue is not being reported")

        // The quantities the riding queue would otherwise report are reported properly, by the
        // responses that exist for them.
        assertTrue(shop.system.approachTime.defaultReportingOption)
        assertTrue(shop.system.rideTime.defaultReportingOption)

        val switchedOn = run(reporting = true)
        for (q in listOf(
            switchedOn.system.awaitingPickupHoldQ, switchedOn.system.ridingHoldQ,
            switchedOn.system.drivingHoldQ
        )) {
            assertTrue(q.defaultReportingOption, "${q.name} did not switch back on")
            assertTrue(q.waitTimeStatOption, "${q.name} did not switch its waiting time back on")
        }
    }
}
