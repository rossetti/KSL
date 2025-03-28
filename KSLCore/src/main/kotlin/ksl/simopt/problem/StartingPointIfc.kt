package ksl.simopt.problem

fun interface StartingPointIfc {

    fun startingPoint(
        problemDefinition: ProblemDefinition,
        roundToGranularity: Boolean
    ): InputMap

}

class FixedStartingPoint(val point: MutableMap<String, Double>) : StartingPointIfc {

    override fun startingPoint(problemDefinition: ProblemDefinition, roundToGranularity: Boolean): InputMap {
        require(problemDefinition.isInputFeasible(point)) {"The supplied starting point is infeasible for this problem"}
        return if (roundToGranularity){
            problemDefinition.roundToGranularity(point.toMutableMap())
        } else {
            InputMap(point)
        }
    }

}
