package ksl.examples.general.simopt.problems

import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rng.RNStreamIfc

/**
 *  The penalty-dynamics isolator of the synthetic ladder: a quadratic whose
 *  unconstrained optimum is response-infeasible. Minimize the sum of (x_i - 5)^2
 *  subject to the (noisily observed) response constraint E(sum of x_i) at most 3 d,
 *  over the box from -10 to 10 per coordinate. The unconstrained optimum (all fives,
 *  coordinate sum 5 d) violates the constraint, so solvers must work on the boundary;
 *  the constrained optimum is the all-threes point (an integer lattice point) with
 *  objective value 4 d. Because the only complication is the constraint, differences
 *  between solver cases on this problem measure feasibility and penalty handling, not
 *  search ability.
 *
 *  @param dimension the number of decision variables
 *  @param noiseLevel the additive Gaussian noise level (applied to both the objective
 *  and the constraint response)
 */
class ConstrainedNoisyQuadratic(
    dimension: Int,
    noiseLevel: NoiseLevel
) : SyntheticFunctionProblem(dimension, noiseLevel) {

    override val familyName: String = "constrainedNoisyQuadratic"
    override val lowerBound: Double = -10.0
    override val upperBound: Double = 10.0
    override val optimum: DoubleArray = DoubleArray(dimension) { 3.0 }
    override val extraResponseNames: List<String> = listOf(CONSTRAINT_RESPONSE)

    override fun trueObjective(point: DoubleArray): Double {
        var sum = 0.0
        for (value in point) {
            val z = value - 5.0
            sum += z * z
        }
        return sum
    }

    override fun replication(point: DoubleArray, stream: RNStreamIfc): Map<String, Double> {
        val variance = noiseLevel.sigma * noiseLevel.sigma
        return mapOf(
            objectiveResponseName to trueObjective(point) + stream.rNormal(0.0, variance),
            CONSTRAINT_RESPONSE to point.sum() + stream.rNormal(0.0, variance)
        )
    }

    override fun configureProblem(problemDefinition: ProblemDefinition) {
        problemDefinition.responseConstraint(
            name = CONSTRAINT_RESPONSE,
            rhsValue = 3.0 * dimension
        )
    }

    companion object {
        /** The name of the noisily observed constraint response (the coordinate sum). */
        const val CONSTRAINT_RESPONSE: String = "coordinateSum"
    }
}
