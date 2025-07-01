package ksl.simopt.solvers

/**
 *  This interface is used by solvers to determine the number of
 *  replications to request when asking the simulation oracle
 *  for responses.  The user can supply different strategies
 *  for determining the number of replications when far from
 *  the optimal or close to the optimal.
 */
fun interface ReplicationPerEvaluationIfc {

    /**
     *  @return the number of iterations to use for the evaluation.
     *  In general, must be greater than or equal to 1.
     */
    fun numReplicationsPerEvaluation(solver: Solver) : Int
}