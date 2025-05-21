package ksl.simopt.solvers

import ksl.simopt.problem.InputMap

/**
 *  Defines a search neighborhood for the provided input
 *  with respect to the problem.
 *  The solver is supplied to allow potential access to its state/memory
 *  within the process to determine the neighborhood.
 */
fun interface NeighborhoodIfc {

    /**
     *  Defines a search neighborhood for the provided input
     *  with respect to the problem. The function should guarantee that
     *  the returned set is not empty.
     *
     *  @param inputMap the location of the current point in the search space
     *  relative to which the neighborhood should be formed
     *  @param solver the solver needing the neighborhood
     *  @return a set of input points that form a search neighborhood around
     *  the provided point.
     */
    fun neighborhood(
        inputMap: InputMap,
        solver: Solver
    ): Set<InputMap>
}