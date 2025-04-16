package ksl.simopt.solvers

import ksl.simopt.problem.InputMap

/**
 *  Defines a search neighborhood for the provided input
 *  with respect to the problem.
 */
fun interface NeighborhoodIfc {

    /**
     *  Defines a search neighborhood for the provided input
     *  with respect to the problem.
     *
     *  @param inputMap the location of the current point in the search space
     *  relative to which the neighborhood should be formed
     *  @return a set of input points that form a search neighborhood around
     *  the provided point.
     */
    fun neighborhood(
        inputMap: InputMap,
    ): Set<InputMap>
}