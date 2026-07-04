package ksl.simopt.benchmark.problems

/**
 *  The named noise levels of the synthetic benchmark ladder: the standard deviation of
 *  the additive Gaussian noise applied to a deterministic test function. Noise level is
 *  an explicit experimental factor (a problem tag), so a study can measure how each
 *  algorithm degrades as the signal-to-noise ratio falls.
 */
enum class NoiseLevel(val sigma: Double) {
    /** Standard deviation 1 — mild noise; algorithms should behave nearly deterministically. */
    LOW(1.0),

    /** Standard deviation 10 — noise on the order of local objective differences. */
    MED(10.0),

    /** Standard deviation 100 — noise dominating all but the largest objective differences. */
    HIGH(100.0)
}
