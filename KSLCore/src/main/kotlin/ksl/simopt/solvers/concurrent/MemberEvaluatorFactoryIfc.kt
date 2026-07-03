package ksl.simopt.solvers.concurrent

import ksl.simopt.evaluator.EvaluatorIfc

/**
 * Provisions the per-member evaluation resources for concurrent solver execution.
 *
 * Nothing above the simulation oracle in the evaluation stack is thread-safe (evaluator
 * counters, solution caches, the stream tape policy, a reused model), so concurrency is
 * achieved by isolation: each concurrently running member gets an evaluator whose entire
 * resource chain is private to it. Implementations may pool expensive resources (built
 * models) across members, as long as a resource is only ever held by one member at a
 * time.
 *
 * Both functions are invoked on member worker threads and must be safe to call
 * concurrently for different member indices.
 */
interface MemberEvaluatorFactoryIfc {

    /**
     * Creates the private evaluator for the member with the given index. Called once per
     * member, on the member's worker thread, before the member's solver is created.
     *
     * @param memberIndex the 0-based member index; implementations use it to assign the
     * member's non-overlapping stream-tape block
     * @return an evaluator private to this member
     */
    fun createEvaluator(memberIndex: Int): EvaluatorIfc

    /**
     * Releases the member's evaluator resources after the member finished (successfully
     * or not). Called exactly once per successful createEvaluator call, on the member's
     * worker thread.
     *
     * @param memberIndex the member's index
     * @param evaluator the evaluator returned by createEvaluator for this member
     * @param reusable true when the member completed normally and its resources (e.g.
     * the built model) can be safely reused by later members; false when the member
     * failed mid-run and the resources should be discarded
     */
    fun release(memberIndex: Int, evaluator: EvaluatorIfc, reusable: Boolean) {
    }
}
