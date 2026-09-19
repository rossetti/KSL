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

package ksl.examples.general.guidedpath

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.ConstantRV

/**
 *  The reference configuration's dimensions.
 *
 *  Named rather than repeated, so the torus, the fleets placed on it and the line each benchmark
 *  prints about it cannot disagree. Both benchmarks read these; the active one imports them.
 */
val benchmarkRows: Int = 4

/** @see benchmarkRows */
val benchmarkColumns: Int = 5

/**
 *  The reference throughput benchmark for guided path transporters.
 *
 *  This is **not** a test and is deliberately not one: it measures wall-clock time, so its answer
 *  depends on the machine it runs on, and a number like that has no business failing a build on
 *  somebody else's laptop. Run it with `main`, record the figure alongside the hardware, and
 *  compare like with like.
 *
 *  The reference configuration is a twenty-intersection, forty-link network of 420 zones -- four
 *  hundred on the links and one per intersection, and the intersection zones are not a rounding
 *  detail, since a junction admits one vehicle at a time and is where a grid queues --
 *  carrying twenty vehicles under saturated demand: every vehicle is given a fresh destination the
 *  instant it arrives, so none is ever idle and the engine is doing nothing but move things. The
 *  layout is a four-by-five torus of one-way aisles, which keeps every intersection reachable from
 *  every other while never letting two vehicles meet head on.
 *
 *  **What the number means.** One zone traversal is one scheduled event, so traversals per second
 *  is very nearly events per second, and it is the figure that decides whether a fine
 *  discretization is affordable. Halving the zone size doubles the events for the same motion. A
 *  modeler choosing zone size for the smoothness of an animation rather than for the granularity of
 *  the control system is spending throughput on the picture, and this is the exchange rate.
 *
 *  The invariant harness and link statistics are off, as the reference configuration specifies:
 *  both are diagnostic tools that walk every zone, and leaving them on would measure them rather
 *  than the engine. Deadlock detection is left **on**, because that is the configuration a model
 *  actually runs in, and a benchmark of a configuration nobody uses is not worth having.
 *
 *  ## Why this reports no confidence intervals
 *
 *  It is a benchmark rather than a study. The quantity of interest is wall-clock time on one
 *  machine, and a half-width over replications of the same deterministic workload would describe
 *  the machine's scheduler rather than the model. Record the figure alongside the hardware and
 *  compare like with like.
 *
 *  The model itself is twenty vehicles, each re-dispatched to a fresh random intersection the
 *  moment it arrives, so that the fleet never stops and the measurement is of movement rather than
 *  of waiting. The reference configuration -- the torus, the fleet size, the run and the report --
 *  is in this file, as top-level declarations the active benchmark of case 10 shares.
 *
 *  @param parent the containing model element
 */
class GuidedPathThroughputBenchmark(
    parent: ModelElement,
    private val numVehicles: Int = 20,
    private val velocity: Double = 10.0,
    private val rows: Int = benchmarkRows,
    private val columns: Int = benchmarkColumns,
    name: String? = null
) : ModelElement(parent, name) {

    init {
        // Placement below indexes the east links by row and column, so a fleet larger than there
        // are east links would ask for a link that does not exist. Checked here rather than left
        // to fail inside the space layer, which would name a zone and not the reason.
        require(numVehicles <= rows * columns) {
            "This benchmark places one vehicle at the head of each east link, and a ${rows}x$columns " +
                    "torus has ${rows * columns} of them; $numVehicles vehicles will not fit."
        }
    }

    // Built to the dimensions above rather than to the function's own defaults: the placement
    // arithmetic reads `columns`, so a network that did not follow it would send vehicles to zones
    // the torus does not have.
    val network = createBenchmarkTorus(rows = rows, columns = columns)
    val system = GuidedPathTransportSystem(this, network, name = "Sys")

    // A stream of its own, so the benchmark repeats exactly and two runs on the same machine
    // differ only in wall-clock time.
    private val stream = RNStreamProvider().rnStream(1)

    val vehicles: List<GuidedTransporter> = (0 until numVehicles).map { i ->
        // One vehicle at the head of each of the first twenty links, which spreads the fleet
        // over the network without two of them ever sharing a zone at the start.
        val r = i / columns
        val c = i % columns
        GuidedTransporter(
            system, TransporterPlacement.OnZone("E${r}_$c.Zone1"),
            ConstantRV(velocity), 1, EndOfZoneControl(), "V$i"
        )
    }

    init {
        for (v in vehicles) {
            v.attachArrivalListener { dispatch(v) }
        }
    }

    override fun initialize() {
        for (v in vehicles) dispatch(v)
    }

    /**
     *  How many arrivals ended without the vehicle being sent anywhere.
     *
     *  Should be zero, and the benchmark reports it rather than trusting that. A destination the
     *  vehicle already stands on is refused, so the loop below retries; with twenty intersections
     *  the chance of eight consecutive self-picks is about four in a hundred thousand million. But
     *  the headline figure is only about twenty saturated vehicles if all twenty stayed moving, and
     *  a vehicle that stopped would quietly shrink the fleet while the number still looked fine.
     *  A count printed beside the result turns that from a hope into a statement.
     */
    var undispatchedArrivals: Int = 0
        private set

    override fun replicationEnded() {
        super.replicationEnded()
        if (undispatchedArrivals > 0) {
            logger.warn {
                "Benchmark ($name): $undispatchedArrivals arrival(s) ended without the vehicle " +
                        "being re-dispatched, so the fleet was not saturated for the whole run and " +
                        "the throughput figure understates the engine."
            }
        }
    }

    private fun dispatch(vehicle: GuidedTransporter) {
        repeat(8) {
            val target = network.intersections[stream.randInt(0, network.intersections.size - 1)]
            if (vehicle.sendTo(target.name)) return
        }
        undispatchedArrivals++
    }
}


private fun nodeName(row: Int, column: Int): String = "N${row}_$column"

/**
 *  A torus of one-way aisles: each intersection sends one link east and one south, wrapping at
 *  the edges. Every intersection is reachable from every other, no link is two-way, and there
 *  are exactly two links per intersection.
 */
fun createBenchmarkTorus(
    rows: Int = benchmarkRows,
    columns: Int = benchmarkColumns,
    zonesPerLink: Int = 10,
    zoneLength: Double = 10.0,
    networkName: String = "BenchmarkTorus"
): GuidedPathNetwork {
    var b = GuidedPathNetwork.builder(networkName)
    for (r in 0 until rows) {
        for (c in 0 until columns) {
            b = b.intersection(nodeName(r, c), x = c * 100.0, y = -r * 100.0)
        }
    }
    val length = zoneLength * zonesPerLink
    for (r in 0 until rows) {
        for (c in 0 until columns) {
            b = b.link(
                "E${r}_$c", nodeName(r, c), nodeName(r, (c + 1) % columns),
                length = length, zoneLength = zoneLength, beginDirection = 0.0
            )
            b = b.link(
                "S${r}_$c", nodeName(r, c), nodeName((r + 1) % rows, c),
                length = length, zoneLength = zoneLength, beginDirection = 270.0
            )
        }
    }
    return b.build()
}

/** What one run measured. */
data class GuidedPathBenchmarkResult(
    val zoneTraversals: Double,
    val eventsScheduled: Double,
    val wallClockSeconds: Double,
    val undispatchedArrivals: Int = 0
) {
    /** The headline figure: zone traversals per minute of wall-clock time. */
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
fun runGuidedPathBenchmark(replicationLength: Double = 200_000.0, replications: Int = 1): GuidedPathBenchmarkResult {
    val m = Model("GuidedPathThroughputBenchmark")
    val fleet = GuidedPathThroughputBenchmark(m, name = "SaturatedFleet")
    // Two diagnostics walk every zone, and leaving either on would benchmark it rather than the
    // engine. Both are set here even though only one has to be: `checkInvariants` is initialised
    // from a system property and so can arrive switched on, while `collectLinkStatistics` merely
    // defaults to false. Stating both means a later change of default cannot quietly turn this
    // into a measurement of the diagnostic.
    fleet.system.checkInvariants = false
    fleet.system.collectLinkStatistics = false
    m.numberOfReplications = replications
    m.lengthOfReplication = replicationLength
    val started = System.nanoTime()
    m.simulate()
    val elapsed = (System.nanoTime() - started) / 1e9
    return GuidedPathBenchmarkResult(
        zoneTraversals = fleet.system.numZoneTraversals.value,
        eventsScheduled = fleet.system.numEventsScheduled.value,
        wallClockSeconds = elapsed,
        undispatchedArrivals = fleet.undispatchedArrivals
    )
}

private fun reportGuidedPathBenchmark() {
    val warmUp = runGuidedPathBenchmark(replicationLength = 20_000.0)
    println("warm-up (JIT): ${"%,.0f".format(warmUp.zoneTraversals)} traversals in ${"%.2f".format(warmUp.wallClockSeconds)} s")
    val result = runGuidedPathBenchmark()
    println()
    val described = createBenchmarkTorus(networkName = "Describe")
    println("Guided path throughput benchmark - reference configuration")
    println(
        "  network            : $benchmarkRows x $benchmarkColumns torus, ${described.intersections.size} intersections, " +
                "${described.links.size} links, ${described.zones.size} zones " +
                "(${described.links.size * 10} on links, one per intersection)"
    )
    println("  vehicles           : 20, saturated (${result.undispatchedArrivals} arrival(s) left undispatched)")
    println("  zone traversals    : ${"%,.0f".format(result.zoneTraversals)}")
    println("  events scheduled   : ${"%,.0f".format(result.eventsScheduled)}")
    println("  events / traversal : ${"%.3f".format(result.eventsPerTraversal)}")
    println("  wall clock         : ${"%.2f".format(result.wallClockSeconds)} s")
    println("  throughput         : ${"%,.0f".format(result.traversalsPerWallClockMinute)} zone traversals per wall-clock minute")
    println()
    println("  JVM                : ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    println("  OS                 : ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    println("  processors         : ${Runtime.getRuntime().availableProcessors()}")
}

fun main() {
    reportGuidedPathBenchmark()
}
