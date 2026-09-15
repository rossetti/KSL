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
 *
 * @param initialPosition the sub-stream index at which the tape begins; defaults to 0. Concurrent
 * solver execution (e.g. parallel random restarts) gives each member its own policy starting at a
 * distinct block offset (member index times a generous block size) so members draw from
 * non-overlapping regions of the sub-stream tape regardless of scheduling. Sub-stream positioning
 * is a constant-time jump, so large offsets cost nothing. Must be non-negative.
 */
class StreamTapePolicy(initialPosition: Int = 0) {

    init {
        require(initialPosition >= 0) { "The initial tape position must be non-negative" }
    }

    private val myInitialPosition: Int = initialPosition

    private var myPosition: Int = initialPosition

    /** The current tape position (the next free sub-stream index). */
    val position: Int
        get() = myPosition

    /** The position at which this tape began (and to which reset() returns). */
    @Suppress("unused")
    val initialPosition: Int
        get() = myInitialPosition

    /**
     * Returns the pre-run sub-stream advance for each point of [inputs], in request order, and moves
     * the tape forward. See the class documentation for the independent vs. CRN semantics.
     *
     * @param inputs the points of the request, in order
     * @param crnOption true for common random numbers (all points share the current block), false for
     * independent, non-overlapping stream blocks
     * @return the advance to apply (as `numberOfStreamAdvancesPriorToRunning`) for each point
     */
    fun advancesFor(inputs: List<ModelInputs>, crnOption: Boolean): List<Int> =
        advancesFor(inputs.map { it.numReplications }, crnOption)

    /**
     * Returns the pre-run sub-stream advance for each of a sequence of runs described only by how
     * many replications each consumes, in order, and moves the tape forward. See the class
     * documentation for the independent vs. CRN semantics.
     *
     * This is the whole of the arithmetic: positioning a run on the tape depends on nothing about a
     * point except its replication count. The [ModelInputs] overload above reads exactly that from
     * each input and delegates here, so a harness outside `ksl.simopt` that positions replications
     * absolutely on a sub-stream tape can reuse this policy rather than manufacture `ModelInputs`
     * values it has no other use for, or hand-roll a weaker version of the same rule.
     *
     * Overloaded on the Kotlin side only: a `List<ModelInputs>` and a `List<Int>` erase to the same
     * JVM signature, so this carries a distinct `@JvmName` and Java callers reach it as
     * `advancesForReplicationCounts`.
     *
     * @param replicationCounts the replications each run will consume, in order; each must be
     * non-negative
     * @param crnOption true for common random numbers (every run shares the current block), false
     * for independent, non-overlapping blocks
     * @return the advance to apply (as `numberOfStreamAdvancesPriorToRunning`) for each run
     */
    @JvmName("advancesForReplicationCounts")
    fun advancesFor(replicationCounts: List<Int>, crnOption: Boolean): List<Int> {
        if (replicationCounts.isEmpty()) return emptyList()
        require(replicationCounts.all { it >= 0 }) {
            "A replication count must be non-negative; got $replicationCounts"
        }
        if (crnOption) {
            val advances = List(replicationCounts.size) { myPosition }
            myPosition += replicationCounts.max()
            return advances
        }
        return replicationCounts.map { count -> myPosition.also { myPosition += count } }
    }

    /** Resets the tape to its initial position. Primarily useful for tests. */
    fun reset() {
        myPosition = myInitialPosition
    }
}
