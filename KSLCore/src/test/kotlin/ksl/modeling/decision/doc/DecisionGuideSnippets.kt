package ksl.modeling.decision.doc

import ksl.examples.general.decision.reviewEvery
import ksl.modeling.decision.ActionSearch
import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.DecisionElement
import ksl.modeling.decision.ExhaustiveSearch
import ksl.modeling.decision.GridSearch
import ksl.modeling.decision.ManagedPolicyIfc
import ksl.modeling.decision.Neutral
import ksl.modeling.decision.NeutralPolicy
import ksl.modeling.decision.PeriodicDecisionElement
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.RunProvenance
import ksl.modeling.decision.ShapeAwarePolicyIfc
import ksl.modeling.decision.TransitionRecord
import ksl.modeling.decision.TransitionSink
import ksl.modeling.decision.decisionElement
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.decision.descriptor.TerminationSource
import ksl.modeling.decision.descriptor.toJson
import ksl.modeling.decision.descriptor.toToml
import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.modeling.decision.capture.DecisionCapture
import ksl.modeling.decision.capture.MemorySink
import ksl.modeling.decision.capture.StoredTransition
import ksl.modeling.decision.capture.TabularSink
import ksl.modeling.decision.capture.TrajectoryFile
import ksl.modeling.decision.descriptor.LeverDomain
import java.nio.file.Path
import kotlin.math.floor
import ksl.modeling.entity.ProcessModel
import ksl.modeling.variable.RandomVariable
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * Compile-only host for every code snippet in `docs/guides/ksl-decision.md`.
 * Each `fun` body is a verbatim snippet (or its body); compiling this file
 * proves every example in the guide references real public APIs.
 *
 * This file is not run as a test — the build only needs to compile it.
 */
/**
 * Host for §2.1's two snippets — the guide's answer to "then who decides when a decision happens":
 * a periodic review, and a review triggered by the system changing.
 *
 * The periodic one is an **event action**, which is what every review this library ships is —
 * `PeriodicDecisionElement`, `PeriodicReview`, `CalendarReview`. A review has no duration, seizes
 * nothing and waits for nothing, so there is no state for a process to carry between suspensions.
 * The host is a `ProcessModel` for the *second* snippet, where a process is the point: a demand's
 * process is where that model's state actually moves.
 */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
private class StockRoom(parent: ModelElement) : ProcessModel(parent, null) {

    private val reviewPeriod = 5.0
    private val reorderPoint = 20.0
    private val demandSize = RandomVariable(this, ExponentialRV(1.0, streamNum = 31))
    private val onHand = TWResponse(this, name = "OnHand", initialValue = 50.0)
    private val inventoryPosition: Double get() = onHand.value
    private val position = onHand

    private fun applyDemand(q: Double) { if (onHand.value >= q) onHand.decrement(q) }
    private fun placeOrder(q: Double) { if (q > 0.0) onHand.increment(q) }

    /** The (s, S) rule §2.1's snippet names. */
    class OrderUpTo(private val s: Double, private val bigS: Double) : PolicyIfc {
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray =
            doubleArrayOf(if (observation[0] <= s) bigS - observation[0] else 0.0)
    }

    val review = PeriodicDecisionElement(this, interval = 30.0, name = "Review") {
        observe(position)
        lever(this@StockRoom, 0..200, neutral = Neutral.Value(0.0)) { q -> placeOrder(q) }
        reward(onHand, rate = 0.5, sense = RewardSense.COST)
        policy = OrderUpTo(s = 20.0, bigS = 60.0)
    }

    /** The plain element §2.1's hand-written blocks drive. */
    val decisions: DecisionElement = decisionElement("Decisions") {
        observe(position)
        lever(this@StockRoom, 0..200, neutral = Neutral.Value(0.0)) { q -> placeOrder(q) }
        policy = NeutralPolicy
    }

    /** §4.13 — continuous review: no condition, no threshold, just a call where the state moved. */
    private fun demandArrives(event: KSLEvent<Nothing>) {
        applyDemand(demandSize.value)
        decisions.decide("demand")
    }

    private inner class Review : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            decisions.decide("periodic")
            schedule(reviewPeriod)
        }
    }

    override fun initialize() {
        Review().schedule(reviewPeriod)
    }

    private inner class Demand : Entity() {
        val demandProcess = process {
            applyDemand(demandSize.value)
            if (inventoryPosition <= reorderPoint) decisions.decide("reorder point")
        }
    }
}

@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
private object DecisionGuideSnippets {

    // -- §3 Quick start: a stock room that decides how much to order ----

    class StockRoom(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {

        val onHand = TWResponse(this, name = "${this.name}:OnHand", initialValue = 50.0)
        val ordersPlaced = Counter(this, name = "${this.name}:Orders")

        private var onOrder: Double = 0.0

        /** The model's own operation. A lever writes through this; it is not decision code. */
        fun placeOrder(quantity: Double) {
            if (quantity <= 0.0) return
            onOrder += quantity
            ordersPlaced.increment()
        }

        val review = PeriodicDecisionElement(this, interval = 5.0, name = "${this.name}:Review") {
            observe(onHand, unit = "units")                       // observation 0
            lever(
                this@StockRoom, limits = 0..200,
                neutral = Neutral.Value(0.0),                     // ordering nothing IS the no-op
                alias = "OrderQty", unit = "units"
            ) { q -> placeOrder(q) }
            reward(onHand, rate = 0.5, sense = RewardSense.COST, alias = "Holding")
            policy = NeutralPolicy
        }
    }

    fun quickStartRun() {
        val model = Model("StockRoomDemo")
        val room = StockRoom(model, "Room")
        model.numberOfReplications = 10
        model.lengthOfReplication = 500.0
        model.simulate()
        model.print()
    }

    // -- §4.1 Writing a rule -------------------------------------------

    /** An (s, S) rule: order up to [bigS] whenever the position is at or below [s]. */
    class OrderUpTo(private val s: Double, private val bigS: Double) : PolicyIfc {
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val position = observation[0]
            return if (position <= s) doubleArrayOf(bigS - position) else doubleArrayOf(0.0)
        }
    }

    fun swapTheRule(room: StockRoom) {
        room.review.element.policy = OrderUpTo(s = 20.0, bigS = 80.0)
        room.review.element.policyLabel = "(20, 80)"
    }

    // -- §4.2 A rule that checks the surface it was given --------------

    class CheckedOrderUpTo(private val s: Double, private val bigS: Double) : ShapeAwarePolicyIfc {

        private var positionIndex = 0

        override fun configure(surface: DecisionSurfaceDescriptor) {
            require(surface.levers.size == 1) {
                "OrderUpTo writes one quantity; this element declares ${surface.levers.size} levers."
            }
            positionIndex = surface.observations.indexOfFirst { it.unit == "units" }
            require(positionIndex >= 0) { "No observation is declared in units." }
        }

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val position = observation[positionIndex]
            return if (position <= s) doubleArrayOf(bigS - position) else doubleArrayOf(0.0)
        }
    }

    // -- §4.3 Parameterizing without touching the model ----------------

    fun parameterize(element: DecisionElement) {
        val qty = element.leverRef("OrderQty")
        element.narrow(qty, 0..120)                 // the experiment's limits, inside the model's
        element.maxEpochs = 100                     // cap the episode
        val limits: IntRange = element.limitsOf(qty)
    }

    // -- §4.4 Reading the description ----------------------------------

    fun readTheDescription(element: DecisionElement) {
        val surface = element.descriptor()

        for ((i, o) in surface.observations.withIndex()) {
            println("observation $i is ${o.name} in ${o.unit ?: "unstated units"}")
        }
        for ((i, l) in surface.levers.withIndex()) {
            println("action $i writes ${l.name}, a ${l.kind}, within ${l.lowerBound}..${l.upperBound}")
        }

        val json: String = surface.toJson()
        val toml: String = surface.toToml()
    }

    // -- §4.4 Reading the live surface ----------------------------------

    fun readTheLiveSurface(element: DecisionElement) {
        val catalog = element.catalog

        // What is there, by name, in observation and lever order.
        println("${catalog.name} observes ${catalog.observationNames}")
        println("${catalog.name} writes  ${catalog.leverNames}")

        // The live thing itself, not a description of it: this reads the
        // model's CURRENT value, which a descriptor cannot do.
        val level = catalog.observation(catalog.observationNames.first())
        println("right now that reads ${level?.value}")

        // A lever's descriptive half. Holding one cannot write anything.
        val info = catalog.leverInfo(catalog.leverNames.first())
        if (info != null) {
            println("${info.name} is a ${info.kind} over ${info.domain}")
            println("the model's own envelope is ${info.modelLowerLimit}..${info.modelUpperLimit}")
            if (info.supportsCurrentValue) println("and it can be read back as well as written")
        }

        // A reward source, if the element declared one under that name.
        val source = catalog.rewardSource("Holding")
        println("accumulated so far: ${source?.accumulated()}")
    }

    // -- §4.5 Recording what happened ----------------------------------

    fun captureToMemory(parent: ModelElement) {
        val sink = MemorySink()
        val element = parent.decisionElement("Captured") {
            observe("Level") { 1.0 }
            lever(parent, limits = 0..10, neutral = Neutral.Value(0.0)) { v -> }
            captureTo { provenance -> sink }        // called once per experiment
            policy = NeutralPolicy
        }.reviewEvery(parent, 5.0)
        // after model.simulate()
        for (row: TransitionRecord in sink.records) {
            println("${row.epochIndex}: ${row.state.toList()} -> ${row.action.toList()} " +
                "reward ${row.reward} over ${row.tau}")
        }
    }

    /** A sink of your own. Told when a run starts and stops; closed by whoever made it. */
    class CountingSink : TransitionSink {
        var rows = 0
            private set
        override fun beginExperiment(provenance: RunProvenance) {
            println("recording ${provenance.elementName} under ${provenance.policyLabel}")
            rows = 0
        }
        override fun write(record: TransitionRecord) { rows++ }
        override fun endExperiment() { println("$rows rows this run") }
    }

    fun captureToYourOwnSink(parent: ModelElement) {
        parent.decisionElement("Counted") {
            observe("Level") { 1.0 }
            lever(parent, limits = 0..10, neutral = Neutral.Value(0.0)) { v -> }
            captureTo { provenance -> CountingSink() }
            policy = NeutralPolicy
        }.reviewEvery(parent, 5.0)
    }


    // -- §4.5 Attaching capture from outside the model ------------------

    fun attachFromOutside(model: Model, element: DecisionElement) {
        val sink = MemorySink()
        element.attachTransitionSink(sink)
        model.simulate()
        element.detachTransitionSink(sink)

        println("${sink.records.size} transitions, and the model never mentioned capture")
    }

    fun captureAWholeModel(model: Model) {
        DecisionCapture.toDirectory(model, outputDir).use {
            model.simulate()
        }
    }

    fun captureSomeElements(model: Model, wanted: Set<String>) {
        val capture = DecisionCapture(model) { element ->
            if (element.name in wanted) MemorySink() else null
        }
        capture.use { model.simulate() }
    }

    fun captureExternallyToDisk(model: Model) {
        DecisionCapture.rolling(model) { provenance ->
            TabularSink(provenance, outputDir.resolve(provenance.experimentName))
        }.use {
            model.simulate()
        }
    }


    // -- §4.5 Keeping a trajectory after the run ends -------------------

    private val outputDir: java.nio.file.Path = java.nio.file.Paths.get("out")

    fun captureDurably(parent: ModelElement) {
        parent.decisionElement("Recorded") {
            observe("Level") { 1.0 }
            lever(parent, limits = 0..10, neutral = Neutral.Value(0.0)) { v -> }
            // One file per experiment, named from the provenance, so a k-rule study does not
            // write over itself. The sink is opened and closed for you, per experiment.
            captureTo { provenance -> TabularSink(provenance, outputDir.resolve(provenance.experimentName)) }
            policy = NeutralPolicy
        }.reviewEvery(parent, 5.0)
    }

    fun readATrajectoryBack(rowsPath: Path) {
        TrajectoryFile(rowsPath).use { trajectory ->
            println("${trajectory.rowCount} transitions written by '${trajectory.provenance.policyLabel}'")

            // What the columns cannot say, the descriptor can.
            val lever = trajectory.descriptor.levers[0]
            println("${lever.name} is a ${lever.domain} ${lever.kind} over ${lever.lowerBound}..${lever.upperBound}")
            lever.levels?.let { println("its values name: $it") }

            for (t: StoredTransition in trajectory.transitions()) {
                // t.state, t.action, t.reward, t.successorState, t.terminated, t.truncated
            }
        }
    }

    // -- §4.12 Training a rule from captured data ----------------------

    /** Fits an order-up-to level from a captured trajectory, reading only the file. */
    fun bestOrderUpTo(rowsPath: Path): Double =
        TrajectoryFile(rowsPath).use { trajectory ->
            val surface = trajectory.descriptor
            val position = surface.observations.indexOfFirst { it.name.endsWith(":Position") }
            val lever = surface.levers[0]

            val best = trajectory.transitions()
                .groupBy { floor((it.state[position] + it.action[0]) / 5.0) * 5.0 }   // post-decision position
                .filterValues { it.size >= 20 }                                        // ignore thin buckets
                .maxByOrNull { (_, rows) -> rows.sumOf { it.reward } / rows.size }!!
                .key + 2.5                                                             // bucket midpoint

            // The descriptor says what a LEGAL order is; the rows do not.
            if (lever.domain == LeverDomain.CONTINUOUS) best else Math.rint(best)
        }

    // -- §4.6 Several levers under a joint constraint ------------------

    class Pools(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {
        var dayStaff = 4
            private set
        var nightStaff = 4
            private set

        fun setDay(n: Int) { dayStaff = n }
        fun setNight(n: Int) { nightStaff = n }
        fun setBoth(values: DoubleArray) {
            dayStaff = values[0].toInt(); nightStaff = values[1].toInt()
        }

        val review = decisionElement("${this.name}:Shift") {
            observe("Demand") { 1.0 }
            val day = lever(this@Pools, limits = 0..8, unit = "staff",
                neutral = Neutral.Current { dayStaff.toDouble() }) { v -> setDay(v.toInt()) }
            val night = lever(this@Pools, limits = 0..8, unit = "staff",
                neutral = Neutral.Current { nightStaff.toDouble() }) { v -> setNight(v.toInt()) }
            budget(day, night, total = 8.0)          // the pair must sum to exactly 8
            batchLever(day, night) { values -> setBoth(values) }   // move both in one act
            policy = NeutralPolicy
        }.reviewEvery(this, 480.0)
    }


    // -- §4.10 Scoring on several things at once ------------------------

    class Clinic(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {
        val queue = TWResponse(this, name = "${this.name}:Queue")
        val treated = Counter(this, name = "${this.name}:Treated")
        var capacity: Int = 4

    val review = decisionElement("${this.name}:Shift") {
        observe(queue)
        val staff = lever(this@Clinic, limits = 0..8,
            neutral = Neutral.Current { capacity.toDouble() }) { v -> capacity = v.toInt() }
        // Every rate is a positive number in the units you think in. `sense` carries the direction.
        reward(treated, rate = 25.0, sense = RewardSense.REWARD, alias = "Revenue")
        reward(queue, rate = 10.0, sense = RewardSense.COST, alias = "Waiting")
        policy = NeutralPolicy
    }.reviewEvery(this, 480.0)
    }

    // -- §4.7 A rule that scores candidates ----------------------------

    class CheapestFeasible(private val search: ActionSearch = ExhaustiveSearch) : PolicyIfc {
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            val best = search.best(ctx.actions) { candidate -> candidate.sum() }
            return best ?: ctx.neutralAction         // an empty feasible set is not an error
        }
    }

    fun chooseASearch(): ActionSearch = GridSearch(pointsPerLever = 9)

    // -- §4.8 A rule that owns a resource ------------------------------

    class LoggingRule(private val path: String) : ManagedPolicyIfc {
        override fun beforeExperiment() { println("opening $path") }
        override fun beforeEpisode(episodeIndex: Int) { }
        override fun onTransition(record: TransitionRecord) { }
        override fun afterEpisode(episodeIndex: Int, source: TerminationSource) { }
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray =
            ctx.neutralAction
        override fun afterExperiment() { }
        override fun close() { println("closing $path") }
    }

    // -- §4.9 Comparing two rules --------------------------------------

    fun compareTwoRules(build: () -> Pair<Model, DecisionElement>) {
        val results = mutableMapOf<String, Double>()
        for (rule in listOf<Pair<String, PolicyIfc>>(
            "do nothing" to NeutralPolicy,
            "(20, 80)" to OrderUpTo(20.0, 80.0)
        )) {
            val (model, element) = build()
            element.policy = rule.second
            element.policyLabel = rule.first
            model.numberOfReplications = 30
            model.lengthOfReplication = 500.0
            model.simulate()
            results[rule.first] = element.estimand.acrossReplicationStatistic.average
        }
        println(results)
    }
}
