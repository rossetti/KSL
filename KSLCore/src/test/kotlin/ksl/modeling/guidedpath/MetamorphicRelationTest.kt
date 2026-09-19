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
package ksl.modeling.guidedpath

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.CyclicalTransporterRule
import ksl.modeling.guidedpath.rules.GuidedTransporterAllocationRuleIfc
import ksl.modeling.guidedpath.rules.DistanceIntoZoneControl
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Statistic
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ksl.modeling.agent.AgentModel
import org.slf4j.LoggerFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  Two hundred guide paths nobody designed, each run twice, checked against **relations between the
 *  two answers** rather than against either answer.
 *
 *  Every other check in this suite needs somebody to know what the right answer is: a closed form, a
 *  reference model, a hand-computed cycle time. That is what limits them -- they can only be applied
 *  to layouts small enough or regular enough for a person to solve, and those are exactly the
 *  layouts least likely to be hiding a defect. This test needs no such oracle. It transforms a model
 *  in a way whose effect on the output is known *by construction* -- multiply every length and every
 *  velocity by four and nothing measured in time may move -- runs both, and compares. The right
 *  answer never has to be known, so the networks can be arbitrary, and they are: they are generated.
 *
 *  ## The corpus
 *
 *  A fixed corpus of two hundred seeds, not fresh randomness on each run. A test that fails only
 *  sometimes is a bad citizen in a suite of five thousand, and a seed that ever finds a defect is
 *  worth more as a permanent regression than as a lottery ticket. The generator is hand-rolled,
 *  since no property-testing library is in this build, and that turns out to be the better choice
 *  anyway: it generates **valid by construction** rather than generating and rejecting, so no draw
 *  is wasted and no failure is ever the builder refusing a malformed network.
 *
 *  Two constraints on what is generated are load-bearing, and are stated here rather than left to be
 *  discovered: every link is one-way, and every vehicle has a private dead-end spur it goes home to.
 *  A one-way network cannot deadlock head-on, and an idle vehicle on its own spur is out of the
 *  traffic entirely. Without both, a fair share of the corpus would legitimately stall and the
 *  relations below would be falsified by correct behaviour. That is the standing price of an
 *  oracle-free test: it can only be run over configurations in which the relation is known to hold.
 *
 *  Within those constraints the draw is wide: three to eight junctions, four link lengths, two zone
 *  sizes, an optional chord, one to three carts, and **one of all three shipped zone control rules**,
 *  so every relation below is checked under each of them rather than under the default. Drawing the
 *  distance rule is deliberate. It carries a **length** as a parameter, and a length that failed to
 *  scale with the network is precisely the defect the dimensional relation exists to catch; without
 *  it in the corpus there would be no such length to get wrong.
 *
 *  ## The relations
 *
 *  Output is fingerprinted by physical dimension -- quantities measured in time, quantities measured
 *  in distance, and quantities measured in neither: counts, ratios, and fractions of a vehicle's
 *  day. A transformation then states what it does to each dimension, and everything it does not
 *  claim must be untouched. The fingerprint carries the **ordered list of completion times**, not
 *  merely their mean, so that a transformation which reorders the work without changing the average
 *  of it is still caught.
 *
 *  | Relation | What only it can catch |
 *  |---|---|
 *  | **Repetition** -- the same specification, run again | Any dependence on the order a hash happened to iterate in |
 *  | **Dimensional scaling** -- every length and velocity times `a` | A constant baked in with units attached |
 *  | **Time scaling** -- velocities times `b`, every input duration divided by `b` | The same, on the temporal axis |
 *  | **Declaration order** -- links and vehicles built in a shuffled order | A decision made by construction order rather than by the stated rule |
 *  | **Renaming** -- every element renamed, by a map that reverses their alphabetical order | A rule tie-breaking on names without saying so |
 *  | **Inert addition** -- an unused spur and an unused station spliced in | Statistics or routing iterating over everything rather than over what matters |
 *  | **Paradigm equivalence** -- the same generated network modelled both ways | Semantic drift between the two subsystems |
 *
 *  Four of the first six are asserted **exactly**: not to a tolerance but to the last bit, because
 *  the arithmetic the two runs perform is meant to be identical and not merely close. The two scaling
 *  relations are asserted to a relative 1e-9, which is generous -- the factors are powers of two, so
 *  the scaling is exact in binary and the observed gaps are zero -- but a tolerance is what the
 *  relation actually claims, so a tolerance is what it is given.
 *
 *  One exclusion, and it is a design decision rather than an omission. The pool is handed its fleet
 *  as a list whose **order is a stated part of its contract**: `ClosestByNetworkDistanceRule` breaks
 *  distance ties by position in it, and says so. The declaration-order relation therefore permutes
 *  the order in which vehicles are *constructed* while leaving the order they are *pooled* in alone.
 *  Permuting the pool's list would change a documented behaviour, which is not the same thing as
 *  exposing a defect.
 *
 *  ## The monotone relations, and the one the corpus refuted
 *
 *  Weaker than the exact relations, and honest about it. Three were planned: an extra cart must not
 *  take longer to clear the same batch of work, finer zoning must not take longer, and a faster
 *  fleet must not take longer. Two of the three hold across the corpus and are asserted. The third
 *  does not, and finding that out is the most interesting thing this test has done.
 *
 *  They are timed by **makespan** -- how long a fixed batch of eighty loads takes -- and not by
 *  completions in a fixed horizon, because that second measure is biased against larger fleets. A
 *  load in flight when the clock stops counts for nothing, and the number in flight at any instant
 *  grows with the fleet, so a bigger fleet is systematically docked a load or two it has nearly
 *  finished. The bias is the size of the effect being looked for, and it duly showed up as one load
 *  in two hundred and ninety before the measure was changed.
 *
 *  **Finer zoning is not monotone, and the reason is not in the movement engine.** Splitting every
 *  zone in two is strictly more permissive about path occupancy, so it ought never to cost anything.
 *  Over sixty networks it made the makespan longer on three, shorter on four, and left it untouched
 *  on fifty-three. Running the counterexamples down:
 *
 *  - At **fleet sizes one and two** the coarse and fine runs are identical to the bit. Refining the
 *    zones is a no-op when nothing contends, which is what it should be, and which is what the
 *    `finerZoningIsANoOpWithoutContention` relation now asserts over the whole small corpus.
 *  - At **fleet size three** on seed 8 the fine run has *less* blocking -- none at all, against the
 *    coarse run's -- and is nonetheless twelve units slower, with the whole difference sitting in
 *    empty travel. Replacing the nearest-vehicle rule with one that reads no position at all makes
 *    the two runs identical again. The cause is that `ClosestByNetworkDistanceRule` reads a cart's
 *    position **at zone resolution**: refining the zones refines the reading, a different cart wins
 *    the comparison, and the greedy choice made with better information turns out worse.
 *  - Blind dispatching narrows it but does not remove it, because zoning still changes *when* each
 *    cart falls idle, and which cart is idle decides how far the next one travels empty.
 *
 *  The quantitative point underneath is worth stating plainly: on these networks **blocking is a
 *  rounding error next to dispatching**. Mean blocked time per transport runs around 0.01 to 0.04
 *  against journey times of six to nine, so a change that improves blocking is easily swamped by the
 *  empty travel it shifts around. A modeller tuning zone size for throughput is tuning the smaller
 *  of the two terms.
 *
 *  What is asserted is therefore the sound half -- refining a zone changes no journey time when
 *  there is nothing to contend for -- and the refuted half is recorded here rather than weakened
 *  into an assertion that would pass without meaning anything. For the two relations that do hold,
 *  a future violation is a **finding to investigate** rather than automatically a defect: they are
 *  asserted only inside a stated regime, a fleet capped at one cart per six zones of circuit on a
 *  one-way network with private spurs, and the failure message says so.
 *
 *  ## What this does not establish
 *
 *  Every relation here is about *internal consistency*. A subsystem uniformly wrong in the same way
 *  in both runs satisfies all of them. This is verification, and it is no substitute for
 *  `QueueingLimitsTest`, which checks the subsystem against arithmetic that predates it, or for the
 *  cross-checks, which check it against an independently built model of the same shop. What it adds
 *  that neither of those can is **breadth**: they check a handful of layouts somebody chose, and
 *  this checks two hundred that nobody did.
 *
 *  ## Two things that come for free
 *
 *  Space-exclusivity checking and the closing audit are on for every test JVM, so all of these runs
 *  are additionally asserting, continuously, that no two vehicles ever shared a zone and that every
 *  clock added up. The combination is worth more than either part alone: two hundred random networks
 *  each checked at every clock advance is a wider net than the invariant harness has otherwise been
 *  cast over.
 *
 *  And when a relation does fail, the failure is shrunk before it is reported. The seed is re-run
 *  with the chord removed, the fleet cut to one, and the geometry flattened, and the **smallest
 *  configuration that still violates** is what the message names. A four-junction one-cart
 *  counterexample can be reasoned about by hand; the eight-junction three-cart network it was found
 *  in cannot.
 */
class MetamorphicRelationTest {

    private companion object {

        /** The corpus. Fixed, so that a failure is reproducible and a fix is provable. */
        val CORPUS: IntRange = 1..200

        /** Enough of it for a comparison that costs two paradigms, or several fleet sizes, per seed. */
        val SMALL_CORPUS: IntRange = 1..60

        /** Below this a run has done too little for any relation over it to be evidence. */
        const val MIN_COMPLETIONS: Double = 20.0

        /** How much work the monotone relations time. Every fleet size is given the same batch. */
        const val BATCH: Long = 80L

        /** How many spurs every generated network has, whatever size fleet is put on it. */
        const val ROSTER_SIZE: Int = 3

        /** Exact. The two runs are meant to do identical arithmetic, so any gap at all is news. */
        const val EXACT: Double = 0.0

        /** For the scaling relations, whose factors are exact in binary but need not have been. */
        const val NEARLY_EXACT: Double = 1.0e-9

        /**
         *  Baselines, computed once and shared by every relation.
         *
         *  Each relation would otherwise re-run the untransformed model, which is half the cost of
         *  the whole test for none of the information.
         */
        val baselines = HashMap<Int, Fingerprint>()
    }

    // ---- the specification -----------------------------------------------------------------------

    data class LinkSpec(
        val name: String,
        val from: String,
        val to: String,
        val length: Double,
        val zoneLength: Double,
        val type: LinkType
    )

    data class VehicleSpec(val name: String, val home: String)

    /** Which of the three shipped rules decides when a vehicle gives up the zone behind it. */
    enum class ZoneControl { START, END, DISTANCE }

    /**
     *  Which idle cart is sent. `CLOSEST` reads each cart's position, and reads it **at zone
     *  resolution**; `CYCLICAL` reads nothing positional at all. The difference matters to exactly
     *  one relation here, and the class comment says which and why.
     */
    enum class Allocation { CLOSEST, CYCLICAL }

    /**
     *  A whole model as data, so that it can be transformed rather than rebuilt.
     *
     *  @param links in the order they are declared, which is what the declaration-order relation
     *    permutes
     *  @param vehicles the vehicles actually built, in the order they are built
     *  @param fleetOrder the order the pool is given them in, which is a stated part of its contract
     *    and is therefore never permuted
     *  @param roster every vehicle the network has a spur for, built or not, so that fleet size can
     *    be varied over a network that does not otherwise change
     */
    data class ModelSpec(
        val links: List<LinkSpec>,
        val stations: List<Pair<String, String>>,
        val vehicles: List<VehicleSpec>,
        val fleetOrder: List<String>,
        val roster: List<VehicleSpec>,
        val pickup: String,
        val drop: String,
        val velocity: Double,
        val meanTba: Double,
        val loading: Double,
        val unloading: Double,
        val horizon: Double,
        val zoneControl: ZoneControl,
        val releaseDistance: Double,
        val arrivalLimit: Long = Long.MAX_VALUE,
        val allocation: Allocation = Allocation.CLOSEST
    ) {

        fun allocationRule(): GuidedTransporterAllocationRuleIfc = when (allocation) {
            Allocation.CLOSEST -> ClosestByNetworkDistanceRule()
            Allocation.CYCLICAL -> CyclicalTransporterRule()
        }
        /**
         *  A fresh rule per vehicle. `releaseDistance` is a **length**, which is the whole reason
         *  the distance rule is in the corpus: a length that failed to scale with the network would
         *  falsify the dimensional relation, and nothing else here would notice.
         */
        fun controlRule(): ZoneControlRuleIfc = when (zoneControl) {
            ZoneControl.START -> StartOfZoneControl()
            ZoneControl.END -> EndOfZoneControl()
            ZoneControl.DISTANCE -> DistanceIntoZoneControl(releaseDistance)
        }

        val shape: String
            get() = "${links.count { it.type != LinkType.SPUR }} links, " +
                    "${links.sumOf { (it.length / it.zoneLength).toInt() }} zones, " +
                    "${fleetOrder.size} cart(s), $zoneControl control"
    }

    // ---- transformations -------------------------------------------------------------------------

    /** Every length and every velocity multiplied by the same factor. No time may move. */
    private fun ModelSpec.scaledInSpace(a: Double): ModelSpec = copy(
        links = links.map { it.copy(length = it.length * a, zoneLength = it.zoneLength * a) },
        velocity = velocity * a,
        releaseDistance = releaseDistance * a
    )

    /** Velocities multiplied and every input duration divided. Times scale; nothing counted moves. */
    private fun ModelSpec.scaledInTime(b: Double): ModelSpec = copy(
        velocity = velocity * b,
        meanTba = meanTba / b,
        loading = loading / b,
        unloading = unloading / b,
        horizon = horizon / b
    )

    /**
     *  The same model built in a different order.
     *
     *  `fleetOrder` is deliberately left alone: see the exclusion in the class comment.
     */
    private fun ModelSpec.permuted(rng: Random): ModelSpec = copy(
        links = links.shuffled(rng),
        vehicles = vehicles.shuffled(rng)
    )

    /**
     *  Every name replaced by one that sorts the other way round.
     *
     *  Reversing the order is the point. A map that preserved it would leave a rule which quietly
     *  sorted by name behaving exactly as before, and the relation would pass without having tested
     *  anything at all.
     */
    private fun ModelSpec.renamed(): ModelSpec {
        val names = LinkedHashSet<String>()
        for (l in links) { names.add(l.name); names.add(l.from); names.add(l.to) }
        for ((alias, at) in stations) { names.add(alias); names.add(at) }
        for (v in roster) { names.add(v.name); names.add(v.home) }
        val ordered = names.toList()
        val map = ordered.withIndex().associate { (i, n) -> n to "X%04d".format(ordered.size - i) }
        fun f(s: String) = map.getValue(s)
        return copy(
            links = links.map { it.copy(name = f(it.name), from = f(it.from), to = f(it.to)) },
            stations = stations.map { (alias, at) -> f(alias) to f(at) },
            vehicles = vehicles.map { it.copy(name = f(it.name), home = f(it.home)) },
            fleetOrder = fleetOrder.map(::f),
            roster = roster.map { it.copy(name = f(it.name), home = f(it.home)) },
            pickup = f(pickup),
            drop = f(drop)
        )
    }

    /** A spur and a station that nothing ever uses, spliced in at a random position. */
    private fun ModelSpec.withInertSpur(rng: Random): ModelSpec {
        val unit = links.first().zoneLength
        val junctions = links.map { it.from }.distinct()
        val at = junctions[rng.nextInt(junctions.size)]
        val spur = LinkSpec("InertSpur", at, "InertEnd", 2.0 * unit, unit, LinkType.SPUR)
        val where = rng.nextInt(links.size + 1)
        return copy(
            links = links.subList(0, where) + spur + links.subList(where, links.size),
            stations = stations + ("InertStation" to at)
        )
    }

    /** The first [n] of the roster. The network is untouched: every spur is there either way. */
    private fun ModelSpec.withFleet(n: Int): ModelSpec = copy(
        vehicles = roster.take(n), fleetOrder = roster.take(n).map { it.name }
    )

    private fun ModelSpec.withVelocity(v: Double): ModelSpec = copy(velocity = v)

    /** Dispatching that cannot see where anything is, so that only contention is left to vary. */
    private fun ModelSpec.withBlindDispatching(): ModelSpec = copy(allocation = Allocation.CYCLICAL)

    /** Every zone split into [k], so control is finer over exactly the same geometry. */
    private fun ModelSpec.withFinerZones(k: Int): ModelSpec = copy(
        links = links.map { it.copy(zoneLength = it.zoneLength / k) },
        releaseDistance = releaseDistance / k
    )

    /**
     *  A fixed batch of work, arriving faster than the fleet can serve it.
     *
     *  The monotone relations are stated over **how long a fixed amount of work takes**, not over
     *  how much work fits in a fixed horizon. The difference is not cosmetic. Counting completions
     *  in a terminating run is biased against larger fleets: a load in flight when the clock stops
     *  counts for nothing, and the number in flight at any instant grows with the fleet, so a bigger
     *  fleet is systematically docked a load or two it has very nearly finished. That bias is the
     *  size of the effect being looked for, and it showed up as exactly that -- one load in 290 --
     *  before this was changed. A makespan has no such boundary.
     */
    private fun ModelSpec.saturated(): ModelSpec {
        val cycle = links.filter { it.type == LinkType.UNIDIRECTIONAL }.sumOf { it.length } / velocity
        return copy(meanTba = 0.4 * cycle, horizon = 200.0 * cycle, arrivalLimit = BATCH)
    }

    // ---- the models --------------------------------------------------------------------------------

    private fun buildNetwork(spec: ModelSpec): GuidedPathNetwork {
        val b = GuidedPathNetwork.builder("Gen")
        for (l in spec.links) b.link(l.name, l.from, l.to, l.length, l.zoneLength, l.type)
        for ((alias, at) in spec.stations) b.station(alias, at)
        return b.build()
    }

    /** The passive paradigm: the entity claims a cart, steers it, and gives it back. */
    private class Shop(parent: ModelElement, private val spec: ModelSpec, network: GuidedPathNetwork) :
        ProcessModel(parent, "Shop") {

        val network: GuidedPathNetwork = network

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        private val carts = LinkedHashMap<String, GuidedTransporter>()

        init {
            for (v in spec.vehicles) {
                carts[v.name] = GuidedTransporter(
                    system, TransporterPlacement.At(v.home), ConstantRV(spec.velocity),
                    1, spec.controlRule(), v.name
                ).apply { homeBase = v.home }
            }
        }

        /** In the order the specification states, which is the order the pool breaks ties in. */
        val fleet: List<GuidedTransporter> = spec.fleetOrder.map { carts.getValue(it) }

        val pool = GuidedTransporterPoolWithQ(
            this, system, fleet, spec.allocationRule(), ReturnToHomeBaseRule(), "Pool"
        )

        val totalTime = Statistic("total")
        val routeLength = Statistic("route")
        val emptyTime = Statistic("empty")
        val loadedTime = Statistic("loaded")
        val blockedTime = Statistic("blocked")
        val zonesTraversed = Statistic("zones")

        /** When each load was set down, in the order they were. Sharper than any mean of them. */
        val completionTimes = mutableListOf<Double>()

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(spec.pickup)
                val started = time
                val r = guidedTransport(
                    pool, spec.drop, pickupLocation = spec.pickup,
                    loadingDelay = ConstantRV(spec.loading),
                    unLoadingDelay = ConstantRV(spec.unloading)
                )
                totalTime.collect(time - started)
                routeLength.collect(r.routeLength)
                emptyTime.collect(r.approachTime)
                loadedTime.collect(r.rideTime)
                blockedTime.collect(r.blockedTime)
                zonesTraversed.collect(r.zonesTraversed.toDouble())
                completionTimes.add(time)
            }
        }

        private val tba = ExponentialRV(spec.meanTba, streamNum = 1)
        private val generator = EntityGenerator(::Part, tba, tba, spec.arrivalLimit)

        override fun initialize() {
            for (s in listOf(totalTime, routeLength, emptyTime, loadedTime, blockedTime, zonesTraversed)) {
                s.reset()
            }
            completionTimes.clear()
        }
    }

    /** The active paradigm: the entity says what it needs and suspends; a dispatcher decides. */
    private class AgvShop(parent: ModelElement, private val spec: ModelSpec, network: GuidedPathNetwork) :
        ProcessModel(parent, "AgvShop") {

        val network: GuidedPathNetwork = network

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        val fleet: List<AgvVehicle> = spec.vehicles.map { v ->
            AgvVehicle(
                agv, TransporterPlacement.At(v.home), ConstantRV(spec.velocity),
                1, spec.controlRule(), v.name
            ).apply { homeBase = v.home }
        }

        val totalTime = Statistic("total")
        val routeLength = Statistic("route")

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(spec.pickup)
                val started = time
                val r = transportByFleet(
                    agv, spec.drop, origin = spec.pickup,
                    loadingDelay = ConstantRV(spec.loading),
                    unLoadingDelay = ConstantRV(spec.unloading)
                )
                totalTime.collect(time - started)
                routeLength.collect(r.routeLength)
            }
        }

        private val tba = ExponentialRV(spec.meanTba, streamNum = 1)
        private val generator = EntityGenerator(::Part, tba, tba, spec.arrivalLimit)

        override fun initialize() {
            totalTime.reset()
            routeLength.reset()
        }
    }

    // ---- what a run is compared by ---------------------------------------------------------------

    /**
     *  A run's output, split by physical dimension so that a relation can say how each part must
     *  move.
     *
     *  `time` scales with the clock, `space` with distance, `pure` with neither. A transformation
     *  states a factor for the first two; everything in `pure` must survive all of them untouched.
     */
    class Fingerprint(
        val time: Map<String, Double>,
        val space: Map<String, Double>,
        val pure: Map<String, Double>
    )

    private fun fingerprint(shop: Shop): Fingerprint {
        val time = LinkedHashMap<String, Double>()
        val space = LinkedHashMap<String, Double>()
        val pure = LinkedHashMap<String, Double>()

        time["meanTotalTime"] = shop.totalTime.average
        time["meanEmptyMove"] = shop.emptyTime.average
        time["meanLoadedMove"] = shop.loadedTime.average
        time["meanBlocked"] = shop.blockedTime.average
        time["meanWaitForCart"] = shop.pool.waitingQ.timeInQ.withinReplicationStatistic.weightedAverage
        time["makespan"] = shop.completionTimes.lastOrNull() ?: 0.0
        shop.completionTimes.forEachIndexed { i, t -> time["completion[$i]"] = t }

        space["meanRouteLength"] = shop.routeLength.average

        pure["completions"] = shop.totalTime.count
        pure["meanZonesTraversed"] = shop.zonesTraversed.average
        pure["numZoneTraversals"] = shop.system.numZoneTraversals.value
        pure["numEventsScheduled"] = shop.system.numEventsScheduled.value
        // Keyed by position in the fleet, never by name, so that renaming cannot silently compare
        // one cart against a different one.
        shop.fleet.forEachIndexed { i, c ->
            pure["cart[$i].fracTransporting"] = c.fracTimeTransporting.withinReplicationStatistic.weightedAverage
            pure["cart[$i].fracMovingEmpty"] = c.fracTimeMovingEmpty.withinReplicationStatistic.weightedAverage
            pure["cart[$i].fracBlocked"] = c.fracTimeBlocked.withinReplicationStatistic.weightedAverage
            pure["cart[$i].timesBlocked"] = c.numTimesBlocked.value
        }
        return Fingerprint(time, space, pure)
    }

    private fun run(spec: ModelSpec, label: String): Fingerprint {
        val m = Model(label)
        val shop = Shop(m, spec, buildNetwork(spec))
        m.numberOfReplications = 1
        m.lengthOfReplication = spec.horizon
        m.simulate()
        return fingerprint(shop)
    }

    private fun baseline(seed: Int): Fingerprint =
        baselines.getOrPut(seed) { run(generate(seed), "Base-$seed") }

    // ---- acceptance ---------------------------------------------------------------------------------

    /**
     *  How [b] fails to be [a] with times multiplied by [timeFactor] and distances by [spaceFactor],
     *  or null when the relation holds.
     */
    private fun violation(
        a: Fingerprint,
        b: Fingerprint,
        timeFactor: Double = 1.0,
        spaceFactor: Double = 1.0,
        tolerance: Double = EXACT,
        includeCounts: Boolean = true
    ): String? {
        fun check(what: String, x: Map<String, Double>, y: Map<String, Double>, factor: Double): String? {
            if (x.keys != y.keys) {
                val missing = (x.keys - y.keys).take(3)
                val extra = (y.keys - x.keys).take(3)
                return "the two runs reported different $what (missing $missing, extra $extra)"
            }
            for ((k, v) in x) {
                val expected = v * factor
                val got = y.getValue(k)
                val gap = abs(got - expected)
                val scale = maxOf(abs(expected), 1.0)
                if (gap / scale > tolerance) {
                    return "$k: expected %.12g, got %.12g (relative gap %.3g)"
                        .format(expected, got, gap / scale)
                }
            }
            return null
        }
        return check("times", a.time, b.time, timeFactor)
            ?: check("distances", a.space, b.space, spaceFactor)
            ?: if (includeCounts) check("counts", a.pure, b.pure, 1.0) else null
    }

    /** A transformation together with what it is claimed to do to each physical dimension. */
    private inner class Relation(
        val name: String,
        val tolerance: Double = EXACT,
        val timeFactor: (Int) -> Double = { 1.0 },
        val spaceFactor: (Int) -> Double = { 1.0 },
        val transform: (Int, ModelSpec) -> ModelSpec
    ) {
        /** Re-derives both sides from scratch, for use on a configuration the corpus never ran. */
        fun check(seed: Int, spec: ModelSpec): String? = violation(
            run(spec, "Shrink-A-$seed"),
            run(transform(seed, spec), "Shrink-B-$seed"),
            timeFactor(seed), spaceFactor(seed), tolerance
        )
    }

    /**
     *  Runs a relation over a corpus, and on the first violation reports the **smallest**
     *  configuration that still violates it rather than the one it happened to be found in.
     */
    private fun assertHolds(relation: Relation, seeds: IntRange = CORPUS) {
        for (seed in seeds) {
            val spec = generate(seed)
            val base = baseline(seed)
            // Without this every relation below would be satisfied by a corpus that had quietly
            // stopped doing anything: two runs of a model that carries no loads agree perfectly.
            assertTrue(
                base.pure.getValue("completions") >= MIN_COMPLETIONS,
                "seed $seed (${spec.shape}) delivered only ${base.pure.getValue("completions")} " +
                        "loads, too few for any relation over it to be evidence"
            )
            val got = run(relation.transform(seed, spec), "${relation.name}-$seed")
            val bad = violation(
                base, got,
                relation.timeFactor(seed), relation.spaceFactor(seed), relation.tolerance
            ) ?: continue
            val (smallest, smallestWhy) = shrink(seed, spec, relation)
            val layout = smallest.links.joinToString("\n             ") {
                "${it.name}: ${it.from} -> ${it.to}, length ${it.length}, zone ${it.zoneLength}, ${it.type}"
            }
            fail(
                """
                |Metamorphic relation '${relation.name}' does not hold.
                |  seed $seed (${spec.shape}): $bad
                |  smallest still violating (${smallest.shape}): $smallestWhy
                |  links:   $layout
                |  carts:   ${smallest.fleetOrder} homed at ${smallest.vehicles.map { it.home }}
                |  loads:   ${smallest.pickup} -> ${smallest.drop} at velocity ${smallest.velocity}
                """.trimMargin()
            )
        }
        println("  %-22s held over %d generated networks".format(relation.name, seeds.count()))
    }

    /**
     *  Cuts a failing configuration down to the smallest one that still fails.
     *
     *  There is no shrinking library here, and a greedy pass over three reductions is worth most of
     *  what one would give. A counterexample on four junctions with one cart can be reasoned about
     *  by hand; the eight-junction three-cart network it was found in cannot.
     */
    private fun shrink(seed: Int, spec: ModelSpec, relation: Relation): Pair<ModelSpec, String> {
        var best = spec
        var why = relation.check(seed, spec) ?: return spec to "(not reproducible on a fresh pair of runs)"
        val reductions: List<(ModelSpec) -> ModelSpec> = listOf(
            { s -> s.copy(links = s.links.filter { it.name != "H0" }) },   // drop the chord
            { s -> s.withFleet(1) },                                      // one cart
            { s ->                                                        // one geometry throughout
                val zone = s.links.maxOf { it.zoneLength }
                s.copy(links = s.links.map { it.copy(length = 2.0 * zone, zoneLength = zone) })
            }
        )
        for (reduce in reductions) {
            val still = try {
                relation.check(seed, reduce(best))
            } catch (e: Exception) {
                null      // the reduction produced a model that will not run; keep what we have
            }
            if (still != null) {
                best = reduce(best)
                why = still
            }
        }
        return best to why
    }

    // ---- the corpus ------------------------------------------------------------------------------------

    /**
     *  A random guide path that is **valid by construction**, so that no draw is wasted and no
     *  failure is ever the builder refusing a malformed network.
     *
     *  Every link is one-way and every vehicle has a private dead-end spur; the class comment says
     *  why both are load-bearing. The roster always holds three spurs even when fewer carts are
     *  built, so that fleet size can be varied over a network that does not otherwise change --
     *  which the inert-addition relation independently establishes costs nothing.
     */
    private fun generate(seed: Int): ModelSpec {
        val rng = Random(seed)
        val n = 3 + rng.nextInt(6)                       // 3..8 junctions on the circuit
        val lengths = listOf(40.0, 60.0, 80.0, 120.0)    // each divisible by either zone size
        val zoneSizes = listOf(10.0, 20.0)

        val links = mutableListOf<LinkSpec>()
        for (i in 0 until n) {
            links.add(
                LinkSpec(
                    "C$i", "I$i", "I${(i + 1) % n}",
                    lengths[rng.nextInt(lengths.size)], zoneSizes[rng.nextInt(zoneSizes.size)],
                    LinkType.UNIDIRECTIONAL
                )
            )
        }
        // A chord skips at least two junctions, so it can never duplicate a circuit link.
        if (n >= 5 && rng.nextBoolean()) {
            val a = rng.nextInt(n)
            val b = (a + 2 + rng.nextInt(n - 3)) % n
            links.add(
                LinkSpec(
                    "H0", "I$a", "I$b",
                    lengths[rng.nextInt(lengths.size)], zoneSizes[rng.nextInt(zoneSizes.size)],
                    LinkType.UNIDIRECTIONAL
                )
            )
        }

        val circuitZones = links.sumOf { (it.length / it.zoneLength).toInt() }
        val roster = mutableListOf<VehicleSpec>()
        for (v in 0 until ROSTER_SIZE) {
            val at = "I${rng.nextInt(n)}"
            links.add(LinkSpec("S$v", at, "H$v", 20.0, 10.0, LinkType.SPUR))
            roster.add(VehicleSpec("V$v", "H$v"))
        }
        // One cart per six zones of circuit: the regime the monotone relations are asserted in, and
        // far enough from gridlock that the corpus does not stall for reasons that are correct.
        val numVehicles = 1 + rng.nextInt(fleetCeilingForZones(circuitZones))

        val a = rng.nextInt(n)
        val b = (a + 1 + rng.nextInt(n - 1)) % n
        val stations = listOf("Pickup" to "I$a", "Drop" to "I$b")

        val velocity = 10.0
        val cycle = links.filter { it.type == LinkType.UNIDIRECTIONAL }.sumOf { it.length } / velocity
        return ModelSpec(
            links = links,
            stations = stations,
            vehicles = roster.take(numVehicles),
            fleetOrder = roster.take(numVehicles).map { it.name },
            roster = roster,
            pickup = "Pickup",
            drop = "Drop",
            velocity = velocity,
            meanTba = 3.0 * cycle / numVehicles,
            loading = 1.0,
            unloading = 2.0,
            horizon = 200.0 * cycle / numVehicles,
            zoneControl = ZoneControl.entries[rng.nextInt(ZoneControl.entries.size)],
            // Half the smallest zone, so the rule genuinely releases inside a zone rather than
            // degenerating to release-at-the-end on every one of them.
            releaseDistance = links.minOf { it.zoneLength } / 2.0
        )
    }

    private fun fleetCeilingForZones(circuitZones: Int): Int =
        minOf(ROSTER_SIZE, maxOf(1, circuitZones / 6))

    /** The largest fleet the generator would put on the circuit it drew for this seed. */
    private fun fleetCeiling(seed: Int): Int = fleetCeilingForZones(
        generate(seed).links.filter { it.type != LinkType.SPUR }
            .sumOf { (it.length / it.zoneLength).toInt() }
    )

    // ---- the exact relations -------------------------------------------------------------------------

    @Test
    @Tag("slow")
    @DisplayName("the same specification run again gives the same numbers, on every generated network")
    fun repetitionChangesNothing() {
        assertHolds(Relation("repetition") { _, s -> s })
    }

    @Test
    @Tag("slow")
    @DisplayName("multiplying every length and every velocity leaves every time exactly where it was")
    fun dimensionalScalingMovesDistancesAndNotTimes() {
        // Both directions, alternating across the corpus, so that neither is the only one tried.
        fun factor(seed: Int) = if (seed % 2 == 0) 4.0 else 0.25
        assertHolds(
            Relation("dimensional scaling", NEARLY_EXACT, spaceFactor = ::factor) { seed, s ->
                s.scaledInSpace(factor(seed))
            }
        )
    }

    @Test
    @Tag("slow")
    @DisplayName("multiplying velocity and dividing every input duration scales every time and no count")
    fun timeScalingMovesTimesAndNotCounts() {
        fun beta(seed: Int) = if (seed % 2 == 0) 2.0 else 0.5
        assertHolds(
            Relation("time scaling", NEARLY_EXACT, timeFactor = { 1.0 / beta(it) }) { seed, s ->
                s.scaledInTime(beta(seed))
            }
        )
    }

    @Test
    @Tag("slow")
    @DisplayName("building the same model in a different order changes nothing")
    fun declarationOrderChangesNothing() {
        assertHolds(Relation("declaration order") { seed, s -> s.permuted(Random(seed + 7919)) })
    }

    @Test
    @Tag("slow")
    @DisplayName("renaming every element, in a way that reverses their order, changes nothing")
    fun renamingChangesNothing() {
        assertHolds(Relation("renaming") { _, s -> s.renamed() })
    }

    @Test
    @Tag("slow")
    @DisplayName("a spur and a station that nothing uses change nothing")
    fun inertAdditionChangesNothing() {
        assertHolds(Relation("inert addition") { seed, s -> s.withInertSpur(Random(seed + 104729)) })
    }

    // ---- the instrument is not vacuous ------------------------------------------------------------------

    @Test
    @DisplayName("the comparison reports a violation when there is one to report")
    fun theComparisonCanFail() {
        // A relation that could not fail proves nothing about the subsystem. The instrument is
        // checked here against transformations whose effect is known to break each claim in turn,
        // so that six passing relations are evidence rather than an artefact of a loose comparison.
        val seed = 1
        val spec = generate(seed)
        val base = baseline(seed)

        val timeScaled = run(spec.scaledInTime(2.0), "Control-time")
        assertTrue(
            violation(base, timeScaled, timeFactor = 1.0, tolerance = NEARLY_EXACT) != null,
            "halving every time went unnoticed when no time factor was claimed"
        )
        // Not merely that a missing factor is caught, but that a nearly-right one is.
        assertTrue(
            violation(base, timeScaled, timeFactor = 1.0 / 1.99, tolerance = NEARLY_EXACT) != null,
            "a time factor that is close but wrong went unnoticed"
        )

        val spaceScaled = run(spec.scaledInSpace(4.0), "Control-space")
        assertTrue(
            violation(base, spaceScaled, spaceFactor = 1.0, tolerance = NEARLY_EXACT) != null,
            "quadrupling every distance went unnoticed"
        )
        // And that the fingerprint responds to a real change in the model, not only to arithmetic.
        val faster = run(spec.withVelocity(spec.velocity * 1.5), "Control-faster")
        assertTrue(
            violation(base, faster, tolerance = NEARLY_EXACT) != null,
            "a fleet travelling half again as fast went unnoticed"
        )
    }

    @Test
    @DisplayName("a violated relation is reported against a smaller configuration than it was found in")
    fun theShrinkerCutsTheCounterexampleDown() {
        // The shrinker only ever runs on the failure path, so without this it would be code that
        // has never executed, waiting to throw on the day it is needed most. A relation that is
        // false by construction -- doubling every velocity cannot leave the answers alone -- drives
        // it through the whole reporting path.
        val wrong = Relation("deliberately false", NEARLY_EXACT) { _, s -> s.withVelocity(s.velocity * 2.0) }
        // A seed with more than one cart, so that there is something for the shrinker to cut.
        val seed = CORPUS.first { generate(it).fleetOrder.size > 1 }
        val failure = assertFailsWith<AssertionError> { assertHolds(wrong, seed..seed) }
        val message = failure.message ?: ""
        assertTrue("smallest still violating" in message, message)
        assertTrue("1 cart(s)" in message, "the shrinker did not cut the fleet down: $message")
    }

    // ---- paradigm equivalence ---------------------------------------------------------------------------

    /**
     *  Runs with the active subsystem's horizon diagnostics silenced, and restores them afterwards.
     *
     *  A terminating run of a queue ends with work outstanding -- that is what a queue is -- and the
     *  active subsystem says so once per replication, naming what it stranded. Right behaviour, and
     *  the wrong volume at sixty replications of it. `QueueingLimitsTest` silences the same thing
     *  for the same reason.
     */
    private fun <T> withoutHorizonDiagnostics(block: () -> T): T {
        val logger = LoggerFactory.getLogger(AgentModel::class.java) as Logger
        val previous = logger.level
        logger.level = Level.ERROR
        try {
            return block()
        } finally {
            logger.level = previous
        }
    }

    @Test
    @Tag("slow")
    @DisplayName("the passive and active paradigms agree on every generated network, one cart each")
    fun theTwoParadigmsAgreeOnGeneratedNetworks() = withoutHorizonDiagnostics {
        // The prize. This generalizes Gate A from the one hand-built shop it was written against to
        // networks nobody designed. Fleet size one, so dispatching cannot differ -- there is only
        // ever one candidate -- and any difference that survives is a difference of paradigm.
        var exact = 0
        var worst = 0.0
        var worstWhere = "nothing"
        for (seed in SMALL_CORPUS) {
            val spec = generate(seed).withFleet(1)
            val passive = run(spec, "Parity-passive-$seed")

            val m = Model("Parity-active-$seed")
            val active = AgvShop(m, spec, buildNetwork(spec))
            m.numberOfReplications = 1
            m.lengthOfReplication = spec.horizon
            m.simulate()

            val pairs = listOf(
                "completions" to (passive.pure.getValue("completions") to active.totalTime.count),
                "meanTotalTime" to (passive.time.getValue("meanTotalTime") to active.totalTime.average),
                "meanRouteLength" to (passive.space.getValue("meanRouteLength") to active.routeLength.average)
            )
            var same = true
            for ((what, both) in pairs) {
                val (p, a) = both
                if (p != a) same = false
                val rel = if (abs(p) > 1.0e-12) abs(a - p) / abs(p) else abs(a - p)
                if (rel > worst) { worst = rel; worstWhere = "seed $seed, $what" }
                // The route a load travels is a property of the network, not of who decided to send
                // the cart. If these differ, the two models are not carrying loads over the same
                // path and no comparison of times would mean anything.
                val bound = if (what == "meanRouteLength") 1.0e-9 else 0.02
                assertTrue(
                    rel <= bound,
                    "seed $seed (${spec.shape}): the paradigms disagree on $what -- " +
                            "passive %.6f, active %.6f, relative %.3g (allowed %.3g)"
                                .format(p, a, rel, bound)
                )
            }
            assertTrue(
                passive.pure.getValue("completions") >= MIN_COMPLETIONS,
                "seed $seed carried too little work for the comparison to mean anything"
            )
            if (same) exact++
        }
        println(
            ("  paradigm equivalence   held over %d generated networks; %d agreed exactly, " +
                    "worst %.3g (%s)").format(SMALL_CORPUS.count(), exact, worst, worstWhere)
        )
    }

    // ---- the monotone relations ------------------------------------------------------------------------

    /**
     *  How long a fixed batch of work took, with a check that the batch actually finished.
     *
     *  Without the check the comparison would silently degrade into one between two runs that each
     *  ran out of clock, and a makespan that is really the horizon compares equal to anything.
     */
    private fun makespan(spec: ModelSpec, label: String): Double {
        val f = run(spec, label)
        val done = f.pure.getValue("completions")
        assertTrue(
            done == spec.arrivalLimit.toDouble(),
            "$label (${spec.shape}) delivered $done of ${spec.arrivalLimit} before the horizon; " +
                    "a makespan can only be compared between runs that both finished the batch"
        )
        return f.time.getValue("makespan")
    }

    /**
     *  A monotone expectation inside a stated regime, per the class comment, rather than a theorem.
     *  The message says so, so that whoever meets a failure does not reach for the wrong conclusion.
     */
    private fun assertNoSlower(what: String, seed: Int, reference: Double, candidate: Double, spec: ModelSpec) {
        assertTrue(
            candidate <= reference + 1.0e-9,
            ("seed $seed (${spec.shape}): $what -- %.6f against %.6f. This is a monotone expectation " +
                    "inside a stated regime and not a theorem: check whether the configuration has " +
                    "left that regime before concluding the subsystem is wrong.").format(candidate, reference)
        )
    }

    @Test
    @Tag("slow")
    @DisplayName("an extra cart never takes longer to clear the same batch of work")
    fun anExtraCartNeverTakesLonger() {
        var comparisons = 0
        for (seed in SMALL_CORPUS) {
            val ceiling = fleetCeiling(seed)
            if (ceiling < 2) continue
            val spec = generate(seed).saturated()
            var previous = Double.MAX_VALUE
            for (k in 1..ceiling) {
                val took = makespan(spec.withFleet(k), "Fleet-$seed-$k")
                if (previous != Double.MAX_VALUE) {
                    assertNoSlower("$k carts were slower than ${k - 1}", seed, previous, took, spec)
                    comparisons++
                }
                previous = took
            }
        }
        println("  extra cart             never took longer over $comparisons comparisons")
    }

    @Test
    @Tag("slow")
    @DisplayName("refining every zone changes no journey time when there is nothing to contend for")
    fun finerZoningIsANoOpWithoutContention() {
        // What is left of the finer-zoning relation after the corpus falsified the monotone form of
        // it -- see the class comment -- and it is the sharper half. Zone size is a choice about
        // *control granularity*: it says how finely the path is handed out, not how long anything
        // is. Where nothing contends, it must therefore change nothing at all, and this asserts that
        // to the bit over every network in the corpus. It is the relation that would catch an
        // off-by-one in zone entry or exit accounting the instant it appeared, since such a defect
        // would cost a fraction of a zone and the fraction would change with the zone.
        for (seed in SMALL_CORPUS) {
            val spec = generate(seed).saturated().withFleet(1)
            val coarse = run(spec, "SoloCoarse-$seed")
            val fine = run(spec.withFinerZones(2), "SoloFine-$seed")
            // Counts are excluded, and only counts: there are twice as many zones to enter, so
            // twice as many traversals is the correct answer rather than a discrepancy.
            val bad = violation(coarse, fine, includeCounts = false)
            assertTrue(bad == null, "seed $seed (${spec.shape}): $bad")
            // And a guard against the relation passing because the transformation did nothing.
            assertTrue(
                fine.pure.getValue("numZoneTraversals") > coarse.pure.getValue("numZoneTraversals"),
                "seed $seed: halving every zone did not increase the number of zones entered"
            )
        }
        println("  finer zoning           changed no journey time over ${SMALL_CORPUS.count()} networks")
    }

    @Test
    @Tag("slow")
    @DisplayName("a faster fleet never takes longer")
    fun aFasterFleetNeverTakesLonger() {
        for (seed in SMALL_CORPUS) {
            val spec = generate(seed).saturated()
            val slow = makespan(spec, "Slow-$seed")
            val fast = makespan(spec.withVelocity(spec.velocity * 2.0), "Fast-$seed")
            assertNoSlower("doubling the velocity was slower", seed, slow, fast, spec)
        }
        println("  faster fleet           never took longer over ${SMALL_CORPUS.count()} networks")
    }
}
