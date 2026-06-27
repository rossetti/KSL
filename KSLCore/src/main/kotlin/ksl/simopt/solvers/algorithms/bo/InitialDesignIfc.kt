package ksl.simopt.solvers.algorithms.bo

import ksl.simopt.problem.InputMap

/**
 *  Strategy for generating the initial design (the first batch of points evaluated before the
 *  surrogate-driven search begins).
 */
fun interface InitialDesignIfc {
    /**
     *  Generates an initial design of (approximately) [numPoints] distinct input points.
     *
     *  @param numPoints the desired number of initial points
     *  @param bo the solver requesting the design
     */
    fun generate(numPoints: Int, bo: BayesianOptimizationSolver): Set<InputMap>
}

/**
 *  A space-filling Latin-hypercube initial design over the input ranges. This is the default. The
 *  points are range-feasible; deterministic-constraint feasibility is governed by the solver's
 *  request handling.
 */
class LatinHyperCubeDesign : InitialDesignIfc {
    override fun generate(numPoints: Int, bo: BayesianOptimizationSolver): Set<InputMap> =
        bo.sampleLatinHyperCubePoints(numPoints)

    override fun toString(): String = "LatinHyperCubeDesign()"
}

/**
 *  A random initial design of distinct input-feasible points (feasible with respect to input
 *  ranges, linear, and functional constraints).
 */
class RandomFeasibleDesign : InitialDesignIfc {
    override fun generate(numPoints: Int, bo: BayesianOptimizationSolver): Set<InputMap> =
        bo.sampleInputFeasiblePoints(numPoints)

    override fun toString(): String = "RandomFeasibleDesign()"
}
