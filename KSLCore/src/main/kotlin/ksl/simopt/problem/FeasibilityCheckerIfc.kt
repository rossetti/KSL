package ksl.simopt.problem

import ksl.simopt.evaluator.EstimatedResponse

fun interface FeasibilityCheckerIfc {

    /**
     *  Returns true if the implied test of feasibility indicates that the constraint is satisfied.
     *
     *  @param responseConstraint the response constraint to test
     *  @param estimatedResponse the supplied response. It must have the same name as the response associated with
     * the constraint and the number of observations (count) must be greater than or equal to 2.
     *  @param confidenceLevel the confidence level for computing the upper limit of the confidence interval
     *  @return true if the test for feasibility passes otherwise false
     */
    fun isFeasible(
        responseConstraint: ResponseConstraint,
        estimatedResponse: EstimatedResponse,
        confidenceLevel: Double
    ): Boolean
}