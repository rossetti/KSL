package ksl.controls.experiments

/**
 * Assigns non-overlapping pre-run sub-stream advances to the selected scenarios.
 *
 * The assignments are written to each scenario's
 * [ExperimentRunParameters.numberOfStreamAdvancesPriorToRunning] field. With
 * the default [streamAdvanceSpacing] of null, each selected scenario receives
 * the next cumulative offset based on the previous selected scenario's number
 * of replications. For example, three selected scenarios with 3, 5, and 2
 * replications receive offsets 0, 3, and 8.
 *
 * If [streamAdvanceSpacing] is supplied, the same fixed spacing is used between
 * selected scenarios. This is useful when callers want stable offsets even if
 * scenario replication counts later change.
 *
 * Invalid indices in [scenarios] are ignored. Assignment follows the order of
 * the supplied progression, so a descending progression assigns offsets in
 * descending index order.
 *
 * @param scenarios indices of scenarios to assign; defaults to the full list
 * @param startingStreamAdvance first assigned advance value; must be >= 0
 * @param streamAdvanceSpacing fixed spacing between selected scenarios; null uses cumulative replication counts
 * @return this list for fluent configuration
 */
@JvmOverloads
fun List<Scenario>.assignIndependentStreamAdvances(
    scenarios: IntProgression = indices,
    startingStreamAdvance: Int = 0,
    streamAdvanceSpacing: Int? = null
): List<Scenario> {
    require(startingStreamAdvance >= 0) { "startingStreamAdvance must be >= 0" }
    streamAdvanceSpacing?.let {
        require(it >= 1) { "streamAdvanceSpacing must be >= 1" }
    }

    var nextAdvance = startingStreamAdvance
    for (index in scenarios) {
        if (index !in indices) continue

        val scenario = this[index]
        scenario.useStreamAdvance(nextAdvance)
        nextAdvance += streamAdvanceSpacing ?: scenario.scenarioRunParameters.numberOfReplications
    }
    return this
}
