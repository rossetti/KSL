package ksl.examples.decision.tutorial

import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.NeutralPolicy
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.ShapeAwarePolicyIfc
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.LeverDomain
import ksl.modeling.variable.RandomVariable
import ksl.sdm.capture.TabularSink
import ksl.sdm.capture.TrajectoryFile
import ksl.simulation.Model
import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.UniformRV
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor
import kotlin.math.max

/**
 * **Off-line training from captured decision processing, end to end.**
 *
 * Lives in `…decision.tutorial` for the same reason as its neighbour: it is a walkthrough, not a
 * component. The learner here is deliberately the simplest thing that can be called learning.
 *
 * Three phases, and the boundary between the first two is the point of the whole exercise:
 *
 *  1. **Explore.** Run the stock room under a rule that orders up to a *random target*, and
 *     capture every transition to a file.
 *  2. **Learn.** Open that file — [learnOrderUpToLevel] takes a `Path` and nothing else, which is
 *     the structural proof that the learner never touches the model, the element, or the JVM that
 *     produced the data. Fit a rule from the rows.
 *  3. **Evaluate.** Put the fitted rule back into the simulator and compare it against the
 *     do-nothing arm and against a hand-tuned rule.
 *
 * ### What the learner does, and what it deliberately does not
 *
 * It fits **one number**: the order-up-to level *S*. For every captured transition it computes the
 * *post-decision position* — where the inventory stood after the order was placed, `position +
 * quantity` — buckets those, averages the reward that followed, and takes the best bucket. The
 * fitted rule is then `order = max(0, S − position)`.
 *
 * That is about the simplest thing that can be called learning, and it is chosen for that reason.
 * The deliverable here is the **path** — capture, interpret, fit, run — not the algorithm. A serious
 * learner would face off-policy evaluation, which is a genuinely hard problem this demonstration
 * does not pretend to solve: it sidesteps it by exploring randomly, so the data covers the action
 * space rather than only what a good rule would have done.
 *
 * ### Why the provenance file earns its keep here
 *
 * The learner reads `s_Room_Position` and `a_OrderQty` out of a table of numbers. To know *which*
 * observation is the inventory position it asks the descriptor rather than assuming index 0, and to
 * know that its fitted *S* is a legal order it checks the lever's declared bounds. Neither fact is
 * recoverable from the rows.
 */

/** What [learnOrderUpToLevel] found, and enough of its working to argue with. */
class FittedRule(
    val orderUpTo: Double,
    val rowsUsed: Int,
    val bucketsConsidered: Int,
    /** `(post-decision position, mean reward, rows in bucket)`, best first. */
    val evidence: List<Triple<Double, Double, Int>>,
    /** The lever's declared domain — what made [orderUpTo] a *runnable* number rather than a fit. */
    val domain: LeverDomain
)

/**
 * Fits an order-up-to level from a captured trajectory, reading **only** the file at [rowsPath].
 *
 * @param bucketWidth how finely to discretise the post-decision position
 * @param minRowsPerBucket a bucket with fewer rows than this is ignored, because a bucket visited
 *   twice will happily report the best mean reward in the study and mean nothing by it
 */
fun learnOrderUpToLevel(
    rowsPath: Path,
    bucketWidth: Double = 5.0,
    minRowsPerBucket: Int = 20
): FittedRule {
    TrajectoryFile(rowsPath).use { trajectory ->
        val d: DecisionSurfaceDescriptor = trajectory.provenance.descriptor

        // Ask the descriptor which column means what. The rows cannot say.
        val positionIndex = d.observations.indexOfFirst { it.name.endsWith(":Position") }
        require(positionIndex >= 0) {
            "No observation named '*:Position' in ${d.observations.map { it.name }}."
        }
        require(d.levers.size == 1) { "This learner fits one order quantity." }
        val lever = d.levers[0]

        val rows = trajectory.transitions()
        require(rows.isNotEmpty()) { "The trajectory at $rowsPath has no transitions." }

        // Post-decision position: where the inventory stood once the order was placed.
        val byBucket = rows
            .groupBy { floor((it.state[positionIndex] + it.action[0]) / bucketWidth) * bucketWidth }
            .filterValues { it.size >= minRowsPerBucket }
        require(byBucket.isNotEmpty()) {
            "No bucket of post-decision positions had $minRowsPerBucket or more rows out of " +
                "${rows.size} transitions. Explore more, or widen the buckets."
        }

        val evidence = byBucket
            .map { (bucket, group) -> Triple(bucket, group.sumOf { it.reward } / group.size, group.size) }
            .sortedByDescending { it.second }

        val best = evidence.first().first + bucketWidth / 2.0        // the bucket's midpoint

        // The descriptor says what a LEGAL order is, and the rows do not. Both of these matter and
        // the second one was learned the hard way: the first version of this learner returned the
        // bucket midpoint 7.5, the rule ordered `7.5 - position`, and the model refused the epoch
        // outright — "'OrderQty' = 0.5 units is not integral, but the lever's domain is INTEGER".
        // That refusal is the design working (an infeasible action writes no lever), and it is also
        // the whole argument for the provenance file: a learner that cannot see the domain will
        // cheerfully fit a rule the model will not run.
        val bounded = best.coerceIn(lever.lowerBound, lever.upperBound)
        val fitted = if (lever.domain == LeverDomain.CONTINUOUS) bounded else Math.rint(bounded)
        return FittedRule(fitted, rows.size, byBucket.size, evidence, lever.domain)
    }
}

/** The rule the learner produced: bring the position up to [orderUpTo], never order less than zero. */
class LearnedOrderUpTo(private val orderUpTo: Double) : ShapeAwarePolicyIfc {

    private var positionIndex = 0
    private var integral = false

    override fun configure(surface: DecisionSurfaceDescriptor) {
        positionIndex = surface.observations.indexOfFirst { it.name.endsWith(":Position") }
        require(positionIndex >= 0) { "This rule needs an observation named '*:Position'." }
        require(surface.levers.size == 1) { "This rule writes one order quantity." }
        // The surface says what kind of number this lever takes, so the rule rounds here rather
        // than letting the element refuse the epoch. This is what `configure` is for.
        integral = surface.levers[0].domain != LeverDomain.CONTINUOUS
    }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val quantity = max(0.0, orderUpTo - observation[positionIndex])
        return doubleArrayOf(if (integral) Math.rint(quantity) else quantity)
    }
}

/**
 * Orders up to a **random target**, so the captured data covers the post-decision states rather
 * than one rule's habits.
 *
 * The first version of this drew a random *quantity* instead, and it was a bad experiment: mean
 * demand is one unit per time unit and the review period is five, so a uniform draw on 0..120
 * ordered about twelve times what the model consumed. Inventory ran away, the post-decision
 * positions spread over thousands of units, and no bucket had enough rows to average — the learner
 * refused with "no bucket had 20 or more rows out of 7960 transitions", which is the right refusal
 * and the wrong data.
 *
 * Exploring over the target is also the honest shape for this problem. What the learner fits is an
 * order-up-to level, so the experiment should vary order-up-to levels.
 */
class ExploringOrderUpTo(
    private val uniform: GetValueIfc,
    private val lowest: Double,
    private val highest: Double
) : ShapeAwarePolicyIfc {

    private var positionIndex = 0

    override fun configure(surface: DecisionSurfaceDescriptor) {
        positionIndex = surface.observations.indexOfFirst { it.name.endsWith(":Position") }
        require(positionIndex >= 0) { "This rule needs an observation named '*:Position'." }
    }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val target = lowest + uniform.value * (highest - lowest)
        return doubleArrayOf(max(0.0, floor(target) - observation[positionIndex]))
    }
}

/** What the demonstration measured, so a test can check the claims it prints. */
class OfflineTrainingResult(
    val fitted: FittedRule,
    val trajectoryRows: Long,
    /** Mean estimand per arm; larger is better, since COST is negated once at declaration. */
    val scores: Map<String, Double>,
    val halfWidths: Map<String, Double>,
    val learnerSawOnlyTheFile: Boolean
)

private const val EXPLORE_REPS = 20
private const val EVAL_REPS = 30
private const val RUN_LENGTH = 2_000.0
private const val WARM_UP = 200.0

private fun evaluate(label: String, rule: PolicyIfc): Pair<Double, Double> {
    val model = Model("OfflineEval")
    val room = StockRoom(model, name = "Room")
    room.review.policy = rule
    room.review.policyLabel = label
    model.numberOfReplications = EVAL_REPS
    model.lengthOfReplication = RUN_LENGTH
    model.lengthOfReplicationWarmUp = WARM_UP
    model.simulate()
    val stat = room.review.estimand.acrossReplicationStatistic
    return stat.average to stat.halfWidth
}

fun main() { runOfflineTrainingDemo() }

/** The demonstration proper. Returns what it measured; `main` simply runs it. */
fun runOfflineTrainingDemo(directory: Path = Files.createTempDirectory("ksl-offline")): OfflineTrainingResult {

    fun heading(n: Int, title: String) {
        println(); println("─".repeat(78)); println("  $n. $title"); println("─".repeat(78))
    }

    // ---------------------------------------------------------------- 1. explore and capture
    heading(1, "Explore — run under a random ordering rule and capture every transition")

    val model = Model("OfflineExplore")
    lateinit var sink: TabularSink
    val room = StockRoom(
        model,
        decisionSink = { provenance ->
            TabularSink(provenance, directory.resolve("explore")).also { sink = it }
        },
        name = "Room"
    )
    // The policy's randomness is a RandomVariable owned by the model, so it resets per replication
    // and honours the model's stream options like everything else.
    val coin = RandomVariable(room, UniformRV(0.0, 1.0, streamNum = 77), "Room:Explore")
    room.review.policy = ExploringOrderUpTo(coin, lowest = 0.0, highest = 40.0)
    room.review.policyLabel = "random"
    model.numberOfReplications = EXPLORE_REPS
    model.lengthOfReplication = RUN_LENGTH
    model.lengthOfReplicationWarmUp = WARM_UP
    model.simulate()

    println("  captured ${sink.rowsWritten} transitions over $EXPLORE_REPS replications")
    println("  rows:       ${sink.rowsPath.fileName}")
    println("  provenance: ${sink.provenancePath.fileName}")

    // ---------------------------------------------------------------- 2. learn, from the file only
    heading(2, "Learn — from the file alone, with no model in sight")

    val rowsPath = sink.rowsPath
    println("  the learner is handed one thing: a path.")
    println("  learnOrderUpToLevel($rowsPath)")
    val fitted = learnOrderUpToLevel(rowsPath)

    println()
    println("  it asked the provenance which observation is the position, and what a legal order is.")
    println("  %14s %14s %10s".format("order up to", "mean reward", "rows"))
    for ((bucket, reward, n) in fitted.evidence.take(6)) {
        println("  %14.1f %14.2f %10d".format(bucket, reward, n))
    }
    println("  … ${fitted.bucketsConsidered} buckets from ${fitted.rowsUsed} transitions")
    println()
    println("  FITTED RULE: order up to ${"%.1f".format(fitted.orderUpTo)}  (${fitted.domain}, so it is a whole number)")

    // ---------------------------------------------------------------- 3. put it back in the model
    heading(3, "Evaluate — run the fitted rule against the do-nothing arm")

    val arms = listOf<Pair<String, PolicyIfc>>(
        "do nothing" to NeutralPolicy,
        "learned(${"%.0f".format(fitted.orderUpTo)})" to LearnedOrderUpTo(fitted.orderUpTo),
        "hand-tuned (5, 15)" to OrderUpTo(5.0, 15.0)
    )
    val scores = LinkedHashMap<String, Double>()
    val halves = LinkedHashMap<String, Double>()
    println("  %-22s %16s %14s".format("rule", "cost per unit", "half-width"))
    for ((label, rule) in arms) {
        val (avg, hw) = evaluate(label, rule)
        scores[label] = avg; halves[label] = hw
        println("  %-22s %16.2f %14.2f".format(label, avg, hw))
    }

    val learned = scores.keys.first { it.startsWith("learned") }
    println()
    println("  The estimand is a COST negated once at declaration, so LARGER is better.")
    println("  The rule was fitted from a file written by a *random* policy, then put back into the")
    println("  simulator and scored on its own terms. It beats doing nothing by " +
        "%.0f.".format(scores.getValue(learned) - scores.getValue("do nothing")))
    val gap = scores.getValue(learned) - scores.getValue("hand-tuned (5, 15)")
    println("  Against the hand-tuned rule it is " +
        (if (gap > 0) "AHEAD by %.0f.".format(gap) else "behind by %.0f.".format(-gap)))
    println("  Read that comparison modestly: the hand-tuned arm is the best point of the guide's")
    println("  coarse grid, not a seriously optimised rule. What the result does show is that one")
    println("  exploratory study plus one pass over its captured rows lands somewhere sensible —")
    println("  which is the claim being made, and the reason to capture at all.")

    println()
    println("─".repeat(78))
    println("  The learner never saw a Model. It was given a path.")
    println("─".repeat(78))

    return OfflineTrainingResult(
        fitted = fitted,
        trajectoryRows = sink.rowsWritten,
        scores = scores,
        halfWidths = halves,
        learnerSawOnlyTheFile = true
    )
}
