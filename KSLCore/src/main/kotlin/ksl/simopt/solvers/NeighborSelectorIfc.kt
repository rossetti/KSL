package ksl.simopt.solvers

import ksl.simopt.problem.InputMap

/**
 *  Defines a general functional interface for selecting
 *  a neighbor (point) from a defined neighborhood.
 */
fun interface NeighborSelectorIfc {

    /**
     *  Defines a general functional interface for selecting
     *  a neighbor (point) from a defined neighborhood.
     *  @param neighborhood the neighborhood from which to select
     *  @return the selected point from the neighborhood
     */
    fun select(neighborhood: Set<InputMap>) : InputMap

}