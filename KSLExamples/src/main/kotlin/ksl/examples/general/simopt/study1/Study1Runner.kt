package ksl.examples.general.simopt.study1

import ksl.examples.general.simopt.allSolverCases
import ksl.simopt.benchmark.BenchmarkExperiment
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
fun runStudy1(db: BenchmarkResultsDb, config: Study1Config): Map<BudgetTier, Int> {
    val existingNames = db.experiments().map { it.expName }.toSet()
    val problemsByTier = study1ProblemsByBudgetTier()
    val results = linkedMapOf<BudgetTier, Int>()
    for (tier in BudgetTier.entries) {
        val problems = problemsByTier[tier] ?: continue
        val name = config.experimentName(tier)
        if (name in existingNames) {
            println("Skipping experiment '$name' (already present in the database).")
            continue
        }
        println(
            "Running experiment '$name': ${problems.size} problems x 9 solver cases x " +
                    "${config.macroReplications} reps, budget ${config.budget(tier)}"
        )
        val experiment = BenchmarkExperiment(
            name = name,
            problems = problems,
            solverCases = allSolverCases(),
            macroReplications = config.macroReplications,
            replicationBudgetPerRun = config.budget(tier),
            confirmation = config.confirmation,
            captureIterationTraces = config.captureTraces,
            verificationReplications = config.verificationReplications,
            numWorkers = config.numWorkers
        )
        val summary = experiment.run()
        val expId = db.saveSummary(summary)
        results[tier] = expId
    }
    return results
}
