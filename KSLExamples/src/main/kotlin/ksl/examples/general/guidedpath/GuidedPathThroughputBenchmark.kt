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
 *  The reference throughput benchmark for guided path transporters.
 *
 *  This is **not** a test and is deliberately not one: it measures wall-clock time, so its answer
 *  depends on the machine it runs on, and a number like that has no business failing a build on
 *  somebody else's laptop. Run it with `main`, record the figure alongside the hardware, and
 *  compare like with like.
 *
 *  The reference configuration is a twenty-intersection, forty-link network of four hundred zones
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
 *  is in the companion object.
 *
 *  @param parent the containing model element
 */
class GuidedPathThroughputBenchmark(parent: ModelElement) : ModelElement(parent, "SaturatedFleet") {

    val network = createNetwork()
    val system = GuidedPathTransportSystem(this, network, name = "Sys")

    // A stream of its own, so the benchmark repeats exactly and two runs on the same machine
    // differ only in wall-clock time.
    private val stream = RNStreamProvider().rnStream(1)

    val vehicles: List<GuidedTransporter> = (0 until NUM_VEHICLES).map { i ->
        // One vehicle at the head of each of the first twenty links, which spreads the fleet
        // over the network without two of them ever sharing a zone at the start.
        val r = i / COLUMNS
        val c = i % COLUMNS
        GuidedTransporter(
            system, TransporterPlacement.OnZone("E${r}_$c.Zone1"),
            ConstantRV(VELOCITY), 1, EndOfZoneControl(), "V$i"
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

    private fun dispatch(vehicle: GuidedTransporter) {
        // Keep trying until the vehicle is actually sent somewhere: a destination it already
        // stands on is refused, and a vehicle left undispatched would quietly stop and make the
        // benchmark measure a smaller fleet than it claims.
        repeat(8) {
            val target = network.intersections[stream.randInt(0, network.intersections.size - 1)]
            if (vehicle.sendTo(target.name)) return
        }
    }

    companion object {

        /** Rows of the reference torus. */
        const val ROWS: Int = 4

        /** Columns of the reference torus: four by five is twenty intersections and forty links. */
        const val COLUMNS: Int = 5

        /** Zones per link, chosen so the network holds four hundred zones. */
        const val ZONES_PER_LINK: Int = 10

        /** Vehicles under saturated demand. */
        const val NUM_VEHICLES: Int = 20

        const val ZONE_LENGTH: Double = 10.0
        const val VELOCITY: Double = 10.0

        private fun nodeName(row: Int, column: Int): String = "N${row}_$column"

        /**
         *  A torus of one-way aisles: each intersection sends one link east and one south, wrapping at
         *  the edges. Every intersection is reachable from every other, no link is two-way, and there
         *  are exactly two links per intersection.
         */
        fun createNetwork(networkName: String = "BenchmarkTorus"): GuidedPathNetwork {
            var b = GuidedPathNetwork.builder(networkName)
            for (r in 0 until ROWS) {
                for (c in 0 until COLUMNS) {
                    b = b.intersection(nodeName(r, c), x = c * 100.0, y = -r * 100.0)
                }
            }
            val length = ZONE_LENGTH * ZONES_PER_LINK
            for (r in 0 until ROWS) {
                for (c in 0 until COLUMNS) {
                    b = b.link(
                        "E${r}_$c", nodeName(r, c), nodeName(r, (c + 1) % COLUMNS),
                        length = length, zoneLength = ZONE_LENGTH, beginDirection = 0.0
                    )
                    b = b.link(
                        "S${r}_$c", nodeName(r, c), nodeName((r + 1) % ROWS, c),
                        length = length, zoneLength = ZONE_LENGTH, beginDirection = 270.0
                    )
                }
            }
            return b.build()
        }

        /** What one run measured. */
        data class Result(
            val zoneTraversals: Double,
            val eventsScheduled: Double,
            val wallClockSeconds: Double
        ) {
            /** The figure the goal is stated in: zone traversals per minute of wall-clock time. */
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
        fun run(replicationLength: Double = 200_000.0, replications: Int = 1): Result {
            val m = Model("GuidedPathThroughputBenchmark")
            val fleet = GuidedPathThroughputBenchmark(m)
            // Both are diagnostics that walk every zone. Leaving them on would benchmark them.
            fleet.system.checkInvariants = false
            m.numberOfReplications = replications
            m.lengthOfReplication = replicationLength
            val started = System.nanoTime()
            m.simulate()
            val elapsed = (System.nanoTime() - started) / 1e9
            return Result(
                zoneTraversals = fleet.system.numZoneTraversals.value,
                eventsScheduled = fleet.system.numEventsScheduled.value,
                wallClockSeconds = elapsed
            )
        }

        fun report() {
            val warmUp = run(replicationLength = 20_000.0)
            println("warm-up (JIT): ${"%,.0f".format(warmUp.zoneTraversals)} traversals in ${"%.2f".format(warmUp.wallClockSeconds)} s")
            val result = run()
            println()
            val described = createNetwork("Describe")
            println("Guided path throughput benchmark - reference configuration")
            println(
                "  network            : $ROWS x $COLUMNS torus, ${described.intersections.size} intersections, " +
                        "${described.links.size} links, ${described.zones.size} zones " +
                        "(${described.links.size * ZONES_PER_LINK} on links, one per intersection)"
            )
            println("  vehicles           : $NUM_VEHICLES, saturated")
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
    }
}

fun main() {
    GuidedPathThroughputBenchmark.report()
}
