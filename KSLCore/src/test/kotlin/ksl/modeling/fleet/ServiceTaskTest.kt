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

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.ParkInPlaceDisposition
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  A self-directed errand: send some vehicle somewhere, to carry nothing.
 *
 *  `Dispatcher.postService` was `internal` until it was made public, and making it public is what
 *  exposed that **it had never worked**. A service task has no pickup stop, and only a pickup calls
 *  `tookPossession` — which is the transition into `IN_PROGRESS`, and the only legal way into
 *  `COMPLETED`. So an errand that ran to completion raised `FleetProtocolException`, and nothing had
 *  ever noticed, because the one test that posted one deliberately removed it from the board before
 *  it could finish.
 *
 *  The fix says something about the state machine rather than patching around it. `IN_PROGRESS`
 *  means *the load is aboard*, and that instant is also the one where the assignment stops being
 *  revocable (`A4`). A task with no load never has that instant, so requiring it to pass through
 *  `IN_PROGRESS` demanded a transition that could never legitimately happen. `ASSIGNED -> COMPLETED`
 *  is now legal exactly when nothing is waiting on the task — and the consequence is right as well
 *  as necessary: a vehicle on an errand stays re-taskable for the whole errand, which is what
 *  `cancel`'s documentation has always promised.
 *
 *  ## What an errand must not disturb
 *
 *  It shares the dispatcher's waiting line with transport requests, because one fleet allocated by
 *  one policy needs one board. What it must **not** do is quietly change what a transport figure
 *  means, and the two tests that matter here are about that: an errand contributes nothing to
 *  `WaitForAssignment`, which decomposes what a *load* waited for; and a completed errand leaves the
 *  queue, because one that stayed would inflate the reported waiting line for the rest of the run
 *  while never recording a wait of its own.
 */
class ServiceTaskTest {

    private companion object {
        const val VELOCITY = 3.0
        const val ERRAND_AT = 50.0
    }

    /** Posts one errand at a fixed time, with or without transport traffic alongside it. */
    private class Shop(
        parent: ModelElement,
        val withTraffic: Boolean,
        val cancelIt: Boolean = false
    ) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY), name = "Cart"
        ).apply {
            homeBase = SimpleAgvNetwork.AGV1_HOME
            // Parked rather than sent home, so that where the cart ends up is where the errand put
            // it. With the default disposition it would drive home afterwards, which is correct
            // behaviour and would hide what this suite is checking.
            dispositionPolicy = ParkInPlaceDisposition()
        }

        var errand: Dispatcher.ServiceTask? = null
        var completions = 0

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
                completions++
            }
        }

        private val tba = ExponentialRV(120.0, streamNum = 1)
        private val generator = if (withTraffic) EntityGenerator(::Part, tba, tba) else null

        override fun initialize() {
            errand = null
            completions = 0
            schedule({ _: KSLEvent<Nothing> ->
                errand = agv.dispatcher.postService(SimpleAgvNetwork.AGV2_HOME)
                if (cancelIt) agv.dispatcher.cancel(errand!!)
            }, ERRAND_AT)
        }
    }

    private fun run(name: String, horizon: Double = 600.0, build: (Model) -> Shop): Shop {
        val m = Model(name)
        val s = build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = horizon
        s.agv.auditAtReplicationEnd = true
        m.simulate()
        return s
    }

    @Test
    @DisplayName("an errand is posted, taken, carried out, and ends COMPLETED")
    fun anErrandRunsToCompletion() {
        val s = run("Errand") { Shop(it, withTraffic = false) }

        assertEquals(
            TaskState.COMPLETED, s.errand?.state,
            "the errand ended ${s.errand?.state} rather than COMPLETED. Before this was fixed it " +
                    "could not end at all: ASSIGNED -> COMPLETED was illegal and the run raised"
        )
        // The vehicle actually went there, rather than the task being marked done where it stood.
        assertEquals(
            SimpleAgvNetwork.AGV2_HOME, s.cart.currentLocationName,
            "the cart finished at (${s.cart.currentLocationName}) rather than at the errand's destination"
        )
        // And the audit is satisfied: posted, completed, and nothing unaccounted for. `auditAtReplicationEnd`
        // is on for every run in this suite, so a shortfall would have raised rather than been asserted.
        assertEquals(1.0, s.agv.dispatcher.numTasksPosted.value, 0.0)
        assertEquals(1.0, s.agv.dispatcher.numTasksCompleted.value, 0.0)
    }

    @Test
    @DisplayName("a completed errand leaves the waiting line")
    fun aCompletedErrandIsDequeued() {
        val s = run("Dequeued") { Shop(it, withTraffic = false) }

        assertEquals(
            0, s.agv.dispatcher.taskQ.size,
            "a completed errand was left in the reported waiting line. A transport task leaves it at " +
                    "pickup; an errand has no pickup, so completion is where it has to leave, or it " +
                    "inflates the queue for the rest of the run and never records a wait at all"
        )
        assertEquals(
            1.0, s.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic.count, 0.0,
            "the errand's wait was never recorded, which is what being left in the queue looks like"
        )
    }

    @Test
    @DisplayName("an errand contributes nothing to what a load waited for")
    fun anErrandDoesNotEnterWaitForAssignment() {
        val quiet = run("NoTraffic") { Shop(it, withTraffic = false) }
        assertEquals(
            0.0, quiet.agv.dispatcher.waitForAssignment.withinReplicationStatistic.count, 0.0,
            "an errand was counted in WaitForAssignment, which decomposes what a *load* waited for. " +
                    "Nothing is suspended on an errand, so letting one contribute would redefine a " +
                    "headline figure the moment a model posted its first one"
        )

        // With traffic, the response counts the deliveries and only the deliveries.
        val busy = run("Traffic") { Shop(it, withTraffic = true) }
        assertTrue(busy.completions > 0, "no loads were delivered, so this half proves nothing")
        // One observation per assignment made, less the single errand. Compared against assignments
        // rather than postings because a task posted near the horizon may never be assigned, and
        // this response measures the commitment, not the posting.
        assertEquals(
            busy.agv.dispatcher.numAssignmentsMade.value - 1.0,
            busy.agv.dispatcher.waitForAssignment.withinReplicationStatistic.count, 0.0,
            "WaitForAssignment should have one observation per transport assignment and none for " +
                    "the errand"
        )
    }

    @Test
    @DisplayName("an errand may be cancelled, and a transport request may not")
    fun anErrandIsCancellable() {
        val s = run("Cancelled") { Shop(it, withTraffic = false, cancelIt = true) }

        assertEquals(
            TaskState.CANCELLED, s.errand?.state,
            "the errand was not cancelled. Nothing is suspended on one, so cancelling it strands " +
                    "nobody -- which is exactly why a transport request cannot be cancelled and this can"
        )
        assertEquals(0, s.agv.dispatcher.taskQ.size, "a cancelled errand was left on the board")
        assertFalse(
            s.cart.currentLocationName == SimpleAgvNetwork.AGV2_HOME,
            "the cart went to the errand's destination although the errand was cancelled before " +
                    "anyone could be assigned to it"
        )
    }

    @Test
    @DisplayName("an errand counts as a duty cycle on the vehicle that ran it")
    fun anErrandIsADutyCycle() {
        val s = run("DutyCycle") { Shop(it, withTraffic = false) }

        // Deliberate, and documented: `LeastUsedVehiclePolicy` balances on this counter and a
        // TASKS_COMPLETED failure model wears on it, and both should see an errand as work done.
        assertEquals(
            1.0, s.cart.numTasksCompleted.value, 0.0,
            "the vehicle did not count the errand among the tasks it completed"
        )
    }
}
