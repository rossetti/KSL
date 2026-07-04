package ksl.simopt.benchmark

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.evaluator.StreamTapePolicy
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.concurrent.ConcurrentRunOptions
import ksl.simopt.solvers.concurrent.MemberEvaluatorFactoryIfc
import java.util.concurrent.ConcurrentHashMap

/**
 *  Provisions per-member evaluators over a response function — the synthetic and static
 *  Monte Carlo counterpart of `ksl.simopt.solvers.concurrent.PooledMemberEvaluatorFactory`.
 *  Each member gets its own `ResponseFunctionOracle` with a private solution cache and a
 *  stream tape starting at the member's block offset, so concurrently running members
 *  draw from non-overlapping regions of the sub-stream tape regardless of scheduling.
 *
 *  There is nothing expensive to pool (no built models), so every member simply receives
 *  fresh resources. On release the factory checks the member's tape consumption and logs
 *  a warning if the member exceeded its block — that would mean overlap with the next
 *  member's block and calls for a larger block size.
 *
 *  The response function is shared across members and invoked concurrently, so it must
 *  be safe for concurrent calls: pure apart from the supplied stream (which is private
 *  to each member), with no shared mutable state.
 *
 *  @param problemDefinition the problem the member evaluators serve; the oracle serves
 *  the problem's model identifier and its full response-name set
 *  @param responseFunction one replication of every response at a design point; shared
 *  by all members, so it must be pure apart from the supplied (member-private) stream
 *  @param substreamBlockSize the per-member sub-stream block; see `ConcurrentRunOptions`
 *  @param solutionCacheFactory creates each member's private solution cache; return null
 *  for no caching. The default creates a fresh in-memory cache per member.
 */
class FunctionMemberEvaluatorFactory(
    private val problemDefinition: ProblemDefinition,
    private val responseFunction: ResponseFunctionIfc,
    private val substreamBlockSize: Int = ConcurrentRunOptions.DEFAULT_SUBSTREAM_BLOCK_SIZE,
    private val solutionCacheFactory: () -> SolutionCacheIfc? = { MemorySolutionCache() }
) : MemberEvaluatorFactoryIfc {

    init {
        require(substreamBlockSize > 0) { "substreamBlockSize must be > 0" }
    }

    private val myTapePolicies = ConcurrentHashMap<Int, StreamTapePolicy>()

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
        val tapePolicy = StreamTapePolicy(initialPosition = blockOffset)
        myTapePolicies[memberIndex] = tapePolicy
        val oracle = ResponseFunctionOracle(
            modelIdentifier = problemDefinition.modelIdentifier,
            responseNames = problemDefinition.allResponseNames.toSet(),
            responseFunction = responseFunction,
            streamTapePolicy = tapePolicy
        )
        return Evaluator(problemDefinition, oracle, solutionCacheFactory())
    }

    override fun release(memberIndex: Int, evaluator: EvaluatorIfc, reusable: Boolean) {
        val tapePolicy = myTapePolicies.remove(memberIndex) ?: return
        val consumed = tapePolicy.position - tapePolicy.initialPosition
        if (consumed > substreamBlockSize) {
            logger.warn {
                "Member $memberIndex consumed $consumed sub-streams, exceeding its block " +
                        "of $substreamBlockSize: its streams overlap the next member's block. " +
                        "Increase substreamBlockSize for stream independence."
            }
        }
    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}
    }
}
