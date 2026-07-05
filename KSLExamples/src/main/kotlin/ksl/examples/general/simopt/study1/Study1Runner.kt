package ksl.examples.general.simopt.study1

import ksl.examples.general.simopt.bayesianOptimizationCase
import ksl.examples.general.simopt.geneticAlgorithmCase
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
    // ISC is DROPPED from Study 1 (user ruling). Its correct-selection guarantee consumes
    // ~(noise/IZ)^2 replications — up to ~9.4M per cell — which is fundamentally
    // incompatible with a fixed-budget benchmark: it overruns the sub-stream block (1M) and
    // overlaps neighboring cells' streams, and no in-study configuration bounds it without
    // dropping the guarantee. ISC needs an absolute replication cap in its clean-up/COMPASS
    // phases (a KSLCore change) before it can participate in equal-budget comparisons; that
    // and the other ISC findings are recorded for follow-on work (see the study plan §11).
    // The eight remaining solvers each consume well under the sub-stream block.
    return standardSolverCases() + listOf(
        geneticAlgorithmCase(),
        particleSwarmCase(),
        // BO's GP fit is O(n^3) in evaluated points; at the study budgets it would do
        // hundreds of GP-refit iterations (~2-3 hours). Cap the GP training set to the
        // best STUDY1_BO_ARCHIVE_CAP points (scalable-BO practice) so each fit is
        // constant-cost regardless of budget. A study-specific (non-default) BO config.
        bayesianOptimizationCase(maxArchiveSize = STUDY1_BO_ARCHIVE_CAP)
    )
}

/** Cap on BO's Gaussian-process training set for Study 1 (bounds the O(n^3) fit cost). */
const val STUDY1_BO_ARCHIVE_CAP: Int = 100

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
