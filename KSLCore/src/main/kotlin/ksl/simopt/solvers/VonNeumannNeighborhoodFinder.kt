package ksl.simopt.solvers

import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.KSLArrays
import ksl.utilities.toDoubles

/**
 *  A von Neumann neighborhood includes all points where the Manhattan distance (sum of absolute differences
 *  in all dimensions) from the center is less than or equal to the radius. This function uses
 *  a direct iteration around a bounding box.  By default, this implementation does
 *  not include the center point of the neighborhood and uses a radius of one.
 *  @param problemDefinition the problem definition over which the neighborhood should be found.
 *  @param radius the radius for the neighborhood. The default is 1.
 */
class VonNeumannNeighborhoodFinder(
    val problemDefinition: ProblemDefinition,
    radius: Int = 1
) : NeighborhoodFinderIfc {
    /**
     *  This is the neighborhood centered at 0 and adjusted for granularity
     */
    private val myBaseNeighborhood: List<DoubleArray>
    private val myInputNames = List(problemDefinition.inputNames.size) { problemDefinition.inputNames[it] }

    init {
        require(problemDefinition.inputDefinitions.isNotEmpty()) { "The problem must have at least one input variable." }
        val dimension = problemDefinition.inputDefinitions.size
        val znb = NeighborhoodFinderIfc.zeroVonNeumannNeighborhood(dimension, radius)
        myBaseNeighborhood = List(znb.size) { znb[it].toDoubles() }
        val granularities = problemDefinition.inputGranularities
        // if there is no granularity, then the default is to assume 1 unit movement
        for (vector in myBaseNeighborhood) {
            for ((i, g) in granularities.withIndex()) {
                if (g > 0.0) {
                    // only apply the granularity if it is not 0.0
                    vector[i] = vector[i] * g
                }
            }
        }
    }

    override fun neighborhood(
        inputMap: InputMap,
        solver: Solver?
    ): MutableSet<InputMap> {
        require(inputMap.problemDefinition == problemDefinition) { "The input map must be associated with the problem definition" }
        require(myInputNames == inputMap.problemDefinition.inputNames) { "The input names are not valid for the problem." }
        val set = mutableSetOf<InputMap>()
        val center = inputMap.inputValues
        for(baseNeighbor in myBaseNeighborhood){
            // shift the center element by the base neighbor to get the new neighbor around the center
            val neighbor = KSLArrays.addElements(center, baseNeighbor)
            set.add(problemDefinition.toInputMap(neighbor))
        }
        return set
    }

}