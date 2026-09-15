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

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV

/**
 *  A rectangular warehouse of pick aisles and cross-aisles, wide enough for traffic both ways.
 *
 *  This is the layout that is neither a loop nor a tree, and it is the one most people actually
 *  have. The modelling question it raises is not *whether* the guide path can express it -- it is
 *  the ordinary case -- but **what an aisle wide enough for two vehicles is worth**, which is a
 *  question with a number rather than an opinion for an answer.
 *
 *  ## How a two-lane aisle is expressed
 *
 *  **A lane is a link.** Two lanes on one span are two links, opposed:
 *
 *  ```
 *  builder.link("B0-T0", "B0", "T0", ...)   // the up lane
 *  builder.link("T0-B0", "T0", "B0", ...)   // the down lane
 *  ```
 *
 *  It is **one network**. Nothing keys on the pair of endpoints, so a second link between the same
 *  two junctions is not a duplicate of anything and needs no second network, no second system and
 *  no coordination between them. Two networks would be worse than redundant: routing, blocking and
 *  deadlock detection are per network, so a vehicle on one could not see a vehicle on the other --
 *  and the whole point of a road layout is that the two directions share the junctions.
 *
 *  A vehicle changes direction by taking the return lane, which is ordinary routing rather than a
 *  manoeuvre: leaving a junction by a link that begins there is what a route already does.
 *
 *  ## The layout
 *
 *  Pick faces at the top of each aisle, a dock on a spur at the bottom left, and a parking spur per
 *  cart so that an idle vehicle is out of the traffic:
 *
 *  ```
 *      Pick0      Pick1      Pick2
 *        T0 <====> T1 <====> T2               top cross-aisle
 *        ^|         ^|         ^|
 *        ||         ||         ||             pick aisles, two lanes each
 *        |v         |v         |v
 *        B0 <====> B1 <====> B2 <====> K0 <====> K1 ...   bottom cross-aisle
 *        |                              |         |
 *      Dock                          park0     park1      (one-cart spurs)
 *  ```
 *
 *  Every `<====>` is **two one-way links**, not one two-way link. That distinction is the subject
 *  of the second study below.
 *
 *  ## What the two studies are for, and the three findings they produce
 *
 *  Demand is deliberately set **above** what the building can serve, so that the layout is the
 *  constraint. A study whose throughput flattens because it has run out of arrivals has measured
 *  its own arrival rate; that is the easiest mistake to make here and the one worth designing out.
 *
 *  **Study 1 sweeps the fleet on the two-lane grid**, and finds three things:
 *
 *  1. **Throughput plateaus.** Around six carts the completion count stops rising in any way worth
 *     paying for -- the last four carts buy a gain smaller than the interval around it. A free-path
 *     model would go on rewarding every cart added, for ever, because nothing in it can represent
 *     an aisle. Where the reward stops is the number a fleet-sizing study exists to find.
 *  2. **The carts you add past that are not idle -- they are blocked.** Fleet time blocked roughly
 *     triples between six carts and ten while throughput does not move. Buying vehicles past the
 *     plateau buys congestion.
 *  3. **The grid gridlocks.** At twelve carts the run raises `GuidedPathDeadlockException`: a
 *     circular wait among six transporters on one span of the top cross-aisle. Both lanes between
 *     `T0` and `T1` are full nose to tail, and the junction at each end is held by a vehicle that
 *     wants the lane the other four are standing in. It is "blocking the box", exactly as at a road
 *     intersection.
 *
 *  That third finding is the one to take away, because it corrects an easy inference:
 *
 *  > **Paired one-way lanes are not deadlock-proof.** They remove head-on deadlock *on a link* --
 *  > two vehicles on one span can never face each other. They do nothing about a cycle that closes
 *  > through the **junctions at each end of a span**, and that is ordinary traffic gridlock. A
 *  > second lane raises the fleet a layout can carry; it does not remove the ceiling.
 *
 *  **Study 2 asks what the second lane bought**, by running the same building with single
 *  bidirectional aisles. A bidirectional link is *one* lane used by one direction at a time under a
 *  direction lock, and here it deadlocks at **two** carts -- against twelve for the two-lane
 *  layout. That is what the second lane is worth on this building, stated as the fleet size each
 *  design can carry rather than as an opinion about aisle width.
 *
 *  A run that deadlocks **raises**, which is deliberate: the model is valid and the answer is "this
 *  configuration deadlocks", which is often the finding a study is after. Both sweeps catch it and
 *  record the design point as infeasible rather than letting it end the study -- the recipe in
 *  `ksl-guidedpath` section 4. Do not "fix" it by disabling detection: the run would still
 *  deadlock and would simply stop saying so.
 *
 *  ## One thing to know before reading the numbers
 *
 *  **A junction is a zone, so it admits one vehicle at a time**, and a junction of zero length is
 *  still held for one traversal of the first zone beyond it. Two lanes decongest the *aisle*; they
 *  never decongest the *crossroads*. In a grid under load that is where the queueing appears, and
 *  it is why the second lane buys less than doubling the aisles would suggest -- and why the
 *  gridlock in finding 3 closes through junctions rather than through lanes. Where two flows
 *  genuinely pass one another rather than cross, the lever is to give each direction its own
 *  junction node; where they genuinely cross, sharing a node is the honest model. See
 *  `JunctionOccupancyTest` for both, measured.
 *
 *  The horizon warnings above the tables are the closing audit doing its job, not a fault: each
 *  replication ends with carts mid-delivery and loads still waiting, which is what an over-loaded
 *  warehouse looks like when the clock stops. The deadlock reports are logged at ERROR by the space
 *  layer at the point of detection, so they appear before the table that records the design point
 *  as infeasible -- read the tables last.
 */
class TwoLaneWarehouseExample(
    parent: ModelElement,
    private val numCarts: Int,
    twoLane: Boolean,
    name: String,
    private val meanTBA: Double = 9.0
) : ProcessModel(parent, name) {

    companion object {
        const val NUM_AISLES: Int = 3
        const val AISLE_SPACING: Double = 40.0
        const val AISLE_LENGTH: Double = 120.0
        const val ZONE: Double = 20.0
        const val SPUR: Double = 10.0
        const val VELOCITY: Double = 25.0

        fun pickFace(i: Int): String = "Pick$i"
        const val DOCK: String = "Dock"

        /**
         *  The same building either way. A span is two opposed one-way links when [twoLane], and a
         *  single bidirectional link when not -- which is the only line that differs between the
         *  two studies below.
         */
        fun build(numCarts: Int, twoLane: Boolean, name: String): GuidedPathNetwork {
            val b = GuidedPathNetwork.builder(name)
            for (i in 0 until NUM_AISLES) {
                b.intersection("B$i", x = i * AISLE_SPACING, y = 0.0)
                b.intersection("T$i", x = i * AISLE_SPACING, y = AISLE_LENGTH)
            }
            // The bottom cross-aisle continues east into a parking row, one spur per cart, so that
            // no two carts are ever sent to the same parking place. A staging area stages one
            // vehicle; the rest stop on the approach and are still "available" while stuck.
            for (k in 0 until numCarts) {
                b.intersection("K$k", x = (NUM_AISLES + k) * AISLE_SPACING, y = 0.0)
                b.intersection("P$k", x = (NUM_AISLES + k) * AISLE_SPACING, y = -SPUR)
                b.link("K$k-P$k", "K$k", "P$k", length = SPUR, zoneLength = SPUR, type = LinkType.SPUR)
            }
            b.intersection("D", x = 0.0, y = -SPUR)
            b.link("B0-D", "B0", "D", length = SPUR, zoneLength = SPUR, type = LinkType.SPUR)

            fun span(from: String, to: String, length: Double) {
                if (twoLane) {
                    b.link("$from-$to", from, to, length = length, zoneLength = ZONE)
                    b.link("$to-$from", to, from, length = length, zoneLength = ZONE)
                } else {
                    b.link("$from~$to", from, to, length = length, zoneLength = ZONE,
                        type = LinkType.BIDIRECTIONAL)
                }
            }

            for (i in 0 until NUM_AISLES) span("B$i", "T$i", AISLE_LENGTH)          // the pick aisles
            for (i in 0 until NUM_AISLES - 1) {
                span("B$i", "B${i + 1}", AISLE_SPACING)                              // bottom cross-aisle
                span("T$i", "T${i + 1}", AISLE_SPACING)                              // top cross-aisle
            }
            // Bottom cross-aisle onward into the parking row.
            span("B${NUM_AISLES - 1}", "K0", AISLE_SPACING)
            for (k in 0 until numCarts - 1) span("K$k", "K${k + 1}", AISLE_SPACING)

            for (i in 0 until NUM_AISLES) b.station(pickFace(i), "T$i")
            return b.station(DOCK, "D").build()
        }
    }

    val network: GuidedPathNetwork = build(numCarts, twoLane, "${name}Net")

    init {
        spatialModel = network
    }

    val agv = AgvSystem(this, network, name = "Fleet")

    val carts: List<AgvVehicle> = List(numCarts) { k ->
        AgvVehicle(
            agv, TransporterPlacement.At("P$k"), ConstantRV(VELOCITY), name = "Cart${k + 1}"
        ).apply { homeBase = "P$k" }
    }

    private val myTimeInSystem = Response(this, "${this.name}:TimeInSystem")
    val timeInSystem: ResponseCIfc
        get() = myTimeInSystem

    private val myDelivered = Counter(this, "${this.name}:Delivered")
    val delivered: CounterCIfc
        get() = myDelivered

    /** The fleet's average blocked fraction, observed once per replication so that it carries a
     *  confidence interval like any other response. */
    private val myFleetBlocked = Response(this, "${this.name}:FleetFracBlocked")
    val fleetBlocked: ResponseCIfc
        get() = myFleetBlocked

    // Two streams, deliberately separated: sharing one would couple which face a pallet came from
    // to when it arrived, so changing the fleet size would change the sequence of pick faces too.
    private val myTimeBetweenArrivals = RandomVariable(
        this, ExponentialRV(meanTBA, streamNum = 1), name = "${this.name}:TBA"
    )
    val timeBetweenArrivalsRV: RandomVariableCIfc
        get() = myTimeBetweenArrivals

    private val myWhichFace = RandomVariable(
        this, UniformRV(0.0, NUM_AISLES.toDouble(), streamNum = 2), name = "${this.name}:WhichFace"
    )
    val whichFaceRV: RandomVariableCIfc
        get() = myWhichFace

    private val myPickTime = RandomVariable(this, ConstantRV(2.0), name = "${this.name}:PickTime")
    val pickTimeRV: RandomVariableCIfc
        get() = myPickTime

    inner class Pallet : Entity() {
        val movement = process(isDefaultProcess = true) {
            val arrived = time
            val face = pickFace(myWhichFace.value.toInt().coerceIn(0, NUM_AISLES - 1))
            currentLocation = network.requireLocation(face)
            transportByFleet(
                agv, destination = DOCK, origin = face,
                loadingDelay = myPickTime, unLoadingDelay = myPickTime
            )
            myTimeInSystem.value = time - arrived
            myDelivered.increment()
        }
    }

    inner class Source : Entity() {
        val arrivals = process(isDefaultProcess = true) {
            while (true) {
                delay(myTimeBetweenArrivals)
                activate(Pallet().movement)
            }
        }
    }

    override fun initialize() {
        activate(Source().arrivals)
    }

    override fun replicationEnded() {
        super.replicationEnded()
        myFleetBlocked.value =
            carts.sumOf { it.fracTimeBlocked.withinReplicationStatistic.weightedAverage } / numCarts
    }
}

// ---- the studies -------------------------------------------------------------------------------

/** Demand deliberately above what the building can serve, so that the layout is the constraint. */
private const val MEAN_TBA: Double = 3.0

private class Outcome(
    val delivered: Double,
    val deliveredHW: Double,
    val timeInSystem: Double,
    val timeInSystemHW: Double,
    val blocked: Double,
    val blockedHW: Double,
    val deadlockedAmong: Int = 0
) {
    val deadlocked: Boolean get() = deadlockedAmong > 0

    companion object {
        fun deadlock(participants: Int) =
            Outcome(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, participants)
    }
}

/**
 *  This sweep is run by hand rather than through a [ksl.controls.experiments.ScenarioRunner], and
 *  the reason is the `catch` below. A design point here can end in a circular wait, which is a
 *  **result** rather than a failure -- "this layout cannot carry this fleet" is very often the
 *  finding a study is after. A runner would propagate the exception out of the whole sweep and lose
 *  the rows on either side of it. The half-widths the runner would have supplied are computed here
 *  instead, from each response's across-replication statistic.
 */

private fun runFleet(carts: Int, twoLane: Boolean): Outcome {
    val tag = if (twoLane) "Two" else "One"
    val m = Model("Warehouse$tag$carts")
    val shop = TwoLaneWarehouseExample(m, carts, twoLane, "W$tag$carts", MEAN_TBA)
    m.numberOfReplications = 10
    m.lengthOfReplication = 5000.0
    m.lengthOfReplicationWarmUp = 1000.0
    return try {
        m.simulate()
        val d = shop.delivered.acrossReplicationStatistic
        val t = shop.timeInSystem.acrossReplicationStatistic
        val b = shop.fleetBlocked.acrossReplicationStatistic
        Outcome(
            delivered = d.average, deliveredHW = d.halfWidth,
            timeInSystem = t.average, timeInSystemHW = t.halfWidth,
            blocked = b.average, blockedHW = b.halfWidth
        )
    } catch (e: GuidedPathDeadlockException) {
        // A domain outcome, not a defect: this layout cannot carry this fleet. The report names
        // every participant in the cycle, which is what says whether it closed through a lane or
        // through the junctions.
        Outcome.deadlock(e.report.participants.size)
    }
}

private fun table(title: String, sizes: List<Int>, results: Map<Int, Outcome>) {
    println()
    println(title)
    println()
    println("  %-6s %11s %8s %11s %9s %9s %8s".format(
        "carts", "delivered", "hw", "in system", "hw", "blocked", "hw"))
    for (n in sizes) {
        val o = results.getValue(n)
        if (o.deadlocked) {
            println("  %-6d %11s %8s %11s %9s %9s %8s".format(
                n, "DEADLOCK", "--", "--", "--", "--", "--"))
        } else {
            println("  %-6d %11.1f %8.1f %11.2f %9.2f %9.4f %8.4f".format(
                n, o.delivered, o.deliveredHW, o.timeInSystem, o.timeInSystemHW,
                o.blocked, o.blockedHW))
        }
    }
}

/** The largest fleet the layout carried without a circular wait. */
private fun largestFeasible(sizes: List<Int>, r: Map<Int, Outcome>): Int? =
    sizes.filter { !r.getValue(it).deadlocked }.maxOrNull()

fun main() {
    val twoLaneSizes = listOf(2, 4, 6, 8, 10, 12)
    val oneLaneSizes = listOf(1, 2, 3, 4)

    println()
    println("A two-lane warehouse grid: ${TwoLaneWarehouseExample.NUM_AISLES} pick aisles and two cross-aisles,")
    println("every span a pair of opposed one-way lanes. Pallets from the pick faces to one dock.")
    println("10 replications of 5000 after a 1000 warm-up, arrivals every %.0f -- above capacity on".format(MEAN_TBA))
    println("purpose, so that the building rather than the arrival stream is what limits the answer.")

    val two = twoLaneSizes.associateWith { runFleet(it, twoLane = true) }
    table("Study 1 -- two-lane aisles: what does adding a cart buy?", twoLaneSizes, two)

    val served = twoLaneSizes.filter { !two.getValue(it).deadlocked }
    val peak = served.maxByOrNull { two.getValue(it).delivered }
    val plateau = served.firstOrNull { n ->
        peak != null && two.getValue(n).delivered >= 0.99 * two.getValue(peak).delivered
    }
    println()
    if (plateau != null && peak != null) {
        val big = served.maxOrNull()!!
        val a = two.getValue(plateau)
        val b = two.getValue(big)
        val gain = b.delivered - a.delivered
        val bound = a.deliveredHW + b.deliveredHW
        println("  Throughput flattens at %d cart(s).".format(plateau))
        println("  From %d to %d carts, deliveries go %.1f (+/- %.1f) -> %.1f (+/- %.1f): a gain of %.1f".format(
            plateau, big, a.delivered, a.deliveredHW, b.delivered, b.deliveredHW, gain
        ))
        println("  against intervals summing to %.1f, so it is %s.".format(
            bound, if (gain > bound) "real but negligible" else "not distinguishable from none"
        ))
        println("  Over the same range fleet time blocked goes %.1f%% -> %.1f%%.".format(
            100.0 * a.blocked, 100.0 * b.blocked
        ))
        }
    val gridlock = twoLaneSizes.filter { two.getValue(it).deadlocked }
    if (gridlock.isNotEmpty()) {
        val n = gridlock.min()
        println()
        println("  At %d cart(s) the grid **deadlocks**, among %d transporters.".format(
            n, two.getValue(n).deadlockedAmong))
    }

    val one = oneLaneSizes.associateWith { runFleet(it, twoLane = false) }
    table("Study 2 -- the same building with single two-way aisles: what did the second lane buy?",
        oneLaneSizes, one)

    val oneMax = largestFeasible(oneLaneSizes, one)
    val twoMax = largestFeasible(twoLaneSizes, two)
    println()
    if (oneMax != null && twoMax != null) {
        println("  Single two-way aisles carry %d cart(s); paired one-way lanes carry %d.".format(oneMax, twoMax))
    }
}
