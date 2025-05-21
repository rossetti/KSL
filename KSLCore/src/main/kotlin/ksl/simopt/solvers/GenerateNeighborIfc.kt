package ksl.simopt.solvers

import ksl.simopt.problem.InputMap

/**
 *   Given input values for a problem, this functional interface should
 *   generate a neighbor relative to the supplied input.
 *   The solver is supplied to allow potential access to its memory
 *   within the generation process.
 */
fun interface GenerateNeighborIfc {

    /**
     *   Given input values for a problem, this function should
     *   generate a neighbor relative to the supplied input.
     *
     *   @param inputMap the input to serve as the basis for determining a neighbor
     *   @param solver the solver requiring the generated neighbor
     *   @param ensureFeasibility indicates if the generation method should ensure the problem
     *   feasibility of the returned value
     *   @return a neighbor to the supplied input
     */
    fun generateNeighbor(inputMap: InputMap, solver: Solver, ensureFeasibility: Boolean) : InputMap

}