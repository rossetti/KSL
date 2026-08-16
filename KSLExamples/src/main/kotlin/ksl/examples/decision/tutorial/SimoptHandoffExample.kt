package ksl.examples.decision.tutorial

import ksl.modeling.decision.NeutralPolicy
import ksl.simopt.solvers.Solver
import ksl.simulation.Model

/*
 * Tutorial Part VI -- searching over a rule's parameters with `ksl.simopt`.
 *
 * Run `main`. It does four things, in this order, because each is only meaningful after
 * the one before it:
 *
 *   1. Shows that the rule's parameters are ordinary model controls.
 *   2. Scores a grid by hand, the way Part II compared rules, and checks it brackets.
 *   3. Hands the same two parameters to a simopt solver on a SMALL budget.
 *   4. Does it again on a larger one, and reads the difference honestly.
 */

/** What the run computed, so a test can check the claims it prints. */
class SimoptHandoffResult(
    val controlKeys: List<String>,
    val gridBestS: Double,
    val gridBestScore: Double,
    val gridBracketsAnOptimum: Boolean,
    val doNothingScore: Double,
    val smallBudgetScore: Double,
    val largeBudgetS: Double,
    val largeBudgetLevel: Double,
    val largeBudgetScore: Double
)

private fun heading(n: Int, title: String) {
    println(); println("─".repeat(78)); println("  $n. $title"); println("─".repeat(78))
}

/** Scores one parameter pair by running the model, exactly as Part II scored a rule. */
private fun score(s: Double, sDelta: Double): Double {
    val model = BuildStockRoomDecisionModel.build(null, null)
    val rule = model.getModelElement("OrderRule") as ParameterizedOrderUpTo
    rule.s = s
    rule.sDelta = sDelta
    model.simulate()
    return model.responses.first { it.name == STOCK_ROOM_OBJECTIVE }
        .acrossReplicationStatistic.average
}

/** One solver run at a given budget. Returns (s, S, raw estimand). */
private fun search(maxIterations: Int, replicationsPerEvaluation: Int): Triple<Double, Double, Double> {
    val problem = makeStockRoomProblem()
    val solver = Solver.createStochasticHillClimberSolver(
        problemDefinition = problem,
        modelBuilder = BuildStockRoomDecisionModel,
        startingPoint = null,
        maxIterations = maxIterations,
        replicationsPerEvaluation = replicationsPerEvaluation
    )
    solver.runAllIterations()
    val best = solver.bestSolution
    // Read the inputs BY NAME. `asString()` prints them positionally in the input map's
    // own iteration order, which is not the order of `problem.inputNames` — measured.
    val s = best.inputMap[STOCK_ROOM_S]!!
    val delta = best.inputMap[STOCK_ROOM_S_DELTA]!!
    // And read `estimatedObjFnc.average`, the RAW average in the modeler's units. On a
    // MAXIMIZE problem `estimatedObjFncValue` is sign-flipped internally, so printing it
    // beside a hand-computed number compares +5,022 with -6,510 and reads backwards.
    return Triple(s, s + delta, best.estimatedObjFnc.average)
}

fun main() { runSimoptHandoffDemo() }

/** The demonstration proper. Returns what it computed; `main` simply runs it. */
fun runSimoptHandoffDemo(
    smallBudget: Int = 30,
    largeBudget: Int = 120,
    replicationsPerEvaluation: Int = 20
): SimoptHandoffResult {

    // ------------------------------------------------------------------ 1. parameters are controls
    heading(1, "A parameterized rule's parameters are ordinary model controls")

    val probe: Model = BuildStockRoomDecisionModel.build(null, null)
    val keys = probe.controls().controlKeys().sorted()
    println("  The model's control keys:")
    keys.forEach { println("      $it") }
    println()
    println("  Two belong to the RULE. It is a ModelElement carrying @KSLControl properties,")
    println("  so the model's control walk finds it like anything else. Nothing in")
    println("  ksl.modeling.decision took part in that — there is no adapter, and that")
    println("  absence is the design decision, not an omission.")
    println()
    println("  Two belong to the decision ELEMENT: WHEN it decides is a parameter too, so")
    println("  'how often should we review?' is a question a search can be asked.")
    println()
    println("  The objective is an ordinary Response:")
    println("      $STOCK_ROOM_OBJECTIVE")
    println("  Larger is better, because COST is negated once at declaration. So the problem")
    println("  is a MAXIMIZE. A search told to minimize it would hunt for the WORST rule and")
    println("  would not fail — it would answer the wrong question, confidently.")

    // ------------------------------------------------------------------ 2. a grid, by hand
    heading(2, "First by hand: a grid, and the bracketing check from Part II")

    val doNothing = run {
        val m = BuildStockRoomDecisionModel.build(null, null)
        (m.getModelElement("Room") as StockRoom).review.policy = NeutralPolicy
        m.simulate()
        m.responses.first { it.name == STOCK_ROOM_OBJECTIVE }.acrossReplicationStatistic.average
    }
    println("  do nothing, the arm that always comes first: %,.0f".format(doNothing))
    println("  Backorders accumulate without bound, so that arm does not merely lose — it")
    println("  diverges. The right result, and a poor discriminator.")
    println()

    // Hold the increment and sweep the reorder point, so the grid is one-dimensional and
    // the bracketing check is easy to read.
    val fixedDelta = 5.0
    val sGrid = listOf(0.0, 4.0, 8.0, 12.0, 20.0)
    println("  sweeping s with the order-up-to increment held at %.0f:".format(fixedDelta))
    println("  %-14s %16s".format("(s, S)", "estimand"))
    val scores = sGrid.map { s -> s to score(s, fixedDelta) }
    scores.forEach { (s, v) ->
        println("  %-14s %16.1f".format("(${s.toInt()}, ${(s + fixedDelta).toInt()})", v))
    }
    val gridBest = scores.maxByOrNull { it.second }!!
    val brackets = gridBest.first != sGrid.first() && gridBest.first != sGrid.last()
    println()
    println("  best of the grid: s=%.0f at %.1f".format(gridBest.first, gridBest.second))
    println(
        if (brackets)
            "  It is an INTERIOR point, so the grid brackets an optimum in s and the\n" +
                "  comparison is worth believing — the same check Part II applied."
        else
            "  It sits at the EDGE, so the honest reading is 'better than everything tried'."
    )
    println()
    println("  But the grid held the second parameter fixed. Sweeping both is exactly the")
    println("  work a search does better, and that is where the two tutorials join.")

    // ------------------------------------------------------------------ 3. a small budget
    heading(3, "Then by search: hand both parameters to simopt, on a small budget")

    val problem = makeStockRoomProblem()
    println("  problem    : ${problem.name}, ${problem.optimizationType}")
    println("  inputs     : ${problem.inputNames}")
    println("  objective  : ${problem.objFnResponseName}")
    println("  compatible : ${problem.validateProblemDefinition(probe)}")
    println()
    println("  The pair is declared (s, sDelta), not (s, S). A numeric control CLAMPS")
    println("  silently, so a solver proposing S below s would have it APPLIED rather than")
    println("  refused, and the search would spend evaluations on points that correspond to")
    println("  no rule the modeler meant. With a non-negative increment, every point of the")
    println("  box is a legal rule by construction.")
    println()

    val (_, _, smallScore) = search(smallBudget, replicationsPerEvaluation)
    println("  %d iterations: estimand %.1f".format(smallBudget, smallScore))
    println("  grid best    : estimand %.1f".format(gridBest.second))
    println()
    println("  On this budget the search does NOT beat a well-chosen grid. That is not a")
    println("  bug and not a criticism of the solver — it is the lesson. A greedy,")
    println("  single-trajectory method on a noisy objective needs enough evaluations to")
    println("  find its way, and effort is the currency of this whole subject.")

    // ------------------------------------------------------------------ 4. a larger budget
    heading(4, "Give it four times the budget")

    val (bigS, bigLevel, bigScore) = search(largeBudget, replicationsPerEvaluation)
    println("  %d iterations: (s=%.0f, S=%.0f) at estimand %.1f".format(
        largeBudget, bigS, bigLevel, bigScore))
    println()
    println("  %-28s %12s".format("arm", "estimand"))
    println("  %-28s %12.1f".format("do nothing", doNothing))
    println("  %-28s %12.1f".format("hand grid, best of 5", gridBest.second))
    println("  %-28s %12.1f".format("search, $smallBudget iterations", smallScore))
    println("  %-28s %12.1f".format("search, $largeBudget iterations", bigScore))
    println()
    println("  Two things worth taking away, and the second is the one that generalizes.")
    println()
    println("  The search is REPRODUCIBLE: run this again and it lands on the same point,")
    println("  because each evaluation builds a fresh model whose stream provider is seeded")
    println("  identically. That is KSL's determinism guarantee, not something simopt adds.")
    println()
    println("  And a single run of one method is still not the final word on a noisy")
    println("  problem. The simopt tutorial's benchmark harness — many solvers, equal")
    println("  replication budgets, a statistical confirmation stage — exists for exactly")
    println("  this, and it is the next thing to read.")

    println()
    println("─".repeat(78))
    println("  Read this alongside docs/guides/ksl-decision-tutorial.md, Part VI,")
    println("  then docs/guides/ksl-simopt-tutorial.md.")
    println("─".repeat(78))

    return SimoptHandoffResult(
        controlKeys = keys,
        gridBestS = gridBest.first,
        gridBestScore = gridBest.second,
        gridBracketsAnOptimum = brackets,
        doNothingScore = doNothing,
        smallBudgetScore = smallScore,
        largeBudgetS = bigS,
        largeBudgetLevel = bigLevel,
        largeBudgetScore = bigScore
    )
}
