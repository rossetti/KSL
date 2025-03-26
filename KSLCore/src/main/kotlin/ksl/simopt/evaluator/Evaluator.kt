package ksl.simopt.evaluator

import ksl.simopt.problem.ProblemDefinition

abstract class Evaluator(
    val problemDefinition: ProblemDefinition
) {

    // the budget (in terms of number of evaluations)
    var budgetReplications: Int = Int.MAX_VALUE
        protected set
    // the total number of batches evaluated
    var numBatches: Int = 0
        protected set
    // the total number of evaluations
    var numRequests: Int = 0
        protected set
    // the total number of replications
    var numReplications: Int = 0
        protected set
    // the number of evaluations that were avoided as duplicates within a batch
    var numDuplicateRequestsInBatch: Int = 0
        protected set
    // the number of evaluations with a direct component
    var numDirectEvaluations = 0
        protected set
    // the number of evaluations with a cached component
    var numCachedEvaluations = 0
        protected set
    // the number of evaluation replications that did not come from the cache
    var numDirectReplications: Int = 0
        protected set
    // the number of evaluations replications that came from the cache
    var numCachedReplications: Int = 0
        protected set

    fun resetStatistics() {
        numBatches = 0
        numRequests = 0
        numReplications = 0
        numDuplicateRequestsInBatch = 0
        numDirectEvaluations = 0
        numCachedEvaluations = 0
        numDirectReplications = 0
        numCachedReplications = 0
    }

}