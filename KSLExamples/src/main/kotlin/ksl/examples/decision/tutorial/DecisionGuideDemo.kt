package ksl.examples.decision.tutorial

import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.DecisionElement
import ksl.modeling.decision.Neutral
import ksl.modeling.decision.NeutralPolicy
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.RunProvenance
import ksl.modeling.decision.TransitionRecord
import ksl.modeling.decision.TransitionSink
import ksl.modeling.decision.decisionElement
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.decision.descriptor.toToml
import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.sdm.capture.MemorySink
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * The runnable demonstration for `docs/guides/ksl-decision.md`.
 *
 * Lives in `…decision.tutorial` rather than beside the worked models: this is a fixture to be read
 * alongside the guide, not a subsystem to build on. `StockRoom` and `OrderUpTo` exist to make the
 * guide concrete, and are deliberately the smallest thing that shows the four declarations working.
 *
 * It is the guide's own stock-room model, wired to demand and run end to end, and it walks the
 * same four tasks the guide teaches, in order:
 *
 *  1. **Declare** a decision element idiomatically, on a model that already works.
 *  2. **Read** the description the element derives from that declaration.
 *  3. **Swap** the rule and compare it against the do-nothing arm — without editing the model.
 *  4. **Record** a trajectory by attaching a sink from outside — the model says nothing about it.
 *
 * Run `main` and read the output alongside §3 and §4 of the guide.
 *
 * The point of step 3 is the one worth stating twice: **`StockRoom` is written once and never
 * touched again.** Everything the study varies — the rule, its parameters, the lever's limits, how
 * often the review happens, whether anything is recorded — is set from outside on the element. A
 * modeler owns the model; whoever is running the study owns the decision.
 *
 * Step 4 is the sharpest case of that, and the reason this class has no capture parameter: whether
 * a run is recorded is a property of *the run*, so the sink is attached to `review` from the
 * demonstration and detached afterwards. A user holding a model somebody else wrote can do the
 * same, which is the case a build-time-only `captureTo` could not serve at all.
 */
class StockRoom(
    parent: ModelElement,
    initialOnHand: Double = 50.0,
    private val meanDemandInterval: Double = 1.0,
    name: String? = null
) : ModelElement(parent, name) {

    val onHand = TWResponse(this, name = "${this.name}:OnHand", initialValue = initialOnHand)
    val backorders = TWResponse(this, name = "${this.name}:Backorders")
    val ordersPlaced = Counter(this, name = "${this.name}:Orders")

    private var onOrder: Double = 0.0
    private val demand = ExponentialRV(meanDemandInterval, streamNum = 21)
    private val leadTime = 3.0

    /** On hand, less what is owed, plus what is already coming — the state a rule reasons about. */
    val inventoryPosition: Double
        get() = onHand.value - backorders.value + onOrder

    // ---- The model's own operations. A lever writes through these; none of this is decision code.

    fun placeOrder(quantity: Double) {
        if (quantity <= 0.0) return
        onOrder += quantity
        ordersPlaced.increment()
        schedule(this::orderArrives, leadTime, message = quantity)
    }

    private fun orderArrives(event: ksl.simulation.KSLEvent<Double>) {
        val quantity = event.message!!
        onOrder -= quantity
        val owed = minOf(backorders.value, quantity)
        if (owed > 0.0) backorders.decrement(owed)
        if (quantity - owed > 0.0) onHand.increment(quantity - owed)
    }

    private fun demandArrives(event: ksl.simulation.KSLEvent<Nothing>) {
        if (onHand.value >= 1.0) onHand.decrement(1.0) else backorders.increment(1.0)
        schedule(this::demandArrives, demand)
    }

    override fun initialize() {
        onOrder = 0.0
        schedule(this::demandArrives, demand)
    }

    // ---- The decision. Four declarations: see, write, score, when.

    val review: DecisionElement = decisionElement("${this.name}:Review") {
        observe("${this@StockRoom.name}:Position", unit = "units") { inventoryPosition }
        observe(backorders, unit = "units")
        lever(
            this@StockRoom, limits = 0..200,
            neutral = Neutral.Value(0.0),                  // ordering nothing IS the no-op
            alias = "OrderQty", unit = "units"
        ) { q -> placeOrder(q) }
        reward(onHand, rate = 0.5, sense = RewardSense.COST, alias = "Holding")
        reward(backorders, rate = 5.0, sense = RewardSense.COST, alias = "Shortage")
        every(5.0)
        policy = NeutralPolicy
    }
}

/** The (s, S) rule from §4.1 of the guide. */
class OrderUpTo(private val s: Double, private val bigS: Double) : PolicyIfc {
    init { require(s < bigS) { "The reorder point s=$s must be below the order-up-to level S=$bigS" } }
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[0]
        return if (position <= s) doubleArrayOf(bigS - position) else doubleArrayOf(0.0)
    }
}

/**
 * A sink that says out loud when a run starts and stops, so step 4 can show the per-experiment
 * handshake rather than assert it.
 *
 * One sink object spans both runs — it is attached once — and is told about each of them
 * separately. That is why provenance arrives at [beginExperiment] rather than at construction:
 * the experiment name and the policy label belong to the run, not to the sink.
 */
class AnnouncingSink : TransitionSink {
    private val rows = mutableListOf<TransitionRecord>()

    /** The rows of the most recent run only; each run starts a fresh list. */
    val records: List<TransitionRecord> get() = rows

    override fun beginExperiment(provenance: RunProvenance) {
        runsStarted++
        rows.clear()
        println("      run STARTED for ${provenance.elementName}, " +
            "experiment '${provenance.experimentName}', policy '${provenance.policyLabel}'")
    }

    override fun write(record: TransitionRecord) { rows += record }

    override fun endExperiment() {
        runsEnded++
        println("      run ENDED after ${rows.size} rows — the sink stays attached for the next one")
    }

    /** Counters so the demonstration's claim about the handshake can be checked, not just printed. */
    companion object {
        var runsStarted = 0
            private set
        var runsEnded = 0
            private set
        fun resetCounts() { runsStarted = 0; runsEnded = 0 }
    }
}

/** What the demonstration computed, so a test can check the claims it prints. */
class DemoResult(
    val scores: Map<String, Double>,
    /** Confidence half-widths for [scores], so a caller can tell a ranking from noise. */
    val halfWidths: Map<String, Double>,
    /** The ordering rules in grid order, control excluded. */
    val orderingArms: List<String>,
    val capturedRows: Int,
    /** How many per-experiment handshakes the attached sink was given, and how many were ended. */
    val runsStarted: Int,
    val runsEnded: Int,
    /** How many sinks the element was left holding after the demonstration detached. */
    val sinksLeftAttached: Int,
    val observationNames: List<String>,
    val leverNames: List<String>
)

private fun buildStudy(): Pair<Model, StockRoom> {
    val model = Model("StockRoomStudy")
    val room = StockRoom(model, name = "Room")
    model.numberOfReplications = 30
    model.lengthOfReplication = 2_000.0
    model.lengthOfReplicationWarmUp = 200.0
    return model to room
}

private fun heading(n: Int, title: String) {
    println()
    println("─".repeat(78))
    println("  $n. $title")
    println("─".repeat(78))
}

fun main() { runDecisionGuideDemo() }

/** The demonstration proper. Returns what it computed; `main` simply runs it. */
fun runDecisionGuideDemo(): DemoResult {

    AnnouncingSink.resetCounts()

    // ---------------------------------------------------------------- 1. declare
    heading(1, "Declare a decision element on a model that already works")

    val (model, room) = buildStudy()
    println("  The model is ${room.name}; the decision element is ${room.review.name}.")
    println("  It is an ordinary ModelElement — nothing about running the model changes.")

    // ---------------------------------------------------------------- 2. read the description
    heading(2, "Read the description the element derives from the declaration")

    val surface = room.review.descriptor()
    surface.observations.forEachIndexed { i, o ->
        println("  observation[$i] = ${o.name}  (${o.unit ?: "unstated units"})")
    }
    surface.levers.forEachIndexed { i, l ->
        println("  action[$i]      = ${l.name}  a ${l.kind} within ${l.lowerBound}..${l.upperBound} ${l.unit ?: ""}")
    }
    surface.rewards.forEach { r ->
        println("  scored on        ${r.name} at ${r.rate} per unit, as ${r.sense}")
    }
    println()
    println("  The same thing, as the TOML a tool or a colleague would read:")
    surface.toToml().lines().take(12).forEach { println("      $it") }
    println("      … (${surface.toToml().lines().size} lines in total)")

    // ---------------------------------------------------------------- 3. swap the rule
    heading(3, "Swap the rule — the model is not edited, and the do-nothing arm comes first")

    val rules = listOf<Pair<String, PolicyIfc>>(
        "do nothing" to NeutralPolicy,
        "(2, 10)" to OrderUpTo(2.0, 10.0),
        "(5, 15)" to OrderUpTo(5.0, 15.0),
        "(8, 22)" to OrderUpTo(8.0, 22.0),
        "(12, 32)" to OrderUpTo(12.0, 32.0),
        "(20, 60)" to OrderUpTo(20.0, 60.0)
    )

    println("  %-14s %14s %14s %12s".format("rule", "cost per unit", "half-width", "orders"))
    val scores = mutableMapOf<String, Double>()
    val halfWidths = mutableMapOf<String, Double>()
    for ((label, rule) in rules) {
        val (m, r) = buildStudy()
        r.review.policy = rule                       // the only line that differs between arms
        r.review.policyLabel = label
        m.simulate()

        // The objective is an ordinary Response, so it is read like any other.
        val stat = r.review.estimand.acrossReplicationStatistic
        val orders = m.counters.first { it.name == "Room:Orders" }.acrossReplicationStatistic.average
        scores[label] = stat.average
        halfWidths[label] = stat.halfWidth
        println("  %-14s %14.3f %14.3f %12.1f".format(label, stat.average, stat.halfWidth, orders))
    }

    val ordering = scores.filterKeys { it != "do nothing" }
    val best = ordering.maxByOrNull { it.value }!!
    val labels = rules.map { it.first }.filter { it != "do nothing" }
    val interior = best.key != labels.first() && best.key != labels.last()

    println()
    println("  The estimand is a COST negated once at declaration, so a LARGER number is better.")
    println()
    println("  Every ordering rule beats 'do nothing', and by an amount that says more about the")
    println("  model than about the rules: with nothing ever ordered, backorders accumulate without")
    println("  bound, so that arm does not merely lose, it diverges. That is the right result and a")
    println("  poor discriminator — the comparison that discriminates is among the ordering rules.")
    println()
    println("  Best ordering rule: '${best.key}' at %.1f.".format(best.value))
    if (interior) {
        println("  It is an INTERIOR point of the grid, so the grid brackets an optimum and the")
        println("  comparison is worth believing.")
    } else {
        println("  It sits at the EDGE of the grid, which means the grid does not bracket an")
        println("  optimum: the honest reading is 'better than everything tried', not 'best'.")
        println("  Widen the grid past '${best.key}' before quoting it as a recommendation.")
    }

    // ---------------------------------------------------------------- 4. record, from outside
    heading(4, "Record a trajectory — by attaching a sink to a model that never mentions capture")

    val (m4, r4) = buildStudy()
    r4.review.policy = OrderUpTo(30.0, 90.0)
    r4.review.policyLabel = "(30, 90)"
    m4.numberOfReplications = 2
    m4.lengthOfReplication = 60.0
    m4.lengthOfReplicationWarmUp = 0.0

    println("  StockRoom has no capture parameter and no captureTo — look at the class. The sink")
    println("  is attached here, from outside, the way an animation trace or a UI panel would be:")
    println()

    val captured = AnnouncingSink()
    r4.review.attachTransitionSink(captured)

    println("  simulate() once, experiment 'first':")
    m4.experimentName = "first"
    m4.simulate()

    println()
    println("  simulate() a SECOND time on the same model. The SAME sink object is still attached,")
    println("  and is handed the new run's provenance — note the experiment name change:")
    m4.experimentName = "second"
    m4.simulate()

    val rows = captured.records
    r4.review.detachTransitionSink(captured)
    println()
    println("  detached: the element now holds ${r4.review.countTransitionSinks} sinks, so a further")
    println("  run would record nothing. Attaching and detaching is refused *during* a run, because")
    println("  a trajectory that starts mid-episode has no predecessor state for its first row.")
    println()
    println("  The last experiment recorded ${rows.size} transitions. The first three:")
    println("      %5s %8s %8s %12s %12s %10s %10s".format(
        "epoch", "time", "tau", "state[0]", "action[0]", "reward", "truncated"))
    for (row in rows.take(3)) {
        println("      %5d %8.2f %8.2f %12.2f %12.2f %10.2f %10s".format(
            row.epochIndex, row.time, row.tau, row.state[0], row.action[0], row.reward, row.truncated))
    }
    println()
    println("  Each row is a complete (state, action, reward, successor) transition. `terminated`")
    println("  and `truncated` are separate fields: an episode that reached a real ending and one")
    println("  the run length cut off are different things, and a learner that bootstraps from the")
    println("  last row needs to know which it got.")

    println()
    println("─".repeat(78))
    println("  Read this alongside docs/guides/ksl-decision.md, §3 and §4.")
    println("─".repeat(78))

    return DemoResult(
        scores = scores,
        halfWidths = halfWidths,
        orderingArms = labels,
        capturedRows = rows.size,
        runsStarted = AnnouncingSink.runsStarted,
        runsEnded = AnnouncingSink.runsEnded,
        sinksLeftAttached = r4.review.countTransitionSinks,
        observationNames = surface.observations.map { it.name },
        leverNames = surface.levers.map { it.name }
    )
}
