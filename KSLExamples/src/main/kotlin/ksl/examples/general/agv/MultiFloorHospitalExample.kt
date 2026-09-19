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
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.controls.experiments.ScenarioRunner
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/*
 *  A hospital on two floors, joined by a lift -- and there is no lift class anywhere in it.
 *
 *  A guide path routes on **declared link lengths**, never on coordinates, so nothing in the network
 *  knows or cares that two of its intersections are one above the other. That makes a dedicated lift
 *  expressible as exactly what it physically is: a one-way link consisting of a single zone. The
 *  zone rules already say that one zone admits one vehicle, so the lift excludes everybody else for
 *  the duration of a ride without a line being written to make it do so. No lift class, no floor
 *  concept, no special case in the dispatcher or in a vehicle's control loop.
 *
 *  Which is a pleasing claim and an easy one to be wrong about, so this example does not simply
 *  assert it. It runs three studies.
 *
 *  ## The layout
 *
 *  A one-way circuit that climbs one shaft and descends the other. Porters collect at the pharmacy
 *  on the first floor and deliver to a ward on the ground floor, so **every delivery cycle rides
 *  each shaft exactly once**: down with a load, up empty.
 *
 *  ```
 *      F3 ◄──────── Pharmacy ◄──────── F1        first floor
 *      │                                ▲
 *   ShaftDown                        ShaftUp      (one zone each: one porter at a time)
 *      ▼                                │
 *    Lobby ────────► WardA ────────────► G3       ground floor
 *      │
 *   parking spurs
 *  ```
 *
 *  The circuit is **400 long in every configuration studied here**, and the porters all travel at 10,
 *  so one porter completes a delivery every 40 time units no matter which configuration is running.
 *  That is arranged deliberately: when the shaft is shortened below, the corridors are lengthened by
 *  the same amount. Without it, a faster lift would also be a shorter round trip, and the study
 *  could not tell the two effects apart.
 *
 *  ## Study 1 -- it works, and one porter is in the shaft at a time
 *
 *  Three porters, a short run, and the shaft's single zone sampled through it. The sampling asks
 *  `hasHolder`, not `isCovered`, and the difference is worth understanding rather than copying. A
 *  transporter *reserves* the zone ahead before it enters -- that reservation is what stops two of
 *  them starting into the same free space -- and marks it OCCUPIED only once it comes to rest
 *  covering it. The last zone of a link is therefore never OCCUPIED, because arriving at its far end
 *  means arriving at the junction beyond. On a single-zone shaft that is the only zone there is, so
 *  `isCovered` would report an idle lift throughout a run that plainly uses one. Exclusion lives on
 *  the reservation, and so must any measurement of it.
 *
 *  ## Study 2 -- adding porters stops helping
 *
 *  A shaft ride of 8 time units passes at best one porter every 8 units, so the circuit cannot carry
 *  more than one delivery per 8 units however many porters are pushed onto it -- that is 5 porters'
 *  worth, and beyond it the extra porters queue at the foot of the shaft rather than deliver. The
 *  table shows throughput flattening, cycle time growing to absorb the difference, and the blocked
 *  fraction climbing.
 *
 *  ## Study 3 -- the same round trip with a fast lift
 *
 *  Identical in every respect except that the ride is 2 time units instead of 8, with the corridors
 *  lengthened to keep the circuit at 400. One porter is therefore no faster than before, and that is
 *  the control: any difference of consequence in the table belongs to the shaft's capacity.
 *
 *  Not *every* difference, and the exception is worth stating rather than rounding away. At three
 *  and four porters, where the shaft binds in neither study, the fast lift is fractionally the
 *  worse of the two -- 7.457 against 7.486, 9.943 against 10.000. The half-widths are 0.00, so this
 *  is deterministic rather than noise, and it is about four parts in a thousand. Holding the
 *  circuit at 400 equalises the distance a porter travels; it does not equalise how that distance is
 *  cut into zones, and a 120-unit corridor has more boundaries to negotiate than a 60-unit one. It
 *  is far too small to touch anything the studies conclude, and it is not the same kind of fact as
 *  the ceiling.
 *
 *  ## Why the orders recirculate
 *
 *  The pharmacy is modelled as always having the next order ready: a fixed number of orders is in
 *  circulation, and finishing one releases the next. That is a **closed** system, chosen because the
 *  question here is one of capacity. Under open arrivals fast enough to saturate eight porters, one
 *  porter's queue would grow without bound, and its mean time in system would then be a fact about
 *  the length of the run rather than about the hospital. Closed, every fleet size is stable, cycle
 *  time means something, and Little's law is available as a check on the table:
 *  `orders in circulation = throughput x cycle time`, which the printed columns satisfy.
 *
 *  ## What the tables show
 *
 *  **Study 1.** Both floors are reachable and the routed distance is a real number, so the network
 *  knows the floors connect -- by declared length, since nothing here has a third coordinate. Every
 *  porter used the lift, and never two at once. Nothing was written to make that true: a zone
 *  admits one vehicle, and a lift is one zone. The held fraction is worth checking rather than
 *  taking on trust: each delivery cycle rides the up shaft once, at 8 units a ride, so 42
 *  deliveries in 600 units account for about 0.56 of it. The sampled figure is a little higher, and
 *  should be -- the zone is held from the moment it is *reserved*, not from the moment a porter
 *  enters it, and the reservation is the exclusion.
 *
 *  **Studies 2 and 3, read against each other, one porter first.** A single porter travels the same
 *  400 in both and delivers at the same rate in both, which is the whole reason the corridors were
 *  lengthened when the shaft was shortened. Any difference further down is therefore about how many
 *  porters the shaft will pass, and about nothing else.
 *
 *  Study 2 scales cleanly to four porters -- 2.486, 4.971, 7.486, 10.000, essentially 2.5 apiece --
 *  then stops dead at 12.486 for six porters and for eight. That ceiling is not an artefact of the
 *  fleet or of the dispatching rule: an 8 unit ride passes at most 12.50 deliveries per 100 units,
 *  which is five porters' worth, and the fleet reaches it and can go no further however many more
 *  are hired.
 *
 *  What the surplus porters do instead is in the last two columns, and the arithmetic is exact. Six
 *  porters are blocked 0.1667 of the time and 6 x 0.1667 is 1; eight are blocked 0.3750 and
 *  8 x 0.3750 is 3. One porter's worth of the fleet is standing still at six, three porters' worth
 *  at eight -- precisely the surplus over the five the shaft will carry. Cycle time rises to match,
 *  from 60.0 at four porters to 80.0 at eight, because the extra orders are waiting rather than
 *  moving. **Buying porters buys queue.**
 *
 *  In study 3 the same fleet sizes keep converting into throughput: eight porters deliver 19.94 per
 *  100 against the 20.00 that perfect scaling would give, because a 2 unit ride will pass 50 per
 *  100 and the fleet never comes near it. Same circuit, same porters, same rule, same code -- a
 *  different lift.
 *
 *  The columns are not independent, and it is worth checking that they hang together. Little's law
 *  says orders outstanding = throughput x cycle time, with throughput put back on a per-unit basis
 *  by dividing the column by 100. Eight porters in study 2: `0.12486 x 80.00 = 9.99`, against 10
 *  orders out. In study 3: `0.19943 x 49.98 = 9.97`. It holds because the system is closed, which
 *  is also why cycle time here is a number about the hospital rather than about the length of the
 *  run.
 *
 *  ## The warnings above each table
 *
 *  Both sweeps print several of these before their table, and they are worth reading rather than
 *  scrolling past:
 *
 *  ```
 *  WARN GuidedPathSpace (Agv:Space): 1 transporter(s) were still waiting when replication 1 ended.
 *       The guide path may have stopped moving rather than run out of work.
 *    (Porter2:Body) holds [GroundB.Zone6] and waits for zone (G3), which is held by (Porter1:Body)
 *  ```
 *
 *  That is the space layer's blocked-transporter check, and it is a genuinely useful one: a guide
 *  path that has seized up looks exactly like this, and on most models a porter still waiting at the
 *  horizon is a question worth asking. Here it is not a question, it is **the answer**. Study 2
 *  exists to show that porters past the fifth queue instead of delivering, so a run that ended with
 *  none of them queueing would mean the study had failed to load the shaft at all.
 *
 *  Read the named pair rather than the count. `Porter2` waiting on `G3`, which `Porter1` holds, is
 *  one porter behind another in the ordinary way, and it resolves as soon as `Porter1` moves. What
 *  would be alarming is a *cycle* -- two porters each holding what the other waits for -- and the
 *  space reports deadlocks separately (`NumDeadlocksDetected`, zero throughout here) rather than
 *  leaving you to spot one in this list.
 *
 *  The entity-side counts are the other half and are quiet for a different reason. A closed system
 *  necessarily has its whole population outstanding when the clock stops, so those never exceed the
 *  orders-out column -- which is exactly the reading that would tell you something was wrong if
 *  they did.
 *
 *  ## What none of this needed
 *
 *  An elevator object, a floor attribute, a capacity semaphore, or a branch anywhere in the
 *  dispatcher or the vehicle control loop. A lift is a one-way link of a single zone. The floors are
 *  placed at their own heights, so the picture is right as well as the behaviour -- and because a
 *  height is layout and nothing else, placing them changed not one number.
 */
/**
 *  Orders are made up at the pharmacy and are wanted on the ward below.
 *
 *  @param numPorters how many porters serve the circuit
 *  @param shaftLength how far a lift ride is, and so how long the shaft is held
 *  @param ordersInCirculation how much work is outstanding at any moment. Finishing an order
 *    releases the next one, so this number is constant for the whole run.
 */
class MultiFloorHospitalExample(
    parent: ModelElement,
    val numPorters: Int,
    shaftLength: Double,
    ordersInCirculation: Int,
    name: String? = null
) : ProcessModel(parent, name) {

    /**
     *  How much work is outstanding. Read only in [initialize], so it is a genuine input a
     *  scenario can override rather than a structural choice baked into the constructor.
     */
    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0)
    var ordersInCirculation: Int = ordersInCirculation
        set(value) {
            require(value > 0) { "There must be at least one order in circulation." }
            require(!model.isRunning) { "Cannot change the outstanding work while the model is running." }
            field = value
        }

    val network = createHospitalNetwork(shaftLength)

    init {
        spatialModel = network
    }

    val agv = AgvSystem(this, network, name = "Agv")

    val porters: List<AgvVehicle> = (1..numPorters).map { i ->
        AgvVehicle(
            agv, TransporterPlacement.At(parkingSpur(i)), ConstantRV(porterSpeed), name = "Porter$i"
        ).apply { homeBase = parkingSpur(i) }
    }

    private val myDelivered = Counter(this, "Delivered")
    val delivered: CounterCIfc
        get() = myDelivered

    private val myCycleTime = Response(this, "CycleTime")
    val cycleTime: ResponseCIfc
        get() = myCycleTime

    /** The fleet's average blocked fraction, observed once per replication so that it carries a
     *  confidence interval like any other response. Averaging the porters' across-replication
     *  averages afterwards would give the same point estimate and no interval at all. */
    private val myFleetBlocked = Response(this, "FleetFracBlocked")
    val fleetBlocked: ResponseCIfc
        get() = myFleetBlocked

    private val myPreparation = RandomVariable(
        this, ExponentialRV(meanPreparation, streamNum = 1), name = "PreparationTime"
    )
    val preparationRV: RandomVariableCIfc
        get() = myPreparation

    private inner class Order : Entity() {
        val delivery: KSLProcess = process(isDefaultProcess = true) {
            val placed = time
            currentLocation = network.requireLocation(pharmacy)
            delay(myPreparation)
            transportByFleet(agv, destination = ward, origin = pharmacy)
            myCycleTime.value = time - placed
            myDelivered.increment()
            // The shelf is never the constraint: the next order is ready the moment this one
            // is delivered, which is what holds the outstanding work constant.
            activate(Order().delivery)
        }
    }

    override fun initialize() {
        repeat(ordersInCirculation) { activate(Order().delivery) }
    }

    override fun replicationEnded() {
        super.replicationEnded()
        myFleetBlocked.value =
            porters.sumOf { it.fracTimeBlocked.withinReplicationStatistic.weightedAverage } / numPorters
    }

}

private val pharmacy = "Pharmacy"
private val ward = "WardA"
private val lobby = "Lobby"

/** The porters' parking spurs, one apiece. Two porters cannot stand in one zone. */
private fun parkingSpur(i: Int): String = "Park$i"

private val porterSpeed = 10.0

/** The circuit is held at this length whatever the shaft costs, so the fleet studies compare. */
private val circuitLength = 400.0

/** Time to make an order up at the pharmacy, which also keeps the fleet from phase-locking. */
private val meanPreparation = 1.0

private val maxPorters = 8

/**
 *  The hospital, with the lift ride costing [shaftLength] of travel.
 *
 *  The two corridor legs that are not fixed at 60 absorb whatever the shafts do not use, which
 *  is what holds the circuit at 400 across configurations. Coordinates are supplied for
 *  drawing only: routing reads the declared lengths, which is precisely why a network can span
 *  floors at all.
 */
private fun createHospitalNetwork(shaftLength: Double): GuidedPathNetwork {
    val corridor = (circuitLength - 2.0 * shaftLength - 120.0) / 2.0
    require(corridor > 0.0) { "the shafts leave no room for corridors" }
    val builder = GuidedPathNetwork.builder("Hospital")
        .intersection("G1", x = 0.0, y = 0.0)
        .intersection("G2", x = 60.0, y = 0.0)
        .intersection("G3", x = 60.0 + corridor, y = 0.0)
        // The first floor sits directly above the ground floor. Before an intersection carried
        // a height this layout had to offset the upper floor in y to be drawable at all, which
        // put the wards somewhere they are not. The heights are layout only: routing reads
        // declared link lengths and never a coordinate.
        .intersection("F1", x = 60.0 + corridor, y = 0.0, z = shaftLength)
        .intersection("F2", x = 60.0, y = 0.0, z = shaftLength)
        .intersection("F3", x = 0.0, y = 0.0, z = shaftLength)
        .link("GroundA", "G1", "G2", length = 60.0, zoneLength = 10.0, beginDirection = 0.0)
        .link("GroundB", "G2", "G3", length = corridor, zoneLength = 10.0, beginDirection = 0.0)
        // The lift: one zone, so exactly one porter may be inside it at a time.
        .link("ShaftUp", "G3", "F1", length = shaftLength, zoneLength = shaftLength, beginDirection = 90.0)
        .link("FirstA", "F1", "F2", length = corridor, zoneLength = 10.0, beginDirection = 180.0)
        .link("FirstB", "F2", "F3", length = 60.0, zoneLength = 10.0, beginDirection = 180.0)
        .link("ShaftDown", "F3", "G1", length = shaftLength, zoneLength = shaftLength, beginDirection = 270.0)
        .station(lobby, "G1")
        .station(ward, "G2")
        .station(pharmacy, "F2")
    // Parking for the largest fleet studied, whatever this configuration runs -- so the network is
    // the same object at every fleet size and a sweep over porters really is a sweep over porters.
    // A spur each rather than a shared lobby because porters "at the lobby" would be several
    // vehicles in one zone, which a guide path does not allow, and a porter left standing on the
    // circuit would deny that space to everyone else for the rest of the run.
    for (i in 1..maxPorters) {
        builder.intersection("P$i", x = -16.0 - 6.0 * i, y = -16.0)
            .link(
                "Spur$i", "G1", "P$i", length = 20.0, zoneLength = 20.0,
                type = LinkType.SPUR, beginDirection = 225.0
            )
            .station(parkingSpur(i), "P$i")
    }
    return builder.build()
}

private val replications = 4
private val horizon = 4_000.0
private val warmUp = 500.0

/** How much work is outstanding: enough that a porter never waits for one, and no more. */
private fun ordersFor(numPorters: Int): Int = numPorters + 2

/** Deliveries per 100 time units, which is the quantity a capacity study is about. */
private fun throughputPer100(deliveries: Double): Double = 100.0 * deliveries / (horizon - warmUp)

/**
 *  One scenario per fleet size, all on the same lift. Every scenario is a fresh model because the
 *  fleet size is structural: `numPorters` decides how many [AgvVehicle] elements the model builds,
 *  and a model element cannot be added to a model that has already been built. So this is a runner
 *  over model instances rather than over control values, and no [ksl.controls.KSLControl] could
 *  make it otherwise.
 *
 *  The *network* is not what makes it structural. It carries parking for the largest fleet studied
 *  in every configuration, which is deliberate: the layout is then identical across the sweep, and
 *  a difference between two rows cannot be a difference between two networks.
 */
private fun buildHospitalRunner(name: String, shaftLength: Double, sizes: List<Int>): ScenarioRunner {
    val runner = ScenarioRunner(name)
    for (n in sizes) {
        val m = Model("${name}_$n")
        MultiFloorHospitalExample(m, n, shaftLength, ordersFor(n), name = "Hospital")
        runner.addScenario(
            model = m, name = "Porters$n", inputs = emptyMap(),
            numberReplications = replications, lengthOfReplication = horizon,
            lengthOfReplicationWarmUp = warmUp
        )
    }
    return runner
}

private fun runWatchedHospital(): WatchedHospital {
    val m = Model("Hospital-Watched")
    val h = WatchedHospital(
        m, numPorters = 3, shaftLength = 80.0, ordersInCirculation = 3, horizon = 600.0,
        name = "Watched"
    )
    m.numberOfReplications = 1
    m.lengthOfReplication = 600.0
    m.simulate()
    return h
}

/**
 *  Runs one fleet sweep and prints it. The full half-width summary report for every fleet size
 *  goes to the KSL output file; the console gets the three columns the study is about, each
 *  with its half-width, because a capacity ceiling that is inside the sampling error is not a
 *  ceiling.
 */
private fun fleetTable(title: String, name: String, shaftLength: Double, sizes: List<Int>) {
    val rideTime = shaftLength / porterSpeed
    val runner = buildHospitalRunner(name, shaftLength, sizes)
    runner.simulate()
    runner.write()
    println("  $title")
    println(
        "  a ride costs %.1f time units, so the shaft passes at most %.2f deliveries per 100"
            .format(rideTime, 100.0 / rideTime)
    )
    println()
    println(
        "    %7s %9s %11s %9s %11s %8s %11s %8s".format(
            "porters", "orders", "deliveries", "hw", "cycle time", "hw", "blocked", "hw"
        )
    )
    for (n in sizes) {
        val run = checkNotNull(runner.scenarioByName("Porters$n")?.simulationRun) {
            "scenario Porters$n did not run"
        }
        val stats = run.acrossReplicationStatistics()
        val d = checkNotNull(stats["Delivered"]) { "no Delivered response" }
        val c = checkNotNull(stats["CycleTime"]) { "no CycleTime response" }
        val b = checkNotNull(stats["FleetFracBlocked"]) { "no FleetFracBlocked response" }
        println(
            "    %7d %9d %11.1f %9.1f %11.2f %8.2f %11.4f %8.4f".format(
                n, ordersFor(n), d.average, d.halfWidth, c.average, c.halfWidth,
                b.average, b.halfWidth
            )
        )
    }
    println()
    println("    throughput per 100 units: " + sizes.joinToString {
        val run = runner.scenarioByName("Porters$it")!!.simulationRun!!
        "%d:%.3f".format(it, throughputPer100(run.acrossReplicationStatistics()["Delivered"]!!.average))
    })
    println()
}

/**
 *  The same hospital, watched. Sampling is on the half-tick so that an observer does not compete
 *  with the zone transitions for ordering at the instants they happen: with a constant velocity
 *  and equal zone lengths every transition lands on a whole number here, and an observer
 *  scheduled at those same instants sees whichever side of them event priority puts it on. That
 *  is not a subtlety of this subsystem -- it is what makes a deterministic model easy to observe
 *  wrongly -- and it reported an unused lift in a model that was plainly using one.
 */
class WatchedHospital(
    parent: ModelElement,
    numPorters: Int,
    shaftLength: Double,
    ordersInCirculation: Int,
    private val horizon: Double,
    name: String? = null
) : ProcessModel(parent, name) {

    private val inner = MultiFloorHospitalExample(
        this, numPorters, shaftLength, ordersInCirculation, name = "Hospital"
    )

    val network get() = inner.network
    val delivered get() = inner.delivered

    /** How many of the samples found the up shaft reserved by somebody. */
    var samples: Int = 0
        private set
    var samplesHeld: Int = 0
        private set

    /** Which porters were ever seen holding it. */
    val holders: MutableSet<String> = sortedSetOf()

    /** The largest number of porters found inside the shaft at once. */
    var maxInShaft: Int = 0
        private set

    override fun initialize() {
        samples = 0
        samplesHeld = 0
        holders.clear()
        maxInShaft = 0
        var t = 0.5
        while (t < horizon) {
            schedule(::sampleShaft, t)
            t += 1.0
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun sampleShaft(event: KSLEvent<Nothing>) {
        val shaft = network.link("ShaftUp")!!.zones
        // `hasHolder`, not `isCovered` -- see the note in this file's header.
        val inside = shaft.count { it.hasHolder }
        samples++
        if (inside > 0) samplesHeld++
        if (inside > maxInShaft) maxInShaft = inside
        shaft.forEach { z -> z.holder?.let { holders.add(it.name) } }
    }
}

fun main() {

    println()
    println("A hospital on two floors - and no lift class anywhere in it")
    println()

    val watched = runWatchedHospital()
    val wardLocation = watched.network.requireLocation(ward)
    val pharmacyLocation = watched.network.requireLocation(pharmacy)
    println("  Study 1: three porters, one shaft, watched for 600 time units")
    println(
        "    is the first floor reachable from the ground floor? %s"
            .format(watched.network.isReachable(wardLocation, pharmacyLocation))
    )
    println("    routed distance, ward to pharmacy:       %8.1f".format(watched.network.distance(wardLocation, pharmacyLocation)))
    println("    deliveries completed:                    %8.0f".format(watched.delivered.value))
    println(
        "    fraction of samples with the shaft held: %8.4f".format(
            watched.samplesHeld.toDouble() / watched.samples
        )
    )
    println("    most porters ever inside the shaft:      %8d".format(watched.maxInShaft))
    println("    porters seen using it:                   %s".format(watched.holders.joinToString(", ")))
    println()

    fleetTable(
        "Study 2: a slow lift - an 8 unit ride, 60 unit corridors, circuit 400",
        name = "HospitalSlowLift", shaftLength = 80.0, sizes = listOf(1, 2, 3, 4, 6, 8)
    )
    fleetTable(
        "Study 3: a fast lift - a 2 unit ride, 120 unit corridors, circuit still 400",
        name = "HospitalFastLift", shaftLength = 20.0, sizes = listOf(1, 2, 3, 4, 6, 8)
    )
    println("  Full half-width summary reports for every fleet size: ${KSL.outDir}")
    println()
}
