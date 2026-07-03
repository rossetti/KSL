package ksl.simopt.solvers.concurrent

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.solvers.Solver

/**
 * Creates a fresh solver instance bound to the supplied per-member evaluation resources.
 *
 * Concurrent solver execution (parallel random restarts, solver portfolios) runs several
 * solver instances at the same time, each on its own worker. Solver instances are
 * stateful and must never be shared across workers, so the runner asks this factory for
 * a new instance per member, handing it an evaluator whose entire resource chain (cache,
 * simulation provider, model, stream tape) is private to that member.
 *
 * Contract for implementations:
 *  - Return a NEW solver instance on every call; never a cached or shared one.
 *  - Bind the instance to the supplied [EvaluatorIfc] only — routing evaluations through
 *    any other (shared) evaluator is a data race under concurrent execution.
 *  - Give the instance its own random number stream provider (the solver constructors'
 *    default of a fresh provider is correct) so member solvers never share solver-side
 *    streams. The member's simulation-side streams are isolated by the evaluator.
 *  - The factory itself may be closed over problem definition and algorithm parameters;
 *    it is invoked on worker threads, so anything it captures must be safe to read
 *    concurrently.
 */
fun interface SolverFactoryIfc {

    /**
     * Creates a fresh solver for one member of a concurrent run.
     *
     * @param evaluator the member's private evaluator; the created solver must use it
     * exclusively
     * @param memberIndex the 0-based index of the member within the run; useful for
     * labeling, logging, or index-dependent configuration
     * @param name a suggested name for the solver instance (derived from the member's
     * label); implementations should pass it to the solver so traces and logs identify
     * the member
     * @return the newly created solver
     */
    fun create(evaluator: EvaluatorIfc, memberIndex: Int, name: String): Solver

    companion object {
        /**
         * The member index handed to a factory when creating a prototype instance —
         * one that exists only for configuration reporting and tracking probes and is
         * never run. Factories that key behavior on the member index can use this to
         * recognize the prototype; most ignore the index.
         */
        const val PROTOTYPE_MEMBER_INDEX: Int = -1
    }
}
