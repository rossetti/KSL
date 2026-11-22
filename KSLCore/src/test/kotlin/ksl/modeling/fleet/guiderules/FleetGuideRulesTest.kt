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

package ksl.modeling.fleet.guiderules

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.AgvTestNetworks
import ksl.modeling.fleet.AlightHere
import ksl.modeling.fleet.Battery
import ksl.modeling.fleet.Dispatcher
import ksl.modeling.fleet.DoInOrder
import ksl.modeling.fleet.FailureModel
import ksl.modeling.fleet.Line
import ksl.modeling.fleet.LineStop
import ksl.modeling.fleet.Stop
import ksl.modeling.fleet.StopContextIfc
import ksl.modeling.fleet.TourStopActionIfc
import ksl.modeling.fleet.doc.BoardOnePerMinute
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.Disposition
import ksl.modeling.fleet.policies.DispositionPolicyIfc
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
 *  Rules stated in the fleet guide (`ksl-fleet.md`), each checked on the smallest model that shows it:
 *
 *  - a vehicle that breaks down on a trip of its own, to a parking place or to a charger, does not settle where it
 *    stopped: once put right it is offered work again and, with none, its disposition decides afresh where it goes
 *    ("...make vehicles break down?");
 *  - a breakdown is never counted as stranding: `NumVehiclesStranded` and `NumTimesStranded` count only vehicles
 *    that ran out of charge (the same section);
 *  - `Dispatcher.removeAndTerminate` refuses a load already aboard ("...abandon an outstanding request?");
 *  - a boarding action that counts `seatsForRiders`, not `spareCapacity`, keeps free the places later pickups in
 *    the round need ("...run a fixed route: writing your own action").
 *
 *  The breakdown cases use the one-way ring N, E (NearStation), S (DropPoint), W (FarStation), links 100 long, with
 *  the depot on a 20-long spur from N, and one cart at the depot moving at 10. Its first disposition, at 0, sends it
 *  toward FarStation, 320 away. A distance-based failure stops it at 150 travelled, at 15, part way from E to S; the
 *  repair takes 7, so it is put right at 22.
 */
class FleetGuideRulesTest {

    // ---------- breakdowns on a trip of its own ----------

    /** Answers each consultation with the next of `answers` (the last one repeated), recording when it was asked. */
    private class Scripted(val answers: List<Disposition>, val clock: () -> Double) : DispositionPolicyIfc {
        val askedAt = mutableListOf<Double>()
        override fun disposition(vehicle: ksl.modeling.fleet.FleetVehicle): Disposition {
            askedAt.add(clock())
            return answers[minOf(askedAt.size - 1, answers.size - 1)]
        }
    }

    private class Ring(
        parent: ModelElement,
        answers: List<Disposition>,
        failure: FailureModel?,
        battery: Battery?,
        charger: String? = null,
        val loadAt: Double? = null
    ) : ProcessModel(parent, "Ring") {
        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        init {
            if (charger != null) agv.addCharger(charger)
        }

        val policy = Scripted(answers) { time }
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart",
            failureModel = failure, battery = battery
        ).apply {
            homeBase = AgvTestNetworks.DEPOT
            dispositionPolicy = policy
        }
        var deliveredAt = Double.NaN
        var endedAt: String? = null

        inner class Load : Entity("A") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(loadAt!!)
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                transportByFleet(agv, AgvTestNetworks.DROP, origin = AgvTestNetworks.NEAR)
                deliveredAt = time
            }
        }

        override fun initialize() {
            policy.askedAt.clear()
            deliveredAt = Double.NaN
            endedAt = null
            if (loadAt != null) activate(Load().p)
        }

        override fun replicationEnded() {
            endedAt = cart.currentLocationName
        }
    }

    private fun simulate(ring: Ring, model: Model, length: Double = 200.0): Throwable? {
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        return try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
    }

    private val failAt150 = FailureModel.distanceBased(ConstantRV(150.0), ConstantRV(7.0))

    private fun atDropPoint(name: String?) = name == AgvTestNetworks.DROP || name == "S"

    @Test
    @DisplayName("a vehicle that breaks down on its way to a parking place does not settle there: its disposition decides afresh")
    fun breakdownOnTheWayToParking() {
        // asked at 0, it is sent to FarStation; asked again when put right at 22, it is sent to DropPoint
        val model = Model("ParkingFail${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(
            model, listOf(Disposition.MoveTo(AgvTestNetworks.FAR), Disposition.MoveTo(AgvTestNetworks.DROP)),
            failAt150, battery = null
        )
        val error = simulate(ring, model)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf(0.0, 22.0), ring.policy.askedAt, "when the disposition was asked")
        assertTrue(atDropPoint(ring.endedAt), "the cart went where its second disposition sent it, not ${ring.endedAt}")
    }

    @Test
    @DisplayName("a vehicle that breaks down on its way to a charger does not settle there: its disposition decides afresh")
    fun breakdownOnTheWayToACharger() {
        // FarStation is a charger; asked at 0, it is sent to charge there; asked again at 22, it is sent to DropPoint
        val model = Model("ChargerFail${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(
            model, listOf(Disposition.GoCharge(AgvTestNetworks.FAR), Disposition.MoveTo(AgvTestNetworks.DROP)),
            failAt150, Battery(capacity = 10_000.0, chargePerDistance = 1.0), charger = AgvTestNetworks.FAR
        )
        val error = simulate(ring, model)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf(0.0, 22.0), ring.policy.askedAt, "when the disposition was asked")
        assertTrue(atDropPoint(ring.endedAt), "the cart went where its second disposition sent it, not ${ring.endedAt}")
        assertEquals(0.0, ring.cart.numChargingSessions!!.acrossReplicationStatistic.average, "it never reached the charger")
    }

    @Test
    @DisplayName("once put right, a vehicle that broke down on a trip of its own is offered work before its disposition applies")
    fun putRightIsOfferedWorkFirst() {
        // a load asks at 17, during the repair, to go from NearStation to DropPoint; put right at 22, the cart takes
        // it, and its disposition is not asked again until the load is delivered
        val model = Model("WorkFirst${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(
            model, listOf(Disposition.MoveTo(AgvTestNetworks.FAR), Disposition.ParkInPlace),
            failAt150, battery = null, loadAt = 17.0
        )
        val error = simulate(ring, model)
        assertNull(error, "the run stopped: $error")
        assertTrue(!ring.deliveredAt.isNaN(), "the load was never delivered")
        // it breaks down every 150 travelled, so again on its way to the load; the first is the one on its own trip
        assertTrue(ring.cart.numFailures!!.acrossReplicationStatistic.average >= 1.0, "the cart never broke down")
        assertEquals(0.0, ring.policy.askedAt.first(), "the first disposition")
        val between = ring.policy.askedAt.drop(1).filter { it < ring.deliveredAt }
        assertEquals(emptyList(), between, "the disposition was asked before the load, delivered at ${ring.deliveredAt}")
    }

    @Test
    @DisplayName("a breakdown is never counted as stranding, even for a vehicle with a battery")
    fun breakdownIsNotStranding() {
        // the repair takes 1000, so the cart, with a battery and plenty of charge, stands broken down at the end
        val model = Model("NotStranded${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(
            model, listOf(Disposition.MoveTo(AgvTestNetworks.FAR)),
            FailureModel.distanceBased(ConstantRV(150.0), ConstantRV(1000.0)),
            Battery(capacity = 10_000.0, chargePerDistance = 1.0)
        )
        val error = simulate(ring, model, length = 100.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(1.0, ring.cart.numFailures!!.acrossReplicationStatistic.average, "the cart broke down once")
        assertEquals(0.0, ring.agv.numVehiclesStranded.acrossReplicationStatistic.average, "NumVehiclesStranded")
        assertEquals(0.0, ring.cart.numTimesStranded!!.acrossReplicationStatistic.average, "NumTimesStranded")
    }

    @Test
    @DisplayName("a vehicle that runs out of charge is counted as stranded")
    fun runningOutOfChargeIsStranding() {
        // the control: no failure, and a battery of 100 that runs flat 100 into the 320 trip to FarStation
        val model = Model("Stranded${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(
            model, listOf(Disposition.MoveTo(AgvTestNetworks.FAR)), failure = null,
            battery = Battery(capacity = 100.0, chargePerDistance = 1.0)
        )
        val error = simulate(ring, model, length = 100.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(1.0, ring.agv.numVehiclesStranded.acrossReplicationStatistic.average, "NumVehiclesStranded")
        assertEquals(1.0, ring.cart.numTimesStranded!!.acrossReplicationStatistic.average, "NumTimesStranded")
    }

    // ---------- removeAndTerminate of a load aboard ----------

    /** One cart at the depot carries a load from NearStation to DropPoint. Once the load is aboard, the model tries to give it up. */
    private class Aboard(parent: ModelElement) : ProcessModel(parent, "Aboard") {
        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart")
            .apply { homeBase = AgvTestNetworks.DEPOT }
        val log = mutableListOf<String>()
        var task: Dispatcher.TransportTask? = null
        private var tried = false

        inner class Load : Entity("A") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                val t = requestFleetTransport(agv, AgvTestNetworks.DROP, origin = AgvTestNetworks.NEAR)
                task = t
                awaitFleetTransport(t)
                log.add("delivered")
            }
        }

        private fun poll(e: KSLEvent<Nothing>) {
            val t = task
            if (!tried && t != null && cart.numLoadsAboard == 1) {
                tried = true
                val outcome = try {
                    agv.dispatcher.removeAndTerminate(t)
                    "given up"
                } catch (ex: RuntimeException) {
                    "refused"
                }
                log.add(outcome)
            }
            if (!tried) schedule(::poll, 0.5)
        }

        override fun initialize() {
            log.clear()
            task = null
            tried = false
            activate(Load().p)
            schedule(::poll, 0.5)
        }
    }

    @Test
    @DisplayName("removeAndTerminate refuses a load already aboard, which is then delivered")
    fun removeAndTerminateRefusesALoadAboard() {
        val model = Model("Aboard${System.nanoTime()}", autoCSVReports = false)
        val shop = Aboard(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("refused", "delivered"), shop.log)
    }

    // ---------- seatsForRiders ----------

    /** Boards like the guide's action, but counts the vehicle's spare room instead of the seats left for riders. */
    private class BoardBySpareCapacity(val stop: Stop) : TourStopActionIfc {
        override val servesStop = stop
        override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
            val here = this@BoardBySpareCapacity.stop
            with(context) {
                for (ride in here.waitingFor(onwardLocations)) {
                    if (vehicle.spareCapacity <= 0) break
                    delay(1.0)
                    if (ride.isWaiting) takeAboard(ride)
                }
            }
        }
    }

    /**
     *  The loop A, B, C, D (links 100), one bus of capacity 2 at A, speed 10, on one cycle of the circular line, each
     *  stop putting down those bound for it and then running `action`. R1 and R2 wait at B for D. A transport load T
     *  asks at 0 to go from C to D; the batched policy gives the cycle and T to the bus together, so T's pickup at C
     *  is part of the round when the bus boards at B.
     */
    private class Town(parent: ModelElement, action: (Stop) -> TourStopActionIfc) : ProcessModel(parent, "Town") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 50.0, beginDirection = 90.0)
            .link("CD", "C", "D", length = 100.0, zoneLength = 50.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 50.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(
            this, network, name = "Fleet",
            assignmentPolicy = BatchedAssignmentPolicy(window = 1.0, inner = ConsolidatingPolicy())
        )
        val stops = listOf("A", "B", "C", "D").map { Stop(fleet, it, "Stop$it") }
        val line = Line("Circular", stops.map { LineStop(it, DoInOrder(AlightHere(it), action(it))) }, cyclic = true)
        val bus = AgvVehicle(fleet, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Bus", loadCapacity = 2)
        val log = mutableListOf<String>()

        inner class Rider(val tag: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("B")
                rideFrom(stops[1], "D")
                log.add("$tag alighted@$time")
            }
        }

        inner class Load : Entity("T") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("C")
                transportByFleet(fleet, "D", origin = "C")
                log.add("T delivered@$time")
            }
        }

        override fun initialize() {
            log.clear()
            fleet.dispatcher.postLine(line)
            for (tag in listOf("R1", "R2")) activate(Rider(tag).p)
            activate(Load().p)
        }
    }

    private fun runTown(action: (Stop) -> TourStopActionIfc): Pair<Town, Throwable?> {
        val model = Model("Seats${System.nanoTime()}", autoCSVReports = false)
        val town = Town(model, action)
        model.numberOfReplications = 1
        model.lengthOfReplication = 300.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        return town to error
    }

    @Test
    @DisplayName("the guide's action, counting seatsForRiders, leaves free the place a pickup later in the round needs")
    fun seatsForRidersKeepsThePickupsPlace() {
        // with T to collect at C, one of the bus's two places is spoken for: one rider boards at B, and T is carried
        val (town, error) = runTown { BoardOnePerMinute(it) }
        assertNull(error, "the run stopped: $error")
        assertEquals(1.0, town.stops[1].numBoarded.value, "riders boarded at B: ${town.log}")
        assertTrue(town.log.any { it.startsWith("T delivered@") }, "T was never delivered: ${town.log}")
    }

    @Test
    @DisplayName("the same action counting spareCapacity takes the place the later pickup needs")
    fun spareCapacityTakesThePickupsPlace() {
        // the contrast: both riders board at B, filling the bus before it reaches T
        val (town, _) = runTown { BoardBySpareCapacity(it) }
        assertEquals(2.0, town.stops[1].numBoarded.value, "riders boarded at B: ${town.log}")
    }
}
