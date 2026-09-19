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
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.ChargeReservePolicy
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.ReassigningPolicy
import ksl.modeling.fleet.policies.ReconsiderOnInterruption
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.charge
import ksl.modeling.entity.tow
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
import kotlin.test.assertTrue

/**
 *  What happens around a breakdown: somebody is told, somebody comes, and the vehicle is pushed out
 *  of the way if it is in anybody's.
 *
 *  Repair used to be a duration. It is now a **procedure** -- an [InterruptionPolicyIfc] that runs
 *  as a process inside the vehicle's own agent and may wait, branch, and move the vehicle. The
 *  single most important thing about that change is what it did *not* do, and the whole of
 *  `FailureTest` and `BatteryTest` passing unchanged is the assertion: the default policy delays for
 *  the drawn repair time and nothing else, which is exactly what the subsystem did before.
 *
 *  ## The one framework change everything here rests on
 *
 *  A tour leg is now **re-issued until the vehicle actually arrives**. The old loop assumed that
 *  when its movement hold returned, the vehicle was at the stop -- true only while a repaired
 *  vehicle resumed the route it never gave up. The moment a vehicle can be pushed somewhere while
 *  it is broken, that route is dead and the assumption is false; the loop would pick up or set down
 *  in the wrong place.
 *
 *  A tour survives being moved because **a tour names stops, not routes**. That was `ADR-4`'s claim
 *  about the control loop and this is the case that tests it: a vehicle pushed to a spur half way
 *  to a pickup re-routes from the spur and collects the load, with its cursor untouched and its
 *  assignment never revoked.
 *
 *  It also forces the per-carry distance and zone count off the **odometers** rather than off the
 *  route, because a leg that took two journeys has no single route to ask. The odometer difference
 *  is right under interruption and under a mid-leg redirection alike, which the route figure was
 *  not -- and the first test here pins it against arithmetic on an uninterrupted run, which is the
 *  case where the two must agree exactly.
 */
class RecoveryTest {

    private companion object {
        const val VELOCITY = 3.0
        const val TOW_VELOCITY = 1.0

        /**
         *  Entry to exit the long way round: `I1`-`I2` 48, `I2`-`I3` 72, `I3`-`I4` 48, spur 36.
         *  Every carry on this layout covers exactly this, so it is an identity and not an average.
         */
        const val CARRY_DISTANCE = 48.0 + 72.0 + 48.0 + 36.0

        fun mean(r: ResponseCIfc) = r.withinReplicationStatistic.weightedAverage
        fun total(c: CounterCIfc) = c.value
    }

    /** Samples the fleet on a fixed grid, which is how a state with no events of its own is seen. */
    private class Sampler(parent: ModelElement, val every: Double, val take: () -> Unit) :
        ModelElement(parent, "Sampler") {

        private val tick = object : EventActionIfc<Nothing> {
            override fun action(event: KSLEvent<Nothing>) {
                take()
                schedule(this, every)
            }
        }

        override fun initialize() {
            schedule(tick, every)
        }
    }

    /**
     *  Pushes the vehicle to a refuge whenever it is in somebody's way, and counts the pushes.
     *
     *  Deliberately simpler than the shipped `VisitAndAssessPolicy`: no technician, no walk, no
     *  assessment, so that a test comparing it against no towing at all is comparing towing and
     *  nothing else.
     */
    private class TowIfObstructing(
        private val refuge: String,
        private val alwaysTow: Boolean = false
    ) : InterruptionPolicyIfc {

        var tows = 0
            private set
        var obstructingWhenItStopped = 0
            private set

        fun reset() {
            tows = 0
            obstructingWhenItStopped = 0
        }

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            if (interruption.isObstructingNow) obstructingWhenItStopped++
            if (alwaysTow || interruption.isObstructingNow) {
                tows++
                tow(interruption.vehicle, refuge, TOW_VELOCITY)
            }
            if (interruption is Interruption.Failed) {
                delay(interruption.repairTime, suspensionName = "${interruption.vehicle.name}:repair")
            }
        }
    }

    /**
     *  The chapter's shop. `I6` is the first cart's own home spur and is used as the refuge, since a
     *  spur off the loop is exactly where a broken vehicle would be pushed to.
     */
    private class Shop(
        parent: ModelElement,
        failure: FailureModel?,
        policy: InterruptionPolicyIfc?,
        meanInterarrival: Double,
        secondCart: Boolean = false,
        battery: Battery? = null,
        charger: String? = null,
        reserve: Boolean = false,
        assignment: AssignmentPolicyIfc? = null,
        listeners: List<VehicleInterruptionListenerIfc> = emptyList(),
        reconsider: Boolean = false
    ) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, name = "Agv",
            assignmentPolicy = assignment
                ?: if (reserve) ChargeReservePolicy(NearestVehiclePolicy()) else NearestVehiclePolicy()
        )

        init {
            charger?.let { agv.addCharger(it) }
            listeners.forEach { agv.attachInterruptionListener(it) }
            if (reconsider) agv.attachInterruptionListener(ReconsiderOnInterruption(agv.dispatcher))
        }

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY),
            name = "Cart", failureModel = failure, battery = battery
        ).apply {
            homeBase = SimpleAgvNetwork.AGV1_HOME
            if (policy != null) interruptionPolicy = policy
        }

        val cart2: AgvVehicle? = if (!secondCart) null else AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME), ConstantRV(VELOCITY), name = "Cart2"
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

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

    private fun run(name: String, horizon: Double, replications: Int = 1, build: (Model) -> Shop): Shop {
        val m = Model(name)
        val s = build(m)
        m.numberOfReplications = replications
        m.lengthOfReplication = horizon
        m.simulate()
        return s
    }

    // ---- 1: the odometer is the route, when nothing interrupts ----------------------------------

    @Test
    @DisplayName("loaded distance off the odometer is the route's own length, to the digit")
    fun theOdometerAgreesWithTheRouteOnAnUninterruptedRun() {
        val s = run("Odometer", horizon = 2000.0) { m ->
            Shop(m, failure = null, policy = null, meanInterarrival = 90.0)
        }

        assertTrue(s.completions > 10, "only ${s.completions} loads were delivered")
        // Every carry on this layout runs the same way round, so this is an identity. It is what
        // says the change from `route.totalLength` to a difference of odometers moved nothing in
        // the ordinary case -- which is the half of the change that has to be invisible.
        assertEquals(
            CARRY_DISTANCE, mean(s.agv.routeLengthPerTransport), 1.0e-9,
            "a carry covered ${mean(s.agv.routeLengthPerTransport)} rather than the $CARRY_DISTANCE " +
                    "feet from the entry station to the exit station the long way round"
        )
        assertTrue(
            mean(s.agv.zonesTraversedPerTransport) > 0.0,
            "no zones were counted for a carry that covered $CARRY_DISTANCE feet"
        )
    }

    // ---- 2: the payoff -- towing a broken vehicle out of the aisle -------------------------------

    @Test
    @DisplayName("pushing a broken vehicle onto a spur unblocks the fleet behind it")
    fun towingOutOfTheAisleUnblocksTheFleet() {
        fun trial(policy: InterruptionPolicyIfc?): Shop = run("Tow${policy != null}", horizon = 4000.0) { m ->
            Shop(
                m, FailureModel.distanceBased(ConstantRV(150.0), ConstantRV(200.0)),
                policy, meanInterarrival = 40.0, secondCart = true
            )
        }

        val stuck = trial(null)
        val policy = TowIfObstructing(SimpleAgvNetwork.AGV1_HOME, alwaysTow = true)
        val towed = trial(policy)

        assertTrue(total(stuck.cart.numFailures!!) > 0.0, "the first cart never failed")
        assertTrue(total(towed.cart.numFailures!!) > 0.0, "the first cart never failed")
        assertTrue(policy.tows > 5, "the broken cart was pushed only ${policy.tows} times")

        // The second cart has no failure model of its own, so anything that stopped it came from
        // outside itself: on a one-way loop, that is the first cart standing in the way.
        val blockedWhenStuck = mean(stuck.cart2!!.fracTimeBlocked)
        val blockedWhenTowed = mean(towed.cart2!!.fracTimeBlocked)
        assertTrue(
            blockedWhenStuck > 0.0,
            "the control case did not block at all, so there was nothing for towing to improve"
        )
        assertTrue(
            blockedWhenTowed < blockedWhenStuck,
            "pushing the broken cart onto a spur left the healthy one blocked for " +
                    "$blockedWhenTowed of its time against $blockedWhenStuck when it was left in " +
                    "the aisle. The payoff of the whole feature is that this number goes down"
        )
        // And the fleet does more work for it, which is the reason anybody would bother.
        assertTrue(
            towed.completions > stuck.completions,
            "the fleet delivered ${towed.completions} loads with towing against ${stuck.completions} " +
                    "without, so clearing the aisle bought nothing"
        )
    }

    // ---- 2b: when to ask whether it is in the way -------------------------------------------------

    /** Waits, then asks whether anybody is stuck behind the vehicle. */
    private class LooksAfter(private val pause: Double) : InterruptionPolicyIfc {
        var sawObstruction = 0
            private set
        var calls = 0
            private set

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            calls++
            if (pause > 0.0) {
                delay(pause, suspensionName = "${interruption.vehicle.name}:beforeLooking")
            }
            if (interruption.isObstructingNow) sawObstruction++
            if (interruption is Interruption.Failed) {
                delay(interruption.repairTime, suspensionName = "${interruption.vehicle.name}:repair")
            }
        }
    }

    @Test
    @DisplayName("a queue behind a stopped vehicle takes time to form, so asking at once says no")
    fun obstructionIsSeenOnlyAfterTimePasses() {
        fun trial(pause: Double): LooksAfter {
            val policy = LooksAfter(pause)
            run("Obstruction$pause", horizon = 4000.0) { m ->
                Shop(
                    m, FailureModel.distanceBased(ConstantRV(150.0), ConstantRV(200.0)),
                    policy, meanInterarrival = 40.0, secondCart = true
                )
            }
            return policy
        }

        val atOnce = trial(0.0)
        val afterAWhile = trial(40.0)

        assertTrue(atOnce.calls > 5 && afterAWhile.calls > 5, "too few breakdowns to compare")
        // The trap, pinned. `isObstructingNow` is a live query, and at the instant a vehicle stops
        // nobody has had time to arrive behind it: the answer is no, on a layout where the same
        // vehicle goes on to block the fleet for three quarters of the run. A policy that branches
        // on it must let time pass first -- which a realistic one does, because somebody has to be
        // told, be free, and walk there before anybody decides anything.
        assertEquals(
            0, atOnce.sawObstruction,
            "asked at the instant it stopped, the vehicle was found to be obstructing " +
                    "${atOnce.sawObstruction} times out of ${atOnce.calls}. If this is no longer " +
                    "zero the guidance on Interruption.isObstructingNow needs revisiting"
        )
        assertTrue(
            afterAWhile.sawObstruction > 0,
            "after waiting, the vehicle was never once found to be in anybody's way, over " +
                    "${afterAWhile.calls} breakdowns on a layout where it blocks the fleet for most " +
                    "of the run"
        )
    }

    // ---- 3: a moved vehicle still finishes its tour ----------------------------------------------

    @Test
    @DisplayName("a vehicle pushed to a spur mid-tour re-routes and finishes the tour")
    fun aTowedVehicleFinishesItsTour() {
        val policy = TowIfObstructing(SimpleAgvNetwork.AGV2_HOME, alwaysTow = true)
        val caught = mutableMapOf<String, Dispatcher.Task>()
        var lastInHand: Dispatcher.Task? = null

        val s = run("TowedTour", horizon = 8000.0) { m ->
            val shop = Shop(
                m, FailureModel.distanceBased(ConstantRV(250.0), ConstantRV(10.0)),
                policy, meanInterarrival = 90.0
            )
            Sampler(shop, 0.25) {
                lastInHand = shop.cart.currentAssignment?.task ?: lastInHand
                if (!shop.cart.isFailed) return@Sampler
                shop.cart.currentAssignment?.task?.let { caught[it.name] = it }
            }
            shop
        }

        assertTrue(policy.tows > 10, "the vehicle was pushed only ${policy.tows} times")
        assertTrue(caught.isNotEmpty(), "no sample caught the vehicle broken down holding a task")
        // Every task in hand when the vehicle was pushed off the aisle was finished from wherever it
        // was pushed to -- with the single exception the horizon always allows.
        val open = caught.values.filter { it.state != TaskState.COMPLETED }
        assertTrue(
            open.all { it === lastInHand },
            "tasks the vehicle was holding when it was towed did not reach their destination: " +
                    open.joinToString { "${it.name}=${it.state}" }
        )
        assertEquals(
            0.0, total(s.agv.dispatcher.numAssignmentsRevoked), 0.0,
            "moving a vehicle revoked its assignment. It keeps its load: the tour names stops, not " +
                    "routes, so being somewhere else is not a reason to give the work back"
        )
        assertTrue(s.completions > 10, "only ${s.completions} loads were delivered")
    }

    // ---- 4: a scarce technician ------------------------------------------------------------------

    /** Waits for one of a shared pool before doing anything, which is the whole point of it. */
    private class NeedsATechnician(
        private val technicians: ResourceWithQ,
        private val walk: Double
    ) : InterruptionPolicyIfc {

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            val tech = seize(technicians, suspensionName = "${interruption.vehicle.name}:awaitingTechnician")
            delay(walk, suspensionName = "${interruption.vehicle.name}:walking")
            if (interruption is Interruption.Failed) {
                delay(interruption.repairTime, suspensionName = "${interruption.vehicle.name}:repair")
            }
            release(tech)
        }
    }

    private class TwoCartShop(parent: ModelElement, technicianCount: Int) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val technicians = ResourceWithQ(this, name = "Technicians", capacity = technicianCount)
        private val failure = FailureModel.distanceBased(ConstantRV(120.0), ConstantRV(30.0))
        private val policy = NeedsATechnician(technicians, walk = 5.0)

        val carts = listOf(
            SimpleAgvNetwork.AGV1_HOME to "Cart1",
            SimpleAgvNetwork.AGV2_HOME to "Cart2"
        ).map { (home, name) ->
            AgvVehicle(
                agv, TransporterPlacement.At(home), ConstantRV(VELOCITY), name = name,
                failureModel = failure
            ).apply {
                homeBase = home
                interruptionPolicy = policy
            }
        }

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        private val tba = ExponentialRV(30.0, streamNum = 1)
        private val generator = EntityGenerator(::Part, tba, tba)
    }

    @Test
    @DisplayName("one technician serialises two breakdowns, and the wait is on the report")
    fun aScarceTechnicianIsAQueue() {
        fun run(count: Int): TwoCartShop {
            val m = Model("Technicians$count")
            val shop = TwoCartShop(m, count)
            m.numberOfReplications = 1
            m.lengthOfReplication = 4000.0
            m.simulate()
            return shop
        }

        val plenty = run(2)
        val scarce = run(1)

        for (s in listOf(plenty, scarce)) {
            assertTrue(
                s.carts.all { total(it.numFailures!!) > 0.0 },
                "a cart never failed, so this comparison is not exercising anything"
            )
        }
        // With one technician the vehicles queue for it, and the queue is an ordinary resource
        // queue -- which is the argument for making repair a process rather than a duration. The
        // subsystem counts nothing here; `ResourceWithQ` does.
        assertTrue(
            scarce.technicians.waitingQ.timeInQ.withinReplicationStatistic.count > 0.0,
            "with a single technician, no vehicle ever waited for it"
        )
        // The whole out-of-service interval, which is what the row is named for: with one technician
        // it includes the wait, and with two it does not.
        val outWhenScarce = mean(scarce.carts[0].timeOutOfService!!)
        val outWhenPlenty = mean(plenty.carts[0].timeOutOfService!!)
        assertTrue(
            outWhenScarce > outWhenPlenty,
            "a vehicle was out of service for $outWhenScarce with one technician and $outWhenPlenty " +
                    "with two. TimeOutOfService is the whole procedure, so the wait for a scarce " +
                    "technician has to show up in it"
        )
        assertTrue(
            outWhenPlenty >= 35.0,
            "with a technician always free the vehicle should be out for the walk plus the repair, " +
                    "which is 35, but it was out for $outWhenPlenty"
        )
    }

    // ---- 5: what C2 could not express at all -----------------------------------------------------

    @Test
    @DisplayName("a flat vehicle can be pushed to a charger and put back to work")
    fun aFlatVehicleCanBeRecovered() {
        // Deliberately no reserve policy, so the vehicle does run flat. Under the default
        // interruption policy that is the end of its replication: it stands where it stopped,
        // holding its zones. Here it is pushed to the charger instead.
        val battery = Battery(capacity = 260.0, chargePerDistance = 0.5, chargingRate = 50.0)

        val abandoned = run("Abandoned", horizon = 3000.0) { m ->
            Shop(m, failure = null, policy = null, meanInterarrival = 40.0,
                battery = battery, charger = SimpleAgvNetwork.AGV2_HOME)
        }
        val recovered = run("Recovered", horizon = 3000.0) { m ->
            Shop(
                m, failure = null,
                policy = TowToChargerPolicy(), meanInterarrival = 40.0,
                battery = battery, charger = SimpleAgvNetwork.AGV2_HOME
            )
        }

        assertTrue(
            mean(abandoned.agv.numVehiclesStranded) > 0.0,
            "the control case did not strand its vehicle, so there was nothing to recover"
        )
        assertEquals(
            0.0, mean(recovered.agv.numVehiclesStranded), 0.0,
            "a vehicle pushed to a charger and recharged was still reported as stranded at the horizon"
        )
        assertTrue(
            total(recovered.cart.numChargingSessions!!) > 1.0,
            "the recovered vehicle charged only ${total(recovered.cart.numChargingSessions!!)} times"
        )
        assertTrue(
            recovered.completions > 3 * abandoned.completions,
            "the recovered fleet delivered ${recovered.completions} loads against " +
                    "${abandoned.completions} for one abandoned where it stopped. Recovering a flat " +
                    "vehicle is supposed to be worth several times the run"
        )
    }

    /** Pushes a flat vehicle to the nearest charger and fills it. Ignores everything else. */
    private class TowToChargerPolicy : InterruptionPolicyIfc {
        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            if (interruption !is Interruption.OutOfCharge) return
            val vehicle = interruption.vehicle
            val charger = vehicle.system.nearestCharger(vehicle.currentLocationName) ?: return
            tow(vehicle, charger, TOW_VELOCITY)
            charge(vehicle)
        }
    }


    // ---- 7: the two questions, and when each is answerable ----------------------------------------

    /** Records both judgements at the instant the vehicle stopped, before any time has passed. */
    private class AsksBothAtOnce : InterruptionPolicyIfc {
        var calls = 0
            private set
        var obstructingNow = 0
            private set
        var onAThroughRoute = 0
            private set

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            calls++
            if (interruption.isObstructingNow) obstructingNow++
            if (interruption.isOnAThroughRoute) onAThroughRoute++
            if (interruption is Interruption.Failed) {
                delay(interruption.repairTime, suspensionName = "${interruption.vehicle.name}:repair")
            }
        }
    }

    @Test
    @DisplayName("where it stopped is answerable at once; who is stuck behind it is not")
    fun theLayoutQuestionIsAnswerableImmediately() {
        val policy = AsksBothAtOnce()
        run("BothAtOnce", horizon = 4000.0) { m ->
            Shop(
                m, FailureModel.distanceBased(ConstantRV(150.0), ConstantRV(200.0)),
                policy, meanInterarrival = 40.0, secondCart = true
            )
        }

        assertTrue(policy.calls > 5, "only ${policy.calls} breakdowns")
        // The pair, and the whole reason there are two. Asked at the instant it stopped, "is anybody
        // stuck behind me" is no, every time -- nobody has tried to pass yet. "Am I standing where
        // traffic has to pass" is a fact about the layout and is true right away.
        assertEquals(
            0, policy.obstructingNow,
            "asked at the instant it stopped, the vehicle was found to be obstructing " +
                    "${policy.obstructingNow} times out of ${policy.calls}"
        )
        assertTrue(
            policy.onAThroughRoute > 0,
            "a vehicle that stops on the main loop of a one-way layout was never once found to be " +
                    "on a through route, over ${policy.calls} breakdowns"
        )
    }

    @Test
    @DisplayName("a spur is not a through route, and the loop is")
    fun aSpurIsARefuge() {
        val m = Model("Refuge")
        val shop = Shop(m, failure = null, policy = null, meanInterarrival = 1000.0)
        val network = shop.network
        // I6 is the far end of a one-cart parking spur: nothing passes through it, which is what a
        // spur is for. I2 is a junction on the loop; three links meet there.
        assertEquals(
            false, network.requireLocation("I6").zone.isOnAThroughRoute,
            "the dead end of a parking spur was reported as a through route"
        )
        assertEquals(
            true, network.requireLocation("I2").zone.isOnAThroughRoute,
            "a junction with three links meeting at it was not reported as a through route"
        )
        assertEquals(
            false, network.link("Link5")!!.zones.first().isOnAThroughRoute,
            "a zone of a spur was reported as a through route"
        )
        assertEquals(
            true, network.link("Link1")!!.zones.first().isOnAThroughRoute,
            "a zone of the main loop was not reported as a through route"
        )
    }

    // ---- 8: who gets told -------------------------------------------------------------------------

    private class Recorder(val label: String) : VehicleInterruptionListenerIfc {
        val events = mutableListOf<String>()
        override fun stopped(interruption: Interruption) {
            events.add("stopped:${interruption.vehicle.name}")
        }

        override fun returnedToService(interruption: Interruption, outOfServiceFor: Double) {
            events.add("back:${interruption.vehicle.name}:$outOfServiceFor")
        }

        override fun outOfService(interruption: Interruption) {
            events.add("out:${interruption.vehicle.name}")
        }
    }

    @Test
    @DisplayName("every listener is told, and a stop is followed by exactly one outcome")
    fun listenersAreToldAndThereAreTwoOutcomes() {
        val first = Recorder("first")
        val second = Recorder("second")
        val s = run("Listeners", horizon = 3000.0) { m ->
            Shop(
                m, FailureModel.distanceBased(ConstantRV(200.0), ConstantRV(15.0)),
                policy = null, meanInterarrival = 90.0, listeners = listOf(first, second)
            )
        }

        assertTrue(first.events.isNotEmpty(), "the first listener was never told anything")
        assertEquals(
            first.events, second.events,
            "two listeners on the same fleet were told different things"
        )
        val stops = first.events.count { it.startsWith("stopped:") }
        val backs = first.events.count { it.startsWith("back:") }
        val outs = first.events.count { it.startsWith("out:") }
        assertEquals(
            total(s.cart.numFailures!!).toInt(), stops,
            "the fleet reported ${total(s.cart.numFailures!!)} failures but listeners heard $stops"
        )
        // Every stop resolves exactly one way, and under the default policy every failure is put
        // right -- so all of them come back and none is written off. The single exception the
        // horizon always allows is a stop still being dealt with when the clock stopped, which is
        // why the last event may be a `stopped` with nothing after it.
        assertTrue(
            stops - (backs + outs) <= 1,
            "$stops stops produced ${backs + outs} outcomes; at most one may be unresolved, and " +
                    "only the one in hand when the run ended"
        )
        assertEquals(0, outs, "the default policy failed to repair a vehicle")
        // Interleaved rather than batched: a stop is always followed by its own outcome before the
        // next stop, because the vehicle's own agent runs the procedure and does nothing else.
        val kinds = first.events.map { it.substringBefore(':') }
        assertEquals(
            List(backs) { listOf("stopped", "back") }.flatten() +
                    if (stops > backs) listOf("stopped") else emptyList(),
            kinds,
            "stops and outcomes did not alternate: ${first.events}"
        )
    }

    @Test
    @DisplayName("a vehicle broken down between tours is not counted as idle capacity")
    fun theFleetCountsPartition() {
        val s = run("Counts", horizon = 3000.0) { m ->
            Shop(
                m, FailureModel.usageBased(ConstantRV(2.0), ConstantRV(60.0)),
                policy = null, meanInterarrival = 90.0
            )
        }

        assertTrue(total(s.cart.numFailures!!) > 5.0, "too few failures to measure")
        // A usage-based failure comes due at the end of a tour, where the vehicle holds no
        // assignment -- which is exactly the case that used to read as idle. With a sixty-unit
        // repair on a one-cart fleet this is a large fraction of the run.
        val out = mean(s.agv.numVehiclesOutOfService)
        assertTrue(
            out > 0.05,
            "a one-cart fleet spending sixty time units under repair after every second task was " +
                    "out of service for only $out of a vehicle on average"
        )
        // The three counts partition the fleet at every instant, so their time-weighted averages
        // sum to the fleet size. That is what stops spare capacity being double-counted.
        val total = mean(s.agv.numVehiclesOnTask) + mean(s.agv.numVehiclesIdle) + out
        assertEquals(
            1.0, total, 1.0e-9,
            "on task, idle and out of service averaged $total over a fleet of one"
        )
    }

    /**
     *  One load, two carts, and nothing else happening.
     *
     *  The cart on `I7` is the nearer of the two to the entry station -- 126 feet against 198 -- so
     *  it is the one given the load, and it is the one that breaks down. It fails three feet out,
     *  which puts it on its own parking spur rather than in the loop, so the other cart's route to
     *  the entry station is clear and nothing about this outcome is about blocking.
     */
    private class QuietShop(parent: ModelElement, reconsider: Boolean) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, name = "Agv",
            assignmentPolicy = ReassigningPolicy(improvementThreshold = 1.0)
        )

        init {
            if (reconsider) agv.attachInterruptionListener(ReconsiderOnInterruption(agv.dispatcher))
        }

        val failing = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME), ConstantRV(VELOCITY),
            name = "Failing",
            failureModel = FailureModel.distanceBased(ConstantRV(3.0), ConstantRV(2000.0))
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

        val spare = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY), name = "Spare"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        var deliveredAt = Double.NaN
        var carriedBy = ""

        private inner class Part : Entity() {
            val move = process {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val r = transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
                deliveredAt = r.totalTime
                carriedBy = r.vehicleName
            }
        }

        override fun initialize() {
            deliveredAt = Double.NaN
            carriedBy = ""
            activate(Part().move)
        }
    }

    @Test
    @DisplayName("on a quiet fleet, a load committed to a broken vehicle is never delivered unless the dispatcher is told")
    fun theDispatcherHearsAboutItOnlyWhenAsked() {
        fun trial(reconsider: Boolean): QuietShop {
            val m = Model("Quiet$reconsider")
            val shop = QuietShop(m, reconsider)
            m.numberOfReplications = 1
            m.lengthOfReplication = 4000.0
            m.simulate()
            return shop
        }

        val silent = trial(false)
        val told = trial(true)

        assertTrue(total(silent.failing.numFailures!!) > 0.0, "the cart never failed")

        // The cost of nobody being told, and it is not a delay -- it is the whole load. A vehicle
        // that stops keeps its assignment, declares nothing and posts nothing, so nothing inside the
        // subsystem wakes the dispatcher. With no other traffic to wake it either, the re-tasking
        // rule is never asked, the healthy cart stands idle at its spur for the whole run, and the
        // load is still suspended when the horizon falls.
        assertTrue(
            silent.deliveredAt.isNaN(),
            "the load was delivered at ${silent.deliveredAt} without anything having woken the " +
                    "dispatcher, so this comparison is not measuring what it claims to"
        )
        assertEquals(
            0.0, total(silent.agv.dispatcher.numAssignmentsRevoked), 0.0,
            "an assignment was revoked with nothing to prompt a dispatching pass"
        )
        assertTrue(
            mean(silent.agv.numEntitiesNeverResumed) > 0.0,
            "the load was neither delivered nor reported as left suspended"
        )

        // Told, the dispatcher takes the task off the stopped vehicle and gives it to the one that
        // can do it. Delivered at 136 on this layout, against a repair of 2000 it would otherwise
        // have waited out.
        assertEquals(
            "Spare", told.carriedBy,
            "the load was carried by (${told.carriedBy}) rather than by the healthy cart"
        )
        assertTrue(
            told.deliveredAt < 500.0,
            "the load took ${told.deliveredAt}, which is long enough that it waited for the repair"
        )
        assertEquals(
            1.0, total(told.agv.dispatcher.numAssignmentsRevoked), 0.0,
            "the task was not taken off the broken vehicle exactly once"
        )
    }

    // ---- 6: the replication boundary --------------------------------------------------------------

    @Test
    @DisplayName("nothing about a recovery survives a replication boundary")
    fun threeReplicationsAreIdentical() {
        val policy = TowIfObstructing(SimpleAgvNetwork.AGV1_HOME, alwaysTow = true)
        val s = run("Reset", horizon = 2000.0, replications = 3) { m ->
            val shop = Shop(
                m, FailureModel.usageBased(ConstantRV(2.0), ConstantRV(5.0)), policy,
                meanInterarrival = 90.0
            )
            shop
        }

        val across = s.cart.numFailures!!.acrossReplicationStatistic
        assertEquals(3.0, across.count, 0.0, "three replications did not produce three observations")
        assertTrue(across.average > 0.0, "no replication produced a failure")
        // Common random numbers make the three replications different experiments, so this is not
        // an equality. What it rules out is state carried over a boundary -- a tow left half done,
        // an odometer not cleared, a vehicle still holding the out-of-service queue -- which would
        // show as one replication unlike the others rather than as ordinary spread.
        assertTrue(
            across.min > 0.0,
            "a replication produced no failures at all while the others did, which is the shape a " +
                    "leaked state has: ${across.min} to ${across.max}"
        )
        assertEquals(
            0.0, mean(s.agv.numVehiclesFailedAtHorizon) % 1.0, 1.0e-12,
            "the number of vehicles under repair at the horizon was not a whole number"
        )
    }
}
