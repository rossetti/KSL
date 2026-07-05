package ksl.examples.general.simopt.study1

import ksl.examples.general.simopt.problems.ConstrainedNoisyQuadratic
import ksl.examples.general.simopt.problems.MultiItemNewsvendor
import ksl.examples.general.simopt.problems.Newsvendor
import ksl.examples.general.simopt.problems.NoiseLevel
import ksl.examples.general.simopt.problems.NoisyRastrigin
import ksl.examples.general.simopt.problems.NoisyRosenbrock
import ksl.examples.general.simopt.problems.NoisySphere
import ksl.simopt.benchmark.ProblemCase

/**
 *  The Study-1 problem grid: 24 toy problems, every one with a KNOWN_OPTIMUM reference
 *  so optimality gaps are exact and success rates are measured against ground truth.
 *  The grid is curated (not fully crossed) into three tiers (see the study plan §3):
 *
 *  - **Tier A — scaling × noise (unconstrained):** noisy sphere, Rosenbrock, Rastrigin
 *    at dimensions {2, 5} × noise {LOW, MED, HIGH} — 18 problems.
 *  - **Tier B — constraint handling:** constrained noisy quadratic at {2, 5} × {LOW,
 *    MED} (4) plus the budget-constrained multi-item newsvendor (1) — 5 problems.
 *  - **Tier C — static Monte Carlo, maximization:** the single-item newsvendor (1).
 *
 *  Each problem carries a meaningful indifference zone τ (the user-approved τ table):
 *  the smallest true-objective change to an adjacent feasible integer-lattice neighbor
 *  of the optimum, except the flat-optimum newsvendors, which use a 1.0 profit-unit
 *  tolerance. τ flows into `ProblemDefinition.indifferenceZoneParameter`, from which ISC
 *  takes its correct-selection guarantees (deltaC = deltaL = τ), and it doubles as the
 *  success threshold in the analysis.
 */

private val DIMENSIONS = listOf(2, 5)
private val ALL_NOISE = listOf(NoiseLevel.LOW, NoiseLevel.MED, NoiseLevel.HIGH)
private val LOW_MED = listOf(NoiseLevel.LOW, NoiseLevel.MED)

/** The user-approved indifference zones (τ) by problem family. */
object IndifferenceZones {
    const val SPHERE = 1.0
    const val RASTRIGIN = 1.0
    const val ROSENBROCK = 100.0
    const val QUADRATIC = 5.0
    const val NEWSVENDOR = 1.0
}

/**
 *  Returns a copy of this problem case whose problem definition is built with the given
 *  indifference zone τ (recorded also as an "indifferenceZone" tag). The zone is set on
 *  the fresh definition the harness creates per problem, so the evaluator and every
 *  solver — ISC in particular — see it.
 */
private fun ProblemCase.withIndifferenceZone(tau: Double): ProblemCase {
    return ProblemCase(
        name = name,
        problemDefinitionFactory = {
            problemDefinitionFactory().also { it.indifferenceZoneParameter = tau }
        },
        evaluatorFactoryProvider = evaluatorFactoryProvider,
        referenceSolution = referenceSolution,
        tags = tags + ("indifferenceZone" to tau.toString())
    )
}

/** Tier A: unconstrained scaling × noise — 18 problems. */
fun tierAProblems(): List<ProblemCase> = buildList {
    for (d in DIMENSIONS) {
        for (nl in ALL_NOISE) {
            add(NoisySphere(d, nl).problemCase().withIndifferenceZone(IndifferenceZones.SPHERE))
            add(NoisyRosenbrock(d, nl).problemCase().withIndifferenceZone(IndifferenceZones.ROSENBROCK))
            add(NoisyRastrigin(d, nl).problemCase().withIndifferenceZone(IndifferenceZones.RASTRIGIN))
        }
    }
}

/** Tier B: constraint handling — 5 problems. */
fun tierBProblems(): List<ProblemCase> = buildList {
    for (d in DIMENSIONS) {
        for (nl in LOW_MED) {
            add(ConstrainedNoisyQuadratic(d, nl).problemCase().withIndifferenceZone(IndifferenceZones.QUADRATIC))
        }
    }
    add(MultiItemNewsvendor().problemCase().withIndifferenceZone(IndifferenceZones.NEWSVENDOR))
}

/** Tier C: static Monte Carlo, maximization — 1 problem. */
fun tierCProblems(): List<ProblemCase> = listOf(
    Newsvendor().problemCase().withIndifferenceZone(IndifferenceZones.NEWSVENDOR)
)

/** The full 24-problem Study-1 grid. */
fun study1Problems(): List<ProblemCase> = tierAProblems() + tierBProblems() + tierCProblems()

/**
 *  Study-1 problems split by the replication-budget tier they belong to. The budget
 *  depends on dimension: problems with 3 or fewer decision variables use the smaller
 *  budget, dimension-5 problems the larger one. Each budget tier becomes its own
 *  benchmark experiment (one budget per experiment).
 */
fun study1ProblemsByBudgetTier(): Map<BudgetTier, List<ProblemCase>> {
    return study1Problems().groupBy { case ->
        val dimension = case.tags["dimension"]?.toIntOrNull() ?: 2
        if (dimension >= 5) BudgetTier.LARGE else BudgetTier.SMALL
    }
}

/** The two replication-budget tiers, keyed by decision-variable dimension. */
enum class BudgetTier(val label: String) {
    /** Dimension ≤ 3. */
    SMALL("d_le_3"),

    /** Dimension = 5. */
    LARGE("d_eq_5")
}
