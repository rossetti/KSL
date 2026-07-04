package ksl.examples.general.simopt

import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.solvers.concurrent.PooledMemberEvaluatorFactory

/**
 *  The classic inventory teaching problems as benchmark-ready problem cases. Each case
 *  pairs the problem definition with a pooled, model-backed member-evaluator factory
 *  over the corresponding model builder — the DEDS counterpart of the synthetic ladder
 *  in `ksl.examples.general.simopt.problems`. Neither problem has a known optimum, so runs are
 *  gapped against the best found within the experiment.
 */

/** The LK inventory model: 2 decision variables (order quantity, reorder point),
 *  unconstrained total-cost minimization. */
fun lkInventoryProblemCase(): ProblemCase {
    return ProblemCase(
        name = "LKInventory",
        problemDefinitionFactory = { makeLKInventoryModelProblemDefinition() },
        evaluatorFactoryProvider = { pd -> PooledMemberEvaluatorFactory(pd, BuildLKModel) },
        tags = mapOf(
            "family" to "inventoryDEDS",
            "dimension" to "2",
            "constrained" to "false"
        )
    )
}

/** The (R,Q) inventory model: 2 decision variables (reorder quantity, reorder point),
 *  ordering-and-holding-cost minimization under a 95 percent fill-rate constraint. */
fun rqInventoryProblemCase(): ProblemCase {
    return ProblemCase(
        name = "RQInventory",
        problemDefinitionFactory = { makeRQInventoryModelProblemDefinition() },
        evaluatorFactoryProvider = { pd -> PooledMemberEvaluatorFactory(pd, BuildRQModel) },
        tags = mapOf(
            "family" to "inventoryDEDS",
            "dimension" to "2",
            "constrained" to "true"
        )
    )
}
