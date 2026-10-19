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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 *  Pins what a transfer from one conveyor to another does, scenario by scenario, so that a change to transfers
 *  can be checked against the behaviour it started from.
 *
 *  Each scenario records, for every item in the order it was created, when it reached the end of its first
 *  conveyor, when the transfer returned (the item is off the first conveyor), when it reached its destination on
 *  the second, and when it was fully off. A scenario's pin is the number of items delivered, the number of events
 *  executed, and a checksum of that record, so a pin holds only if every one of those times and the event count
 *  are the same. Where a number says what a scenario is about (the time between items leaving the first
 *  conveyor), it is asserted on its own as well.
 */
class ConveyorTransferTest {

    /** What a scenario is pinned to. */
    data class Pin(val delivered: Int, val events: Long, val checksum: Long)

    /** A model that records what its items do. */
    private abstract class Rig(parent: ModelElement) : ProcessModel(parent, null) {
        val record = mutableListOf<String>()
        val marks = mutableMapOf<String, MutableList<Double>>()
        var delivered = 0
        var events = 0L
        private var created = 0

        protected fun Entity.mark(what: String) {
            record += "${(this as Item).number}:$what@${"%.9f".format(time)}"
            marks.getOrPut(what) { mutableListOf() }.add(time)
        }

        protected abstract suspend fun KSLProcessBuilder.flow(item: Entity)

        protected inner class Item(val label: String = "") : Entity() {
            val number = ++created
            val work = process {
                flow(this@Item)
                delivered++
            }
        }

        /** Activates [n] items at the current time, so that they queue at the first conveyor's entry. */
        protected fun startItems(n: Int) {
            repeat(n) { activate(Item().work) }
        }

        override fun initialize() {
            record.clear()
            marks.clear()
            delivered = 0
            created = 0
        }

        override fun replicationEnded() {
            events = executive.numEventsExecuted
        }

        /** The average time between successive [what] marks, over the [from]-th to the [to]-th. */
        fun gap(what: String, from: Int, to: Int): Double {
            val times = marks[what]!!
            return (times[to] - times[from]) / (to - from)
        }

        fun pin(): Pin {
            val crc = CRC32()
            crc.update(record.joinToString("\n").toByteArray())
            return Pin(delivered, events, crc.value)
        }
    }

    /**
     *  A feeder (Entry to Merge, [feederCells] cells, 12 cells a minute) and a receiving conveyor (Merge to Ship,
     *  [mainCells] cells, [mainVelocity] cells a minute). [n] items of [cells] cells each are all waiting at the
     *  feeder's entry at time 0, so the feeder is never short of items; each rides to Merge, transfers, rides to
     *  Ship, holds there for [holdAtShip], and leaves.
     */
    private open class Junction(
        parent: ModelElement,
        val mainVelocity: Double,
        val cells: Int = 1,
        val n: Int = 150,
        feederType: Conveyor.Type = Conveyor.Type.ACCUMULATING,
        mainType: Conveyor.Type = Conveyor.Type.ACCUMULATING,
        feederCells: Int = 20,
        mainCells: Int = 25,
        private val holdAtShip: Double = 0.0
    ) : Rig(parent) {
        val feeder = Conveyor.builder(this, "Feeder").conveyorType(feederType).velocity(12.0).cellSize(1)
            .maxCellsAllowed(3).firstSegment("Entry", "Merge", feederCells).build()
        val main = Conveyor.builder(this, "Main").conveyorType(mainType).velocity(mainVelocity).cellSize(1)
            .maxCellsAllowed(3).firstSegment("Merge", "Ship", mainCells).build()

        override fun initialize() {
            super.initialize()
            startItems(n)
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(feeder, "Entry", cells)
            rideConveyor(cr, "Merge")
            item.mark("end")
            val tr = transferTo(cr, main, "Merge")
            item.mark("off")
            afterTransfer(item, tr)
        }

        open suspend fun KSLProcessBuilder.afterTransfer(item: Entity, tr: ConveyorRequestIfc) {
            rideConveyor(tr, "Ship")
            item.mark("arrive")
            if (holdAtShip > 0.0) delay(holdAtShip)
            exitConveyor(tr)
            item.mark("done")
        }
    }

    private fun <T : Rig> run(name: String, length: Double = 100.0, factory: (Model) -> T): T {
        val m = Model(name, autoCSVReports = false)
        val s = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        m.simulate()
        return s
    }

    private fun assertPin(expected: Pin, s: Rig) {
        val actual = s.pin()
        assertEquals(expected, actual, "record begins:\n" + s.record.take(40).joinToString("\n"))
    }

    // ---- 1. throughput: the time between items leaving the feeder ---------------------------------------------

    /**
     *  The feeder can bring an item every 1/12 minute. Today the next item may not start its push-off until the
     *  item before it has made its first move on the receiving conveyor, so with a receiving conveyor at v cells a
     *  minute (v at least 12) the time between items lies between 1/12 and 1/12 + 1/v, depending on where the
     *  receiving conveyor's moves fall. A slower receiving conveyor sets the pace.
     */
    @Test
    @DisplayName("Throughput of a transfer, by the receiving conveyor's speed")
    fun throughputBySpeed() {
        val expected = mapOf(
            6.0 to (1.0 / 6.0),
            12.0 to (1.0 / 6.0),
            25.0 to (3.0 / 25.0),
            50.0 to (5.0 / 50.0),
            1000.0 to (1.0 / 12.0 + 1.0 / 1000.0),
        )
        for ((v, gap) in expected) {
            val s = run("speed$v") { Junction(it, v) }
            assertEquals(gap, s.gap("off", 40, 120), 1e-9, "receiving conveyor at $v cells a minute")
        }
    }

    @Test
    @DisplayName("Pinned: transfer onto a receiving conveyor at 6 cells a minute")
    fun speed6() = assertPin(Pin(150, 2596, 1027664292L), run("s6") { Junction(it, 6.0) })

    @Test
    @DisplayName("Pinned: transfer onto a receiving conveyor at 12 cells a minute")
    fun speed12() = assertPin(Pin(150, 2745, 946063530L), run("s12") { Junction(it, 12.0) })

    @Test
    @DisplayName("Pinned: transfer onto a receiving conveyor at 25 cells a minute")
    fun speed25() = assertPin(Pin(150, 2894, 389327581L), run("s25") { Junction(it, 25.0) })

    @Test
    @DisplayName("Pinned: transfer onto a receiving conveyor at 50 cells a minute")
    fun speed50() = assertPin(Pin(150, 3192, 3853472379L), run("s50") { Junction(it, 50.0) })

    @Test
    @DisplayName("Pinned: transfer onto a receiving conveyor at 1000 cells a minute")
    fun speed1000() = assertPin(Pin(150, 6321, 2341080135L), run("s1000") { Junction(it, 1000.0) })

    /**
     *  The first items, with the receiving conveyor at 25 cells a minute: item 1 leaves the feeder one feeder cell
     *  time after reaching its end; item 2 reaches the end as item 1 leaves, waits 1/25 for the receiving entry
     *  cell, then takes its own feeder cell time.
     */
    @Test
    @DisplayName("The first items' times through the junction")
    fun firstItems() {
        val s = run("first") { Junction(it, 25.0, n = 3) }
        val end = s.marks["end"]!!
        val off = s.marks["off"]!!
        assertEquals(20.0 / 12.0, end[0], 1e-9)
        assertEquals(20.0 / 12.0 + 1.0 / 12.0, off[0], 1e-9)
        assertEquals(off[0], end[1], 1e-9)
        assertEquals(end[1] + 1.0 / 25.0 + 1.0 / 12.0, off[1], 1e-9)
    }

    // ---- 2. a merge: two feeders onto one receiving conveyor -------------------------------------------------

    /**
     *  Two feeders, A and B (10 cells each, 12 cells a minute), both end at Sort, where the receiving conveyor
     *  (Sort to Out, 20 cells, 12 cells a minute) starts. Items alternate between the feeders and are all waiting
     *  at time 0, so items from both reach Sort together.
     */
    private class Merge(parent: ModelElement, val n: Int = 20) : Rig(parent) {
        val a = Conveyor.builder(this, "A").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("InA", "Sort", 10).build()
        val b = Conveyor.builder(this, "B").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0).cellSize(1)
            .maxCellsAllowed(1).firstSegment("InB", "Sort", 10).build()
        val main = Conveyor.builder(this, "Main").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("Sort", "Out", 20).build()
        val order = mutableListOf<String>()

        override fun initialize() {
            super.initialize()
            order.clear()
            repeat(n) { i -> activate(Item(if (i % 2 == 0) "A" else "B").work) }
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val label = (item as Item).label
            val feeder = if (label == "A") a else b
            val cr = requestConveyor(feeder, "In$label", 1)
            rideConveyor(cr, "Sort")
            item.mark("end$label")
            val tr = transferTo(cr, main, "Sort")
            item.mark("off$label")
            order += label
            rideConveyor(tr, "Out")
            item.mark("arrive")
            exitConveyor(tr)
        }
    }

    @Test
    @DisplayName("Pinned: two feeders merging onto one conveyor")
    fun merge() {
        val s = run("merge") { Merge(it) }
        assertPin(Pin(20, 400, 2315883804L), s)
    }

    // ---- 3. a transfer onto an entry partway along, with riders upstream ----------------------------------------

    /**
     *  The receiving conveyor runs Start to Join (10 cells) to End (20), 12 cells a minute. Riders enter at Start
     *  every 1/12 minute from time 0 to 3 and ride to End. A feeder (In to Join, 10 cells) brings 8 items, all
     *  waiting at time 0, which transfer at Join and ride to End. With [enteringPriority], transfers at Join go
     *  ahead of riders.
     */
    private class MidEntry(parent: ModelElement, private val enteringPriority: Boolean) : Rig(parent) {
        val main = Conveyor.builder(this, "Main").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("Start", "Join", 10).nextSegment("End", 20).build()
        val feeder = Conveyor.builder(this, "Feeder").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(1).firstSegment("In", "Join", 10).build()

        init {
            main.enteringPriorityAt("Join", enteringPriority)
        }

        override fun initialize() {
            super.initialize()
            repeat(8) { activate(Item("transfer").work) }
            schedule(::rider, 0.0)
        }

        private fun rider(e: KSLEvent<Nothing>) {
            activate(Item("rider").work)
            if (time < 3.0) schedule(::rider, 1.0 / 12.0)
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            if ((item as Item).label == "rider") {
                val cr = requestConveyor(main, "Start", 1)
                rideConveyor(cr, "End")
                item.mark("riderArrive")
                exitConveyor(cr)
            } else {
                val cr = requestConveyor(feeder, "In", 1)
                rideConveyor(cr, "Join")
                item.mark("end")
                val tr = transferTo(cr, main, "Join")
                item.mark("off")
                rideConveyor(tr, "End")
                item.mark("arrive")
                exitConveyor(tr)
            }
        }
    }

    @Test
    @DisplayName("Pinned: transfer onto a mid-conveyor entry, riders first")
    fun midEntryRidersFirst() = assertPin(Pin(45, 517, 3496542498L), run("midRiders") { MidEntry(it, false) })

    @Test
    @DisplayName("Pinned: transfer onto a mid-conveyor entry, entering priority")
    fun midEntryEnteringPriority() = assertPin(Pin(45, 517, 1679128360L), run("midEntering") { MidEntry(it, true) })

    // ---- 4. pausing at the junction, and leaving there ------------------------------------------------------

    /** Each item stays at the junction for 0.1 after the transfer, then rides. */
    private class PauseAtJunction(parent: ModelElement) : Junction(parent, 25.0, n = 10) {
        override suspend fun KSLProcessBuilder.afterTransfer(item: Entity, tr: ConveyorRequestIfc) {
            delay(0.1)
            item.mark("rides")
            rideConveyor(tr, "Ship")
            item.mark("arrive")
            exitConveyor(tr)
        }
    }

    /** Each item leaves at the junction after the transfer, without riding the receiving conveyor. */
    private class LeaveAtJunction(parent: ModelElement) : Junction(parent, 25.0, n = 10) {
        override suspend fun KSLProcessBuilder.afterTransfer(item: Entity, tr: ConveyorRequestIfc) {
            exitConveyor(tr)
            item.mark("left")
        }
    }

    @Test
    @DisplayName("Pinned: pausing at the junction before riding on")
    fun pauseAtJunction() = assertPin(Pin(10, 262, 2173346804L), run("pause") { PauseAtJunction(it) })

    @Test
    @DisplayName("Pinned: leaving at the junction without riding on")
    fun leaveAtJunction() = assertPin(Pin(10, 141, 1314471813L), run("leave") { LeaveAtJunction(it) })

    // ---- 5. non-accumulating conveyors ------------------------------------------------------------------------

    @Test
    @DisplayName("Pinned: a non-accumulating feeder")
    fun nonAccumulatingFeeder() = assertPin(Pin(40, 804, 3607506081L),
        run("naFeeder") { Junction(it, 25.0, n = 40, feederType = Conveyor.Type.NON_ACCUMULATING) })

    @Test
    @DisplayName("Pinned: a non-accumulating receiving conveyor")
    fun nonAccumulatingReceiver() = assertPin(Pin(40, 726, 2208616566L),
        run("naMain") { Junction(it, 25.0, n = 40, mainType = Conveyor.Type.NON_ACCUMULATING) })

    // ---- 6. the receiving conveyor stopped, and blocked -------------------------------------------------------

    /** The receiving conveyor is stopped from 2.0 to 2.5 while items transfer. */
    private class StoppedMain(parent: ModelElement) : Junction(parent, 25.0, n = 40) {
        override fun initialize() {
            super.initialize()
            schedule(::stop, 2.0)
            schedule(::start, 2.5)
        }

        private fun stop(e: KSLEvent<Nothing>) = main.stopConveyor()
        private fun start(e: KSLEvent<Nothing>) = main.startConveyor()
    }

    @Test
    @DisplayName("Pinned: the receiving conveyor stopped while items transfer")
    fun stoppedReceiver() = assertPin(Pin(40, 806, 2567925893L), run("stopped") { StoppedMain(it) })

    /**
     *  Each item holds the receiving conveyor's end for 0.5 before leaving, so the receiving conveyor (5 cells)
     *  fills, and items wait to transfer.
     */
    @Test
    @DisplayName("Pinned: the receiving conveyor backed up to the junction")
    fun blockedReceiver() = assertPin(Pin(30, 598, 1082753356L),
        run("blocked") { Junction(it, 25.0, n = 30, mainCells = 5, holdAtShip = 0.5) })

    // ---- 7. items longer than one cell ------------------------------------------------------------------------

    /**
     *  A k-cell item is pushed off the feeder in k moves, and the next item's push-off waits until it is fully on
     *  the receiving conveyor (25 cells a minute here), which takes k more moves: today the time between items is
     *  3k/25, against k/12 without the transfer.
     */
    @Test
    @DisplayName("Throughput of a transfer, by item length")
    fun throughputByLength() {
        for (k in 2..3) {
            val s = run("len$k") { Junction(it, 25.0, cells = k, feederCells = 30, mainCells = 30) }
            assertEquals(3.0 * k / 25.0, s.gap("off", 40, 120), 1e-9, "$k-cell items")
        }
    }

    @Test
    @DisplayName("Pinned: 2-cell items, receiving conveyor at 25, 12 and 6 cells a minute")
    fun twoCellItems() {
        val expected = mapOf(
            25.0 to Pin(150, 3507, 1169645705L),
            12.0 to Pin(150, 3209, 381523370L),
            6.0 to Pin(150, 2995, 2575713369L)
        )
        for ((v, pin) in expected) {
            assertPin(pin, run("two$v") { Junction(it, v, cells = 2, feederCells = 30, mainCells = 30) })
        }
    }

    @Test
    @DisplayName("Pinned: 3-cell items, receiving conveyor at 25, 12 and 6 cells a minute")
    fun threeCellItems() {
        val expected = mapOf(
            25.0 to Pin(150, 4105, 3892319475L),
            12.0 to Pin(150, 3658, 3561961515L),
            6.0 to Pin(138, 3242, 4173859015L)  // 12 items are still on the conveyors when the run ends
        )
        for ((v, pin) in expected) {
            assertPin(pin, run("three$v") { Junction(it, v, cells = 3, feederCells = 30, mainCells = 30) })
        }
    }

    // ---- 8. the reported model ---------------------------------------------------------------------------------

    /**
     *  The reported model: Poisson arrivals at [rate] a minute onto a 20-foot feeder at 12 feet a minute (1-foot
     *  cells), which either exits at its end or transfers onto a 25-foot conveyor at 25 feet a minute.
     */
    private class Reported(parent: ModelElement, rate: Double, val transfer: Boolean) : ProcessModel(parent, "Line") {
        private val feeder = Conveyor.builder(this, "Feeder").conveyorType(Conveyor.Type.ACCUMULATING)
            .velocity(12.0).cellSize(1).maxCellsAllowed(1).firstSegment("Entry", "Merge", 20).build()
        private val main = Conveyor.builder(this, "Main").conveyorType(Conveyor.Type.ACCUMULATING)
            .velocity(25.0).cellSize(1).maxCellsAllowed(1).firstSegment("Merge", "Ship", 25).build()
        val wip = TWResponse(this, "NumInSystem")
        val done = Counter(this, "NumShipped")
        private val tba = ExponentialRV(1.0 / rate, 1)
        private val generator = EntityGenerator({ Part() }, tba, tba)

        private inner class Part : Entity() {
            val p: KSLProcess = process(isDefaultProcess = true) {
                wip.increment()
                val cr = requestConveyor(feeder, "Entry", 1)
                rideConveyor(cr, "Merge")
                if (transfer) {
                    val tr = transferTo(cr, main, "Merge")
                    rideConveyor(tr, "Ship")
                    exitConveyor(tr)
                } else {
                    exitConveyor(cr)
                }
                done.increment()
                wip.decrement()
            }
        }
    }

    private fun reported(rate: Double, transfer: Boolean): Pair<Double, Double> {
        val m = Model("reported_${transfer}_$rate", autoCSVReports = false)
        m.numberOfReplications = 5
        m.lengthOfReplication = 480.0
        val line = Reported(m, rate, transfer)
        m.simulate()
        return Pair(
            line.done.acrossReplicationStatistic.average / 480.0,
            line.wip.acrossReplicationStatistic.average
        )
    }

    @Test
    @DisplayName("The reported model: shipped per minute and number in system")
    fun reportedModel() {
        val expected = mapOf(
            Pair(6.0, false) to Pair(6.005833333333, 10.529835804075),
            Pair(10.0, false) to Pair(9.951250000000, 18.917842422580),
            Pair(6.0, true) to Pair(5.987083333333, 16.655912322844),
            Pair(10.0, true) to Pair(8.297916666667, 422.699495061677),
        )
        for ((case, values) in expected) {
            val (rate, transfer) = case
            val (shipped, inSystem) = reported(rate, transfer)
            assertEquals(values.first, shipped, 1e-6, "rate $rate, transfer $transfer: shipped per minute")
            assertEquals(values.second, inSystem, 1e-6, "rate $rate, transfer $transfer: number in system")
        }
    }

    // ---- 9. where a landing of a longer item must stop short ----------------------------------------------------

    /**
     *  3-cell items transfer at X onto a receiving conveyor X to Near (2 cells) to Far (20), 12 cells a minute,
     *  and ride to Near: the destination is closer than the item is long.
     */
    private class NearDestination(parent: ModelElement) : Rig(parent) {
        val feeder = Conveyor.builder(this, "Feeder").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(3).firstSegment("In", "X", 20).build()
        val main = Conveyor.builder(this, "Main").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(3).firstSegment("X", "Near", 2).nextSegment("Far", 20).build()

        override fun initialize() {
            super.initialize()
            startItems(10)
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(feeder, "In", 3)
            rideConveyor(cr, "X")
            item.mark("end")
            val tr = transferTo(cr, main, "X")
            item.mark("off")
            rideConveyor(tr, "Near")
            item.mark("arrive")
            exitConveyor(tr)
            item.mark("done")
        }
    }

    /**
     *  Today this throws when the first item reaches Near: it reaches its destination while still getting on,
     *  exits, and the next cell move tries to put it on the conveyor again. The same happens without a transfer,
     *  to any item whose ride is shorter than its length.
     */
    @Test
    @DisplayName("Pinned: a longer item whose destination is closer than its length throws")
    fun nearDestination() {
        val e = assertFailsWith<IllegalStateException> { run("near") { NearDestination(it) } }
        assertEquals(true, e.message!!.contains("Request status must be OFF to enter the conveyor"), e.message)
    }

    /**
     *  As [NearDestination], but each item stops at Near for 0.5, holding its exit cell, then rides on to Far:
     *  the next item lands with an exit ahead of it held.
     */
    private class HeldExitAhead(parent: ModelElement) : Rig(parent) {
        val feeder = Conveyor.builder(this, "Feeder").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(3).firstSegment("In", "X", 20).build()
        val main = Conveyor.builder(this, "Main").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(3).firstSegment("X", "Near", 4).nextSegment("Far", 20).build()

        override fun initialize() {
            super.initialize()
            startItems(10)
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(feeder, "In", 3)
            rideConveyor(cr, "X")
            item.mark("end")
            val tr = transferTo(cr, main, "X")
            item.mark("off")
            rideConveyor(tr, "Near")
            item.mark("near")
            delay(0.5)
            rideConveyor(tr, "Far")
            item.mark("arrive")
            exitConveyor(tr)
            item.mark("done")
        }
    }

    @Test
    @DisplayName("Pinned: a longer item landing with an exit ahead held by the item before it")
    fun heldExitAhead() = assertPin(Pin(10, 351, 1931607171L), run("heldExit") { HeldExitAhead(it) })

    /**
     *  3-cell items transfer at X onto X to E2 (2 cells) to Far (20), 12 cells a minute, and ride to Far. Another
     *  entity uses the entry at E2: with [rides] false it holds that entry from 1.9 to 2.6 and leaves without
     *  riding; with [rides] true it asks for it at 1.9 and rides to Far.
     */
    private class SecondEntryNearby(parent: ModelElement, private val rides: Boolean) : Rig(parent) {
        val feeder = Conveyor.builder(this, "Feeder").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(3).firstSegment("In", "X", 20).build()
        val main = Conveyor.builder(this, "Main").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(3).firstSegment("X", "E2", 2).nextSegment("Far", 20).build()

        override fun initialize() {
            super.initialize()
            startItems(6)
            schedule(::other, 1.9)
        }

        private fun other(e: KSLEvent<Nothing>) {
            activate(Item("other").work)
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            if ((item as Item).label == "other") {
                val cr = requestConveyor(main, "E2", 1)
                item.mark("otherAccess")
                if (rides) {
                    rideConveyor(cr, "Far")
                    item.mark("otherArrive")
                } else {
                    delay(0.7)
                }
                exitConveyor(cr)
                item.mark("otherDone")
            } else {
                val cr = requestConveyor(feeder, "In", 3)
                rideConveyor(cr, "X")
                item.mark("end")
                val tr = transferTo(cr, main, "X")
                item.mark("off")
                rideConveyor(tr, "Far")
                item.mark("arrive")
                exitConveyor(tr)
                item.mark("done")
            }
        }
    }

    @Test
    @DisplayName("Pinned: a longer item landing near a second entry held by a request")
    fun secondEntryHeld() = assertPin(Pin(7, 195, 2652891964L), run("e2held") { SecondEntryNearby(it, false) })

    @Test
    @DisplayName("Pinned: a longer item landing near a second entry with an item getting on")
    fun secondEntryRider() = assertPin(Pin(7, 192, 722600338L), run("e2rides") { SecondEntryNearby(it, true) })

    /**
     *  3-cell items transfer onto a circular conveyor X to Y (5 cells) to X (5), 12 cells a minute, and ride to
     *  Y.
     */
    private class CircularReceiver(parent: ModelElement) : Rig(parent) {
        val feeder = Conveyor.builder(this, "Feeder").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(3).firstSegment("In", "X", 20).build()
        val main = Conveyor.builder(this, "Main").conveyorType(Conveyor.Type.ACCUMULATING).velocity(12.0)
            .cellSize(1).maxCellsAllowed(3).firstSegment("X", "Y", 5).nextSegment("X", 5).build()

        override fun initialize() {
            super.initialize()
            startItems(10)
        }

        override suspend fun KSLProcessBuilder.flow(item: Entity) {
            val cr = requestConveyor(feeder, "In", 3)
            rideConveyor(cr, "X")
            item.mark("end")
            val tr = transferTo(cr, main, "X")
            item.mark("off")
            rideConveyor(tr, "Y")
            item.mark("arrive")
            exitConveyor(tr)
            item.mark("done")
        }
    }

    @Test
    @DisplayName("Pinned: longer items transferring onto a circular conveyor")
    fun circularReceiver() = assertPin(Pin(10, 267, 448579105L), run("circular") { CircularReceiver(it) })
}
