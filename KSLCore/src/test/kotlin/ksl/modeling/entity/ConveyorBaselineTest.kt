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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.zip.CRC32
import kotlin.test.assertEquals

/**
 *  Pins what conveyors do, scenario by scenario, so that a change to the conveyor code can be checked against
 *  the behaviour it started from.
 *
 *  Each scenario records, for every item in the order it was created, when it gained access to the conveyor,
 *  when it reached each destination, and when it was fully off. A scenario's pin is the number of items
 *  delivered, the number of events executed, and a checksum of that record, so a pin holds only if every one of
 *  those times and the event count are the same.
 *
 *  Two kinds of scenario:
 *  - **Unchanged** scenarios have no request waiting to enter while the conveyor moves: arrivals are spaced, or
 *    the conveyor is stopped or blocked when they arrive. Their pins did not move for the entry fixes of R1.8.
 *    Those with a non-accumulating conveyor that moves while items board, and those that stop the conveyor, moved
 *    in R1.8 when a cell move came to be paused rather than restarted.
 *  - **Queued entry** scenarios have requests always waiting at an entry while the conveyor moves. Since R1.8 those
 *    items enter nose to tail, and these pins moved; the earlier pins are kept in comments.
 */
class ConveyorBaselineTest {

    /** What a scenario is pinned to. */
    data class Pin(val delivered: Int, val events: Long, val checksum: Long)

    /** A model whose items are created every [gap] minutes and follow [flow]. */
    private abstract class Scenario(parent: ModelElement, gap: Double) : ProcessModel(parent, null) {
        val record = mutableListOf<String>()
        var delivered = 0
        var events = 0L
        private var created = 0
        private val generator = EntityGenerator(::Item, ConstantRV(gap), ConstantRV(gap))

        /** The order in which this item was created, from 1. */
        protected val Entity.number: Int
            get() = (this as Item).number

        protected fun Entity.mark(what: String) {
            record += "${(this as Item).number}:$what@${"%.9f".format(time)}"
        }

        protected abstract suspend fun KSLProcessBuilder.flow(item: Entity)

        private inner class Item : Entity() {
            val number = ++created
            val work = process(isDefaultProcess = true) {
                flow(this@Item)
                delivered++
            }
        }

        override fun initialize() {
            record.clear()
            delivered = 0
            created = 0
        }

        override fun replicationEnded() {
            events = executive.numEventsExecuted
        }

        fun pin(): Pin {
            val crc = CRC32()
            crc.update(record.joinToString("\n").toByteArray())
            return Pin(delivered, events, crc.value)
        }
    }

    /** Enters at [entry] (In or Mid) and rides to Out, on In to Mid (10) to Out (20 more), 12 per minute. */
    private class Straight(
        parent: ModelElement, type: Conveyor.Type, private val cells: Int, gap: Double,
        private val entry: String = "In"
    ) : Scenario(parent, gap) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(3).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, entry, cells)
            item.mark("access")
            rideConveyor(cr, "Out")
            item.mark("arrive")
            exitConveyor(cr)
            item.mark("off")
        }
    }

    /** Rides In to Mid, stops there for 0.2, rides on to Out. */
    private class RideOn(parent: ModelElement, type: Conveyor.Type) : Scenario(parent, 0.5) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, "In", 1)
            item.mark("access")
            rideConveyor(cr, "Mid")
            item.mark("mid")
            delay(0.2)
            rideConveyor(cr, "Out")
            item.mark("arrive")
            exitConveyor(cr)
            item.mark("off")
        }
    }

    /** Every other item takes the entry for 0.1 and leaves without riding; the others ride to Out. */
    private class ExitWithoutRiding(parent: ModelElement) : Scenario(parent, 0.5) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()
        private var count = 0

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, "In", 1)
            item.mark("access")
            if (++count % 2 == 0) {
                delay(0.1)
                exitConveyor(cr)
                item.mark("left")
            } else {
                rideConveyor(cr, "Out")
                item.mark("arrive")
                exitConveyor(cr)
                item.mark("off")
            }
        }
    }

    /** Rides one conveyor In to X, transfers to a second, X to Out. */
    private class Transfer(parent: ModelElement) : Scenario(parent, 0.5) {
        val first = Conveyor.builder(this, "First").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("In", "X", 10).build()
        val second = Conveyor.builder(this, "Second").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("X", "Out", 10).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(first, "In", 1)
            item.mark("access")
            rideConveyor(cr, "X")
            item.mark("x")
            val tr = transferTo(cr, second, "X")
            item.mark("transferred")
            rideConveyor(tr, "Out")
            item.mark("arrive")
            exitConveyor(tr)
            item.mark("off")
        }
    }

    /** Spaced items; the conveyor is stopped from 1.05 to 1.3, when no item arrives. */
    private class StopStart(parent: ModelElement, type: Conveyor.Type) : Scenario(parent, 0.5) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, "In", 1)
            item.mark("access")
            rideConveyor(cr, "Out")
            item.mark("arrive")
            exitConveyor(cr)
            item.mark("off")
        }

        private fun stop(e: ksl.simulation.KSLEvent<Nothing>) = conveyor.stopConveyor()
        private fun start(e: ksl.simulation.KSLEvent<Nothing>) = conveyor.startConveyor()

        override fun initialize() {
            super.initialize()
            schedule(::stop, 1.05)
            schedule(::start, 1.3)
        }
    }

    /** A circular conveyor A to B (10) to A (10); items enter at A and ride to B. */
    private class Circular(parent: ModelElement, gap: Double) : Scenario(parent, gap) {
        val conveyor = Conveyor.builder(this, "Loop").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("A", "B", 10).nextSegment("A", 10).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, "A", 1)
            item.mark("access")
            rideConveyor(cr, "B")
            item.mark("arrive")
            exitConveyor(cr)
            item.mark("off")
        }
    }

    /** Rides to Out and holds the exit for [hold] before getting off. */
    private class HoldAtExit(parent: ModelElement, type: Conveyor.Type, gap: Double, private val hold: Double) :
        Scenario(parent, gap) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, "In", 1)
            item.mark("access")
            rideConveyor(cr, "Out")
            item.mark("arrive")
            delay(hold)
            exitConveyor(cr)
            item.mark("off")
        }
    }

    /** Requests at the mid-conveyor entry, alternately two cells and one cell, always waiting. */
    private class MixedSizesMid(parent: ModelElement) : Scenario(parent, 0.05) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(2).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, "Mid", if (item.number % 2 == 1) 2 else 1)
            item.mark("access")
            rideConveyor(cr, "Out")
            item.mark("arrive")
            exitConveyor(cr)
            item.mark("off")
        }
    }

    /**
     *  Every third item enters at Mid, the others at In and ride past Mid, so requests at Mid compete with riders.
     *  With [enteringPriority], requests at Mid are given priority over riders.
     */
    private class RidersAndEntering(parent: ModelElement, type: Conveyor.Type, enteringPriority: Boolean) :
        Scenario(parent, 0.1) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()

        init {
            conveyor.enteringPriorityAt("Mid", enteringPriority)
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, if (item.number % 3 == 0) "Mid" else "In", 1)
            item.mark("access")
            rideConveyor(cr, "Out")
            item.mark("arrive")
            exitConveyor(cr)
            item.mark("off")
        }
    }

    /** Each item takes 0.05 to load after gaining access, then rides. */
    private class LoadThenRide(parent: ModelElement, type: Conveyor.Type) : Scenario(parent, 0.3) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(conveyor, "In", 1)
            item.mark("access")
            delay(0.05)
            rideConveyor(cr, "Out")
            item.mark("arrive")
            exitConveyor(cr)
            item.mark("off")
        }
    }

    /** One item rides; the conveyor is stopped from 0.2 to 0.7, and a second item asks to ride at 0.4. */
    private class StopWithRider(parent: ModelElement, type: Conveyor.Type) : Scenario(parent, 100.0) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("In", "Out", 10).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {}

        inner class Rider(private val label: String, private val at: Double) : Entity() {
            val work = process {
                delay(at)
                val cr = requestConveyor(conveyor, "In", 1)
                record += "$label:access@${"%.9f".format(time)}"
                rideConveyor(cr, "Out")
                record += "$label:arrive@${"%.9f".format(time)}"
                exitConveyor(cr)
                record += "$label:off@${"%.9f".format(time)}"
                delivered++
            }
        }

        private fun stop(e: KSLEvent<Nothing>) = conveyor.stopConveyor()
        private fun start(e: KSLEvent<Nothing>) = conveyor.startConveyor()

        override fun initialize() {
            super.initialize()
            activate(Rider("1", 0.0).work)
            activate(Rider("2", 0.4).work)
            schedule(::stop, 0.2)
            schedule(::start, 0.7)
        }
    }

    /** A rides In to Out; X takes Mid at 0.3, with A behind it, holds it for 1.0 and leaves without riding. */
    private class LeaveWithRiderBehind(parent: ModelElement, type: Conveyor.Type) : Scenario(parent, 100.0) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("In", "Mid", 5).nextSegment("Out", 5).build()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {}

        inner class A : Entity() {
            val work = process {
                val cr = requestConveyor(conveyor, "In", 1)
                record += "A:access@${"%.9f".format(time)}"
                rideConveyor(cr, "Out")
                record += "A:arrive@${"%.9f".format(time)}"
                exitConveyor(cr)
                record += "A:off@${"%.9f".format(time)}"
                delivered++
            }
        }

        inner class X : Entity() {
            val work = process {
                delay(0.3)
                val cr = requestConveyor(conveyor, "Mid", 1)
                record += "X:access@${"%.9f".format(time)}"
                delay(1.0)
                exitConveyor(cr)
                record += "X:left@${"%.9f".format(time)}"
                delivered++
            }
        }

        override fun initialize() {
            super.initialize()
            activate(A().work)
            activate(X().work)
        }
    }

    /**
     *  Thirty items queued at In at time 0 ride nose to tail past Mid to Out; one request arrives at Mid at 1.0,
     *  while the stream is passing, and rides to Out.
     */
    private class StreamPastMid(parent: ModelElement, type: Conveyor.Type, enteringPriority: Boolean) :
        Scenario(parent, 100.0) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(type).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()
        var lastRiderPastMid = Double.NaN
        var enteredAt = Double.NaN

        init {
            conveyor.enteringPriorityAt("Mid", enteringPriority)
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {}

        inner class Rider(private val last: Boolean) : Entity() {
            val work = process {
                val cr = requestConveyor(conveyor, "In", 1)
                rideConveyor(cr, "Out")
                // the last rider's front reaches Out 20 cells after it was in the cell before Mid
                if (last) lastRiderPastMid = time - 20.0 / 12.0
                exitConveyor(cr)
            }
        }

        inner class Entering : Entity() {
            val work = process {
                delay(1.0)
                val cr = requestConveyor(conveyor, "Mid", 1)
                enteredAt = time
                rideConveyor(cr, "Out")
                exitConveyor(cr)
            }
        }

        override fun initialize() {
            super.initialize()
            for (i in 1..30) activate(Rider(i == 30).work)
            activate(Entering().work)
        }
    }

    /**
     *  A rider stops at Mid, its destination, and holds that exit cell, just before the Mid entry, until 2.0. At Mid
     *  a request for two cells arrives at 1.0, needing that cell too, and a request for one cell at 1.1, needing
     *  only the entry cell, which is free.
     */
    private class SmallBehindLarge(parent: ModelElement) : Scenario(parent, 100.0) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(2).firstSegment("In", "Mid", 10).nextSegment("Out", 20).build()
        val granted = mutableListOf<String>()

        override suspend fun KSLProcessBuilder.flow(item: Entity) {}

        inner class Holder : Entity() {
            val work = process {
                val cr = requestConveyor(conveyor, "In", 1)
                rideConveyor(cr, "Mid")
                delay(2.0 - time)
                exitConveyor(cr)
            }
        }

        inner class Request(private val label: String, private val at: Double, private val cells: Int) : Entity() {
            val work = process {
                delay(at)
                val cr = requestConveyor(conveyor, "Mid", cells)
                granted += label
                rideConveyor(cr, "Out")
                exitConveyor(cr)
            }
        }

        override fun initialize() {
            super.initialize()
            granted.clear()
            activate(Holder().work)
            activate(Request("large", 1.0, 2).work)
            activate(Request("small", 1.1, 1).work)
        }
    }

    private fun run(name: String, factory: (Model) -> Scenario): Scenario {
        val m = Model(name, autoCSVReports = false)
        val s = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()
        return s
    }

    private fun assertPin(expected: Pin, s: Scenario) {
        val actual = s.pin()
        assertEquals(expected, actual, "record begins:\n" + s.record.take(40).joinToString("\n"))
    }

    // ---- unchanged: no request waits to enter while the conveyor moves --------------------------------------

    @Test
    @DisplayName("Unchanged: accumulating, spaced arrivals, entry at the start")
    fun accumulatingSpaced() =
        assertPin(Pin(4, 119, 999187291L), run("u1") { Straight(it, Conveyor.Type.ACCUMULATING, 1, 0.5) })

    // before R1.8: Pin(4, 112, 350020516)
    @Test
    @DisplayName("Unchanged: non-accumulating, spaced arrivals, entry at the start")
    fun nonAccumulatingSpaced() =
        assertPin(Pin(4, 119, 999187291L), run("u2") { Straight(it, Conveyor.Type.NON_ACCUMULATING, 1, 0.5) })

    @Test
    @DisplayName("Unchanged: accumulating, spaced two-cell items entering mid-conveyor")
    fun accumulatingSpacedMid() =
        assertPin(Pin(6, 123, 1501904748L), run("u3") { Straight(it, Conveyor.Type.ACCUMULATING, 2, 0.5, "Mid") })

    // before R1.8: Pin(5, 117, 3399706916)
    @Test
    @DisplayName("Unchanged: non-accumulating, spaced three-cell items entering mid-conveyor")
    fun nonAccumulatingSpacedMid() =
        assertPin(Pin(6, 123, 2453636893L), run("u4") { Straight(it, Conveyor.Type.NON_ACCUMULATING, 3, 0.5, "Mid") })

    @Test
    @DisplayName("Unchanged: accumulating, riding on past a destination")
    fun accumulatingRideOn() = assertPin(Pin(4, 141, 349066127L), run("u5") { RideOn(it, Conveyor.Type.ACCUMULATING) })

    // before R1.8: Pin(1, 107, 2211216165)
    @Test
    @DisplayName("Unchanged: non-accumulating, riding on past a destination")
    fun nonAccumulatingRideOn() = assertPin(Pin(2, 115, 447664933L), run("u6") { RideOn(it, Conveyor.Type.NON_ACCUMULATING) })

    /**
     *  Before R1.8 the items that left without riding never finished: their exit completed the request but never
     *  resumed the entity, which waited in the exiting hold queue for the rest of the run. They now leave at once;
     *  the riders' times are unchanged.
     */
    // before R1.8: Pin(2, 112, 581193216)
    @Test
    @DisplayName("Unchanged: leaving the entry without riding")
    fun exitWithoutRiding() = assertPin(Pin(6, 112, 4252308689L), run("u7") { ExitWithoutRiding(it) })

    /** Takes the entry at 1, leaves without riding at 1.5, then asks again and rides to Out. */
    private class LeaveThenRide(parent: ModelElement) : ProcessModel(parent, null) {
        val conveyor = Conveyor.builder(this, "Belt").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("In", "Out", 10).build()
        var leftAt = Double.NaN
        var arrivedAt = Double.NaN

        inner class Item : Entity() {
            val work = process {
                delay(1.0)
                val first = requestConveyor(conveyor, "In", 1)
                delay(0.5)
                exitConveyor(first)
                leftAt = time
                val second = requestConveyor(conveyor, "In", 1)
                rideConveyor(second, "Out")
                arrivedAt = time
                exitConveyor(second)
            }
        }

        override fun initialize() {
            activate(Item().work)
        }
    }

    @Test
    @DisplayName("Leaving the entry without riding releases it at once, and the entity can ask again")
    fun leaveThenRide() {
        val m = Model("leaveThenRide", autoCSVReports = false)
        val s = LeaveThenRide(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()
        assertEquals(1.5, s.leftAt, 1e-9, "no time passes leaving an entry it never rode from")
        assertEquals(1.5 + 10.0 / 12.0, s.arrivedAt, 1e-9, "then rides the ten cells to Out")
    }

    /**
     *  Since R1.8.1 an item's move off the first conveyor is its move onto the second: it lands on the second
     *  conveyor's first cell as it rides, rather than entering it at that conveyor's next cell move.
     */
    // before R1.8.1: Pin(6, 230, 144759797)
    @Test
    @DisplayName("Transfer between conveyors")
    fun transfer() = assertPin(Pin(6, 232, 4106755587L), run("u8") { Transfer(it) })

    // before R1.8: Pin(4, 117, 4156148379)
    @Test
    @DisplayName("Unchanged: accumulating, stopped and started")
    fun accumulatingStopStart() = assertPin(Pin(5, 119, 1790093574L), run("u9") { StopStart(it, Conveyor.Type.ACCUMULATING) })

    // before R1.8: Pin(4, 110, 3180507172)
    @Test
    @DisplayName("Unchanged: non-accumulating, stopped and started")
    fun nonAccumulatingStopStart() =
        assertPin(Pin(5, 119, 1790093574L), run("u10") { StopStart(it, Conveyor.Type.NON_ACCUMULATING) })

    @Test
    @DisplayName("Unchanged: circular, spaced arrivals")
    fun circularSpaced() = assertPin(Pin(8, 129, 2709662911L), run("u11") { Circular(it, 0.5) })

    // before R1.8: Pin(2, 101, 3953655155)
    @Test
    @DisplayName("Unchanged: non-accumulating, each item holds the exit for 0.3")
    fun nonAccumulatingHoldAtExit() =
        assertPin(Pin(3, 106, 683012339L), run("u12") { HoldAtExit(it, Conveyor.Type.NON_ACCUMULATING, 0.5, 0.3) })

    // ---- queued entry: requests always wait at an entry while the conveyor moves ------------------------------

    // before R1.8: Pin(15, 1665, 3505887645)
    @Test
    @DisplayName("Queued entry: accumulating, one-cell items at the start")
    fun accumulatingQueued1() =
        assertPin(Pin(29, 1769, 367452068L), run("c1") { Straight(it, Conveyor.Type.ACCUMULATING, 1, 0.01) })

    // before R1.8: Pin(10, 1630, 1902342537)
    @Test
    @DisplayName("Queued entry: accumulating, two-cell items at the start")
    fun accumulatingQueued2() =
        assertPin(Pin(14, 1664, 897217007L), run("c2") { Straight(it, Conveyor.Type.ACCUMULATING, 2, 0.01) })

    // before R1.8: Pin(7, 1613, 2467643123)
    @Test
    @DisplayName("Queued entry: accumulating, three-cell items at the start")
    fun accumulatingQueued3() =
        assertPin(Pin(9, 1629, 2894638361L), run("c3") { Straight(it, Conveyor.Type.ACCUMULATING, 3, 0.01) })

    // before R1.8: Pin(15, 1665, 3505887645)
    @Test
    @DisplayName("Queued entry: non-accumulating, one-cell items at the start")
    fun nonAccumulatingQueued1() =
        assertPin(Pin(29, 1769, 367452068L), run("c4") { Straight(it, Conveyor.Type.NON_ACCUMULATING, 1, 0.01) })

    // before R1.8: Pin(20, 1680, 2357046034)
    @Test
    @DisplayName("Queued entry: accumulating, one-cell items mid-conveyor")
    fun accumulatingQueuedMid() =
        assertPin(Pin(39, 1799, 693813790L), run("c5") { Straight(it, Conveyor.Type.ACCUMULATING, 1, 0.01, "Mid") })

    // before R1.8: Pin(13, 1641, 1102945771)
    @Test
    @DisplayName("Queued entry: non-accumulating, two-cell items mid-conveyor")
    fun nonAccumulatingQueuedMid() =
        assertPin(Pin(19, 1679, 329283093L), run("c6") { Straight(it, Conveyor.Type.NON_ACCUMULATING, 2, 0.01, "Mid") })

    // before R1.8: Pin(25, 1695, 1041427740)
    @Test
    @DisplayName("Queued entry: circular")
    fun circularQueued() = assertPin(Pin(49, 1829, 3050456766L), run("c7") { Circular(it, 0.01) })

    /**
     *  Items always waiting enter nose to tail: in steady state they arrive at Out one every k cell times, so a
     *  12 per minute conveyor of 1-unit cells delivers 12 / k a minute. Before R1.8 an empty cell was left
     *  between items, one every k + 1 cell times.
     */
    @Test
    @DisplayName("Items always waiting enter nose to tail, on both types, at the start and mid-conveyor")
    fun noseToTail() {
        val cellTime = 1.0 / 12.0
        for (type in Conveyor.Type.entries) for (entry in listOf("In", "Mid")) for (k in 1..3) {
            val m = Model("noseToTail", autoCSVReports = false)
            val s = Straight(m, type, k, 0.01, entry)
            m.numberOfReplications = 1
            m.lengthOfReplication = 100.0
            m.simulate()
            val label = "$type, entry $entry, $k cells"
            val accessOrder = s.record.filter { it.contains(":access@") }.map { it.substringBefore(':').toInt() }
            assertEquals(accessOrder.sorted(), accessOrder, "$label: requests gain access in the order they asked")
            val arrivals = s.record.filter { it.contains(":arrive@") }.map { it.substringAfter('@').toDouble() }
            val gaps = arrivals.zipWithNext { a, b -> b - a }.drop(10)
            assertEquals(k * cellTime, gaps.min(), 1e-9, "$label: closest arrivals")
            assertEquals(k * cellTime, gaps.max(), 1e-9, "$label: furthest arrivals")
            // from the first arrival on, one item every k cell times
            val expected = ((100.0 - arrivals.first()) / (k * cellTime)).toInt() + 1
            assertEquals(expected.toDouble(), s.delivered.toDouble(), 1.0, "$label: delivered")
        }
    }

    /**
     *  Random traffic on both types, straight and circular, with items of up to three cells, three entries,
     *  random destinations, exits held for a while, some requests that leave without riding, and the conveyor
     *  stopped and started: no run may fail. A failure here is an internal check, such as two items in one cell.
     */
    @Test
    @DisplayName("Random traffic never breaks the conveyor's own checks, and requests at each entry gain access in turn")
    fun randomTraffic() {
        for (seed in 1..48) {
            val m = Model("random$seed", autoCSVReports = false)
            val traffic = RandomTraffic(m, seed)
            m.numberOfReplications = 1
            m.lengthOfReplication = 20.0
            val error = runCatching { m.simulate() }.exceptionOrNull()
            assertEquals(null, error, "seed $seed: ${error?.message}")
            for ((entry, granted) in traffic.granted) {
                val asked = traffic.asked.getValue(entry)
                assertEquals(asked.take(granted.size), granted, "seed $seed, entry $entry: access in turn")
            }
        }
    }

    private class RandomTraffic(parent: ModelElement, seed: Int) : ProcessModel(parent, null) {
        private val rnd = kotlin.random.Random(seed)
        private val maxCells = 1 + seed % 3
        private val conveyor = Conveyor.builder(this, "Belt")
            .conveyorType(if (seed % 2 == 0) Conveyor.Type.ACCUMULATING else Conveyor.Type.NON_ACCUMULATING)
            .velocity(12.0).cellSize(1).maxCellsAllowed(maxCells)
            .firstSegment("A", "B", 8 + rnd.nextInt(6)).nextSegment("C", 6 + rnd.nextInt(6))
            .nextSegment(if (seed % 3 == 0) "A" else "D", 6 + rnd.nextInt(6)).build()
        private val mean = listOf(0.02, 0.05, 0.1, 0.3)[seed % 4]
        val asked = mutableMapOf<String, MutableList<Int>>()
        val granted = mutableMapOf<String, MutableList<Int>>()
        private var created = 0
        private val generator = EntityGenerator(::Item, ExponentialRV(mean, seed * 7), ExponentialRV(mean, seed * 7 + 1))

        private fun stop(e: KSLEvent<Nothing>) {
            conveyor.stopConveyor()
            schedule(::start, rnd.nextDouble(0.05, 0.4))
        }

        private fun start(e: KSLEvent<Nothing>) {
            conveyor.startConveyor()
            schedule(::stop, rnd.nextDouble(0.5, 3.0))
        }

        override fun initialize() {
            schedule(::stop, rnd.nextDouble(0.5, 3.0))
        }

        private inner class Item : Entity() {
            private val number = ++created
            val work = process(isDefaultProcess = true) {
                val entry = listOf("A", "B", "C")[rnd.nextInt(3)]
                val destinations = conveyor.exitLocations.filter { it != entry && conveyor.isReachable(entry, it) }
                asked.getOrPut(entry) { mutableListOf() } += number
                val cr = requestConveyor(conveyor, entry, 1 + rnd.nextInt(maxCells))
                granted.getOrPut(entry) { mutableListOf() } += number
                if (rnd.nextDouble() < 0.05) {
                    delay(0.1)
                    exitConveyor(cr)
                } else {
                    rideConveyor(cr, destinations[rnd.nextInt(destinations.size)])
                    if (rnd.nextDouble() < 0.3) delay(rnd.nextDouble(0.0, 0.3))
                    exitConveyor(cr)
                }
            }
        }
    }

    // before R1.8: Pin(5, 441, 2922862228)
    @Test
    @DisplayName("Queued entry: accumulating, the belt backs up behind an exit held for 0.4")
    fun accumulatingBackedUp() =
        assertPin(Pin(5, 427, 2278560873L), run("c8") { HoldAtExit(it, Conveyor.Type.ACCUMULATING, 0.05, 0.4) })

    // ---- scenarios added before the R1.8 entry-rule and timing fixes; each names the fix expected to move it ----

    @Test
    @DisplayName("Entry in turn: two-cell and one-cell requests alternating at a mid-conveyor entry")
    fun mixedSizesMid() = assertPin(Pin(26, 520, 939594979L), run("p1") { MixedSizesMid(it) })

    // before R1.8: Pin(26, 389, 3126792337), equal to entering priority's
    @Test
    @DisplayName("Riders first: requests at Mid compete with riders, accumulating")
    fun ridersFirstAccumulating() =
        assertPin(Pin(26, 389, 1734366974L), run("p2a") { RidersAndEntering(it, Conveyor.Type.ACCUMULATING, false) })

    // before R1.8: Pin(22, 368, 428472262)
    @Test
    @DisplayName("Riders first: requests at Mid compete with riders, non-accumulating")
    fun ridersFirstNonAccumulating() =
        assertPin(Pin(26, 389, 1734366974L), run("p2b") { RidersAndEntering(it, Conveyor.Type.NON_ACCUMULATING, false) })

    @Test
    @DisplayName("Entering priority: requests at Mid have priority over riders, accumulating")
    fun enteringPriorityAccumulating() =
        assertPin(Pin(26, 389, 3126792337L), run("p2c") { RidersAndEntering(it, Conveyor.Type.ACCUMULATING, true) })

    // before R1.8: Pin(4, 155, 1337382434)
    @Test
    @DisplayName("Movement timing: non-accumulating, each item loads for 0.05 before riding")
    fun nonAccumulatingLoadThenRide() =
        assertPin(Pin(6, 161, 622235305L), run("p3") { LoadThenRide(it, Conveyor.Type.NON_ACCUMULATING) })

    // before R1.8: Pin(2, 32, 1412068310)
    @Test
    @DisplayName("Movement timing: accumulating, stopped from 0.2 to 0.7 with a rider asking during the stop")
    fun accumulatingStopWithRider() =
        assertPin(Pin(2, 32, 3077581115L), run("p4a") { StopWithRider(it, Conveyor.Type.ACCUMULATING) })

    // before R1.8: Pin(2, 32, 3710179474)
    @Test
    @DisplayName("Movement timing: non-accumulating, stopped from 0.2 to 0.7 with a rider asking during the stop")
    fun nonAccumulatingStopWithRider() =
        assertPin(Pin(2, 32, 3077581115L), run("p4b") { StopWithRider(it, Conveyor.Type.NON_ACCUMULATING) })

    // before R1.8: Pin(1, 15, 3778948367), A never arrived
    @Test
    @DisplayName("Restart: accumulating, an entity leaves Mid without riding, with a rider behind it")
    fun accumulatingLeaveWithRiderBehind() =
        assertPin(Pin(2, 24, 3213235258L), run("p5a") { LeaveWithRiderBehind(it, Conveyor.Type.ACCUMULATING) })

    /**
     *  A is stopped behind X at Mid until X leaves at 1.3; the conveyor then carries it on. Before R1.8 it never
     *  restarted.
     */
    @Test
    @DisplayName("Leaving an entry without riding sets the items behind it moving again")
    fun leavingAnEntryRestartsTheConveyor() {
        for (type in Conveyor.Type.entries) {
            val s = run("restart$type") { LeaveWithRiderBehind(it, type) }
            val arrived = s.record.firstOrNull { it.startsWith("A:arrive@") }
            assertEquals(true, arrived != null, "$type: A arrives: ${s.record}")
            assertEquals(true, arrived!!.substringAfter('@').toDouble() > 1.3, "$type: after X leaves: $arrived")
        }
    }

    // before R1.8: Pin(1, 14, 3778948367), A never arrived; before the pausing fix: Pin(2, 25, 915597255)
    @Test
    @DisplayName("Restart: non-accumulating, an entity leaves Mid without riding, with a rider behind it")
    fun nonAccumulatingLeaveWithRiderBehind() =
        assertPin(Pin(2, 24, 2603167386L), run("p5b") { LeaveWithRiderBehind(it, Conveyor.Type.NON_ACCUMULATING) })

    /**
     *  By default the request at Mid waits for the stream of riders to pass; with entering priority it takes the
     *  entry at once and the riders wait behind it.
     */
    @Test
    @DisplayName("Riders go first at an entry by default, and entering priority reverses it")
    fun ridersFirstAndEnteringPriority() {
        val cellTime = 1.0 / 12.0
        for (type in Conveyor.Type.entries) {
            val riders = run("riders$type") { StreamPastMid(it, type, false) } as StreamPastMid
            assertEquals(true, riders.enteredAt >= riders.lastRiderPastMid,
                "$type: the request waits for the stream: entered ${riders.enteredAt}, stream past ${riders.lastRiderPastMid}")
            val entering = run("entering$type") { StreamPastMid(it, type, true) } as StreamPastMid
            assertEquals(true, entering.enteredAt <= 1.0 + cellTime + 1e-9,
                "$type: with entering priority the request goes first: entered ${entering.enteredAt}")
        }
    }

    /** The small request fits first but waits its turn behind the large one. Before R1.8 it went first. */
    @Test
    @DisplayName("A request that fits waits its turn behind a larger request at the same entry")
    fun smallWaitsBehindLarge() {
        val s = run("inTurn") { SmallBehindLarge(it) } as SmallBehindLarge
        assertEquals(listOf("large", "small"), s.granted)
    }

    /**
     *  A stop costs exactly its length, on both types, and nothing moves the conveyor while it is stopped: the
     *  first item's ten-cell trip of 0.833 takes 1.333 with a stop of 0.5. The second item asks to ride during the
     *  stop, and enters as the paused cell move completes. Before R1.8 the trip took 1.367, or on a non-accumulating
     *  conveyor 1.067, the boarding having moved it while stopped.
     */
    @Test
    @DisplayName("A stop costs exactly its length, and a stopped conveyor does not move")
    fun stopCostsItsLength() {
        for (type in Conveyor.Type.entries) {
            val s = run("stop$type") { StopWithRider(it, type) }
            val arrival = s.record.first { it.startsWith("1:arrive@") }.substringAfter('@').toDouble()
            assertEquals(10.0 / 12.0 + 0.5, arrival, 1e-9, "$type")
        }
    }

    /**
     *  On a non-accumulating conveyor a boarding stops the conveyor for exactly as long as the item takes to load:
     *  for no time when it rides at once (the first item's 30-cell trip takes 2.5 with five boardings behind it),
     *  and for 0.05 when it loads for 0.05 (ten boardings during the first item's trip add 0.5). Before R1.8 each
     *  boarding restarted the cell move in progress.
     */
    @Test
    @DisplayName("A non-accumulating conveyor stops for a boarding exactly as long as the loading takes")
    fun boardingCostsItsLoadingTime() {
        val cellTime = 1.0 / 12.0
        val atOnce = run("atOnce") { HoldAtExit(it, Conveyor.Type.NON_ACCUMULATING, 0.5, 0.3) }
        val first = atOnce.record.first { it.startsWith("1:arrive@") }.substringAfter('@').toDouble()
        assertEquals(0.5 + 30 * cellTime, first, 1e-9, "boarding at once costs nothing")
        val loading = run("loading") { LoadThenRide(it, Conveyor.Type.NON_ACCUMULATING) }
        val arrival = loading.record.first { it.startsWith("1:arrive@") }.substringAfter('@').toDouble()
        val boardingsDuringTrip = loading.record.count {
            it.contains(":access@") && !it.startsWith("1:") && it.substringAfter('@').toDouble() < arrival
        }
        assertEquals(0.3 + 0.05 + 30 * cellTime + 0.05 * boardingsDuringTrip, arrival, 1e-9, "each loading costs 0.05")
    }
}
