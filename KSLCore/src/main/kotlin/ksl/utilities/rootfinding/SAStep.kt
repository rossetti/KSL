package ksl.utilities.rootfinding

/**
 *  Immutable snapshot of the SA algorithm state at the completion of one iteration.
 *  Emitted through [SAStepEmitterIfc.stepEmitter] and optionally accumulated via
 *  [StochasticApproximationRootFinder.saveSteps]. Safe to store, share, or pass
 *  across threads without mutation risk.
 *
 *  @param x                Candidate root evaluated this iteration.
 *  @param fOfX             Function value f(x).
 *  @param rmSeries         Robbins-Monroe series value after Kesten update this iteration.
 *  @param rsc              Exponentially smoothed accumulator after update this iteration:
 *                          rsc = alpha * rsc_prev + (1 - alpha) * f(x).
 *  @param stoppingCriteria Convergence signal |scaleFactor * rmSeries * rsc|.
 *  @param iterationCount   1-based iteration number when this snapshot was taken.
 */
data class SAStep(
    val x: Double,
    val fOfX: Double,
    val rmSeries: Double,
    val rsc: Double,
    val stoppingCriteria: Double,
    val iterationCount: Int
) {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("SAStep").append(System.lineSeparator())
        sb.append("  Iteration        : $iterationCount").append(System.lineSeparator())
        sb.append("  x                : $x").append(System.lineSeparator())
        sb.append("  f(x)             : $fOfX").append(System.lineSeparator())
        sb.append("  RM Series        : $rmSeries").append(System.lineSeparator())
        sb.append("  RSC              : $rsc").append(System.lineSeparator())
        sb.append("  Stopping Crit.   : $stoppingCriteria").append(System.lineSeparator())
        return sb.toString()
    }
}