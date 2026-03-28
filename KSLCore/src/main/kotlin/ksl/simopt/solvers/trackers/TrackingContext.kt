package ksl.simopt.solvers.trackers

/**
 * Contains metadata about the current state of the tracker itself.
 * This context is passed to column extractors to allow them to stamp rows
 * with identifying information across multiple continuous solver runs.
 *
 * @property runNumber The autonomous counter indicating which consecutive run this is (1-indexed).
 * @property experimentName An optional, user-defined string to semantically label the current run.
 * @property macroIteration If this tracker is monitoring a nested solver architecture,
 * this holds the current outer (macro) iteration number. Null for standard single-level solvers.
 */
data class TrackingContext(
    val runNumber: Int,
    var experimentName: String = "DefaultExperiment",
    val macroIteration: Int? = null
)