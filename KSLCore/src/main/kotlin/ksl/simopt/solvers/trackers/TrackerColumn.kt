package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.SolverStateSnapshot

/**
 * Defines a single column for a structured, string-based tracker output (like CSV).
 *
 * @property headerName The title of the column to be printed in the header row.
 * @property stringifier A function that extracts and formats data from either the
 * mathematical [SolverStateSnapshot] or the tracker's [TrackingContext].
 */
data class TrackerColumn(
    val headerName: String,
    val stringifier: (SolverStateSnapshot, TrackingContext) -> String
)

/**
 * Defines a single column for a strongly-typed DataFrame tracker.
 *
 * @property columnName The title of the column in the resulting DataFrame.
 * @property extractor A function that extracts strongly-typed data (Double, Int, String, Map, etc.)
 * from either the [SolverStateSnapshot] or the [TrackingContext].
 */
data class DataFrameColumn(
    val columnName: String,
    val extractor: (SolverStateSnapshot, TrackingContext) -> Any?
)

/**
 * Defines a single column for a structured, nested tracker output (like CSV).
 */
data class NestedTrackerColumn(
    val headerName: String,
    val macroStringifier: (SolverStateSnapshot, TrackingContext) -> String,
    val microStringifier: (SolverStateSnapshot, TrackingContext) -> String
)

/**
 * Defines a single column for a nested DataFrame tracking.
 */
data class NestedDataFrameColumn(
    val columnName: String,
    val macroExtractor: (SolverStateSnapshot, TrackingContext) -> Any?,
    val microExtractor: (SolverStateSnapshot, TrackingContext) -> Any?
)