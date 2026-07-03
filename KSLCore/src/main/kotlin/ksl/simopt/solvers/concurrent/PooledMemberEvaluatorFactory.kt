package ksl.simopt.solvers.concurrent

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.SimulationProvider
import ksl.simopt.evaluator.StreamTapePolicy
import ksl.simopt.evaluator.silenceModelReporting
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The standard member-evaluator provisioner: each member gets its own sequential
 * `SimulationProvider` over a pooled, silenced model, with a private solution cache and a
 * stream tape starting at the member's block offset.
 *
 * Model pooling mirrors `ParallelSimulationProvider`: models are borrowed from a
 * concurrent queue and built fresh only when the pool is empty, so the number of builds
 * settles at the worker count rather than the member count. Because every simulation run
 * positions its streams absolutely (reset plus advance, driven by the member's tape
 * policy) and run parameters are restored after each request, a reused model behaves
 * identically to a fresh one. A member that fails mid-run has its model discarded rather
 * than returned to the pool.
 *
 * Stream independence across members (and reproducibility regardless of scheduling)
 * comes from deterministic block partitioning of the sub-stream tape: member k's tape
 * starts at k times [substreamBlockSize]. On release the factory checks the tape's final
 * position and logs a warning if the member consumed more than its block — that would
 * mean overlap with the next member's block and calls for a larger block size.
 *
 * The supplied model builder MUST return an independent, freshly built model on each
 * call (the same contract as `ParallelSimulationProvider`).
 *
 * @param problemDefinition the problem the member evaluators serve; validated for
 * input/response compatibility against the first built model
 * @param modelBuilder builds a fresh model per call; must yield independent instances
 * @param modelConfiguration opaque configuration forwarded to the model builder
 * @param baseRunParameters run parameters applied when building models; when null the
 * builder's defaults apply
 * @param substreamBlockSize the per-member sub-stream block; see [ConcurrentRunOptions]
 * @param solutionCacheFactory creates each member's private solution cache; return null
 * for no caching. The default creates a fresh in-memory cache per member, which makes
 * "clear the cache between runs" structural: each member's cache is born empty and dies
 * with the member.
 */
class PooledMemberEvaluatorFactory(
    private val problemDefinition: ProblemDefinition,
    private val modelBuilder: ModelBuilderIfc,
    private val modelConfiguration: Map<String, String>? = null,
    private val baseRunParameters: ExperimentRunParametersIfc? = null,
    private val substreamBlockSize: Int = ConcurrentRunOptions.DEFAULT_SUBSTREAM_BLOCK_SIZE,
    private val solutionCacheFactory: () -> SolutionCacheIfc? = { MemorySolutionCache() }
) : MemberEvaluatorFactoryIfc {

    init {
        require(substreamBlockSize > 0) { "substreamBlockSize must be > 0" }
    }

    private val myModelPool = ConcurrentLinkedQueue<Model>()
    private val myBorrowedModels = ConcurrentHashMap<Int, Model>()
    private val myTapePolicies = ConcurrentHashMap<Int, StreamTapePolicy>()
    private val myValidatedFirstModel = AtomicBoolean(false)

    override fun createEvaluator(memberIndex: Int): EvaluatorIfc {
        require(memberIndex >= 0) { "The member index must be non-negative" }
        val blockOffset = try {
            Math.multiplyExact(memberIndex, substreamBlockSize)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException(
                "Member $memberIndex with substreamBlockSize $substreamBlockSize overflows " +
                        "the sub-stream tape; reduce the block size or the member count.", e
            )
        }
        val model = myModelPool.poll() ?: buildFreshModel()
        myBorrowedModels[memberIndex] = model
        val tapePolicy = StreamTapePolicy(initialPosition = blockOffset)
        myTapePolicies[memberIndex] = tapePolicy
        val provider = SimulationProvider(model, null, tapePolicy)
        return Evaluator(problemDefinition, provider, solutionCacheFactory())
    }

    override fun release(memberIndex: Int, evaluator: EvaluatorIfc, reusable: Boolean) {
        val tapePolicy = myTapePolicies.remove(memberIndex)
        if (tapePolicy != null) {
            val consumed = tapePolicy.position - tapePolicy.initialPosition
            if (consumed > substreamBlockSize) {
                logger.warn {
                    "Member $memberIndex consumed $consumed sub-streams, exceeding its block " +
                            "of $substreamBlockSize: its streams overlap the next member's block. " +
                            "Increase substreamBlockSize for stream independence."
                }
            }
        }
        val model = myBorrowedModels.remove(memberIndex)
        if (model != null && reusable) {
            myModelPool.offer(model)
        }
    }

    private fun buildFreshModel(): Model {
        val model = modelBuilder.build(modelConfiguration, baseRunParameters)
        silenceModelReporting(model)
        if (myValidatedFirstModel.compareAndSet(false, true)) {
            require(problemDefinition.validateProblemDefinition(model)) {
                "The problem definition and the built model are not input/response compatible."
            }
        }
        return model
    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}
    }
}
