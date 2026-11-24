package ksl.simopt.solvers.concurrent

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import java.util.concurrent.atomic.AtomicInteger

/**
 * An evaluator that simulates nothing: each call returns a strictly better value of the response named
 * `responseName` than the one before, after sleeping `perEvaluationDelayMillis`.
 */
internal class ImprovingStubEvaluator(
    private val pd: ProblemDefinition,
    private val perEvaluationDelayMillis: Long,
    private val responseName: String = "TotalCost"
) : EvaluatorIfc {
    override var totalEvaluatorCalls: Int = 0
    override var totalDesignPointsEvaluated: Int = 0
    override var totalOracleReplications: Int = 0
    override var totalCachedReplications: Int = 0
    override val cache: SolutionCacheIfc? = null
    override fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
        if (perEvaluationDelayMillis > 0) Thread.sleep(perEvaluationDelayMillis)
        totalEvaluatorCalls++
        return evaluationRequest.modelInputs.associateWith { mi ->
            Solution(
                pd.toInputMap(mi.inputs.toMutableMap()),
                EstimatedResponse(responseName, -totalEvaluatorCalls.toDouble(), 1e-12, mi.numReplications.toDouble()),
                emptyList(),
                totalEvaluatorCalls
            )
        }
    }
}

/** A member factory that hands out an [ImprovingStubEvaluator], and counts those handed out and those released. */
internal class CountingMemberFactory(
    private val pd: ProblemDefinition,
    private val perEvaluationDelayMillis: Long
) : MemberEvaluatorFactoryIfc {
    val created = AtomicInteger()
    val released = AtomicInteger()
    override fun createEvaluator(memberIndex: Int): EvaluatorIfc {
        created.incrementAndGet()
        return ImprovingStubEvaluator(pd, perEvaluationDelayMillis)
    }

    override fun release(memberIndex: Int, evaluator: EvaluatorIfc, reusable: Boolean) {
        released.incrementAndGet()
    }

    /** Waits until at least one member's evaluator has been handed out. */
    fun awaitFirstCreated() {
        while (created.get() == 0) Thread.sleep(1)
    }
}
