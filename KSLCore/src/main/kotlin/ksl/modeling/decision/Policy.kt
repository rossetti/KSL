package ksl.modeling.decision

/** STUB — Appendix E.2, §4.5. */

fun interface PolicyIfc {
    fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray
}

/** Build-time. Handed to a policy factory once, at element construction. Pure data. */
interface PolicyCreationContext {
    val elementName: String
    val modelName: String
    val observationNames: List<String>
    val leverNames: List<String>
    val leverBounds: List<ClosedFloatingPointRange<Double>>
    fun budgetTotal(): Double?
}

/** Live. Valid ONLY during the action() call that receives it. Never retained. */
interface DecisionContext {
    val simulationTime: Double
    val intervalSinceLastEpoch: Double
    val remainingRunLength: Double
    val epochIndex: Int
    val replicationId: Int
    /** Each lever's value right now, in declaration order (§4.10.2 step 6). */
    val currentAction: DoubleArray
}

interface ManagedPolicyIfc : PolicyIfc, AutoCloseable {
    fun beforeEpisode(episodeIndex: Int) {}
    fun afterEpisode(episodeIndex: Int, source: ksl.modeling.decision.descriptor.TerminationSource) {}
    override fun close() {}
}

/** Change nothing. The Level-1 compatibility baseline (§6). */
object HoldCurrentPolicy : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray = ctx.currentAction
}

/** A constant action. Arity is checked against the declaration at element construction. */
class FixedPolicy(private val values: DoubleArray) : PolicyIfc {
    val arity: Int get() = values.size
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray = values.copyOf()
}
