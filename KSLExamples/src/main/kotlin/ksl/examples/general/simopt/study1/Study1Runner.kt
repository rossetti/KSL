package ksl.examples.general.simopt.study1

import ksl.examples.general.simopt.bayesianOptimizationCase
import ksl.examples.general.simopt.geneticAlgorithmCase
import ksl.examples.general.simopt.iscCase
import ksl.examples.general.simopt.particleSwarmCase
import ksl.examples.general.simopt.standardSolverCases
import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.SolverCase
import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.simopt.solvers.concurrent.ConfirmationOptions

/**
 *  Configuration for one run of the Study-1 grid: the two replication-budget tiers
 *  (dimension ≤ 3 and dimension = 5), the macro-replication count, and the confirmation
 *  and verification settings. The smoke phase uses reduced budgets and few reps; the
 *  main run uses the design numbers of the study plan §4.
 *
 *  @param experimentPrefix a prefix for the two per-tier experiment names (so smoke and
 *  main runs are distinguishable in one database)
 *  @param macroReplications macro-replications per (problem, solver)
 *  @param smallBudget replication budget for dimension ≤ 3 problems
 *  @param largeBudget replication budget for dimension = 5 problems
 *  @param confirmation confirmation-stage options; null disables confirmation
 *  @param verificationReplications winner re-simulation count; null disables verification
 *  @param captureTraces whether to capture per-iteration traces (always on for Study 1)
 *  @param numWorkers max concurrent cells; null uses the processor default
 */
data class Study1Config(
    val experimentPrefix: String,
    val macroReplications: Int,
    val smallBudget: Int,
    val largeBudget: Int,
    val confirmation: ConfirmationOptions? = ConfirmationOptions(topK = 5, replicationsPerCandidate = 200),
    val verificationReplications: Int? = 1000,
    val captureTraces: Boolean = true,
    val numWorkers: Int? = null
) {
    /** The experiment name for a budget tier. */
    fun experimentName(tier: BudgetTier): String = "${experimentPrefix}_${tier.label}"

    /** The replication budget for a tier. */
    fun budget(tier: BudgetTier): Int = when (tier) {
        BudgetTier.SMALL -> smallBudget
        BudgetTier.LARGE -> largeBudget
    }
}

/**
 *  Runs the Study-1 grid over the full nine-case roster into the supplied database, one
 *  benchmark experiment per replication-budget tier. Resumable: any per-tier experiment
 *  whose name is already present in the database is skipped, so an interrupted run picks
 *  up at the next tier. Returns the experiment ids that were run (in tier order),
 *  excluding any that were skipped.
 *
 *  @param db the results database (opened with deleteIfExists = false to accumulate)
 *  @param config the run configuration
 *  @return the experiment ids created by this call, keyed by budget tier
 */
fun runStudy1(db: BenchmarkResultsDb, config: Study1Config): Map<String, Int> {
    val existingNames = db.experiments().map { it.expName }.toSet()
    val problemsByTier = study1ProblemsByBudgetTier()
    val results = linkedMapOf<String, Int>()
    for (tier in BudgetTier.entries) {
        val tierProblems = problemsByTier[tier] ?: continue
        val budget = config.budget(tier)
        val roster = study1Roster()
        // Within a tier, one experiment per distinct excluded-solver set: problems with no
        // exclusions form the main experiment; each excluded problem (e.g. BO/CE on the
        // multi-item newsvendor) runs as its own experiment with the reduced roster.
        val groups = tierProblems.groupBy { STUDY1_SOLVER_EXCLUSIONS[it.name] ?: emptySet() }
        for ((excluded, groupProblems) in groups) {
            val suffix = if (excluded.isEmpty()) "" else "_ex_" + excluded.sorted().joinToString("_")
            val name = config.experimentName(tier) + suffix
            if (name in existingNames) {
                println("Skipping experiment '$name' (already present in the database).")
                continue
            }
            val groupRoster = roster.filter { it.label !in excluded }
            println(
                "Running experiment '$name': ${groupProblems.size} problems x " +
                        "${groupRoster.size} solver cases x ${config.macroReplications} reps, budget $budget"
            )
            val experiment = BenchmarkExperiment(
                name = name,
                problems = groupProblems,
                solverCases = groupRoster,
                macroReplications = config.macroReplications,
                replicationBudgetPerRun = budget,
                confirmation = config.confirmation,
                captureIterationTraces = config.captureTraces,
                verificationReplications = config.verificationReplications,
                numWorkers = config.numWorkers
            )
            val summary = experiment.run()
            results[name] = db.saveSummary(summary)
        }
    }
    return results
}

/**
 *  The Study-1 nine-case roster with ISC's global phase bounded to the benchmark budget.
 *  Identical to the general `allSolverCases()` except that the ISC case caps its global
 *  phase so its replication consumption stays comparable to the other solvers under the
 *  equal-budget comparison.
 *
 *  @param iscGlobalBudget the replication budget for ISC's global phase (the benchmark
 *  budget of the tier the roster runs in)
 */
fun study1Roster(): List<SolverCase> {
    return standardSolverCases() + listOf(
        geneticAlgorithmCase(),
        particleSwarmCase(),
        bayesianOptimizationCase(),
        // ISC runs at LIBRARY DEFAULTS and its ACTUAL consumption is reported (user ruling).
        // Its cost is dominated by the clean-up correct-selection guarantee, which needs
        // ~(noise/IZ)^2 replications and so ignores the benchmark budget on flat/high-noise
        // problems — a fundamental property of the R&S guarantee, not a bug. Bounding it
        // (global cap, COMPASS degraded mode) cannot touch the clean-up cost without
        // dropping the guarantee, so ISC is characterized as-is and its overshoot reported
        // as a finding. (The OOM on dimension >= 3 problems was fixed separately in KSLCore.)
        iscCase()
    )
}

/**
 *  Per-problem solver exclusions for Study 1 (problem name → solver labels that do NOT
 *  run on it), from the smoke-phase V&V findings. BO and CE do not retain a feasible
 *  incumbent on the budget-constrained multi-item newsvendor (its feasible region is a
 *  small fraction of the box), so they are excluded there and reported as a finding; all
 *  other solvers run on it, and BO/CE run on every other problem.
 */
val STUDY1_SOLVER_EXCLUSIONS: Map<String, Set<String>> = mapOf(
    "multiItemNewsvendor_k3" to setOf("BO", "CE")
)
