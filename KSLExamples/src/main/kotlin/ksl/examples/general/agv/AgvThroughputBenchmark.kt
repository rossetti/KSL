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

package ksl.examples.general.agv

import ksl.examples.general.guidedpath.createBenchmarkTorus
import ksl.examples.general.guidedpath.runGuidedPathBenchmark
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.ParkInPlaceDisposition
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.ConstantRV

/**
 *  The reference throughput benchmark for **active** guided vehicles, on the same layout and the
 *  same fleet as the passive one.
 *
 *  Like its passive counterpart it is **not** a test: it measures wall-clock time, so its answer
 *  belongs to the machine it ran on and has no business failing a build on somebody else's. Run it
 *  with `main`, record the figure alongside the hardware, and compare like with like.
 *
 *  ## What it is for
 *
 *  Two questions, and only the second is about speed.
 *
 *  The first is whether the two paradigms are **comparable at all** on the same work. The network,
 *  the zone count, the fleet size, the velocity and the saturation are shared with
 *  [runGuidedPathBenchmark] -- this file imports its layout rather than restating it, so
 *  they cannot drift apart -- and the traversal count should land in the same neighbourhood.
 *  A large gap would mean the two subsystems are not moving the same vehicles over the same aisles,
 *  which would make every other comparison between them suspect.
 *
 *  The second is **what deciding costs**. The passive fleet is dispatched by a rule evaluated
 *  inside the asking entity's own process; here a dispatcher agent wakes, considers the board, and
 *  awards. That is strictly more machinery, and the honest thing to do with it is measure it rather
 *  than assert it is cheap. Events per zone traversal is where it shows up: the passive engine's
 *  floor is one, and whatever this reports above that is the price of having somewhere to put a
 *  dispatching decision.
 *
 *  ## Saturation, expressed the way this paradigm expresses work
 *
 *  The passive benchmark saturates by re-dispatching each vehicle the instant it arrives, which it
 *  can do because a transporter is a thing you command. Here nobody commands a vehicle: work exists
 *  because a load asked for it. So the load side is what saturates -- a standing population of
 *  loads, each of which asks to be carried somewhere, and on arrival immediately asks again. With
 *  more loads than vehicles the board is never empty and no vehicle is ever idle for want of a task,
 *  which is the same condition the passive benchmark creates from the other end.
 *
 *  [ParkInPlaceDisposition] is deliberate and matches the passive configuration: with the board
 *  never empty, a vehicle is re-assigned the moment it declares itself available, so no disposition
 *  ever runs. Choosing a rule that would send vehicles home would measure a repositioning that a
 *  saturated fleet never does.
 *
 *  The invariant harness is off, as it is there, because it walks every zone and leaving it on
 *  would benchmark the harness. Deadlock detection is left **on**, because that is the configuration
 *  a model actually runs in.
 *
 *  ## Why this reports no confidence intervals
 *
 *  It is a benchmark rather than a study. The quantity of interest is wall-clock time on one
 *  machine, and a half-width over replications of the same deterministic workload would describe
 *  the machine's scheduler rather than the model. Record the figure alongside the hardware and
 *  compare like with like.
 */
class AgvThroughputBenchmark(
    parent: ModelElement,
    private val numLoads: Int = 40,
    private val numVehicles: Int = 20,
    private val velocity: Double = 10.0,
    private val columns: Int = 5,
    name: String? = null
) : ProcessModel(parent, name) {

    val network = createAgvBenchmarkNetwork()

    init {
        spatialModel = network
    }

    val agv = AgvSystem(this, network, assignmentPolicy = NearestVehiclePolicy(), name = "Agv")

    // A stream of its own, so the benchmark repeats exactly and two runs on the same machine
    // differ only in wall-clock time.
    private val stream = RNStreamProvider().rnStream(1)

    val vehicles: List<AgvVehicle> = (0 until numVehicles).map { i ->
        // One vehicle at the head of each of the first twenty links, exactly as the passive
        // benchmark places them, so neither fleet begins with an advantage over the other.
        val r = i / columns
        val c = i % columns
        AgvVehicle(
            agv, TransporterPlacement.OnZone("E${r}_$c.Zone1"),
            ConstantRV(velocity), 1, EndOfZoneControl(), "V$i"
        ).apply { dispositionPolicy = ParkInPlaceDisposition() }
    }

    private fun somewhere(): String =
        network.intersections[stream.randInt(0, network.intersections.size - 1)].name

    private inner class Load : Entity() {
        val circulating = process(isDefaultProcess = true) {
            currentLocation = network.requireLocation(somewhere())
            while (true) {
                val there = somewhere()
                if (there != currentLocation.name) {
                    transportByFleet(agv, destination = there, origin = currentLocation.name)
                } else {
                    // Asking to be carried where it already stands would be refused, and a load
                    // that stopped asking would quietly shrink the population this claims to run.
                    delay(0.0)
                }
            }
        }
    }

    override fun initialize() {
        repeat(numLoads) { activate(Load().circulating) }
    }
}

/**
 *  The same torus the passive benchmark uses, borrowed rather than rebuilt so that the two
 *  measurements are of one layout and stay that way.
 */
private fun createAgvBenchmarkNetwork(networkName: String = "BenchmarkTorus"): GuidedPathNetwork =
    createBenchmarkTorus(networkName = networkName)

/** What one run measured. The same three quantities the passive benchmark reports. */
private data class AgvBenchmarkResult(
    val zoneTraversals: Double,
    val eventsScheduled: Double,
    val tasksCompleted: Double,
    val wallClockSeconds: Double
) {
    val traversalsPerWallClockMinute: Double
        get() = zoneTraversals / wallClockSeconds * 60.0

    val eventsPerTraversal: Double
        get() = if (zoneTraversals > 0.0) eventsScheduled / zoneTraversals else Double.NaN
}

/**
 *  Runs the reference configuration.
 *
 *  @param replicationLength how long to run, in simulated minutes
 *  @param replications how many replications to run
 */
private fun runAgvBenchmark(replicationLength: Double = 200_000.0, replications: Int = 1): AgvBenchmarkResult {
    val m = Model("AgvThroughputBenchmark")
    val fleet = AgvThroughputBenchmark(m, name = "SaturatedFleet")
    fleet.agv.checkInvariants = false
    m.numberOfReplications = replications
    m.lengthOfReplication = replicationLength
    val started = System.nanoTime()
    m.simulate()
    val elapsed = (System.nanoTime() - started) / 1e9
    return AgvBenchmarkResult(
        zoneTraversals = fleet.agv.numZoneTraversals.value,
        eventsScheduled = fleet.agv.numEventsScheduled.value,
        tasksCompleted = fleet.agv.dispatcher.numTasksCompleted.value,
        wallClockSeconds = elapsed
    )
}

private fun reportAgvBenchmark() {
    val warmUp = runAgvBenchmark(replicationLength = 20_000.0)
    println(
        "warm-up (JIT): ${"%,.0f".format(warmUp.zoneTraversals)} traversals in " +
                "${"%.2f".format(warmUp.wallClockSeconds)} s"
    )
    val active = runAgvBenchmark()
    val passive = runGuidedPathBenchmark()
    val described = createAgvBenchmarkNetwork("Describe")

    println()
    println("AGV throughput benchmark - reference configuration, both paradigms")
    println(
        "  network            : 4 x 5 torus, " +
                "${described.intersections.size} intersections, ${described.links.size} links, " +
                "${described.zones.size} zones"
    )
    println("  vehicles           : 20, saturated")
    println("  loads circulating  : 40  (active only; the passive fleet saturates itself)")
    println()
    println("  %-22s %18s %18s".format("", "active", "passive"))
    println(
        "  %-22s %18s %18s".format(
            "zone traversals",
            "%,.0f".format(active.zoneTraversals), "%,.0f".format(passive.zoneTraversals)
        )
    )
    println(
        "  %-22s %18s %18s".format(
            "events scheduled",
            "%,.0f".format(active.eventsScheduled), "%,.0f".format(passive.eventsScheduled)
        )
    )
    println(
        "  %-22s %18s %18s".format(
            "events / traversal",
            "%.3f".format(active.eventsPerTraversal), "%.3f".format(passive.eventsPerTraversal)
        )
    )
    println(
        "  %-22s %18s %18s".format(
            "wall clock (s)",
            "%.2f".format(active.wallClockSeconds), "%.2f".format(passive.wallClockSeconds)
        )
    )
    println(
        "  %-22s %18s %18s".format(
            "traversals / minute",
            "%,.0f".format(active.traversalsPerWallClockMinute),
            "%,.0f".format(passive.traversalsPerWallClockMinute)
        )
    )
    println("  %-22s %18s %18s".format("tasks completed", "%,.0f".format(active.tasksCompleted), "--"))
    println()
    println("  JVM                : ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    println("  OS                 : ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    println("  processors         : ${Runtime.getRuntime().availableProcessors()}")
}

fun main() {
    reportAgvBenchmark()
}
