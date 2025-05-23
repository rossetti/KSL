package ksl.simopt.solvers

class FixedReplicationsPerEvaluation(
    numReplications: Int
) : ReplicationPerEvaluationIfc {
    init {
        require(numReplications > 0 ) {"The number of replications for each evaluation must be > 0"}
    }

    var numReplications : Int = numReplications
        set(value) {
            require(value > 0 ) {"The number of replications for each evaluation must be > 0"}
            field = value
        }

    override fun numReplicationsPerEvaluation(solver: Solver): Int {
        return numReplications
    }

    override fun toString(): String {
        return "Fixed replications per evaluation: $numReplications"
    }
}