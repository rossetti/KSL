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

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 *  An entity terminated while a fleet is to carry it, is carrying it, or while it waits for or rides a line:
 *  the fleet is free of it at once. A task that was never collected is taken off the board and cancelled; a
 *  vehicle committed to it is released and does what it would do on any other revocation. A load aboard is
 *  taken off where the vehicle is, its task cancelled and the vehicle's commitment ended. A rider standing at a
 *  stop leaves the line; a rider aboard gets off. The vehicle finishes the move, loading or unloading it has
 *  begun, and the closing audit balances.
 *
 *  Transport: the ring N, E, S, W with links 100 long and a 20-long spur to the depot at Park; one cart at the
 *  depot, speed 10, so a link takes 10 and the spur 2. A load at the near station (E) bound for the drop (S),
 *  posted at 0, is reached at 12 and delivered at 22, and the cart is home again at 44.
 *
 *  Rides: the loop A, B, C, D with links 100 long; one bus at A, speed 10, running the circular line. A rider
 *  joining at B bound for D at 0 boards at 10 and alights at 30.
 */
class TerminatedInFleetTest {

    // ------------------------------------------------------------------------------------------------------
    // transport rig
    // ------------------------------------------------------------------------------------------------------

    private class Shop(
        parent: ModelElement, capacity: Int = 1,
        policy: ksl.modeling.fleet.policies.AssignmentPolicyIfc = ksl.modeling.fleet.policies.NearestVehiclePolicy()
    ) : ProcessModel(parent, "Shop") {
        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart", loadCapacity = capacity
        ).apply { homeBase = AgvTestNetworks.DEPOT }

        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()
        val actors = mutableMapOf<String, Entity>()
        val tasks = mutableMapOf<String, Dispatcher.TransportTask>()
        private val myBoardTriggers = mutableMapOf<Long, () -> Unit>()
        private val myAlightTriggers = mutableMapOf<Long, () -> Unit>()

        /**
         *  Asks for transport at [from] from [origin] to [destination]; when [split] is given, waits that long
         *  between asking and waiting to be carried.
         */
        inner class Load(
            val tag: String, val from: Double, val origin: String, val destination: String,
            val loading: Double, val unloading: Double, val split: Double?
        ) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation(origin)
                val task = requestFleetTransport(
                    agv, destination, origin, ConstantRV(loading), ConstantRV(unloading)
                )
                tasks[tag] = task
                if (split != null) {
                    delay(split)
                    log.add("$tag ready@$time")
                }
                awaitFleetTransport(task)
                log.add("$tag delivered@$time")
            }
        }

        /** Asks for two transports from [origin] at 0, to S and to W, and waits for neither. */
        inner class TwoAsks(val tag: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                tasks["$tag-1"] = requestFleetTransport(agv, AgvTestNetworks.DROP)
                tasks["$tag-2"] = requestFleetTransport(agv, AgvTestNetworks.FAR)
                delay(100.0)
            }
        }

        fun twoAsks(tag: String) = TwoAsks(tag).also {
            actors[tag] = it
            activate(it.p)
        }

        fun load(
            tag: String, from: Double = 0.0, origin: String = AgvTestNetworks.NEAR,
            destination: String = AgvTestNetworks.DROP, loading: Double = 0.0, unloading: Double = 0.0,
            split: Double? = null
        ) = Load(tag, from, origin, destination, loading, unloading, split).also {
            actors[tag] = it
            activate(it.p)
        }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        fun terminate(tag: String, t: Double) = at(t) { actors.getValue(tag).terminateProcess() }

        /** Terminates [tag] in an event of its own at the instant it goes aboard, ahead of its resumption. */
        fun terminateWhenBoarded(tag: String) {
            myBoardTriggers[actors.getValue(tag).id] = { terminateNow(tag) }
        }

        /** Terminates [tag] in an event of its own at the instant it is set down, ahead of its resumption. */
        fun terminateWhenSetDown(tag: String) {
            myAlightTriggers[actors.getValue(tag).id] = { terminateNow(tag) }
        }

        private fun terminateNow(tag: String) {
            schedule({ _: KSLEvent<Nothing> ->
                log.add("$tag terminated@$time")
                actors.getValue(tag).terminateProcess()
            }, 0.0, priority = 0)
        }

        fun onAnimation(event: AnimationEvent) {
            when (event) {
                is AnimationEvent.VehicleLoadBoarded -> myBoardTriggers.remove(event.entityId)?.invoke()
                is AnimationEvent.VehicleLoadAlighted -> myAlightTriggers.remove(event.entityId)?.invoke()
                else -> Unit
            }
        }

        fun probe(t: Double) = at(t) {
            log.add("probe@$t at=${cart.currentLocationName} aboard=${cart.numLoadsAboard} task=${cart.currentAssignment?.task?.name}")
        }

        override fun initialize() {
            log.clear()
            actors.clear()
            tasks.clear()
            myBoardTriggers.clear()
            myAlightTriggers.clear()
            for (s in setups) s()
        }
    }

    private class ShopOutcome(val logs: List<List<String>>, val error: Throwable?, val shop: Shop) {
        val log: List<String> get() = logs.first()
    }

    private fun runShop(
        reps: Int = 1, capacity: Int = 1,
        policy: ksl.modeling.fleet.policies.AssignmentPolicyIfc = ksl.modeling.fleet.policies.NearestVehiclePolicy(),
        setup: Shop.() -> Unit
    ): ShopOutcome {
        val logs = mutableListOf<List<String>>()
        val model = Model("FleetTermination${System.nanoTime()}", autoCSVReports = false)
        lateinit var shop: Shop
        model.animationSink = object : AnimationSink {
            override val isActive = true
            override fun emit(event: AnimationEvent) = shop.onAnimation(event)
        }
        shop = Shop(model, capacity, policy)
        shop.setups.add(setup)
        shop.setups.add { at(199.0) { logs.add(log.toList()) } }
        model.numberOfReplications = reps
        model.lengthOfReplication = 200.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        if (logs.isEmpty()) logs.add(shop.log.toList())
        return ShopOutcome(logs, error, shop)
    }

    /** The run completed, the closing audit included, and nothing of [tags] is left with the fleet. */
    private fun assertFreed(case: String, o: ShopOutcome, vararg tags: String) {
        assertNull(o.error, "$case: the run stopped: ${o.error}")
        val system = o.shop.agv
        for (tag in tags) {
            val e = o.shop.actors.getValue(tag)
            assertFalse(system.awaitingPickupHoldQ.contains(e), "$case: $tag still awaits pickup")
            assertFalse(system.inTransitHoldQ.contains(e), "$case: $tag is still in transit")
            assertFalse(o.shop.cart.body.manifest.contains(e), "$case: $tag is still aboard the cart")
        }
    }

    private fun assertCounts(
        case: String, o: ShopOutcome, posted: Int, completed: Int, cancelled: Int, made: Int, revoked: Int
    ) {
        val d = o.shop.agv.dispatcher
        assertEquals(posted.toDouble(), d.numTasksPosted.value, "$case: tasks posted")
        assertEquals(completed.toDouble(), d.numTasksCompleted.value, "$case: tasks completed")
        assertEquals(cancelled.toDouble(), d.numTasksCancelled.value, "$case: tasks cancelled")
        assertEquals(made.toDouble(), d.numAssignmentsMade.value, "$case: assignments made")
        assertEquals(revoked.toDouble(), d.numAssignmentsRevoked.value, "$case: assignments revoked")
    }

    // ---------- controls ----------

    @Test
    @DisplayName("control: a load posted at 0 is delivered at 22, and the cart is home at 44 (a probe names the end of the link the cart is on)")
    fun transportControl() {
        val o = runShop {
            load("A")
            probe(30.0)
            probe(45.0)
        }
        assertFreed("control", o, "A")
        assertEquals(listOf("A delivered@22.0", "probe@30.0 at=W aboard=0 task=null", "probe@45.0 at=Park aboard=0 task=null"), o.log)
        assertCounts("control", o, posted = 1, completed = 1, cancelled = 0, made = 1, revoked = 0)
    }

    // ---------- waiting for a vehicle ----------

    @Test
    @DisplayName("terminated while its task waits on the board: the task is cancelled and no vehicle comes for it")
    fun whilePosted() {
        val o = runShop {
            load("A")
            load("B", from = 1.0)
            terminate("B", 5.0)
            probe(45.0)
        }
        assertFreed("posted", o, "B")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("B").state)
        assertEquals(listOf("A delivered@22.0", "probe@45.0 at=Park aboard=0 task=null"), o.log)
        assertCounts("posted", o, posted = 2, completed = 1, cancelled = 1, made = 1, revoked = 0)
    }

    @Test
    @DisplayName("terminated while the cart comes for it: the cart is released, given the next load, and goes on to the same pickup")
    fun whileFetched() {
        val o = runShop {
            load("A")
            load("B", from = 1.0)
            terminate("A", 5.0)
        }
        assertFreed("fetched", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        assertEquals(listOf("B delivered@22.0"), o.log)
        assertCounts("fetched", o, posted = 2, completed = 1, cancelled = 1, made = 2, revoked = 1)
    }

    @Test
    @DisplayName("terminated while being loaded: the cart finishes loading, takes nothing aboard, and carries the next load")
    fun whileLoading() {
        val o = runShop {
            load("A", loading = 5.0)
            load("B", from = 1.0, loading = 5.0)
            terminate("A", 14.0)
        }
        assertFreed("loading", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        // B is the cart's from 14; the cart finishes A's loading at 17, loads B until 22, and is at S at 32.
        assertEquals(listOf("B delivered@32.0"), o.log)
        assertCounts("loading", o, posted = 2, completed = 1, cancelled = 1, made = 2, revoked = 1)
    }

    @Test
    @DisplayName("removed and terminated from the board while being loaded: the cart finishes loading and takes nothing aboard")
    fun removedAndTerminatedWhileLoading() {
        val o = runShop {
            load("A", loading = 5.0)
            load("B", from = 1.0, loading = 5.0)
            at(14.0) { (agv.dispatcher.taskQ as TaskQ).removeAndTerminate(tasks.getValue("A")) }
        }
        assertFreed("removed while loading", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        assertEquals(listOf("B delivered@32.0"), o.log)
        assertCounts("removed while loading", o, posted = 2, completed = 1, cancelled = 1, made = 2, revoked = 1)
    }


    @Test
    @DisplayName("removing and terminating every task while the cart loads one ends both: neither load is delivered")
    fun removeAllAndTerminateWhileLoading() {
        val o = runShop {
            load("A", loading = 5.0)
            load("B", from = 1.0, loading = 5.0)
            at(14.0) { (agv.dispatcher.taskQ as TaskQ).removeAllAndTerminate() }
        }
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("B").state)
        assertEquals(emptyList(), o.log)
    }

    @Test
    @DisplayName("a commitment taken back while the cart loads: the cart finishes loading, and boards the load under its new commitment")
    fun revokedWhileLoading() {
        val o = runShop {
            load("A", loading = 5.0)
            at(14.0) { agv.dispatcher.revoke(cart.currentAssignment!!) }
        }
        assertFreed("revoked while loading", o, "A")
        assertEquals(TaskState.COMPLETED, o.shop.tasks.getValue("A").state)
        // Given straight back to the only cart at 14; loading ends at 17 and the cart is at S at 27.
        assertEquals(listOf("A delivered@27.0"), o.log)
        assertCounts("revoked while loading", o, posted = 1, completed = 1, cancelled = 0, made = 2, revoked = 1)
    }

    @Test
    @DisplayName("a round whose only pickup was cancelled carried nothing, and the loads-per-round statistic says so")
    fun aCancelledPickupIsNotCarried() {
        val o = runShop(capacity = 2) {
            load("A")
            terminate("A", 5.0)
        }
        assertFreed("cancelled pickup", o, "A")
        val perTour = o.shop.cart.loadsPerTour!!.withinReplicationStatistic
        assertEquals(1.0, perTour.count, "rounds observed")
        assertEquals(0.0, perTour.weightedAverage, "loads carried in the round")
    }

    @Test
    @DisplayName("a commitment revoked while the cart waits at the pickup for its load, and given straight back: the cart goes on waiting")
    fun revokedWhileWaitingForTheLoad() {
        val o = runShop {
            load("A", split = 30.0)
            at(20.0) { agv.dispatcher.revoke(cart.currentAssignment!!) }
        }
        assertFreed("revoked while waiting", o, "A")
        assertEquals(listOf("A ready@30.0", "A delivered@40.0"), o.log)
        assertCounts("revoked while waiting", o, posted = 1, completed = 1, cancelled = 0, made = 2, revoked = 1)
    }

    @Test
    @DisplayName("terminated while its task is being auctioned: the auction's result for it is dropped")
    fun terminatedDuringAnAuction() {
        val o = runShop(policy = ksl.modeling.fleet.policies.ContractNetAssignmentPolicy(5.0)) {
            load("A")
            terminate("A", 2.0)
            probe(10.0)
        }
        assertFreed("auction", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        assertEquals(listOf("probe@10.0 at=Park aboard=0 task=null"), o.log)
        assertCounts("auction", o, posted = 1, completed = 0, cancelled = 1, made = 0, revoked = 0)
    }

    @Test
    @DisplayName("an entity with two transports asked for and not yet waited for is terminated: both are withdrawn")
    fun twoRequestsWithdrawn() {
        val o = runShop {
            twoAsks("A")
            terminate("A", 5.0)
            probe(45.0)
        }
        assertFreed("two requests", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A-1").state)
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A-2").state)
        assertEquals(listOf("probe@45.0 at=Park aboard=0 task=null"), o.log)
        assertCounts("two requests", o, posted = 2, completed = 0, cancelled = 2, made = 1, revoked = 1)
    }

    // ---------- aboard ----------

    @Test
    @DisplayName("terminated as it goes aboard, before it resumes: it is taken off at once and the cart goes on empty")
    fun whenBoardedBeforeResuming() {
        val o = runShop {
            load("A")
            terminateWhenBoarded("A")
            probe(14.0)
            probe(45.0)
        }
        assertFreed("boarded", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        assertEquals(
            listOf("A terminated@12.0", "probe@14.0 at=S aboard=0 task=null", "probe@45.0 at=Park aboard=0 task=null"),
            o.log
        )
        assertCounts("boarded", o, posted = 1, completed = 0, cancelled = 1, made = 1, revoked = 1)
    }

    @Test
    @DisplayName("terminated while carried: it is taken off at once, and the cart finishes the move and goes home")
    fun whileCarried() {
        val o = runShop(reps = 2) {
            load("A")
            terminate("A", 15.0)
            probe(16.0)
            probe(45.0)
        }
        assertFreed("carried", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        val expected = listOf("probe@16.0 at=S aboard=0 task=null", "probe@45.0 at=Park aboard=0 task=null")
        assertEquals(expected, o.logs[0], "first replication")
        assertEquals(expected, o.logs[1], "second replication")
    }

    @Test
    @DisplayName("terminated while carried with another load waiting: the cart is free for it at once")
    fun whileCarriedWithAnotherWaiting() {
        val o = runShop {
            load("A")
            load("B", from = 1.0, origin = AgvTestNetworks.FAR)
            terminate("A", 15.0)
        }
        assertFreed("carried, B waiting", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        // Given B at 15 on the ES link and sent on to W: S at 22, W at 32, and round by N and E to S at 62.
        assertEquals(listOf("B delivered@62.0"), o.log)
        assertCounts("carried, B waiting", o, posted = 2, completed = 1, cancelled = 1, made = 2, revoked = 1)
    }

    @Test
    @DisplayName("terminated while being unloaded: it is off at once, the cart finishes unloading, and the task is cancelled")
    fun whileUnloading() {
        val o = runShop {
            load("A", unloading = 5.0)
            terminate("A", 24.0)
            probe(25.0)
            probe(50.0)
        }
        assertFreed("unloading", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        assertEquals(listOf("probe@25.0 at=S aboard=0 task=null", "probe@50.0 at=Park aboard=0 task=null"), o.log)
        assertCounts("unloading", o, posted = 1, completed = 0, cancelled = 1, made = 1, revoked = 1)
    }

    @Test
    @DisplayName("terminated as it is set down, before it resumes: the delivery stands")
    fun whenSetDownBeforeResuming() {
        val o = runShop {
            load("A")
            terminateWhenSetDown("A")
        }
        assertFreed("set down", o, "A")
        assertEquals(TaskState.COMPLETED, o.shop.tasks.getValue("A").state)
        assertEquals(listOf("A terminated@22.0"), o.log)
        assertCounts("set down", o, posted = 1, completed = 1, cancelled = 0, made = 1, revoked = 0)
    }

    // ---------- asking and waiting apart ----------

    @Test
    @DisplayName("a load that asks, then waits later: a cart that arrives first waits for it at the pickup")
    fun splitVerbsCartArrivesFirst() {
        val o = runShop {
            load("A", split = 30.0)
            probe(20.0)
        }
        assertFreed("split", o, "A")
        assertEquals(
            listOf("probe@20.0 at=E aboard=0 task=${o.shop.tasks.getValue("A").name}", "A ready@30.0", "A delivered@40.0"),
            o.log
        )
        assertCounts("split", o, posted = 1, completed = 1, cancelled = 0, made = 1, revoked = 0)
    }

    @Test
    @DisplayName("a load that asks, then is terminated before it waits: the cart waiting for it is released and goes home")
    fun splitVerbsTerminatedBeforeWaiting() {
        val o = runShop {
            load("A", split = 30.0)
            terminate("A", 20.0)
            probe(53.0)
        }
        assertFreed("split, terminated", o, "A")
        assertEquals(TaskState.CANCELLED, o.shop.tasks.getValue("A").state)
        // From E at 20: S 30, W 40, N 50, the depot at 52.
        assertEquals(listOf("probe@53.0 at=Park aboard=0 task=null"), o.log)
        assertCounts("split, terminated", o, posted = 1, completed = 0, cancelled = 1, made = 1, revoked = 1)
    }

    @Test
    @DisplayName("a transport to where the load already is, on a guide path: the cart comes, and sets it down where it is")
    fun transportToWhereItIsOnAGuidePath() {
        val o = runShop {
            load("A", destination = AgvTestNetworks.NEAR)
        }
        assertFreed("same place", o, "A")
        assertEquals(TaskState.COMPLETED, o.shop.tasks.getValue("A").state)
        assertEquals(listOf("A delivered@12.0"), o.log)
    }

    private class Yard(parent: ModelElement) : ProcessModel(parent, "Yard") {
        val plane = ksl.modeling.spatial.Euclidean2DPlane()
        val places = listOf(plane.Point(0.0, 0.0, "Depot"), plane.Point(100.0, 0.0, "Press"))

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(this, plane, places, name = "Fleet")

        val cart = FreePathVehicle(fleet, "Depot", ConstantRV(10.0), name = "Cart", stepSize = 5.0)
        val log = mutableListOf<String>()

        var destination = "Press"
        val terminations = mutableListOf<Double>()
        val probes = mutableListOf<Double>()

        inner class Load : Entity("Load") {
            val p = process(isDefaultProcess = true) {
                currentLocation = fleet.space.requireLocation("Press")
                transportByFleet(fleet, destination, origin = "Press")
                log.add("delivered@$time")
            }
        }

        override fun initialize() {
            log.clear()
            val load = Load()
            activate(load.p)
            for (t in terminations) schedule({ _: KSLEvent<Nothing> -> load.terminateProcess() }, t)
            for (t in probes) schedule({ _: KSLEvent<Nothing> ->
                // A free-path vehicle between named places reports an unnamed point.
                val at = cart.currentLocationName.let { if (it in setOf("Depot", "Press")) it else "between" }
                log.add("probe@$t at=$at aboard=${cart.numLoadsAboard}")
            }, t)
        }
    }

    @Test
    @DisplayName("a transport to where the load already is, on a free path: the cart comes, and sets it down where it is")
    fun transportToWhereItIsOnAFreePath() {
        val model = Model("FreePathSamePlace${System.nanoTime()}", autoCSVReports = false)
        val yard = Yard(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        assertEquals(listOf("delivered@10.0"), yard.log)
    }

    @Test
    @DisplayName("terminated while carried on a free path: it is taken off at once, and the cart finishes the move")
    fun whileCarriedOnAFreePath() {
        val model = Model("FreePathCarried${System.nanoTime()}", autoCSVReports = false)
        val yard = Yard(model)
        yard.destination = "Depot"
        yard.terminations.add(15.0)
        yard.probes.addAll(listOf(16.0, 21.0))
        model.numberOfReplications = 2
        model.lengthOfReplication = 100.0
        model.simulate()
        // Fetched from the depot by 10, carried back towards it, terminated at 15 half way.
        assertEquals(listOf("probe@16.0 at=between aboard=0", "probe@21.0 at=Depot aboard=0"), yard.log)
        assertEquals(1.0, yard.fleet.dispatcher.numTasksCancelled.value)
        assertEquals(0.0, yard.fleet.dispatcher.numTasksCompleted.value)
    }

    // ---------- a vehicle carrying more than one load ----------

    /**
     *  The loop A, B, C, D with links 100 long in 25-long zones, and a two-load cart at A, speed 10. A batching
     *  policy hands it One (A to C) and Two (B to D) together at 5: One aboard at 5, Two at 15, One set down at C at
     *  25, Two at D at 35.
     */
    private class Loop(parent: ModelElement) : ProcessModel(parent, "Loop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            .link("CD", "C", "D", length = 100.0, zoneLength = 25.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 25.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network,
            assignmentPolicy = ksl.modeling.fleet.policies.BatchedAssignmentPolicy(
                window = 5.0, inner = ksl.modeling.fleet.policies.ConsolidatingPolicy()
            ),
            name = "Agv"
        )
        val cart = AgvVehicle(agv, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart", loadCapacity = 2)
        val log = mutableListOf<String>()
        val setups = mutableListOf<Loop.() -> Unit>()
        val actors = mutableMapOf<String, Entity>()

        inner class Load(val tag: String, val from: Double, val origin: String, val destination: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation(origin)
                transportByFleet(agv, destination, origin)
                log.add("$tag delivered@$time at=${currentLocation.name} cart=${cart.currentLocationName}")
            }
        }

        fun load(tag: String, origin: String, destination: String, from: Double = 0.0) =
            Load(tag, from, origin, destination).also {
                actors[tag] = it
                activate(it.p)
            }

        fun terminate(tag: String, t: Double) = at(t) { actors.getValue(tag).terminateProcess() }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            actors.clear()
            for (s in setups) s()
        }
    }

    private fun runLoop(setup: Loop.() -> Unit): Pair<Loop, Throwable?> {
        val model = Model("FleetLoop${System.nanoTime()}", autoCSVReports = false)
        val loop = Loop(model)
        loop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 300.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return loop to error
    }

    @Test
    @DisplayName("control: a two-load cart delivers One at C at 25 and Two at D at 35")
    fun twoLoadControl() {
        val (loop, error) = runLoop {
            load("One", "A", "C")
            load("Two", "B", "D")
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("One delivered@25.0 at=C cart=C", "Two delivered@35.0 at=D cart=D"), loop.log)
    }

    @Test
    @DisplayName("one of two loads terminated aboard, with other work waiting: the cart sets the other down where it is bound before taking new work")
    fun oneOfTwoTerminatedWithWorkWaiting() {
        val (loop, error) = runLoop {
            load("One", "A", "C")
            load("Two", "B", "D")
            load("Three", "A", "B", from = 16.0)
            terminate("Two", 20.0)
        }
        assertNull(error, "the run stopped: $error")
        // One at C at 25, where the round ends; the batching policy gives the cart Three at the end of its 5-long
        // window, at 30; the cart collects it at A at 50 and sets it down at B at 60.
        assertEquals(listOf("One delivered@25.0 at=C cart=C", "Three delivered@60.0 at=B cart=B"), loop.log)
    }

    @Test
    @DisplayName("a commitment revoked from a cart carrying another load: the cart sets that load down where it is bound before anything else")
    fun revokedFromACartCarryingAnother() {
        val (loop, error) = runLoop {
            load("One", "A", "C")
            load("Two", "D", "A")
            at(10.0) {
                agv.dispatcher.revoke(cart.assignments.first { it.task.waitingEntity === actors.getValue("Two") })
            }
        }
        assertNull(error, "the run stopped: $error")
        // Two, revoked at 10, waits on the board while the cart finishes its round with One at C at 25; the batching
        // policy gives it back to the cart at 30, which collects it at D at 40 and sets it down at A at 50.
        assertEquals(listOf("One delivered@25.0 at=C cart=C", "Two delivered@50.0 at=A cart=A"), loop.log)
    }

    // ------------------------------------------------------------------------------------------------------
    // ride rig
    // ------------------------------------------------------------------------------------------------------

    private class Town(parent: ModelElement, capacity: Int) : ProcessModel(parent, "Town") {
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
        val stopA = Stop(fleet, "A", "StopA")
        val stopB = Stop(fleet, "B", "StopB")
        val stopC = Stop(fleet, "C", "StopC")
        val stopD = Stop(fleet, "D", "StopD")
        val line = Line(
            "Circular",
            listOf(LineStop(stopA), LineStop(stopB), LineStop(stopC), LineStop(stopD)),
            cyclic = true
        )
        val bus = AgvVehicle(fleet, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Bus", loadCapacity = capacity)

        val log = mutableListOf<String>()
        val setups = mutableListOf<Town.() -> Unit>()
        val actors = mutableMapOf<String, Entity>()
        val passed = mutableMapOf<String, Int>()
        private val myBoardTriggers = mutableMapOf<Long, () -> Unit>()
        private val myAlightTriggers = mutableMapOf<Long, () -> Unit>()

        inner class Rider(val tag: String, val from: Double, val stop: Stop, val to: String, val split: Double?) :
            Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation(stop.location)
                val ride = requestRide(stop, to)
                if (split != null) {
                    delay(split)
                    log.add("$tag ready@$time")
                }
                val r = awaitRide(ride)
                passed[tag] = r.numVehiclesPassed
                log.add("$tag alighted@$time")
            }
        }

        fun rider(tag: String, stop: Stop, to: String, from: Double = 0.0, split: Double? = null) =
            Rider(tag, from, stop, to, split).also {
                actors[tag] = it
                activate(it.p)
            }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        fun terminate(tag: String, t: Double) = at(t) { actors.getValue(tag).terminateProcess() }

        fun terminateWhenBoarded(tag: String) {
            myBoardTriggers[actors.getValue(tag).id] = { terminateNow(tag) }
        }

        fun terminateWhenSetDown(tag: String) {
            myAlightTriggers[actors.getValue(tag).id] = { terminateNow(tag) }
        }

        private fun terminateNow(tag: String) {
            schedule({ _: KSLEvent<Nothing> ->
                log.add("$tag terminated@$time")
                actors.getValue(tag).terminateProcess()
            }, 0.0, priority = 0)
        }

        fun onAnimation(event: AnimationEvent) {
            when (event) {
                is AnimationEvent.VehicleLoadBoarded -> myBoardTriggers.remove(event.entityId)?.invoke()
                is AnimationEvent.VehicleLoadAlighted -> myAlightTriggers.remove(event.entityId)?.invoke()
                else -> Unit
            }
        }

        override fun initialize() {
            log.clear()
            actors.clear()
            passed.clear()
            myBoardTriggers.clear()
            myAlightTriggers.clear()
            repeat(6) { fleet.dispatcher.postLine(line) }
            for (s in setups) s()
        }
    }

    private class TownOutcome(val log: List<String>, val error: Throwable?, val town: Town) {
        fun passedOf(tag: String): Int = town.passed.getValue(tag)
    }

    private fun runTown(capacity: Int = 1, setup: Town.() -> Unit): TownOutcome {
        val model = Model("RideTermination${System.nanoTime()}", autoCSVReports = false)
        lateinit var town: Town
        model.animationSink = object : AnimationSink {
            override val isActive = true
            override fun emit(event: AnimationEvent) = town.onAnimation(event)
        }
        town = Town(model, capacity)
        town.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 300.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return TownOutcome(town.log.toList(), error, town)
    }

    private fun assertRiderFreed(case: String, o: TownOutcome, vararg tags: String) {
        assertNull(o.error, "$case: the run stopped: ${o.error}")
        val system = o.town.fleet
        for (tag in tags) {
            val e = o.town.actors.getValue(tag)
            assertFalse(system.awaitingBoardingHoldQ.contains(e), "$case: $tag still awaits boarding")
            assertFalse(system.inTransitHoldQ.contains(e), "$case: $tag is still in transit")
            assertFalse(o.town.bus.ridersAboard.any { it.rider === e }, "$case: $tag is still aboard the bus")
            for (s in system.stops) assertFalse(s.waiting.any { it.rider === e }, "$case: $tag still stands at ${s.name}")
        }
    }

    @Test
    @DisplayName("ride control: a rider joining at B at 0 bound for D alights at 30")
    fun rideControl() {
        val o = runTown { rider("R", stopB, "D") }
        assertRiderFreed("ride control", o, "R")
        assertEquals(listOf("R alighted@30.0"), o.log)
    }

    @Test
    @DisplayName("terminated while standing at a stop: it leaves the line, and the one behind it is taken in its place")
    fun whileStandingAtAStop() {
        val o = runTown {
            rider("R1", stopB, "D")
            rider("R2", stopB, "D")
            terminate("R1", 5.0)
        }
        assertRiderFreed("standing", o, "R1")
        assertEquals(listOf("R2 alighted@30.0"), o.log)
        assertEquals(1.0, o.town.stopB.numBoarded.value, "boarded at B")
        assertEquals(1.0, o.town.stopD.numAlighted.value, "alighted at D")
    }

    @Test
    @DisplayName("a rider that joins, then waits later: a bus that comes first does not take it, and the next does")
    fun splitRideBusComesFirst() {
        val control = runTown { rider("R", stopB, "D", from = 15.0) }
        assertRiderFreed("split ride control", control, "R")
        val o = runTown { rider("R", stopB, "D", split = 15.0) }
        assertRiderFreed("split ride", o, "R")
        assertEquals(listOf("R ready@15.0") + control.log, o.log)
        assertEquals(1.0, o.town.stopB.numBoarded.value, "boarded at B")
    }

    @Test
    @DisplayName("a rider not yet waiting when a full bus leaves others standing was not passed by")
    fun aRiderNotYetWaitingIsNotPassedBy() {
        val o = runTown {
            rider("R1", stopB, "D")
            rider("R3", stopB, "D")
            rider("R2", stopB, "D", split = 15.0)
        }
        assertRiderFreed("not yet waiting", o, "R1", "R2", "R3")
        // The one-seat bus takes R1 at 10 and leaves R3 standing; R2 is not yet waiting. At 50 it takes R3 and leaves
        // R2, which is waiting by then.
        assertEquals(2.0, o.town.stopB.numPassedByFull.value, "visits that left somebody standing")
        assertEquals(1, o.passedOf("R3"), "R3 was passed by at 10")
        assertEquals(1, o.passedOf("R2"), "R2 was passed by at 50 only")
    }

    @Test
    @DisplayName("a rider that joins, then is terminated before it waits: it leaves the line")
    fun splitRideTerminatedBeforeWaiting() {
        val o = runTown {
            rider("R1", stopB, "D", split = 30.0)
            rider("R2", stopB, "D")
            terminate("R1", 5.0)
        }
        assertRiderFreed("split ride, terminated", o, "R1")
        assertEquals(listOf("R2 alighted@30.0"), o.log)
        assertEquals(1.0, o.town.stopB.numBoarded.value, "boarded at B")
    }

    @Test
    @DisplayName("terminated as it boards, before it resumes: it is off at once and its seat is free at the next stop")
    fun whenRiderBoardedBeforeResuming() {
        val o = runTown {
            rider("R1", stopB, "D")
            rider("R3", stopC, "D")
            terminateWhenBoarded("R1")
        }
        assertRiderFreed("rider boarded", o, "R1")
        assertEquals(listOf("R1 terminated@10.0", "R3 alighted@30.0"), o.log)
        assertEquals(1.0, o.town.stopB.numBoarded.value, "boarded at B")
        assertEquals(1.0, o.town.stopC.numBoarded.value, "boarded at C")
        assertEquals(0.0, o.town.stopC.numPassedByFull.value, "passed by full at C")
        assertEquals(1.0, o.town.stopD.numAlighted.value, "alighted at D")
    }

    @Test
    @DisplayName("terminated while riding: it is off at once and its seat is free at the next stop")
    fun whileRiding() {
        val o = runTown {
            rider("R1", stopB, "D")
            rider("R3", stopC, "D")
            terminate("R1", 15.0)
        }
        assertRiderFreed("riding", o, "R1")
        assertEquals(listOf("R3 alighted@30.0"), o.log)
        assertEquals(0.0, o.town.stopC.numPassedByFull.value, "passed by full at C")
        assertEquals(1.0, o.town.stopD.numAlighted.value, "alighted at D")
    }

    @Test
    @DisplayName("a one-seat bus whose rider is carried on into the next cycle starts that cycle")
    fun fullBusIntoTheNextCycle() {
        val o = runTown { rider("R1", stopC, "B") }
        assertRiderFreed("full into next cycle", o, "R1")
        // Boards at C at 20; D at 30, A at 40 ends the cycle; the next starts at A and reaches B at 50.
        assertEquals(listOf("R1 alighted@50.0"), o.log)
    }

    @Test
    @DisplayName("a line cycle cancelled mid-cycle: the bus serves none of its remaining stops, and the next cycle carries the rider on")
    fun lineCycleCancelledMidCycle() {
        val o = runTown {
            rider("R1", stopB, "C")
            at(15.0) { fleet.dispatcher.cancel(bus.currentAssignment!!.task) }
        }
        assertRiderFreed("cancelled cycle", o, "R1")
        // The bus, on BC at 15, starts the next cycle from A: C at 20 and D at 30 passed, A at 40, B at 50, C at 60.
        assertEquals(listOf("R1 alighted@60.0"), o.log)
        assertEquals(1.0, o.town.stopC.numAlighted.value, "alighted at C")
    }

    @Test
    @DisplayName("terminated as it alights, before it resumes: the ride stands")
    fun whenRiderSetDownBeforeResuming() {
        val o = runTown {
            rider("R1", stopB, "D")
            terminateWhenSetDown("R1")
        }
        assertRiderFreed("rider set down", o, "R1")
        assertEquals(listOf("R1 terminated@30.0"), o.log)
        assertEquals(1.0, o.town.stopD.numAlighted.value, "alighted at D")
    }
}
