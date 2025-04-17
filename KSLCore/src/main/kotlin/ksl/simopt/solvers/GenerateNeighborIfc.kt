package ksl.simopt.solvers

import ksl.simopt.problem.InputMap

/**
 *   Given input values for a problem, this functional interface should
 *   generate a neighbor relative to the supplied input.
 */
fun interface GenerateNeighborIfc {

    /**
     *   Given input values for a problem, this function should
     *   generate a neighbor relative to the supplied input.
     *
     *   @param inputMap the input to serve as the basis for determining a neighbor
     *   @return a neighbor to the supplied input
     */
    fun generateNeighbor(inputMap: InputMap) : InputMap

}