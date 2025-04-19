package ksl.simopt.problem

class FixedStartingPoint(val point: MutableMap<String, Double>) : StartingPointIfc {

    override fun startingPoint(problemDefinition: ProblemDefinition): InputMap {
        require(problemDefinition.isInputFeasible(point)) {"The supplied starting point is infeasible for this problem"}
        return InputMap(problemDefinition, point)
    }

}