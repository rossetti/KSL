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
import ksl.modeling.station.queueingNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

/**
 *  Test and Repair Shop expressed with the Phase-1 builder DSL. Same model as
 *  [TestAndRepairStation], rendered top-down inside `queueingNetwork { ... }`.
 *  The plan dispatch uses the source marking hook to attach one of four
 *  Route senders to each Part.
 */
fun main() {
    val tba = ExponentialRV(20.0, 63)

    val model = Model("TestAndRepairDsl")

    // build the network; capture stations/resources locally so the marking can attach routes
    lateinit var routes: List<Route>
    lateinit var planList: REmpiricalList<Route>

    val net = model.queueingNetwork("TR") {
        val diagWorkers = resource("DiagnosticWorkers", capacity = 2)
        val diagMachines = resource("DiagnosticMachines", capacity = 2)
        val tw1 = resource("TestWorker1", capacity = 1)
        val tw2 = resource("TestWorker2", capacity = 1)
        val tw3 = resource("TestWorker3", capacity = 1)
        val tm1 = resource("TestMachine1", capacity = 1)
        val tm2 = resource("TestMachine2", capacity = 1)
        val tm3 = resource("TestMachine3", capacity = 1)
        val repairWorkers = resource("RepairWorkers", capacity = 3)
        val transportWorkers = resource("TransportWorkers", capacity = 8)

        val sDW = seize("SeizeDW", diagWorkers)
        val sDM = seize("SeizeDM", diagMachines)
        val dDelay = delay("DiagDelay", ExponentialRV(30.0, 61))
        val rDM = release("ReleaseDM", diagMachines)
        val rDW = release("ReleaseDW", diagWorkers)

        val sTW1 = seize("SeizeTW1", tw1);   val rTW1 = release("ReleaseTW1", tw1)
        val sTW2 = seize("SeizeTW2", tw2);   val rTW2 = release("ReleaseTW2", tw2)
        val sTW3 = seize("SeizeTW3", tw3);   val rTW3 = release("ReleaseTW3", tw3)
        val sTM1 = seize("SeizeTM1", tm1);   val rTM1 = release("ReleaseTM1", tm1)
        val sTM2 = seize("SeizeTM2", tm2);   val rTM2 = release("ReleaseTM2", tm2)
        val sTM3 = seize("SeizeTM3", tm3);   val rTM3 = release("ReleaseTM3", tm3)

        val sTr = seize("SeizeTransport", transportWorkers)
        val move = delay("Move", UniformRV(2.0, 4.0, 62))
        val rTr = release("ReleaseTransport", transportWorkers)

        val sRep = seize("SeizeRepair", repairWorkers)
        val rRep = release("ReleaseRepair", repairWorkers)

        // per-step delays
        val dT11 = delay("Delay_t11", LognormalRV(20.0, 4.1 * 4.1, 11))
        val dT12 = delay("Delay_t12", LognormalRV(12.0, 4.2 * 4.2, 12))
        val dT13 = delay("Delay_t13", LognormalRV(18.0, 4.3 * 4.3, 13))
        val dT14 = delay("Delay_t14", LognormalRV(16.0, 4.0 * 4.0, 14))
        val dT21 = delay("Delay_t21", LognormalRV(12.0, 4.0 * 4.0, 21))
        val dT22 = delay("Delay_t22", LognormalRV(15.0, 4.0 * 4.0, 22))
        val dT31 = delay("Delay_t31", LognormalRV(18.0, 4.2 * 4.2, 31))
        val dT32 = delay("Delay_t32", LognormalRV(14.0, 4.4 * 4.4, 32))
        val dT33 = delay("Delay_t33", LognormalRV(12.0, 4.3 * 4.3, 33))
        val dT41 = delay("Delay_t41", LognormalRV(24.0, 4.0 * 4.0, 41))
        val dT42 = delay("Delay_t42", LognormalRV(30.0, 4.0 * 4.0, 42))
        val dR1 = delay("Repair_r1", TriangularRV(30.0, 60.0, 80.0, 51))
        val dR2 = delay("Repair_r2", TriangularRV(45.0, 55.0, 70.0, 52))
        val dR3 = delay("Repair_r3", TriangularRV(30.0, 40.0, 60.0, 53))
        val dR4 = delay("Repair_r4", TriangularRV(35.0, 65.0, 75.0, 54))

        val exit = sink("Exit")

        val diagnostic: List<QObjectReceiverIfc> = listOf(sDW, sDM, dDelay, rDM, rDW, sTr, move, rTr)
        fun step(seizeTester: QObjectReceiverIfc, seizeMachine: QObjectReceiverIfc,
                 delayStep: QObjectReceiverIfc, releaseMachine: QObjectReceiverIfc,
                 releaseTester: QObjectReceiverIfc): List<QObjectReceiverIfc> = listOf(
            seizeTester, seizeMachine, delayStep, releaseMachine, releaseTester, sTr, move, rTr
        )
        fun repair(d: QObjectReceiverIfc): List<QObjectReceiverIfc> = listOf(sRep, d, rRep, exit)

        val plan1 = route("Plan1", *(diagnostic +
            step(sTW2, sTM2, dT11, rTM2, rTW2) +
            step(sTW3, sTM3, dT12, rTM3, rTW3) +
            step(sTW2, sTM2, dT13, rTM2, rTW2) +
            step(sTW1, sTM1, dT14, rTM1, rTW1) +
            repair(dR1)).toTypedArray())
        val plan2 = route("Plan2", *(diagnostic +
            step(sTW3, sTM3, dT21, rTM3, rTW3) +
            step(sTW1, sTM1, dT22, rTM1, rTW1) +
            repair(dR2)).toTypedArray())
        val plan3 = route("Plan3", *(diagnostic +
            step(sTW1, sTM1, dT31, rTM1, rTW1) +
            step(sTW3, sTM3, dT32, rTM3, rTW3) +
            step(sTW1, sTM1, dT33, rTM1, rTW1) +
            repair(dR3)).toTypedArray())
        val plan4 = route("Plan4", *(diagnostic +
            step(sTW2, sTM2, dT41, rTM2, rTW2) +
            step(sTW3, sTM3, dT42, rTM3, rTW3) +
            repair(dR4)).toTypedArray())

        routes = listOf(plan1, plan2, plan3, plan4)
        planList = REmpiricalList<Route>(network, routes, doubleArrayOf(0.25, 0.375, 0.75, 1.0), streamNum = 70)

        source("Arrivals", tba, firstReceiver = sDW,
            marking = { q ->
                val chosen = planList.randomElement
                q.qObjectType = routes.indexOf(chosen) + 1
                q.sender(chosen.newSender())
            })
    }

    model.numberOfReplications = 5
    model.lengthOfReplication = 50000.0
    model.lengthOfReplicationWarmUp = 5000.0
    model.simulate()
    model.print()
    println()
    println("Part system time = ${net.systemTime.acrossReplicationStatistic.average}")
    println("Throughput       = ${net.numCompleted.acrossReplicationStatistic.average}")
}
