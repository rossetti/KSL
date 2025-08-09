package ksl.simopt.solvers

import ksl.utilities.collections.pow
import kotlin.math.ceil

/**
 * Provides a replication schedule that increases according to a fixed-rate
 * based on the number of iterations completed by the solver.
 * @param initialNumReps the initial starting number of replications
 * @param growthRate the growth rate. The default is set by [defaultReplicationGrowthRate].
 * @param maxNumReplications the maximum number of replications permitted. If
 * the growth exceeds this value, then this value is used for all future replications.
 * The default is determined by [defaultMaxNumReplications]
 */
class FixedGrowthRateReplicationSchedule(
    initialNumReps: Int,
    growthRate: Double = defaultReplicationGrowthRate,
    maxNumReplications: Int = defaultMaxNumReplications
) : ReplicationPerEvaluationIfc {
    init {
        require(initialNumReps > 0) { "The initial number of replication for each evaluation must be > 0" }
        require(growthRate > 0) { "The replication growth rate must be > 0" }
        require(maxNumReplications > 0) { "The maximum number of replication for each evaluation must be > 0" }
    }

    var initialNumReps: Int = initialNumReps
        set(value) {
            require(value > 0) { "The initial number of replication for each evaluation must be > 0" }
            field = value
        }

    var growthRate: Double = growthRate
        set(value) {
            require(value > 0) { "The replication growth rate must be > 0" }
            field = value
        }

    var maxNumReplications: Int = maxNumReplications
        set(value) {
            require(value > 0) { "The maximum number of replication for each evaluation must be > 0" }
            field = value
        }

    var currentNumReplications: Int = initialNumReps
        private set

    override fun numReplicationsPerEvaluation(solver: Solver): Int {
        val k = solver.iterationCounter
        if (k == 1) {
            return initialNumReps
        }
        val m = initialNumReps * (1.0 + growthRate).pow(k - 1)
        currentNumReplications = minOf(maxNumReplications, ceil(m).toInt())
        return currentNumReplications
    }

    override fun toString(): String {
        return "FixedGrowthRateReplicationSchedule(" +
                "initialNumReps=$initialNumReps, " +
                "growthRate=$growthRate, " +
                "maxNumReplications=$maxNumReplications, " +
                "currentNumReplications=$currentNumReplications" +
                ")"
    }

    companion object {

        /**
         *  The default maximum number of replications. By default, this is 1000.
         */
        var defaultMaxNumReplications: Int = 1000
            set(value) {
                require(value > 0) { "The default maximum number of replication for each evaluation must be > 0" }
                field = value
            }

        /**
         *  The default growth rate. By default, this is 50% (0.5).
         */
        var defaultReplicationGrowthRate: Double = 0.5
            set(value) {
                require(value > 0) { "The replication growth rate must be > 0" }
                field = value
            }

    }

}