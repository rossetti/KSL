/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.models.station

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.station.QObjectReceiverIfc
import ksl.modeling.station.Route
import ksl.modeling.station.SResource
import ksl.modeling.station.StationNetwork
import ksl.modeling.station.StationNetworkCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

/**
 *  Test and Repair Shop (book chapter 8), reimplemented with Phase-3 station
 *  primitives. The Arena-style atomic seize/release showcase: every test step
 *  holds a tester *and* a test machine concurrently — the canonical nested
 *  hold pattern that `SingleQStation` (welded seize+delay+release) cannot
 *  express. Each [SeizeStation] is paired with a [ReleaseStation] across the
 *  delay; the entity continues to hold each allocation until the matching
 *  release.
 *
 *  Each Part follows one of four random test plans (probabilities .25 / .125 /
 *  .375 / .25). Plans differ in length (4 / 2 / 3 / 2 test steps), in which
 *  (tester, machine) pair they visit at each step, in service times per step,
 *  and in repair times. The plan is chosen at the source via a [MarkingHookIfc]
 *  and attached to the Part as a [Route] — so the Part walks a unique sequence
 *  of stations, but the seize/release stations themselves are *shared* across
 *  plans (each station honors the QObject's attached sender to advance the
 *  route).
 *
 *  Resources:
 *  - DiagnosticWorkers: SResource cap 2 (the workers shared by the diag steps)
 *  - DiagnosticMachines: SResource cap 2
 *  - TestWorker1/2/3 and TestMachine1/2/3: 6 SResources cap 1 each
 *  - RepairWorkers: SResource cap 3
 *  - TransportWorkers: SResource cap 8
 *
 *  Deviation from the legacy: the legacy's `TransportWorkers` is the union of
 *  all 8 workers from the diag/test/repair pools (a worker doing diagnostics
 *  is not available for transport). This requires a resource shared across
 *  pools, which the current framework does not express; here transport is an
 *  independent cap-8 pool. The model structure (nested holds, plan-based
 *  routing) is otherwise identical.
 */
class TestAndRepairStation(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val net: StationNetwork = StationNetwork(this, "${this.name}:Net")

    val network: StationNetworkCIfc
        get() = net

    // ---- RVs (streams pinned for reproducibility) ----
    // Test plan 1 step times
    private val t11 = LognormalRV(20.0, 4.1 * 4.1, 11)
    private val t12 = LognormalRV(12.0, 4.2 * 4.2, 12)
    private val t13 = LognormalRV(18.0, 4.3 * 4.3, 13)
    private val t14 = LognormalRV(16.0, 4.0 * 4.0, 14)
    // Test plan 2 step times
    private val t21 = LognormalRV(12.0, 4.0 * 4.0, 21)
    private val t22 = LognormalRV(15.0, 4.0 * 4.0, 22)
    // Test plan 3 step times
    private val t31 = LognormalRV(18.0, 4.2 * 4.2, 31)
    private val t32 = LognormalRV(14.0, 4.4 * 4.4, 32)
    private val t33 = LognormalRV(12.0, 4.3 * 4.3, 33)
    // Test plan 4 step times
    private val t41 = LognormalRV(24.0, 4.0 * 4.0, 41)
    private val t42 = LognormalRV(30.0, 4.0 * 4.0, 42)
    // Repair times per plan
    private val r1 = TriangularRV(30.0, 60.0, 80.0, 51)
    private val r2 = TriangularRV(45.0, 55.0, 70.0, 52)
    private val r3 = TriangularRV(30.0, 40.0, 60.0, 53)
    private val r4 = TriangularRV(35.0, 65.0, 75.0, 54)
    private val diagnosticTime = ExponentialRV(30.0, 61)
    private val moveTime = UniformRV(2.0, 4.0, 62)
    private val tba = ExponentialRV(20.0, 63)

    // ---- resources ----
    private val diagWorkers: SResource = net.resource("DiagnosticWorkers", capacity = 2)
    private val diagMachines: SResource = net.resource("DiagnosticMachines", capacity = 2)
    private val tw1: SResource = net.resource("TestWorker1", capacity = 1)
    private val tw2: SResource = net.resource("TestWorker2", capacity = 1)
    private val tw3: SResource = net.resource("TestWorker3", capacity = 1)
    private val tm1: SResource = net.resource("TestMachine1", capacity = 1)
    private val tm2: SResource = net.resource("TestMachine2", capacity = 1)
    private val tm3: SResource = net.resource("TestMachine3", capacity = 1)
    private val repairWorkers: SResource = net.resource("RepairWorkers", capacity = 3)
    private val transportWorkers: SResource = net.resource("TransportWorkers", capacity = 8)

    // ---- shared seize/release stations (each created once; routes reference them) ----
    private val seizeDW = net.seizeStation("SeizeDW", diagWorkers)
    private val seizeDM = net.seizeStation("SeizeDM", diagMachines)
    private val diagDelay = net.activityStation("DiagDelay", diagnosticTime)
    private val releaseDM = net.releaseStation("ReleaseDM", diagMachines)
    private val releaseDW = net.releaseStation("ReleaseDW", diagWorkers)

    private val seizeTW1 = net.seizeStation("SeizeTW1", tw1)
    private val seizeTW2 = net.seizeStation("SeizeTW2", tw2)
    private val seizeTW3 = net.seizeStation("SeizeTW3", tw3)
    private val seizeTM1 = net.seizeStation("SeizeTM1", tm1)
    private val seizeTM2 = net.seizeStation("SeizeTM2", tm2)
    private val seizeTM3 = net.seizeStation("SeizeTM3", tm3)
    private val releaseTM1 = net.releaseStation("ReleaseTM1", tm1)
    private val releaseTM2 = net.releaseStation("ReleaseTM2", tm2)
    private val releaseTM3 = net.releaseStation("ReleaseTM3", tm3)
    private val releaseTW1 = net.releaseStation("ReleaseTW1", tw1)
    private val releaseTW2 = net.releaseStation("ReleaseTW2", tw2)
    private val releaseTW3 = net.releaseStation("ReleaseTW3", tw3)

    private val seizeTransport = net.seizeStation("SeizeTransport", transportWorkers)
    private val moveDelay = net.activityStation("Move", moveTime)
    private val releaseTransport = net.releaseStation("ReleaseTransport", transportWorkers)

    private val seizeRepair = net.seizeStation("SeizeRepair", repairWorkers)
    private val releaseRepair = net.releaseStation("ReleaseRepair", repairWorkers)

    // ---- per-step delay stations (one per (plan, step) since service times differ) ----
    private val delayT11 = net.activityStation("Delay_t11", t11)
    private val delayT12 = net.activityStation("Delay_t12", t12)
    private val delayT13 = net.activityStation("Delay_t13", t13)
    private val delayT14 = net.activityStation("Delay_t14", t14)
    private val delayT21 = net.activityStation("Delay_t21", t21)
    private val delayT22 = net.activityStation("Delay_t22", t22)
    private val delayT31 = net.activityStation("Delay_t31", t31)
    private val delayT32 = net.activityStation("Delay_t32", t32)
    private val delayT33 = net.activityStation("Delay_t33", t33)
    private val delayT41 = net.activityStation("Delay_t41", t41)
    private val delayT42 = net.activityStation("Delay_t42", t42)

    private val delayR1 = net.activityStation("Repair_r1", r1)
    private val delayR2 = net.activityStation("Repair_r2", r2)
    private val delayR3 = net.activityStation("Repair_r3", r3)
    private val delayR4 = net.activityStation("Repair_r4", r4)

    private val exit = net.sink("Exit")

    // ---- diagnostic prelude (shared across all plans) ----
    private val diagnostic: List<QObjectReceiverIfc> = listOf(
        seizeDW, seizeDM, diagDelay, releaseDM, releaseDW,
        seizeTransport, moveDelay, releaseTransport
    )

    // ---- a "test step" subroute that holds tester+machine across the delay ----
    private fun testStep(seizeTester: QObjectReceiverIfc, seizeMachine: QObjectReceiverIfc,
                         delay: QObjectReceiverIfc, releaseMachine: QObjectReceiverIfc,
                         releaseTester: QObjectReceiverIfc): List<QObjectReceiverIfc> = listOf(
        seizeTester, seizeMachine, delay, releaseMachine, releaseTester,
        seizeTransport, moveDelay, releaseTransport
    )

    private fun repair(delay: QObjectReceiverIfc): List<QObjectReceiverIfc> = listOf(
        seizeRepair, delay, releaseRepair, exit
    )

    // ---- the four plan routes (built from the shared pieces) ----
    private val plan1Steps = diagnostic +
            testStep(seizeTW2, seizeTM2, delayT11, releaseTM2, releaseTW2) +
            testStep(seizeTW3, seizeTM3, delayT12, releaseTM3, releaseTW3) +
            testStep(seizeTW2, seizeTM2, delayT13, releaseTM2, releaseTW2) +
            testStep(seizeTW1, seizeTM1, delayT14, releaseTM1, releaseTW1) +
            repair(delayR1)

    private val plan2Steps = diagnostic +
            testStep(seizeTW3, seizeTM3, delayT21, releaseTM3, releaseTW3) +
            testStep(seizeTW1, seizeTM1, delayT22, releaseTM1, releaseTW1) +
            repair(delayR2)

    private val plan3Steps = diagnostic +
            testStep(seizeTW1, seizeTM1, delayT31, releaseTM1, releaseTW1) +
            testStep(seizeTW3, seizeTM3, delayT32, releaseTM3, releaseTW3) +
            testStep(seizeTW1, seizeTM1, delayT33, releaseTM1, releaseTW1) +
            repair(delayR3)

    private val plan4Steps = diagnostic +
            testStep(seizeTW2, seizeTM2, delayT41, releaseTM2, releaseTW2) +
            testStep(seizeTW3, seizeTM3, delayT42, releaseTM3, releaseTW3) +
            repair(delayR4)

    private val plan1 = Route("Plan1", plan1Steps).also { net.registerRoute(it) }
    private val plan2 = Route("Plan2", plan2Steps).also { net.registerRoute(it) }
    private val plan3 = Route("Plan3", plan3Steps).also { net.registerRoute(it) }
    private val plan4 = Route("Plan4", plan4Steps).also { net.registerRoute(it) }

    // probabilistic plan selection (matches the legacy CDF)
    private val plans = listOf(plan1, plan2, plan3, plan4)
    private val planList = REmpiricalList<Route>(this, plans, doubleArrayOf(0.25, 0.375, 0.75, 1.0), streamNum = 70)

    init {
        // Source attaches the chosen plan as the Part's sender (via Route.newSender),
        // so the Part walks the plan as a sequence of receivers. Stations honor the
        // attached sender to advance. The source's first hop also routes via the sender
        // since the QObject already has one when the source's receive() runs.
        net.source(
            name = "Arrivals",
            interArrivalRV = tba,
            firstReceiver = seizeDW,  // unused: the marking attaches a sender that drives the first hop
            marking = { q ->
                val chosen = planList.randomElement
                q.qObjectType = plans.indexOf(chosen) + 1
                q.sender(chosen.newSender())
            }
        )
    }
}

fun main() {
    val model = Model("TestAndRepairStation")
    val tr = TestAndRepairStation(model, "TestRepair")
    model.numberOfReplications = 30
    model.lengthOfReplication = 480.0 * 10.0  // 10 days
    model.lengthOfReplicationWarmUp = 480.0 * 2.0
    model.simulate()
    model.print()
    println()
    println("Number in system  = ${tr.network.numInSystem.acrossReplicationStatistic.average}")
    println("Part system time  = ${tr.network.systemTime.acrossReplicationStatistic.average}")
    println("Throughput        = ${tr.network.numCompleted.acrossReplicationStatistic.average}")
    println("WIP at run end    = ${tr.network.holdingsAtRunEnd.acrossReplicationStatistic.average}")
}
