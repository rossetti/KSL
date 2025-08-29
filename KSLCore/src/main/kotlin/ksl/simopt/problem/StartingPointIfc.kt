package ksl.simopt.problem

fun interface StartingPointIfc {

    fun startingPoint(
        problemDefinition: ProblemDefinition,
    ): InputMap

    /**
     *  Generates a set of input feasible points by repeatedly calling startingPoint()
     *  and checking if the point is feasible.
     */
    fun generateInputFeasiblePoints(numPoints: Int, problemDefinition: ProblemDefinition): Set<InputMap> {
        require(numPoints > 0) { "The number of points must be > 0" }
        val set = mutableSetOf<InputMap>()
        while(set.size <= numPoints) {
            val point = startingPoint(problemDefinition)
            if (problemDefinition.isInputFeasible(point)) {
                set.add(point)
            }
        }
        return set
    }
}

