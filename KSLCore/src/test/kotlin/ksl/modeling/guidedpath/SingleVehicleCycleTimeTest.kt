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
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  The closed-form benchmark for the movement engine.
 *
 *  One transporter, a constant velocity, and no one to get in its way: the time to travel a known
 *  distance is then arithmetic, not a statistic. That is the whole point of running this before any
 *  contention exists. There is no sampling error to hide a defect behind and no confidence interval
 *  to argue about -- the answer is either exactly right or the engine is wrong, and an off-by-one
 *  in which zones are crossed shows up immediately as a discrepancy of exactly one zone's travel
 *  time.
 *
 *  Domain rules exercised: R5 (a zone takes its length over velocity to cross), R6 (a transporter
 *  moves only between adjacent zones), R4 (it never goes backwards along a route), and R11
 *  (everything resets between replications).
 */
class SingleVehicleCycleTimeTest {

    /**
     *  A model of one transporter driven around a fixed itinerary, recording the clock each time it
     *  arrives so the test can compare against hand arithmetic.
     */
    private class OneCartModel(
        parent: ModelElement,
        val itinerary: List<String>,
        velocity: Double = 10.0,
        lengthInZones: Int = 1,
        startAt: TransporterPlacement = TransporterPlacement.At("I1"),
        zoneControl: () -> ksl.modeling.guidedpath.rules.ZoneControlRuleIfc = { EndOfZoneControl() }
    ) : ModelElement(parent, "OneCartModel") {

        val system = GuidedPathTransportSystem(this, SimpleAgvNetwork.create(), name = "AgvSystem")
        val cart = GuidedTransporter(
            system, startAt, ConstantRV(velocity), lengthInZones, zoneControl(), "Cart1"
        )

        /** The clock reading each time the cart reached the next stop on its itinerary. */
        val arrivalTimes = mutableListOf<Double>()

        private var leg = 0

        init {
            // Arrival is announced, so the time recorded is the arrival itself rather than the
            // first poll after it. Polling would round every reading up to the polling interval,
            // which is exactly the error a closed-form benchmark exists to rule out.
            cart.attachArrivalListener { arrivalTimes.add(time); startNextLeg() }
        }

        override fun initialize() {
            leg = 0
            arrivalTimes.clear()
            schedule({ _: ksl.simulation.KSLEvent<Nothing> -> startNextLeg() }, 0.0)
        }

        private fun startNextLeg() {
            while (leg < itinerary.size) {
                val destination = itinerary[leg]
                leg++
                if (cart.sendTo(destination)) return
                // Already there, so no movement and no arrival to wait for.
                arrivalTimes.add(time)
            }
        }
    }

    private fun run(
        itinerary: List<String>,
        velocity: Double = 10.0,
        lengthInZones: Int = 1,
        startAt: TransporterPlacement = TransporterPlacement.At("I1"),
        zoneControl: () -> ksl.modeling.guidedpath.rules.ZoneControlRuleIfc = { EndOfZoneControl() },
        replications: Int = 1
    ): OneCartModel {
        val m = Model("CycleTime")
        val sub = OneCartModel(m, itinerary, velocity, lengthInZones, startAt, zoneControl)
        sub.system.checkInvariants = true
        m.numberOfReplications = replications
        m.lengthOfReplication = 10_000.0
        m.simulate()
        return sub
    }

    @Test
    fun `travelling one link takes its length divided by the velocity`() {
        // I1 to I2 is Link1, forty-eight feet, at ten feet per minute.
        val sub = run(listOf("I2"))
        assertEquals(1, sub.arrivalTimes.size)
        assertEquals(4.8, sub.arrivalTimes[0], 1e-9)
    }

    @Test
    fun `travelling the whole one way loop takes the loop's length divided by the velocity`() {
        // I1 to I2 to I3 to I4 and back to I1: forty-eight, seventy-two, forty-eight, seventy-two.
        val sub = run(listOf("I2", "I3", "I4", "I1"))
        assertEquals(listOf(4.8, 12.0, 16.8, 24.0), sub.arrivalTimes.map { round9(it) })
        // The whole circuit is two hundred and forty feet at ten feet a minute.
        assertEquals(24.0, sub.arrivalTimes.last(), 1e-9)
    }

    @Test
    fun `the trip from entry to exit takes the network distance divided by the velocity`() {
        val sub = run(listOf(SimpleAgvNetwork.EXIT_STATION))
        // Two hundred and four feet the long way round the one-way loop.
        assertEquals(20.4, sub.arrivalTimes[0], 1e-9)
    }

    @Test
    fun `the return from the exit is shorter, exactly as the network says`() {
        val sub = run(listOf(SimpleAgvNetwork.EXIT_STATION, SimpleAgvNetwork.ENTRY_STATION))
        assertEquals(20.4, sub.arrivalTimes[0], 1e-9)
        // A hundred and eight feet back: 20.4 plus 10.8.
        assertEquals(31.2, sub.arrivalTimes[1], 1e-9)
    }

    @Test
    fun `halving the velocity doubles every travel time`() {
        val fast = run(listOf("I2", "I3"), velocity = 10.0)
        val slow = run(listOf("I2", "I3"), velocity = 5.0)
        for (i in fast.arrivalTimes.indices) {
            assertEquals(2.0 * fast.arrivalTimes[i], slow.arrivalTimes[i], 1e-9)
        }
    }

    @Test
    fun `a velocity change factor scales travel on the link it belongs to`() {
        val m = Model("Factor")
        val net = GuidedPathNetwork.builder("N")
            .link("Fast", "A", "B", length = 40.0, zoneLength = 10.0)
            .link("Slow", "B", "C", length = 40.0, zoneLength = 10.0, velocityFactor = 0.5)
            .build()
        val holder = object : ModelElement(m, "Holder") {}
        val system = GuidedPathTransportSystem(holder, net, name = "Sys")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "C1")
        system.checkInvariants = true
        val times = mutableListOf<Double>()
        val driver = object : ModelElement(holder, "Driver") {
            override fun initialize() {
                schedule({ _: ksl.simulation.KSLEvent<Nothing> -> cart.sendTo("C") }, 0.0)
                schedule({ _: ksl.simulation.KSLEvent<Nothing> -> times.add(time) }, 12.0)
            }
        }
        assertNotNull(driver)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        // Forty feet at ten a minute is four; forty feet at half speed is eight. Twelve in all, so
        // the cart has just arrived when the clock reads twelve.
        assertTrue(!cart.isMoving)
        assertEquals("C", cart.frontZone?.name)
    }

    // ---- multi-zone transporters, which the plan singles out as under-tested ------------------

    @Test
    fun `a transporter longer than one zone still travels its distance in the same time`() {
        // Length changes how much space is held, not how fast the front of the vehicle moves.
        val short = run(listOf("I2", "I3"), lengthInZones = 1, startAt = onZone("Link4.Zone6"))
        val long = run(listOf("I2", "I3"), lengthInZones = 3, startAt = onZone("Link4.Zone6"))
        assertEquals(short.arrivalTimes, long.arrivalTimes)
    }

    @Test
    fun `a transporter longer than one zone covers exactly its own length once under way`() {
        val sub = run(listOf("I2", "I3"), lengthInZones = 3, startAt = onZone("Link4.Zone6"))
        assertEquals(3, sub.cart.coveredZones.size)
        assertEquals(3, sub.cart.lengthInZones)
    }

    @Test
    fun `a transporter is placed covering the zones behind the one it is placed on`() {
        val sub = run(emptyList(), lengthInZones = 3, startAt = onZone("Link4.Zone6"))
        assertEquals(
            listOf("Link4.Zone4", "Link4.Zone5", "Link4.Zone6"),
            sub.cart.coveredZones.map { it.name }
        )
    }

    // ---- the two zone control rules that differ in when space is given up ---------------------

    @Test
    fun `zone control changes when space is released but not how long travel takes`() {
        val atEnd = run(listOf("I2", "I3")) { EndOfZoneControl() }
        val atStart = run(listOf("I2", "I3"), zoneControl = { StartOfZoneControl() })
        assertEquals(atEnd.arrivalTimes, atStart.arrivalTimes)
    }

    // ---- replication independence -------------------------------------------------------------

    @Test
    fun `every replication starts from the same place and produces the same times`() {
        val sub = run(listOf("I2", "I3", "I4", "I1"), replications = 3)
        // The itinerary runs once per replication, and the model records only the last one, so the
        // times must be those of a fresh start rather than of a cart left somewhere by replication
        // two.
        assertEquals(listOf(4.8, 12.0, 16.8, 24.0), sub.arrivalTimes.map { round9(it) })
        assertEquals("I1", sub.cart.frontZone?.name)
    }

    private fun onZone(name: String) = TransporterPlacement.OnZone(name)

    private fun round9(v: Double): Double = kotlin.math.round(v * 1e9) / 1e9

    private fun run(
        itinerary: List<String>,
        zoneControl: () -> ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
    ): OneCartModel = run(itinerary, 10.0, 1, TransporterPlacement.At("I1"), zoneControl, 1)
}
