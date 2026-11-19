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

package ksl.modeling.entity

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A transfer onto an accumulating conveyor that is jammed, with nothing terminated: the item waiting to land
 *  lands when the jam clears, at the times an accumulating conveyor gives without the transfer.
 *
 *  C1 runs A to B and C2 runs B to D, each 5 cells of size 1, velocity 1, so a cell move takes 1. On C2, Y rides
 *  from B to D and holds its exit until 20; four more ride behind it and stop, the last covering C2's entry cell.
 *  R rides C1 from A, reaches B and asks to transfer to C2 while C2 is stopped.
 */
class ConveyorLandingTimingTest {

    private class Jam(parent: ModelElement, val hold: Double, val rArrive: Double = 3.0) : ProcessModel(parent, "jam") {
        val c1: Conveyor = Conveyor.builder(this, "C1").conveyorType(Conveyor.Type.ACCUMULATING).velocity(1.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("A", "B", 5).build()
        val c2: Conveyor = Conveyor.builder(this, "C2").conveyorType(Conveyor.Type.ACCUMULATING).velocity(1.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("B", "D", 5).build()
        val log = mutableListOf<String>()

        inner class OnC2(val tag: String, val arrive: Double, val holdUntil: Double) : Entity(tag) {
            val p: KSLProcess = process("onC2") {
                delay(arrive)
                val cr = requestConveyor(c2, "B")
                rideConveyor(cr, "D")
                if (time < holdUntil) delay(holdUntil - time)
                exitConveyor(cr)
                log.add("$tag off C2@$time")
            }
        }

        inner class Transferring(val tag: String, val arrive: Double) : Entity(tag) {
            val p: KSLProcess = process("transfer") {
                delay(arrive)
                val cr = requestConveyor(c1, "A")
                rideConveyor(cr, "B")
                log.add("$tag at B@$time")
                val tr = transferTo(cr, c2, "B")
                log.add("$tag on C2@$time")
                rideConveyor(tr, "D")
                exitConveyor(tr)
                log.add("$tag off C2@$time")
            }
        }

        fun eventsSoFar(): Long = executive.numEventsExecuted

        override fun initialize() {
            log.clear()
            activate(OnC2("Y", 0.0, hold).p)
            for (i in 1..4) activate(OnC2("Z$i", i.toDouble(), 0.0).p)
            activate(Transferring("R", rArrive).p)
        }
    }

    private fun run(hold: Double, rArrive: Double): Pair<List<String>, Long> {
        val model = Model("LandingJam${System.nanoTime()}", autoCSVReports = false)
        val jam = Jam(model, hold, rArrive)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return jam.log.toList() to jam.eventsSoFar()
    }

    @Test
    @DisplayName("a transfer onto a jammed accumulating conveyor lands when the jam clears, and the conveyor makes no empty moves while jammed")
    fun landingBehindAJam() {
        // C2 is full by 5 and stays so until Y leaves its exit at the hold plus a cell move: then every item moves on
        // one cell a move, and R lands on C2 as Y leaves, behind Z4
        for ((hold, r) in listOf(20.0 to 3.0, 20.3 to 0.0, 20.7 to 2.5)) {
            val (log, events) = run(hold, r)
            val t = hold + 1.0
            assertEquals(
                listOf("R at B@${5.0 + r}", "Y off C2@$t", "R on C2@$t", "Z1 off C2@${t + 1}", "Z2 off C2@${t + 2}",
                    "Z3 off C2@${t + 3}", "Z4 off C2@${t + 4}", "R off C2@${t + 5}"),
                log, "hold $hold, R from $r"
            )
            // the same count as before a landing could keep a conveyor moving: no cell moves while the conveyor is jammed
            assertEquals(74L, events, "hold $hold, R from $r: events executed")
        }
    }
}
