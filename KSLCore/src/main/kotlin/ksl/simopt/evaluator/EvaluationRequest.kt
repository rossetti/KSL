package ksl.simopt.evaluator

import ksl.simopt.problem.FeasibilityIfc
import ksl.simopt.problem.InputMap


/**
 *  A request for evaluation by the simulation oracle for the provided input values.
 *  Note that two requests are considered equal if their input maps are the same.
 *  Input maps are considered the same if all (name, value) pairs are equivalent.
 *  The number of replications of the request is not considered in the determination
 *  of equality.
 *  @param numReps the number of replications requested for the evaluation
 *  @param inputMap the inputs to be evaluated
 */
class EvaluationRequestBuggers(
    numReps: Int,
    val inputMap: InputMap,
) : FeasibilityIfc by inputMap {

    init {
        require(numReps >= 1) { "The number of replications must be >= 1" }
    }

    /**
     *  Since requests may cover portions of an experiment that has multiple replication,
     *  the starting replication number may be some number between 1 and the total
     *  number of replications in the experiment. The chunking process may
     *  set the starting replication number to the starting replication of the chunk
     *  of replications.
     */
    var startingReplicationNum: Int = 1
        set(value) {
            require(value >= 1) { "The starting replication number must be >= 1" }
            field = value
        }

    var numReplications: Int = numReps
        set(value) {
            field = maxOf(value, 0)
        }

    /**
     *  Sets the number of requested replications to the maximum of the supplied
     *  [numReps] or the current setting for the number of requested replications.
     */
    fun maxOfReplication(numReps: Int) {
        numReplications = maxOf(numReplications, numReps)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EvaluationRequestBuggers

        return inputMap == other.inputMap
    }

    override fun hashCode(): Int {
        return inputMap.hashCode()
    }

    val inputValues: DoubleArray
        get() = inputMap.inputValues

}