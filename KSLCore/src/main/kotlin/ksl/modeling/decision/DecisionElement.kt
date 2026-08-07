package ksl.modeling.decision

import ksl.modeling.decision.descriptor.*
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.ResponseIfc
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

/**
 *  A declared observation: a name and something to read it from.
 */
internal class ObservationDecl(
    val name: String,
    val source: GetValueIfc
)

/**
 *  A declared lever. [read] is optional because KSL has no uniform settable interface, so
 *  the block form of the DSL can always supply a write and cannot always supply a read.
 *  Whether it was supplied is what [LeverInfo.supportsCurrentValue] reports.
 */
internal class LeverDecl(
    val name: String,
    val owner: ModelElement,
    val domain: LeverDomain,
    val modelLowerLimit: Double,
    val modelUpperLimit: Double,
    val levels: List<String>?,
    val write: (Double) -> Unit,
    val read: (() -> Double)?,
    /** 𝒳(s) for this lever, evaluated at every epoch. Null means "the envelope is the set". */
    val boundsFn: (() -> ClosedFloatingPointRange<Double>)? = null
) {
    var lowerBound: Double = modelLowerLimit
    var upperBound: Double = modelUpperLimit

    val stateDependent: Boolean get() = boundsFn != null

    /**
     *  §4.3.3: envelope ∩ narrowed ∩ 𝒳(s). The first two are [modelLowerLimit]..[modelUpperLimit]
     *  and [lowerBound]..[upperBound]; the third comes from [boundsFn] if declared.
     */
    fun feasibleRange(): ClosedFloatingPointRange<Double> {
        val f = boundsFn ?: return lowerBound..upperBound
        val x = f()
        // INTERSECTION, not containment. "What this region is owed" may legitimately exceed
        // "what one truck holds"; the effective set is simply the smaller of the two, and an
        // empty intersection is the empty-set case of §4.4.6.3 rather than an error. An
        // earlier version required 𝒳(s) to lie inside the envelope and rejected the first
        // natural declaration written against it.
        return maxOf(lowerBound, x.start)..minOf(upperBound, x.endInclusive)
    }

    fun info(): LeverInfo = LeverInfo(
        name = name,
        domain = domain,
        modelLowerLimit = modelLowerLimit,
        modelUpperLimit = modelUpperLimit,
        supportsCurrentValue = read != null,
        levels = levels
    )
}

/**
 *  §4.10.1 and §4.10.2. Declared through [decisionElement]; never constructed directly.
 */
class DecisionElement internal constructor(
    parent: ModelElement,
    name: String
) : ModelElement(parent, name) {

    // ---- Declared structure, fixed at construction ------------------------------
    internal val observationDecls = mutableListOf<ObservationDecl>()
    internal val leverDecls = mutableListOf<LeverDecl>()
    internal val jointConstraints = mutableListOf<JointConstraint>()

    /**
     *  The joint constraints as the element evaluates them. [totalFn] is re-read at every
     *  epoch, so a budget may itself be a state (§4.4.6.1). [jointConstraints] keeps the
     *  declared, serializable form for the descriptor.
     */
    internal class JointDecl(
        val equality: Boolean,
        val names: List<String>,
        val totalFn: () -> Double,
        val stateDependent: Boolean
    )
    internal val jointDecls = mutableListOf<JointDecl>()

    lateinit var catalog: DecisionCatalog
        internal set

    internal fun buildCatalog() {
        catalog = DecisionCatalog(
            owner = this,
            observations = observationDecls.associate { it.name to it.source },
            actuators = leverDecls.associate { d -> d.name to declToActuator(d) },
            leverInfos = leverDecls.associate { it.name to it.info() },
            rewardSources = emptyMap(),
            observationNames = observationDecls.map { it.name },
            leverNames = leverDecls.map { it.name }
        )
    }

    private fun declToActuator(decl: LeverDecl): LeverActuator {
        val r = decl.read
        return if (r == null) {
            object : LeverActuator {
                override val domain: LeverDomain get() = decl.domain
                override val lowerBound: Double get() = decl.lowerBound
                override val upperBound: Double get() = decl.upperBound
                override fun apply(value: Double) = decl.write(value)
            }
        } else {
            object : StatefulLeverActuator {
                override val domain: LeverDomain get() = decl.domain
                override val lowerBound: Double get() = decl.lowerBound
                override val upperBound: Double get() = decl.upperBound
                override fun apply(value: Double) = decl.write(value)
                override fun currentValue(): Double = r()
            }
        }
    }

    fun descriptor(): DecisionSurfaceDescriptor = DecisionSurfaceDescriptor(
        name = this.name,
        observations = observationDecls.map { ObservationDescriptor(it.name) },
        levers = leverDecls.map {
            LeverDescriptor(
                name = it.name,
                domain = it.domain,
                modelLowerLimit = it.modelLowerLimit,
                modelUpperLimit = it.modelUpperLimit,
                lowerBound = it.lowerBound,
                upperBound = it.upperBound,
                stateDependent = it.stateDependent,
                levels = it.levels
            )
        },
        constraints = jointConstraints.toList(),
        rewards = emptyList(),
        epochs = EpochDescriptor(
            kind = epochKind,
            interval = if (epochKind == EpochKind.PERIODIC) myEpochInterval else null,
            calendar = if (epochKind == EpochKind.CALENDAR) calendar.toList() else null,
            firstAtTimeZero = firstAtTimeZero,
            priority = epochPriority
        ),
        episode = EpisodeDescriptor(maxEpochs = myMaxEpochs, hasTerminalCondition = terminalCondition != null),
        feasibility = myFeasibilityPolicy
    )

    // ---- Parameterization: replication-initial (§4.1.3) -------------------------
    private var myPolicy: PolicyIfc = HoldCurrentPolicy

    /**
     *  The rule. Replication-initial: the setter throws while the model is running.
     *  Assigning a [ShapeAwarePolicyIfc] calls its configure(descriptor()) immediately,
     *  so a rule that requires something of the shape, or must build something from it,
     *  fails or does its work here rather than at the first epoch.
     */
    var policy: PolicyIfc
        get() = myPolicy
        set(value) {
            requireNotRunning("policy")
            (myPolicy as? ManagedPolicyIfc)?.close()
            myPolicy = value
            if (value is ShapeAwarePolicyIfc) value.configure(descriptor())
        }

    internal var myEpochInterval: Double = Double.POSITIVE_INFINITY
    var epochInterval: Double
        get() = myEpochInterval
        set(value) {
            requireNotRunning("epochInterval")
            require(value > 0.0) { "The epoch interval must be > 0.0" }
            myEpochInterval = value
        }

    internal var epochKind: EpochKind = EpochKind.PERIODIC
    internal var firstAtTimeZero: Boolean = false
    internal val calendar = mutableListOf<Double>()
    internal var epochPriority: Int = KSLEvent.MEDIUM_LOW_PRIORITY
    internal var terminalCondition: (() -> Boolean)? = null

    private var myFeasibilityPolicy: FeasibilityPolicy = FeasibilityPolicy.REJECT
    var feasibilityPolicy: FeasibilityPolicy
        get() = myFeasibilityPolicy
        set(value) { requireNotRunning("feasibilityPolicy"); myFeasibilityPolicy = value }

    internal var myMaxEpochs: Int = Int.MAX_VALUE
    var maxEpochs: Int
        get() = myMaxEpochs
        set(value) { requireNotRunning("maxEpochs"); myMaxEpochs = value }

    /**
     *  Resolve the lever declared over [owner]. The owner is a lookup KEY here, not the
     *  lever's identity (§4.1.2.2): it must resolve to exactly one lever, and throws
     *  AmbiguousLeverException if it backs several.
     */
    fun leverFor(owner: ModelElement): LeverRef {
        val hits = leverDecls.filter { it.owner === owner }
        if (hits.isEmpty()) {
            throw BindingException(owner.name, leverDecls.map { it.name })
        }
        if (hits.size > 1) {
            throw AmbiguousLeverException(owner.name, hits.map { it.name })
        }
        return LeverRef(hits.single().name)
    }

    /** Resolve a declared lever by name. */
    fun leverRef(declaredName: String): LeverRef {
        leverDecls.firstOrNull { it.name == declaredName }
            ?: throw BindingException(declaredName, leverDecls.map { it.name })
        return LeverRef(declaredName)
    }

    private fun declOf(lever: LeverRef): LeverDecl =
        leverDecls.firstOrNull { it.name == lever.declaredName }
            ?: throw BindingException(lever.declaredName, leverDecls.map { it.name })

    fun narrow(lever: LeverRef, limits: IntRange) {
        requireNotRunning("narrow")
        narrow(lever, limits.first.toDouble()..limits.last.toDouble())
    }

    fun narrow(lever: LeverRef, limits: ClosedFloatingPointRange<Double>) {
        requireNotRunning("narrow")
        val d = declOf(lever)
        if (limits.start < d.modelLowerLimit || limits.endInclusive > d.modelUpperLimit) {
            throw NarrowingException(
                "Cannot narrow '${d.name}' to [${limits.start}, ${limits.endInclusive}]: the model " +
                    "declares [${d.modelLowerLimit}, ${d.modelUpperLimit}]. Narrowing may only shrink."
            )
        }
        d.lowerBound = limits.start
        d.upperBound = limits.endInclusive
    }

    fun limitsOf(lever: LeverRef): IntRange {
        val d = declOf(lever)
        return d.lowerBound.toInt()..d.upperBound.toInt()
    }

    fun boundsOf(lever: LeverRef): ClosedFloatingPointRange<Double> {
        val d = declOf(lever)
        return d.lowerBound..d.upperBound
    }

    fun rewardFor(source: ResponseIfc): RewardRef = TODO("not in the vertical slice")
    fun rewardRef(declaredName: String): RewardRef = TODO("not in the vertical slice")
    fun rewardRate(term: RewardRef, rate: Double) { requireNotRunning("rewardRate") }

    private var myPolicyLabel: String? = null
    /** Labels this rule in trajectories and reports. Defaults to the policy's class name. */
    var policyLabel: String
        get() = myPolicyLabel ?: (myPolicy::class.simpleName ?: "policy")
        set(value) { requireNotRunning("policyLabel"); myPolicyLabel = value }

    // ---- Observation ------------------------------------------------------------
    val estimand: ResponseCIfc get() = TODO("not in the vertical slice")

    private var myEpochCount: Int = 0
    val epochCount: Int get() = myEpochCount

    private var myLastTermination: TerminationSource? = null
    val lastTermination: TerminationSource? get() = myLastTermination

    private fun requireNotRunning(what: String) {
        check(model.isNotRunning) {
            "Attempted to set '$what' on ${this.name} while the simulation was running. " +
                "Decision parameters are replication-initial."
        }
    }

    // ---- Runtime ----------------------------------------------------------------
    internal lateinit var binding: DefaultActionBinding
    private lateinit var ctx: MutableDecisionContext
    private var lastEpochTime: Double = 0.0
    private var calendarIndex: Int = 0

    internal fun bind() {
        binding = DefaultActionBinding(this)
        ctx = MutableDecisionContext(this)
    }

    private inner class EpochAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) = runEpoch()
    }

    private val epochAction = EpochAction()

    private fun readObservations(): DoubleArray =
        DoubleArray(observationDecls.size) { observationDecls[it].source.value }

    private fun runEpoch() {
        // Step 1 — observe.
        val s = readObservations()

        // Steps 2-4 (rewards, transition emission) are outside the vertical slice.

        // Step 3 — classify the ending.
        val terminal = terminalCondition?.invoke() == true
        if (terminal) {
            myLastTermination = TerminationSource.NATURAL
            return
        }
        if (myEpochCount >= myMaxEpochs) {
            myLastTermination = TerminationSource.MAX_EPOCHS
            return
        }

        // Step 6 — decide and act.
        ctx.update(time, time - lastEpochTime, myEpochCount)
        val action = try {
            myPolicy.action(s, ctx)
        } catch (e: Throwable) {
            myLastTermination = TerminationSource.POLICY_ERROR
            throw e
        }
        when (val prepared = binding.prepare(action)) {
            is PreparedAction.Ready -> binding.apply(prepared.plan)
            is PreparedAction.Invalid -> {
                if (myFeasibilityPolicy == FeasibilityPolicy.CLAMP_THEN_REJECT) {
                    val clamped = binding.clamp(action)
                    when (val second = binding.prepare(clamped)) {
                        is PreparedAction.Ready -> binding.apply(second.plan)
                        is PreparedAction.Invalid -> throw ActionValidationException(second.violations)
                    }
                } else {
                    throw ActionValidationException(prepared.violations)
                }
            }
        }

        // Step 7 — carry forward and schedule.
        myEpochCount++
        lastEpochTime = time
        scheduleNextEpoch()
    }

    private fun scheduleNextEpoch() {
        when (epochKind) {
            EpochKind.PERIODIC -> {
                if (myEpochInterval.isFinite()) {
                    epochAction.schedule(myEpochInterval, priority = epochPriority)
                }
            }
            EpochKind.CALENDAR -> {
                if (calendarIndex < calendar.size) {
                    val next = calendar[calendarIndex++]
                    val dt = next - time
                    if (dt >= 0.0) epochAction.schedule(dt, priority = epochPriority)
                }
            }
        }
    }

    // ---- Lifecycle (§4.10.3) ----------------------------------------------------
    override fun initialize() {
        myEpochCount = 0
        myLastTermination = null
        lastEpochTime = 0.0
        calendarIndex = 0
        when (epochKind) {
            EpochKind.PERIODIC -> {
                if (myEpochInterval.isFinite()) {
                    val first = if (firstAtTimeZero) 0.0 else myEpochInterval
                    epochAction.schedule(first, priority = epochPriority)
                }
            }
            EpochKind.CALENDAR -> scheduleNextEpoch()
        }
    }

    override fun warmUp() {
        // The pending transition and reward baseline are outside the vertical slice.
        // The epoch at this instant, if any, has already run: MEDIUM_LOW_PRIORITY (100 000)
        // sorts ahead of DEFAULT_WARMUP_EVENT_PRIORITY (1 000 000).
    }

    override fun replicationEnded() {}

    override fun afterExperiment() {
        (myPolicy as? ManagedPolicyIfc)?.close()
    }
}

/**
 *  §4.4.2. Checks box bounds and joint constraints, then plans the writes.
 */
internal class DefaultActionBinding(private val element: DecisionElement) : ActionBinding {

    private val decls: List<LeverDecl> get() = element.leverDecls

    fun clamp(action: DoubleArray): DoubleArray =
        DoubleArray(action.size) { i ->
            val r = decls[i].feasibleRange()
            if (r.isEmpty()) Double.NaN else action[i].coerceIn(r.start, r.endInclusive)
        }

    override fun prepare(action: DoubleArray): PreparedAction {
        val violations = mutableListOf<String>()
        if (action.size != decls.size) {
            return PreparedAction.Invalid(
                listOf("The policy returned ${action.size} values; ${decls.size} levers are declared.")
            )
        }
        for ((i, d) in decls.withIndex()) {
            val v = action[i]
            // §4.3.3: envelope ∩ narrowed ∩ 𝒳(s), re-evaluated at every epoch.
            val range = d.feasibleRange()
            if (v.isNaN()) {
                violations += "'${d.name}' received NaN."
            } else if (range.isEmpty()) {
                violations += "'${d.name}' has an empty feasible set at this epoch: " +
                    "[${range.start}, ${range.endInclusive}]. No value is available (§4.4.6.3)."
            } else if (v < range.start || v > range.endInclusive) {
                val why = if (d.stateDependent) " (the state-dependent set, inside the envelope " +
                    "[${d.modelLowerLimit}, ${d.modelUpperLimit}])" else ""
                violations += "'${d.name}' = $v is outside [${range.start}, ${range.endInclusive}]$why."
            } else if (d.domain == LeverDomain.INTEGER && v != Math.rint(v)) {
                violations += "'${d.name}' = $v is not integral, but the lever's domain is INTEGER."
            }
        }
        val index = decls.withIndex().associate { (i, d) -> d.name to i }
        for (c in element.jointDecls) {
            val sum = c.names.sumOf { n ->
                val i = index[n] ?: return PreparedAction.Invalid(listOf("Constraint names unknown lever '$n'."))
                action[i]
            }
            val total = c.totalFn()
            val what = if (c.stateDependent) "the state-dependent total" else "the declaration"
            if (c.equality) {
                if (Math.abs(sum - total) > 1e-9) {
                    violations += "Sum of ${c.names} is $sum; $what requires exactly $total."
                }
            } else {
                if (sum > total + 1e-9) {
                    violations += "Sum of ${c.names} is $sum; $what allows at most $total."
                }
            }
        }
        if (violations.isNotEmpty()) return PreparedAction.Invalid(violations)

        // Plan. A step whose target equals its source is ELIDED, not written: writing a
        // value back is not a no-op in KSL (TWResponse.assignValue collects an observation
        // and notifies observers regardless of whether the value changed).
        val steps = mutableListOf<ActionPlan.Step>()
        for ((i, d) in decls.withIndex()) {
            val from = element.catalog.actuator(d.name).let { a ->
                if (a is StatefulLeverActuator) a.currentValue() else Double.NaN
            }
            val to = action[i]
            if (!from.isNaN() && from == to) continue
            steps += ActionPlan.Step(d.name, from, to, element.catalog.actuator(d.name)!!)
        }
        // Decreases before increases (§4.4): frees capacity before committing it.
        steps.sortBy { if (it.from.isNaN()) 0.0 else it.to - it.from }
        return PreparedAction.Ready(ActionPlan(steps))
    }

    override fun apply(plan: ActionPlan) {
        for (step in plan.steps) {
            try {
                step.actuator.apply(step.to)
            } catch (e: Throwable) {
                throw ActionApplicationException(
                    "Applying '${step.name}' = ${step.to} failed after ${plan.steps.indexOf(step)} " +
                        "of ${plan.steps.size} writes had been made. The model is partially mutated.",
                    e
                )
            }
        }
    }
}

/**
 *  §4.5.3. One instance per element, reused; the epoch-scoped fields are updated in place.
 */
internal class MutableDecisionContext(private val element: DecisionElement) : DecisionContext {

    override var simulationTime: Double = 0.0
        private set
    override var intervalSinceLastEpoch: Double = 0.0
        private set
    override var epochIndex: Int = 0
        private set

    override val remainingRunLength: Double
        get() = element.model.lengthOfReplication - simulationTime
    override val replicationId: Int
        get() = element.model.currentReplicationId

    override val elementName: String = element.name
    override val modelName: String = element.model.name
    override val observationNames: List<String> = element.observationDecls.map { it.name }
    override val leverNames: List<String> = element.leverDecls.map { it.name }

    override val leverBounds: List<ClosedFloatingPointRange<Double>>
        get() = element.leverDecls.map { it.lowerBound..it.upperBound }

    override val constraints: List<JointConstraint> = element.jointConstraints.toList()

    /**
     *  The total governing this lever **as it stands now**. Once a budget can itself be a
     *  state (§4.4.6.1) the declared total and the current total are different numbers, and
     *  a policy allocating within the budget needs the current one — the declared envelope
     *  is still available through [constraints]. Returning the declared value here would
     *  hand every allocating rule an upper bound and call it the budget.
     */
    override fun budgetTotal(leverIndex: Int): Double? {
        val name = leverNames[leverIndex]
        val d = element.jointDecls.firstOrNull { it.names.contains(name) } ?: return null
        return d.totalFn()
    }

    // ---- The feasible set 𝒳(s) as an object, §4.4.6.5.
    override val actions: ActionSet = ElementActionSet(element)

    override val currentAction: DoubleArray
        get() = DoubleArray(element.leverDecls.size) { i ->
            val a = element.catalog.actuator(element.leverDecls[i].name)
            if (a is StatefulLeverActuator) a.currentValue() else Double.NaN
        }

    fun update(now: Double, sinceLast: Double, index: Int) {
        simulationTime = now
        intervalSinceLastEpoch = sinceLast
        epochIndex = index
    }
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
    fun observe(source: ResponseIfc) = observe(source, source.name)

    fun observe(source: ResponseIfc, alias: String) {
        // ResponseIfc carries ValueIfc, NOT GetValueIfc: KSL has two interfaces declaring
        // `val value: Double` and they are unrelated. The catalog is typed on GetValueIfc,
        // so a response must be adapted. See §8.1.
        element.observationDecls += ObservationDecl(alias, GetValueIfc { source.value })
    }

    fun observe(name: String, source: GetValueIfc) {
        element.observationDecls += ObservationDecl(name, source)
    }

    // Each returns the declared lever's identity, for use by budget/atMost and by
    // DecisionElement.narrow. Generic in the owner so the setter receiver resolves.
    //
    // [read] is optional and supplies the lever's CURRENT value. Without it the element
    // cannot answer DecisionContext.currentAction for this lever, and cannot tell whether
    // a write would change anything. See §8.1.
    fun <T : ModelElement> lever(
        owner: T,
        limits: IntRange,
        alias: String? = null,
        bounds: (T.() -> ClosedFloatingPointRange<Double>)? = null,
        read: (T.() -> Double)? = null,
        set: T.(Double) -> Unit
    ): LeverRef = declare(
        owner, LeverDomain.INTEGER, limits.first.toDouble(), limits.last.toDouble(), null, alias,
        bounds, read, set
    )

    fun <T : ModelElement> lever(
        owner: T,
        limits: ClosedFloatingPointRange<Double>,
        alias: String? = null,
        bounds: (T.() -> ClosedFloatingPointRange<Double>)? = null,
        read: (T.() -> Double)? = null,
        set: T.(Double) -> Unit
    ): LeverRef = declare(
        owner, LeverDomain.CONTINUOUS, limits.start, limits.endInclusive, null, alias, bounds, read, set
    )

    fun <T : ModelElement> lever(
        owner: T,
        levels: List<String>,
        alias: String? = null,
        bounds: (T.() -> ClosedFloatingPointRange<Double>)? = null,
        read: (T.() -> Double)? = null,
        set: T.(Double) -> Unit
    ): LeverRef = declare(
        owner, LeverDomain.CATEGORICAL, 0.0, (levels.size - 1).toDouble(), levels, alias,
        bounds, read, set
    )

    private fun <T : ModelElement> declare(
        owner: T,
        domain: LeverDomain,
        lower: Double,
        upper: Double,
        levels: List<String>?,
        alias: String?,
        bounds: (T.() -> ClosedFloatingPointRange<Double>)?,
        read: (T.() -> Double)?,
        set: T.(Double) -> Unit
    ): LeverRef {
        require(lower <= upper) { "Lever limits for '${alias ?: owner.name}' are unordered: [$lower, $upper]" }
        val name = alias ?: owner.name
        require(element.leverDecls.none { it.name == name }) { "Lever '$name' is declared twice." }
        element.leverDecls += LeverDecl(
            name = name,
            owner = owner,
            domain = domain,
            modelLowerLimit = lower,
            modelUpperLimit = upper,
            levels = levels,
            write = { v -> owner.set(v) },
            read = if (read == null) null else ({ owner.read() }),
            boundsFn = if (bounds == null) null else ({ owner.bounds() })
        )
        return LeverRef(name)
    }

    fun batchLever(vararg levers: LeverRef, applyAll: (DoubleArray) -> Unit) {
        TODO("not in the vertical slice")
    }

    fun budget(vararg levers: LeverRef, total: Double) {
        val names = levers.map { it.declaredName }
        element.jointConstraints += SumEquals(names, total)
        element.jointDecls += DecisionElement.JointDecl(true, names, { total }, false)
    }

    fun atMost(vararg levers: LeverRef, total: Double) {
        val names = levers.map { it.declaredName }
        element.jointConstraints += SumAtMost(names, total)
        element.jointDecls += DecisionElement.JointDecl(false, names, { total }, false)
    }

    /**
     *  A budget that is itself a state (§4.4.6.1) — "ship no more than is on hand". The
     *  descriptor records [envelope] as the declared total and flags the constraint
     *  state-dependent, because a serialized descriptor cannot carry a lambda.
     */
    fun budget(vararg levers: LeverRef, envelope: Double, total: () -> Double) {
        val names = levers.map { it.declaredName }
        element.jointConstraints += SumEquals(names, envelope)
        element.jointDecls += DecisionElement.JointDecl(true, names, total, true)
    }

    fun atMost(vararg levers: LeverRef, envelope: Double, total: () -> Double) {
        val names = levers.map { it.declaredName }
        element.jointConstraints += SumAtMost(names, envelope)
        element.jointDecls += DecisionElement.JointDecl(false, names, total, true)
    }

    fun reward(
        source: ResponseIfc, rate: Double,
        sense: RewardSense = RewardSense.COST, alias: String? = null
    ): RewardRef = RewardRef(alias ?: source.name)

    fun every(interval: Double, firstAtTimeZero: Boolean = false) {
        element.epochKind = EpochKind.PERIODIC
        element.myEpochInterval = interval
        element.firstAtTimeZero = firstAtTimeZero
    }

    fun onCalendar(times: List<Double>) {
        element.epochKind = EpochKind.CALENDAR
        element.calendar.clear()
        element.calendar += times.sorted()
    }

    var epochPriority: Int
        get() = element.epochPriority
        set(value) { element.epochPriority = value }

    fun maxEpochs(n: Int) { element.myMaxEpochs = n }
    fun terminalWhen(condition: () -> Boolean) { element.terminalCondition = condition }

    var feasibility: FeasibilityPolicy
        get() = element.feasibilityPolicy
        set(value) { element.feasibilityPolicy = value }

    var policy: PolicyIfc? = null

    fun captureTo(factory: (RunProvenance) -> TransitionSink) {
        TODO("not in the vertical slice")
    }

    internal fun build(): DecisionElement {
        require(policy != null) { "A decision element requires a policy." }
        require(element.observationDecls.isNotEmpty()) { "A decision element requires at least one observation." }
        require(element.leverDecls.isNotEmpty()) { "A decision element requires at least one lever." }
        val declared = element.leverDecls.map { it.name }.toSet()
        for (c in element.jointConstraints) {
            for (n in c.names) {
                require(n in declared) { "Constraint names lever '$n', which is not declared. Declared: $declared" }
            }
        }
        element.buildCatalog()
        element.bind()
        element.policy = policy!!
        return element
    }
}
