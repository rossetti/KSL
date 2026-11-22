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

package ksl.modeling.fleet.doc

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.AlightHere
import ksl.modeling.fleet.DoInOrder
import ksl.modeling.fleet.Line
import ksl.modeling.fleet.LineStop
import ksl.modeling.fleet.Stop
import ksl.modeling.fleet.StopContextIfc
import ksl.modeling.fleet.TourStopActionIfc
import ksl.modeling.fleet.exceptions.FleetProtocolException
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  The fleet guide's own boarding action (`BoardOnePerMinute`, from "Writing your own action"), run as the guide
 *  shows it: a rider in the list it took is terminated while it boards the one ahead, and the action goes on. A
 *  boarding action that does not check `Ride.isWaiting` after its delay is refused by `takeAboard`, with a message
 *  that names what happened to the rider.
 *
 *  The bus (capacity 3) runs the cyclic line A, B, C, D. R1 and R2 wait at B for D. The bus is at B from 10, boards
 *  R1 at 11 and R2 at 12, and puts both down at D at 32.
 */
class BoardOnePerMinuteTest {

    /** The guide's action as it was, with no check after the delay. */
    private class BoardWithoutCheck(val stop: Stop) : TourStopActionIfc {
        override val servesStop = stop
        override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
            val here = this@BoardWithoutCheck.stop
            with(context) {
                for (ride in here.waitingFor(onwardLocations)) {
                    if (seatsForRiders <= 0) break
                    delay(1.0)
                    takeAboard(ride)
                }
            }
        }
    }

    /** Boards from the whole line, including riders who have joined but not yet begun to wait. */
    private class BoardTheWholeLine(val stop: Stop) : TourStopActionIfc {
        override val servesStop = stop
        override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
            val here = this@BoardTheWholeLine.stop
            with(context) {
                for (ride in here.waiting) takeAboard(ride)
            }
        }
    }

    private class Town(parent: ModelElement, action: (Stop) -> TourStopActionIfc, val lateRider: Boolean) :
        ProcessModel(parent, "Town") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 50.0, beginDirection = 90.0)
            .link("CD", "C", "D", length = 100.0, zoneLength = 50.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 50.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, name = "Fleet")
        val stops = listOf("A", "B", "C", "D").map { Stop(fleet, it, "Stop$it") }
        val line = Line("Circular", stops.map { LineStop(it, DoInOrder(AlightHere(it), action(it))) }, cyclic = true)
        val bus = AgvVehicle(fleet, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Bus", loadCapacity = 3)
        val log = mutableListOf<String>()
        val riders = mutableMapOf<String, Entity>()
        var terminateAt: Double? = null

        inner class Rider(val tag: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("B")
                if (lateRider && tag == "R2") {
                    // joins the line, and begins to wait only after the bus has come
                    val ride = requestRide(stops[1], "D")
                    delay(50.0)
                    awaitRide(ride)
                } else {
                    rideFrom(stops[1], "D")
                }
                log.add("$tag alighted@$time")
            }
        }

        override fun initialize() {
            log.clear()
            riders.clear()
            repeat(4) { fleet.dispatcher.postLine(line) }
            for (tag in listOf("R1", "R2")) Rider(tag).also { riders[tag] = it; activate(it.p) }
            terminateAt?.let { t -> schedule({ _: KSLEvent<Nothing> -> riders.getValue("R2").terminateProcess() }, t) }
        }
    }

    private fun run(
        terminateAt: Double?,
        lateRider: Boolean = false,
        action: (Stop) -> TourStopActionIfc = { BoardOnePerMinute(it) }
    ): Pair<List<String>, Throwable?> {
        val model = Model("BoardOnePerMinute${System.nanoTime()}", autoCSVReports = false)
        val town = Town(model, action, lateRider)
        town.terminateAt = terminateAt
        model.numberOfReplications = 1
        model.lengthOfReplication = 300.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        return town.log.toList() to error
    }

    private fun Throwable.rootCause(): Throwable {
        var c: Throwable = this
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c
    }

    @Test
    @DisplayName("the guide's action boards both riders when nothing happens to them")
    fun boardsBoth() {
        val (log, error) = run(null)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("R1 alighted@32.0", "R2 alighted@32.0"), log)
    }

    @Test
    @DisplayName("the guide's action goes on when a rider in its list is terminated while it boards the one ahead")
    fun riderTerminatedWhileBoarding() {
        val (log, error) = run(11.5)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("R1 alighted@32.0"), log)
    }

    @Test
    @DisplayName("an action that does not check after its delay is refused, the message saying the rider left the line")
    fun refusedForARiderWhoLeft() {
        val (_, error) = run(11.5) { BoardWithoutCheck(it) }
        val cause = error?.rootCause()
        assertTrue(cause is FleetProtocolException, "expected a FleetProtocolException, got $error")
        assertTrue("has left the line" in cause.message!!, cause.message)
    }

    @Test
    @DisplayName("an action that boards a rider not yet waiting is refused, the message saying so")
    fun refusedForARiderNotYetWaiting() {
        val (_, error) = run(null, lateRider = true) { BoardTheWholeLine(it) }
        val cause = error?.rootCause()
        assertTrue(cause is FleetProtocolException, "expected a FleetProtocolException, got $error")
        assertTrue("not yet waiting" in cause.message!!, cause.message)
    }
}
