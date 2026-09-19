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
import ksl.modeling.fleet.policies.ChargeReservePolicy
import ksl.modeling.fleet.policies.ChargeWhenLowDisposition
import ksl.modeling.fleet.policies.MoveToStagingDisposition
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.ParkInPlaceDisposition
import ksl.modeling.fleet.policies.ReturnToHomeBaseDisposition
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathSpace
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Batteries: the arithmetic, the two rates, what it costs the event calendar, and the hazard.
 *
 *  Charge is a **derived quantity**. Nothing schedules an event for it: the level is a closed-form
 *  function of two odometers, computed whenever it is asked for, in the same pattern the guide path
 *  already uses for blocked time. Three consequences follow, and each has a test here:
 *
 *  1. The arithmetic can be checked as an **identity** rather than an approximation, at any instant,
 *     including part way through a zone traversal.
 *  2. Adding a battery must not change how many events a run takes. The subsystem publishes its
 *     events per zone traversal as a performance figure, and a feature that moved it would be
 *     paying for charge in the currency this design exists to protect.
 *  3. Exhaustion is not observed continuously, so it has to be *checked* somewhere. That somewhere
 *     is the zone boundary, which is also the only place a vehicle can be stopped without leaving a
 *     claimed zone with no arrival.
 *
 *  ## The two rates are the point
 *
 *  A parked AGV still draws current. Traction scales with distance and stops when the vehicle does;
 *  hotel load scales with time and does not. Two of the tests below pin each rate to its own axis --
 *  a pure time advance must cost a distance-only battery nothing, and a journey must cost a
 *  time-only battery exactly what standing still would have cost -- because a single-rate
 *  implementation satisfies neither pair.
 *
 *  The second rate also *sharpens the hazard*, and the last two tests are about that rather than
 *  about arithmetic. Under distance-only depletion an idle fleet is safe. With idle draw, a
 *  **lightly loaded** fleet -- the configuration a modeller would assume is benign -- eventually
 *  loses every vehicle, and a vehicle that runs flat on a guide path is not merely out of service:
 *  it stands on the zones it holds and closes every route through them for the rest of the run.
 *  `ChargeReservePolicy` is what prevents that, and the negative control here is what says the
 *  hazard is real rather than hypothetical.
 *
 *  ## Two policies, and neither is sufficient alone
 *
 *  A charging *disposition* is consulted only once the dispatcher has declined to assign, so a fleet
 *  with a queue of work never reaches it -- and a fleet with a queue of work is precisely the one
 *  emptying its batteries. A charge *reserve* is what makes a low vehicle refuse the next load, and
 *  refusing is what makes it idle enough for its disposition to be asked. Each is tested here on its
 *  own, including the case where the disposition alone changes nothing, because a modeller who ships
 *  one of the two has a fleet that looks protected and is not.
 */
class BatteryTest {

    private companion object {
        const val VELOCITY = 10.0
        const val LEG = 100.0
        const val ZONE = 20.0

        /** A two-intersection loop: A to B and back, each leg [LEG] long. */
        fun loop(name: String): GuidedPathNetwork = GuidedPathNetwork.builder(name)
            .link("Out", "A", "B", length = LEG, zoneLength = ZONE, beginDirection = 0.0)
            .link("Back", "B", "A", length = LEG, zoneLength = ZONE, beginDirection = 180.0)
            .build()

        fun mean(r: ResponseCIfc) = r.withinReplicationStatistic.weightedAverage
        fun total(c: CounterCIfc) = c.value
    }

    /** Reads something at a stated instant, so an identity can be asserted mid-journey. */
    private class Probe(parent: ModelElement, val at: Double, val read: () -> Unit) :
        ModelElement(parent, "Probe$at") {
        override fun initialize() {
            schedule({ _: KSLEvent<Nothing> -> read() }, at)
        }
    }

    // ---- 1: the odometer, which everything else is derived from --------------------------------

    private class Shuttle(parent: ModelElement) : ModelElement(parent, "Shuttle") {
        val network = loop("Shuttle")

        init {
            spatialModel = network
        }

        val space = GuidedPathSpace(this, network, name = "Space")
        val car = GuidedTransporter(space, TransporterPlacement.At("A"), ConstantRV(VELOCITY), name = "Car")

        private var trips = 0

        init {
            car.attachArrivalListener { if (++trips < 3) it.sendTo(if (trips % 2 == 0) "B" else "A") }
        }

        override fun initialize() {
            trips = 0
            schedule({ _: KSLEvent<Nothing> -> car.sendTo("B") }, 0.0)
        }
    }

    @Test
    @DisplayName("the odometer is exact between boundaries as well as at them")
    fun theOdometerIsExactAtAnyInstant() {
        val m = Model("Odometer")
        val shop = Shuttle(m)
        val readings = mutableListOf<Pair<Double, Double>>()
        // Deliberately not a zone boundary. Boundaries fall every 2.0 time units at this velocity
        // and zone size, so 5.0 lands half way through the third zone -- which is where a design
        // that credits distance only when a zone is entered would read 40 instead of 50.
        Probe(shop, 5.0) { readings.add(5.0 to shop.car.distanceTravelled) }
        Probe(shop, 25.0) { readings.add(25.0 to shop.car.distanceTravelled) }
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()

        assertEquals(2, readings.size)
        assertEquals(VELOCITY * 5.0, readings[0].second, 1.0e-9, "mid-traversal reading was wrong")
        // Three legs of 100 take 30; at 25 the shuttle is half way through the third.
        assertEquals(VELOCITY * 25.0, readings[1].second, 1.0e-9, "reading on the third leg was wrong")
        assertEquals(3 * LEG, shop.car.distanceTravelled, 1.0e-9, "the total distance was wrong")
    }

    // ---- 2: the charge arithmetic, both rates at once -------------------------------------------

    /** One vehicle that drives A to B once and then parks there, so both regimes are exercised. */
    private class OneTrip(parent: ModelElement, val battery: Battery?, park: Boolean = false) :
        ProcessModel(parent, "OneTrip") {
        val network = loop("OneTrip")

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At("A"), ConstantRV(VELOCITY), name = "Cart", battery = battery
        ).apply {
            dispositionPolicy = if (park) ParkInPlaceDisposition() else MoveToStagingDisposition("B")
        }
    }

    @Test
    @DisplayName("state of charge is the hand calculation, at any instant, with both rates running")
    fun theChargeArithmeticIsAnIdentity() {
        val battery = Battery(capacity = 1000.0, chargePerDistance = 1.0, chargePerTime = 0.5)
        val m = Model("ChargeArithmetic")
        val shop = OneTrip(m, battery)
        val readings = mutableMapOf<Double, Double>()
        for (t in listOf(5.0, 25.0, 60.0)) {
            Probe(shop, t) { readings[t] = shop.cart.stateOfCharge }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()

        // The trip is 100 long at velocity 10, so the vehicle is moving until t = 10 and parked
        // after. Distance is therefore min(10t, 100), and the two rates both apply throughout.
        for ((t, charge) in readings) {
            val distance = minOf(VELOCITY * t, LEG)
            val expected = 1000.0 - distance * 1.0 - t * 0.5
            assertEquals(expected, charge, 1.0e-9, "the charge at t=$t was wrong")
        }
        // The last reading is 60 time units in and 40 of them were spent standing still, which is
        // the half of the arithmetic a distance-only implementation gets wrong.
        assertEquals(1000.0 - 100.0 - 30.0, readings[60.0]!!, 1.0e-9)
    }

    @Test
    @DisplayName("a pure time advance costs a distance-only battery nothing")
    fun timeIsFreeWithoutAnIdleDraw() {
        val battery = Battery(capacity = 1000.0, chargePerDistance = 1.0, chargePerTime = 0.0)
        val m = Model("DistanceOnly")
        val shop = OneTrip(m, battery)
        val readings = mutableMapOf<Double, Double>()
        for (t in listOf(15.0, 90.0)) {
            Probe(shop, t) { readings[t] = shop.cart.stateOfCharge }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()

        assertEquals(900.0, readings[15.0]!!, 1.0e-9, "the trip did not cost exactly its distance")
        assertEquals(
            readings[15.0]!!, readings[90.0]!!, 0.0,
            "seventy-five time units of standing still changed the charge of a battery whose only " +
                    "drain is distance"
        )
    }

    @Test
    @DisplayName("a journey costs a time-only battery exactly what standing still would have cost")
    fun distanceIsFreeWithoutATractionDraw() {
        val battery = Battery(capacity = 1000.0, chargePerDistance = 0.0, chargePerTime = 0.5)
        val moved = mutableMapOf<Double, Double>()
        val parked = mutableMapOf<Double, Double>()
        for ((park, into) in listOf(false to moved, true to parked)) {
            val m = Model(if (park) "TimeOnlyParked" else "TimeOnlyMoved")
            val shop = OneTrip(m, battery, park = park)
            for (t in listOf(5.0, 40.0)) {
                Probe(shop, t) { into[t] = shop.cart.stateOfCharge }
            }
            m.numberOfReplications = 1
            m.lengthOfReplication = 100.0
            m.simulate()
        }
        assertEquals(1000.0 - 2.5, moved[5.0]!!, 1.0e-9)
        for (t in listOf(5.0, 40.0)) {
            assertEquals(
                parked[t]!!, moved[t]!!, 0.0,
                "at t=$t a vehicle that drove 100 feet and one that never left its spot reported " +
                        "different charges, on a battery whose only drain is time"
            )
        }
    }

    // ---- 3: what it costs the event calendar ----------------------------------------------------

    @Test
    @DisplayName("adding a battery schedules no events")
    fun chargeCostsTheCalendarNothing() {
        fun run(battery: Battery?): Pair<Double, Double> {
            val m = Model("Calendar")
            val shop = OneTrip(m, battery)
            m.numberOfReplications = 1
            m.lengthOfReplication = 100.0
            m.simulate()
            return total(shop.agv.spaceSystem.numEventsScheduled) to
                    total(shop.agv.spaceSystem.numZoneTraversals)
        }

        val without = run(null)
        val with = run(Battery(capacity = 1000.0, chargePerDistance = 1.0, chargePerTime = 0.5))
        assertEquals(
            without, with,
            "the same model scheduled a different number of events once its vehicle had a battery, " +
                    "so charge is being stepped by events rather than derived from the odometers"
        )
    }

    // ---- 4: charging, and the hazard the reserve exists for -------------------------------------

    /**
     *  The chapter's shop, on a fleet whose vehicles have to be kept charged.
     *
     *  One cart, so nothing about the outcome depends on which vehicle was chosen; `I7`, the second
     *  cart's home spur, stands in as the charger, which is what a charger is -- an ordinary place
     *  on the network that a vehicle drives to.
     */
    private class ChargedShop(
        parent: ModelElement,
        battery: Battery,
        charge: Boolean,
        reserve: Boolean,
        meanInterarrival: Double = 40.0
    ) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, name = "Agv",
            assignmentPolicy = if (reserve) ChargeReservePolicy(NearestVehiclePolicy())
            else NearestVehiclePolicy()
        )

        init {
            agv.addCharger(SimpleAgvNetwork.AGV2_HOME)
        }

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY),
            name = "Cart", battery = battery
        ).apply {
            homeBase = SimpleAgvNetwork.AGV1_HOME
            dispositionPolicy =
                if (charge) ChargeWhenLowDisposition(threshold = 0.6, otherwise = ReturnToHomeBaseDisposition())
                else ReturnToHomeBaseDisposition()
        }

        var completions = 0

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
                completions++
            }
        }

        private val tba = ExponentialRV(meanInterarrival, streamNum = 1)
        private val generator = EntityGenerator(::Part, tba, tba)

        override fun initialize() {
            completions = 0
        }
    }

    private fun run(shop: (Model) -> ChargedShop, horizon: Double = 2000.0): ChargedShop {
        val m = Model("Charged")
        val s = shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = horizon
        m.simulate()
        return s
    }

    @Test
    @DisplayName("a vehicle that goes to charge comes back and keeps working")
    fun aVehicleChargesAndReturnsToService() {
        val battery = Battery(
            capacity = 1000.0, chargePerDistance = 0.5, chargePerTime = 0.0, chargingRate = 100.0
        )
        val s = run({ ChargedShop(it, battery, charge = true, reserve = true) })

        assertTrue(
            total(s.cart.numChargingSessions!!) > 0.0,
            "the vehicle never charged, so this test is not exercising what it claims to"
        )
        assertTrue(s.completions > 30, "only ${s.completions} loads were delivered")
        assertEquals(
            0.0, mean(s.agv.numVehiclesStranded), 0.0,
            "a fleet with a working charging policy still ran a vehicle flat"
        )
        // The identity that says charging really happened, rather than merely being counted: the
        // vehicle drew several batteries' worth of traction energy over the run and finished it
        // still moving. That is only possible if the top-up at the charger cancels the draw.
        val drawn = s.cart.distanceTravelled * battery.chargePerDistance
        assertTrue(
            drawn > 2.0 * battery.capacity,
            "the vehicle drew $drawn over the run, which is less than two batteries' worth, so " +
                    "nothing here needed a charger and the test proves nothing"
        )
        assertTrue(
            mean(s.cart.minStateOfCharge!!) > 0.0,
            "the vehicle reached zero charge at some point, which a working reserve should prevent"
        )
    }

    @Test
    @DisplayName("a charging policy alone does not save a busy fleet -- it needs the reserve too")
    fun theDispositionAloneIsNotEnough() {
        val battery = Battery(
            capacity = 1000.0, chargePerDistance = 0.5, chargePerTime = 0.0, chargingRate = 100.0
        )
        val s = run({ ChargedShop(it, battery, charge = true, reserve = false) })

        // A disposition is consulted only when the dispatcher has already declined to assign, so a
        // fleet with a queue of work never reaches it -- and a fleet with a queue of work is
        // exactly the one emptying its batteries. This is not a defect in either policy; it is why
        // they are two policies, and why the pair is what the guide recommends.
        assertEquals(
            0.0, total(s.cart.numChargingSessions!!), 0.0,
            "a saturated fleet found time to charge, which would mean a vehicle went idle while " +
                    "work was waiting"
        )
        assertTrue(
            mean(s.agv.numVehiclesStranded) > 0.0,
            "the vehicle neither charged nor ran flat, so something else ended the run early"
        )
    }

    @Test
    @DisplayName("without a reserve a vehicle strands itself, and with one it does not")
    fun theReserveIsWhatPreventsStranding() {
        // Small enough that two deliveries cannot both be made on one charge, and with no charging
        // policy the vehicle has no way to top up. What it does about that is the whole test.
        val battery = Battery(capacity = 260.0, chargePerDistance = 0.5, chargePerTime = 0.0)

        val unguarded = run({ ChargedShop(it, battery, charge = false, reserve = false) })
        assertTrue(
            mean(unguarded.agv.numVehiclesStranded) > 0.0,
            "the negative control did not fire: with no reserve and no way to charge, the vehicle " +
                    "was expected to run flat on the guide path. Without this, the guarded case " +
                    "below proves nothing -- it would pass on a model that never ran low at all"
        )
        assertTrue(
            total(unguarded.cart.numTimesStranded!!) > 0.0,
            "the fleet reported a stranded vehicle but the vehicle did not report being stranded"
        )

        val guarded = run({ ChargedShop(it, battery, charge = false, reserve = true) })
        assertEquals(
            0.0, mean(guarded.agv.numVehiclesStranded), 0.0,
            "a vehicle under a charge reserve still ran flat on the guide path"
        )
        // It refuses work it cannot finish, so the loads it refuses go unserved. That is the honest
        // outcome and the one a modeller must see: the reserve does not conjure capacity, it trades
        // stranded vehicles for unserved demand and says so on the report.
        assertTrue(
            mean(guarded.agv.numTasksNeverAssigned) > 0.0,
            "the guarded run served everything, so the reserve was never binding and this half of " +
                    "the comparison is vacuous"
        )
    }

    @Test
    @DisplayName("idle draw strands a lightly loaded fleet, which distance-only depletion never would")
    fun idleDrawStrandsALightlyLoadedFleet() {
        // No traction draw at all, so nothing about this outcome can be blamed on the work done.
        // The fleet is idle almost all of the time and that is exactly what empties it.
        val battery = Battery(capacity = 100.0, chargePerDistance = 0.0, chargePerTime = 0.1)
        val s = run(
            { ChargedShop(it, battery, charge = false, reserve = false, meanInterarrival = 300.0) },
            horizon = 3000.0
        )

        assertEquals(
            0.0, s.cart.stateOfCharge, 0.0,
            "a vehicle that drew ${battery.chargePerTime} per unit time for 3000 of them, from a " +
                    "capacity of ${battery.capacity}, still had charge left"
        )
        assertTrue(
            mean(s.agv.numVehiclesStranded) > 0.0 || mean(s.agv.numEntitiesNeverResumed) > 0.0,
            "a fleet that ran its batteries flat on idle draw alone finished the run with nothing " +
                    "stranded and nothing left suspended, which cannot be right"
        )
    }

    // ---- 5: configurations the subsystem refuses -------------------------------------------------

    @Test
    @DisplayName("a battery that cannot discharge, and a charger that cannot outpace the draw")
    fun refusedConfigurations() {
        val neverEmpties = assertFailsWith<IllegalArgumentException> {
            Battery(capacity = 100.0, chargePerDistance = 0.0, chargePerTime = 0.0)
        }
        assertTrue("never discharge" in (neverEmpties.message ?: ""), neverEmpties.message ?: "")

        val tooSlow = assertFailsWith<IllegalArgumentException> {
            Battery(
                capacity = 100.0, chargePerDistance = 1.0, chargePerTime = 2.0, chargingRate = 1.5
            )
        }
        assertTrue("never reaches full" in (tooSlow.message ?: ""), tooSlow.message ?: "")
    }

    @Test
    @DisplayName("a reserve with nowhere to reserve for is refused rather than passing everything through")
    fun aReserveWithNoChargerIsRefused() {
        val battery = Battery(capacity = 1000.0, chargePerDistance = 0.5)
        val m = Model("NoCharger")
        val shop = object : ProcessModel(m, "Shop") {
            val network = loop("NoCharger")

            init {
                spatialModel = network
            }

            val agv = AgvSystem(
                this, network, name = "Agv", assignmentPolicy = ChargeReservePolicy(NearestVehiclePolicy())
            )
            val cart = AgvVehicle(
                agv, TransporterPlacement.At("A"), ConstantRV(VELOCITY), name = "Cart", battery = battery
            )

            private inner class Part : Entity() {
                val move = process(isDefaultProcess = true) {
                    currentLocation = network.requireLocation("A")
                    transportByFleet(agv, "B", origin = "A")
                }
            }

            private val tba = ConstantRV(10.0)
            private val generator = EntityGenerator(::Part, tba, tba)
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        val thrown = assertFailsWith<IllegalStateException> { m.simulate() }
        assertTrue("no chargers" in (thrown.message ?: ""), thrown.message ?: "")
        assertTrue(shop.cart.battery != null)
    }
}
