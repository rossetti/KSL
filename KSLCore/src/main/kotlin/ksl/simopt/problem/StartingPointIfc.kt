package ksl.simopt.problem

fun interface StartingPointIfc {

    fun startingPoint(
        problemDefinition: ProblemDefinition,
        roundToGranularity: Boolean
    ): Map<String, Double>

}

class FixedStartingPoint(val point: Map<String, Double>) : StartingPointIfc {

    override fun startingPoint(problemDefinition: ProblemDefinition, roundToGranularity: Boolean): Map<String, Double> {
        require(problemDefinition.isInputFeasible(point)) {"The supplied starting point is infeasible for this problem"}
        return if (roundToGranularity){
            problemDefinition.roundToGranularity(point.toMutableMap())
        } else {
            point
        }
    }

}
