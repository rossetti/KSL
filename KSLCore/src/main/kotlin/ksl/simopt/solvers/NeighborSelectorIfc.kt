package ksl.simopt.solvers

import ksl.simopt.problem.InputMap

/**
 *  Defines a general functional interface for selecting
 *  a neighbor (point) from a defined neighborhood.
 *  The solver is supplied to allow potential access to its state/memory
 *  within the process to determine the neighbor.
 */
fun interface NeighborSelectorIfc {

    /**
     *  Defines a general functional interface for selecting
     *  a neighbor (point) from a defined neighborhood.
     *  @param neighborhood the neighborhood from which to select
     *  @param solver the solver requiring the selected neighbor
     *  @return the selected point from the neighborhood
     */
    fun select(neighborhood: Set<InputMap>, solver: Solver) : InputMap

}