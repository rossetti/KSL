package ksl.simopt.evaluator

/**
 * Computes the sub-stream advance applied before each point of an [EvaluationRequest] is run, so
 * that consecutive requests draw fresh, non-overlapping random numbers — exactly the way a reused
 * model's streams advance continuously in [SimulationProvider].
 *
 * The policy holds a persistent tape position (a sub-stream index) that survives across requests:
 *  - **Independent streams** (`crnOption == false`): each point consumes its replications and the
 *    tape advances cumulatively, so point i runs on sub-streams [position, position + numReplications).
 *  - **Common random numbers** (`crnOption == true`): every point of the request starts at the
 *    current tape position (the same block, for paired comparison) and the tape then advances by the
 *    request's maximum replication count.
 *
 * Each planned run is positioned **absolutely** (reset-to-start + advance), so the advances returned
 * here do not depend on which model executes a point or in what order. This lets one policy drive
 * both the sequential [SimulationProvider] and the concurrent [ParallelSimulationProvider] (including
 * a pool of reused models) and have them produce identical streams.
 *
 * Not thread-safe: [advancesFor] mutates the tape position. It is intended to be called once per
 * request on the single solver thread, before the request fans out to workers.
 */
class StreamTapePolicy {

    private var myPosition: Int = 0

    /** The current tape position (the next free sub-stream index). */
    val position: Int
        get() = myPosition

    /**
     * Returns the pre-run sub-stream advance for each point of [inputs], in request order, and moves
     * the tape forward. See the class documentation for the independent vs. CRN semantics.
     *
     * @param inputs the points of the request, in order
     * @param crnOption true for common random numbers (all points share the current block), false for
     * independent, non-overlapping stream blocks
     * @return the advance to apply (as `numberOfStreamAdvancesPriorToRunning`) for each point
     */
    fun advancesFor(inputs: List<ModelInputs>, crnOption: Boolean): List<Int> {
        if (inputs.isEmpty()) return emptyList()
        if (crnOption) {
            val advances = List(inputs.size) { myPosition }
            myPosition += inputs.maxOf { it.numReplications }
            return advances
        }
        return inputs.map { modelInputs -> myPosition.also { myPosition += modelInputs.numReplications } }
    }

    /** Resets the tape to the beginning. Primarily useful for tests. */
    fun reset() {
        myPosition = 0
    }
}
