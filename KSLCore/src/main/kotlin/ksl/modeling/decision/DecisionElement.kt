package ksl.modeling.decision

import ksl.modeling.decision.descriptor.*
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.ResponseIfc
import ksl.sdm.capture.RunProvenance
import ksl.sdm.capture.TransitionSink
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc

/**
 *  The identity of a declared lever. A lever is (target, limits, domain, write), so its
 *  identity is its own — not that of the element it writes, which may back several levers.
 *  Pure data: holding one confers no access to anything.
 */
data class LeverRef internal constructor(val declaredName: String)

/**
 *  The identity of a declared reward term. A term is (source, kind, rate, sense), so its
 *  identity is its own — one source may back several terms at different rates or senses.
 */
data class RewardRef internal constructor(val declaredName: String)

/** STUB — Appendix E.2, §4.10.1. */
class DecisionElement internal constructor(
    parent: ModelElement,
    name: String
) : ModelElement(parent, name) {

    // ---- Structure -------------------------------------------------------------
    lateinit var catalog: DecisionCatalog
        internal set

    fun descriptor(): DecisionSurfaceDescriptor = TODO("stub")

    // ---- Parameterization: replication-initial (§4.1.3) -------------------------
    var policy: PolicyIfc
        get() = TODO("stub")
        set(@Suppress("UNUSED_PARAMETER") value) { requireNotRunning("policy") }

    /** Named `policyFrom`, not an overload of `policy`: PolicyIfc is a fun interface,
     *  so `policy { … }` would be ambiguous with assigning the property. */
    fun policyFrom(factory: (PolicyCreationContext) -> PolicyIfc) { requireNotRunning("policy") }

    var epochInterval: Double
        get() = TODO("stub")
        set(@Suppress("UNUSED_PARAMETER") value) { requireNotRunning("epochInterval") }

    var feasibilityPolicy: FeasibilityPolicy
        get() = TODO("stub")
        set(@Suppress("UNUSED_PARAMETER") value) { requireNotRunning("feasibilityPolicy") }

    var maxEpochs: Int
        get() = TODO("stub")
        set(@Suppress("UNUSED_PARAMETER") value) { requireNotRunning("maxEpochs") }

    /** Resolve a declared lever by name. Throws BindingException if there is no such lever. */
    fun leverRef(declaredName: String): LeverRef = TODO("stub")

    fun narrow(lever: LeverRef, limits: IntRange) { requireNotRunning("narrow") }
    fun narrow(lever: LeverRef, limits: ClosedFloatingPointRange<Double>) { requireNotRunning("narrow") }
    fun limitsOf(lever: LeverRef): IntRange = TODO("stub")
    fun boundsOf(lever: LeverRef): ClosedFloatingPointRange<Double> = TODO("stub")

    fun rewardRef(declaredName: String): RewardRef = TODO("stub")
    fun rewardRate(term: RewardRef, rate: Double) { requireNotRunning("rewardRate") }

    /** Labels this rule in trajectories and reports. Defaults to the policy's class name. */
    var policyLabel: String
        get() = TODO("stub")
        set(@Suppress("UNUSED_PARAMETER") value) { requireNotRunning("policyLabel") }

    // ---- Observation ------------------------------------------------------------
    val estimand: ResponseCIfc get() = TODO("stub")
    val epochCount: Int get() = TODO("stub")
    val lastTermination: TerminationSource? get() = null

    private fun requireNotRunning(what: String) {
        check(model.isNotRunning) {
            "Attempted to set '$what' on ${this.name} while the simulation was running. " +
                "Decision parameters are replication-initial."
        }
    }

    // ---- Lifecycle (§4.10.3) ----------------------------------------------------
    override fun initialize() {}
    override fun warmUp() {}
    override fun replicationEnded() {}
    override fun afterExperiment() {}
}

@DslMarker
annotation class KSLDecisionDsl

/** Entry point, shaped like ModelElement.queueingNetwork (§4.1.2.1). */
fun ModelElement.decisionElement(
    name: String,
    block: DecisionElementBuilder.() -> Unit
): DecisionElement {
    val element = DecisionElement(this, name)
    val builder = DecisionElementBuilder(element)
    builder.block()
    return builder.build()
}

@KSLDecisionDsl
class DecisionElementBuilder internal constructor(
    private val element: DecisionElement
) {
    fun observe(source: ResponseIfc) {}
    fun observe(source: ResponseIfc, alias: String) {}
    fun observe(name: String, source: GetValueIfc) {}

    // Each returns the declared lever's identity, for use by budget/atMost and by
    // DecisionElement.narrow. Generic in the owner so the setter receiver resolves.
    fun <T : ModelElement> lever(
        owner: T, limits: IntRange, alias: String? = null, set: T.(Double) -> Unit
    ): LeverRef = LeverRef(alias ?: owner.name)

    fun <T : ModelElement> lever(
        owner: T, limits: ClosedFloatingPointRange<Double>, alias: String? = null, set: T.(Double) -> Unit
    ): LeverRef = LeverRef(alias ?: owner.name)

    fun <T : ModelElement> lever(
        owner: T, levels: List<String>, alias: String? = null, set: T.(Double) -> Unit
    ): LeverRef = LeverRef(alias ?: owner.name)

    fun batchLever(vararg levers: LeverRef, applyAll: (DoubleArray) -> Unit) {}

    fun budget(vararg levers: LeverRef, total: Double) {}
    fun atMost(vararg levers: LeverRef, total: Double) {}

    fun reward(
        source: ResponseIfc, rate: Double,
        sense: RewardSense = RewardSense.COST, alias: String? = null
    ): RewardRef = RewardRef(alias ?: source.name)

    fun every(interval: Double, firstAtTimeZero: Boolean = false) {}
    fun onCalendar(times: List<Double>) {}
    var epochPriority: Int = KSLEvent.MEDIUM_LOW_PRIORITY

    fun maxEpochs(n: Int) {}
    fun terminalWhen(condition: () -> Boolean) {}
    var feasibility: FeasibilityPolicy = FeasibilityPolicy.REJECT

    var policy: PolicyIfc? = null
    fun policyFrom(factory: (PolicyCreationContext) -> PolicyIfc) {}
    fun captureTo(factory: (RunProvenance) -> TransitionSink) {}

    internal fun build(): DecisionElement {
        require(policy != null) { "A decision element requires a policy." }
        return element
    }
}
