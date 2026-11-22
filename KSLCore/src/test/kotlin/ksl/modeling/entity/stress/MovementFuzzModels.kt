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

package ksl.modeling.entity.stress

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.AgvTestNetworks
import ksl.modeling.fleet.Dispatcher
import ksl.modeling.fleet.FailureModel
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/**
 *  Two conveyors in a line, C1 (A to M to B) and C2 (B to D), each allowing items of up to two cells. An
 *  item rides, stops at a mid point, enters part way along, transfers from C1 to C2, or exits where it
 *  entered, chosen by its mode. [snapshot] reports items neither done nor terminated, and cells still
 *  occupied or blocked, or access queues not empty, once all should have cleared.
 */
internal class ConveyorFuzzLines(parent: ModelElement, t1: Conveyor.Type, t2: Conveyor.Type) : ProcessModel(parent, "lines") {
    val c1: Conveyor = Conveyor.builder(this, "C1").conveyorType(t1).velocity(1.0).cellSize(1)
        .maxCellsAllowed(2).firstSegment("A", "M", 3).nextSegment("B", 3).build()
    val c2: Conveyor = Conveyor.builder(this, "C2").conveyorType(t2).velocity(1.0).cellSize(1)
        .maxCellsAllowed(2).firstSegment("B", "D", 4).build()
    val done = linkedSetOf<String>()
    val killed = linkedSetOf<String>()
    val actors = linkedMapOf<String, Item>()
    val log = mutableListOf<String>()
    val setups = mutableListOf<ConveyorFuzzLines.() -> Unit>()
    var endReport: String? = null

    inner class Item(val tag: String, val arrive: Double, val cells: Int, val mode: Int, val d: Double) : Entity(tag) {
        val p: KSLProcess = process("item") {
            delay(arrive)
            when (mode) {
                0 -> {
                    val r = requestConveyor(c1, "A", cells)
                    rideConveyor(r, "B")
                    if (d > 0.0) delay(d)
                    exitConveyor(r)
                }
                1 -> {
                    val r = requestConveyor(c1, "A", cells)
                    rideConveyor(r, "M")
                    if (d > 0.0) delay(d)
                    rideConveyor(r, "B")
                    exitConveyor(r)
                }
                2 -> {
                    val r = requestConveyor(c1, "M", cells)
                    if (d > 0.0) delay(d)
                    rideConveyor(r, "B")
                    exitConveyor(r)
                }
                3 -> {
                    val r = requestConveyor(c1, "A", cells)
                    rideConveyor(r, "B")
                    if (d > 0.0) delay(d)
                    val tr = transferTo(r, c2, "B")
                    rideConveyor(tr, "D")
                    exitConveyor(tr)
                }
                4 -> {
                    val r = requestConveyor(c2, "B", cells)
                    rideConveyor(r, "D")
                    if (d > 0.0) delay(d)
                    exitConveyor(r)
                }
                5 -> {
                    val r = requestConveyor(c2, "B", cells)
                    if (d > 0.0) delay(d)
                    exitConveyor(r)
                }
                else -> {
                    val r = requestConveyor(c1, "M", cells)
                    if (d > 0.0) delay(d)
                    exitConveyor(r)
                }
            }
            done.add(tag)
            log.add("$tag done@$time")
        }
    }

    fun item(tag: String, arrive: Double, cells: Int, mode: Int, d: Double) {
        val it = Item(tag, arrive, cells, mode, d)
        actors[tag] = it
        activate(it.p)
    }

    fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
        schedule({ _: KSLEvent<Nothing> -> action() }, t, priority = priority)
    }

    fun kill(tag: String, t: Double, priority: Int) = at(t, priority) {
        val e = actors.getValue(tag)
        if (tag !in done) {
            log.add("$tag kill@$time")
            e.terminateProcess { killed.add(tag) }
        }
    }

    override fun initialize() {
        done.clear(); killed.clear(); actors.clear(); log.clear(); endReport = null
        for (s in setups) s()
    }

    fun snapshot(): String? {
        val problems = mutableListOf<String>()
        val stuck = actors.keys.filter { it !in done && it !in killed }
        if (stuck.isNotEmpty()) problems.add("stuck=" + stuck.joinToString { "$it(m${actors.getValue(it).mode},c${actors.getValue(it).cells})" })
        for (c in listOf(c1, c2)) {
            val occ = c.conveyorCells.filter { it.isOccupied }.map { "${it.cellNumber}:${it.item?.entity?.name}" }
            val blk = c.conveyorCells.filter { it.isBlocked }.map { it.cellNumber }
            if (occ.isNotEmpty()) problems.add("${c.name} occupied=$occ")
            if (blk.isNotEmpty()) problems.add("${c.name} blocked=$blk")
            for (loc in c.entryCells.keys) {
                val n = c.accessQueueAt(loc).size
                if (n > 0) problems.add("${c.name} accessQ@$loc=$n")
            }
        }
        return if (problems.isEmpty()) null else problems.joinToString("; ")
    }
}

/**
 *  An AGV fleet of two carts on a ring with two parks, the first cart optionally failing by distance
 *  travelled. Each load asks the fleet for transport and awaits it, optionally doing other work in between.
 *  [snapshot] reports loads neither delivered nor terminated, carts still loaded or assigned, posted tasks
 *  neither completed nor cancelled, completions that differ from deliveries, and loads left in hold queues.
 */
internal class FleetFuzzYard(
    parent: ModelElement, policy: AssignmentPolicyIfc, capacity: Int, failing: Boolean
) : ProcessModel(parent, "Yard") {
    val network = AgvTestNetworks.ringWithTwoParks()

    init {
        spatialModel = network
    }

    val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")
    val cart1 = AgvVehicle(
        agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart1", loadCapacity = capacity,
        failureModel = if (failing) FailureModel.distanceBased(ConstantRV(700.0), ConstantRV(7.0)) else null
    ).apply { homeBase = AgvTestNetworks.DEPOT }
    val cart2 = AgvVehicle(
        agv, TransporterPlacement.At(AgvTestNetworks.SECOND_DEPOT), ConstantRV(10.0), name = "Cart2", loadCapacity = capacity
    ).apply { homeBase = AgvTestNetworks.SECOND_DEPOT }
    val carts = listOf(cart1, cart2)

    val log = mutableListOf<String>()
    val done = linkedSetOf<String>()
    val killed = linkedSetOf<String>()
    val setups = mutableListOf<FleetFuzzYard.() -> Unit>()
    val actors = linkedMapOf<String, Entity>()
    val tasks = linkedMapOf<String, Dispatcher.TransportTask>()
    var endReport: String? = null

    inner class Load(
        val tag: String, val from: Double, val origin: String, val destination: String,
        val loading: Double, val unloading: Double, val split: Double?
    ) : Entity(tag) {
        val p: KSLProcess = process(isDefaultProcess = true) {
            delay(from)
            currentLocation = network.requireLocation(origin)
            val task = requestFleetTransport(
                agv, destination, origin,
                if (loading == 0.0) ConstantRV.ZERO else ConstantRV(loading),
                if (unloading == 0.0) ConstantRV.ZERO else ConstantRV(unloading)
            )
            tasks[tag] = task
            if (split != null) delay(split)
            val r = awaitFleetTransport(task)
            done.add(tag)
            log.add("$tag delivered@$time by ${r.vehicleName}")
        }
    }

    fun load(
        tag: String, from: Double = 0.0, origin: String = AgvTestNetworks.NEAR,
        destination: String = AgvTestNetworks.ALT_DROP, loading: Double = 0.0, unloading: Double = 0.0,
        split: Double? = null
    ) = Load(tag, from, origin, destination, loading, unloading, split).also {
        actors[tag] = it
        activate(it.p)
    }

    fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
        schedule({ _: KSLEvent<Nothing> -> action() }, t, priority = priority)
    }

    fun kill(tag: String, t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY) = at(t, priority) {
        if (tag !in done) {
            log.add("$tag kill@$time state=${tasks[tag]?.state}")
            actors.getValue(tag).terminateProcess { killed.add(tag) }
        }
    }

    fun snapshot(): String? {
        val problems = mutableListOf<String>()
        val stuck = actors.keys.filter { it !in done && it !in killed }
        if (stuck.isNotEmpty()) problems.add("stuck=" + stuck.joinToString { "$it(${tasks[it]?.state})" })
        for (c in carts) {
            if (c.numLoadsAboard != 0) problems.add("${c.name} aboard=${c.numLoadsAboard}")
            if (c.currentAssignment != null) problems.add("${c.name} task=${c.currentAssignment?.task?.name}")
        }
        val d = agv.dispatcher
        val posted = d.numTasksPosted.value
        val ended = d.numTasksCompleted.value + d.numTasksCancelled.value
        if (posted != ended) problems.add("posted=$posted completed=${d.numTasksCompleted.value} cancelled=${d.numTasksCancelled.value}")
        if (d.numTasksCompleted.value != done.size.toDouble()) problems.add("completed=${d.numTasksCompleted.value} but delivered=${done.size}")
        if (agv.awaitingPickupHoldQ.size + agv.inTransitHoldQ.size + agv.awaitingLoadHoldQ.size != 0) {
            problems.add("holdQs pickup=${agv.awaitingPickupHoldQ.size} transit=${agv.inTransitHoldQ.size} awaitingLoad=${agv.awaitingLoadHoldQ.size}")
        }
        return if (problems.isEmpty()) null else problems.joinToString("; ")
    }

    override fun initialize() {
        log.clear(); done.clear(); killed.clear(); actors.clear(); tasks.clear(); endReport = null
        for (s in setups) s()
    }
}

/**
 *  Two guided transporters on a ring with two parks, in a pool that allocates the closest and sends idle
 *  ones home. Each part requests a transporter, is carried, and gives it back. [snapshot] reports parts
 *  neither delivered nor terminated, transporters still busy, loaded, failed or off shift, and a pool queue
 *  not empty.
 */
internal class GuidedFuzzFloor(parent: ModelElement) : ProcessModel(parent, "Floor") {
    val network = AgvTestNetworks.ringWithTwoParks()

    init {
        spatialModel = network
    }

    val system = GuidedPathTransportSystem(this, network, name = "Sys")
    val c1 = GuidedTransporter(system, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "C1")
    val c2 = GuidedTransporter(system, TransporterPlacement.At(AgvTestNetworks.SECOND_DEPOT), ConstantRV(10.0), name = "C2")
    val carts = listOf(c1, c2)
    val pool = GuidedTransporterPoolWithQ(this, system, carts, allocationRule = ClosestByNetworkDistanceRule(), name = "Carts")

    init {
        c1.homeBase = AgvTestNetworks.DEPOT
        c2.homeBase = AgvTestNetworks.SECOND_DEPOT
        pool.idleDispositionRule = ReturnToHomeBaseRule()
    }

    val log = mutableListOf<String>()
    val done = linkedSetOf<String>()
    val killed = linkedSetOf<String>()
    val setups = mutableListOf<GuidedFuzzFloor.() -> Unit>()
    val actors = linkedMapOf<String, Entity>()

    inner class Part(
        val tag: String, val from: Double, val pickup: String, val destination: String,
        val loading: Double, val unloading: Double
    ) : Entity(tag) {
        val p: KSLProcess = process(isDefaultProcess = true) {
            delay(from)
            currentLocation = network.requireLocation(pickup)
            val request = requestGuidedTransporter(pool, pickup)
            transportBy(request, destination, loadingDelay = ConstantRV(loading), unLoadingDelay = ConstantRV(unloading))
            releaseGuidedTransporter(request, pool)
            done.add(tag)
            log.add("$tag delivered@$time")
        }
    }

    fun part(tag: String, from: Double, pickup: String, destination: String, loading: Double, unloading: Double) =
        Part(tag, from, pickup, destination, loading, unloading).also { actors[tag] = it; activate(it.p) }

    fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
        schedule({ _: KSLEvent<Nothing> -> action() }, t, priority = priority)
    }

    fun kill(tag: String, t: Double, priority: Int) = at(t, priority) {
        if (tag !in done) {
            log.add("$tag kill@$time")
            actors.getValue(tag).terminateProcess { killed.add(tag) }
        }
    }

    fun snapshot(): String? {
        val problems = mutableListOf<String>()
        val stuck = actors.keys.filter { it !in done && it !in killed }
        if (stuck.isNotEmpty()) problems.add("stuck=$stuck")
        for (c in carts) {
            if (c.numBusy != 0) problems.add("${c.name} busy=${c.numBusy}")
            if (c.numLoadsAboard != 0) problems.add("${c.name} loads=${c.numLoadsAboard}")
            if (c.isFailed || c.isOffShift) problems.add("${c.name} failed=${c.isFailed} offShift=${c.isOffShift}")
        }
        if (pool.waitingQ.size != 0) problems.add("poolQ=${pool.waitingQ.size}")
        if (problems.isNotEmpty()) problems.add("carts=" + carts.joinToString { "${it.name}:${it.transporterState}@${it.positionNow.name}" })
        return if (problems.isEmpty()) null else problems.joinToString("; ")
    }

    override fun initialize() {
        log.clear(); done.clear(); killed.clear(); actors.clear()
        for (s in setups) s()
    }
}
