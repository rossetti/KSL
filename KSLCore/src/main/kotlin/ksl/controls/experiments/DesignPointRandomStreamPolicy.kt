package ksl.controls.experiments

/**
 * Defines how design points in a [ParallelDesignedExperiment] are positioned
 * in the model's random-number streams.
 */
enum class DesignPointRandomStreamPolicy {

    /**
     * Design points receive non-overlapping pre-run sub-stream advances.
     *
     * This is the default because it matches the legacy [DesignedExperiment]
     * behavior produced by reusing one model sequentially across design points.
     */
    INDEPENDENT_RANDOM_STREAMS,

    /**
     * Design points start from the same stream positions.
     *
     * This can be useful when comparing design points with common random numbers.
     */
    COMMON_RANDOM_NUMBERS
}
