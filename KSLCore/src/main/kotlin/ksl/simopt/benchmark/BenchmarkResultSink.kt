package ksl.simopt.benchmark

import kotlinx.datetime.Instant

/**
 *  What is known about a benchmark experiment before its first problem runs.
 *
 *  A sink needs this to open a record it can append to. It deliberately excludes everything that
 *  only exists once cells have run — solver configurations, traces, results — since the point of
 *  streaming is to write those as they arrive rather than at the end.
 *
 *  @param experimentName the experiment's name
 *  @param macroReplications the number of macro-replications this experiment runs
 *  @param replicationBudgetPerRun the per-cell replication budget
 *  @param confirmationTopK the confirmation stage's finalist count; null when confirmation is off
 *  @param confirmationReplications the confirmation stage's replications per candidate; null when
 *  confirmation is off
 *  @param verificationReplications the verification replication count; null when verification is off
 *  @param numProblems the number of problems the experiment covers, counting any a resumed run
 *  skips, so the record describes the study rather than this pass over it
 *  @param solverCaseDescriptions solver case label to its description
 *  @param startTime when the experiment's run() began
 *  @param tracesCaptured whether the experiment is capturing iteration traces
 *  @param solverStateCaptured whether the experiment is capturing solver-specific state
 */
data class BenchmarkSummaryHeader(
    val experimentName: String,
    val macroReplications: Int,
    val replicationBudgetPerRun: Int,
    val confirmationTopK: Int?,
    val confirmationReplications: Int?,
    val verificationReplications: Int?,
    val numProblems: Int,
    val solverCaseDescriptions: Map<String, String>,
    val startTime: Instant,
    val tracesCaptured: Boolean,
    val solverStateCaptured: Boolean
)

/**
 *  A destination that receives a benchmark experiment's results **as each problem completes**
 *  rather than in one call at the end.
 *
 *  Why this exists. `BenchmarkExperiment.run()` builds every problem's result in memory and returns
 *  one summary, so a study held its entire output in heap and wrote once. An interruption at hour 30
 *  of a 32-hour run lost all of it — and the checkpointing the class documents works by splitting a
 *  study into several experiments over disjoint macro-replication ranges, which does nothing for a
 *  study that is a single experiment.
 *
 *  `runProblem` already completes a problem entirely — cells, confirmation and verification — before
 *  the next begins, so the checkpoint boundary exists in the control flow and needs no restructuring
 *  to use. Six problems become six checkpoints instead of one, and an interruption costs at most one
 *  problem.
 *
 *  **Ordering.** Exactly one `beginExperiment`, then zero or more `problemCompleted` in problem
 *  order, then one `endExperiment`. An interrupted run stops partway and never calls
 *  `endExperiment`, which is how [completedProblems] can tell an unfinished experiment from a
 *  finished one.
 *
 *  **Threading.** Every call arrives on the thread that invoked `run()`, serialized between
 *  problems. An implementation needs no synchronization of its own.
 */
interface BenchmarkResultSink {

    /**
     *  Opens a record for the experiment and returns the id that keys every later call.
     *
     *  @param header what is known before the first problem runs
     *  @param resume when true, attach to an existing **unfinished** record for the same experiment
     *  name and return its id, so a re-run continues where the interrupted one stopped. A finished
     *  record is never resumed into; it gets a fresh id, preserving the append semantics under which
     *  re-running a completed experiment accumulates alongside it. When false, always a fresh id.
     *  @return the experiment id
     */
    fun beginExperiment(header: BenchmarkSummaryHeader, resume: Boolean = false): Int

    /**
     *  Receives one problem's completed result. Called after the problem's cells, confirmation and
     *  verification have all finished, so the result is final and will not be revisited.
     *
     *  @param expId the id returned by [beginExperiment]
     *  @param result the problem's result
     *  @param traces the iteration traces of this problem's cells, keyed by cell label; empty when
     *  the experiment is not capturing traces. Passed here rather than read from a summary because
     *  no summary exists yet.
     */
    fun problemCompleted(
        expId: Int,
        result: ProblemBenchmarkResult,
        traces: Map<String, List<IterationTracePoint>> = emptyMap()
    )

    /**
     *  Closes the experiment's record. After this the experiment counts as finished, and
     *  [completedProblems] stops reporting its problems as resumable.
     *
     *  @param expId the id returned by [beginExperiment]
     *  @param endTime when the experiment's run() completed
     *  @param solverConfigurations solver case label to the configuration properties read from the
     *  first solver instance the case actually created — not known until cells have run, which is
     *  why it arrives here rather than in the header
     */
    fun endExperiment(
        expId: Int,
        endTime: Instant,
        solverConfigurations: Map<String, Map<String, String>> = emptyMap()
    )

    /**
     *  The names of problems already recorded for an **unfinished** experiment of this name, which
     *  a re-run may therefore skip.
     *
     *  Returns an empty set by default, so a sink that cannot answer never causes a problem to be
     *  skipped. Silence must mean "run it", never "assume it is done".
     *
     *  @param expName the experiment name
     */
    fun completedProblems(expName: String): Set<String> = emptySet()
}
