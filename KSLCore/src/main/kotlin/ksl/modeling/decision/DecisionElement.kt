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
    val source: GetValueIfc,
    /**
     *  What this reading is measured in — "jobs", "server-units", "$/hour". Optional, and
     *  optional deliberately (§4.2.4, G.9 row 7): requiring it would be ceremony on every
     *  declaration, and the library cannot verify it against anything. What it can do is
     *  carry it where a rule and an error message can reach it.
     */
    val unit: String? = null
)

/**
 *  A declared lever.
 *
 *  [read] is non-null exactly when [kind] is [LeverKind.SETTING], and it is not a separate
 *  optional argument any more: it is the content of `Neutral.Current` (§8.2.3). A reader used
 *  to be optional because KSL has no uniform settable interface, so the block form of the DSL
 *  can always supply a write and cannot always supply a read — true, but it made "no reader"
 *  mean two different things, which is what §8.2.2 measured going wrong.
 *
 *  [neutralValue] is what this lever does when a rule declines to act on it. For a setting it
 *  reads the current value; for a transaction it returns the declared constant.
 */
internal class LeverDecl(
    val name: String,
    val owner: ModelElement,
    val domain: LeverDomain,
    val kind: LeverKind,
    val modelLowerLimit: Double,
    val modelUpperLimit: Double,
    val levels: List<String>?,
    val write: (Double) -> Unit,
    val read: (() -> Double)?,
    val neutralValue: () -> Double,
    /** 𝒳(s) for this lever, evaluated at every epoch. Null means "the envelope is the set". */
    val boundsFn: (() -> ClosedFloatingPointRange<Double>)? = null,
    /** What this lever is measured in. Optional; see [ObservationDecl.unit] (§4.2.4). */
    val unit: String? = null
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
        kind = kind,
        modelLowerLimit = modelLowerLimit,
        modelUpperLimit = modelUpperLimit,
        supportsCurrentValue = read != null,
        levels = levels,
        unit = unit
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
    ) {
        /**
         *  How the constraint reads back to whoever declared it. Used in the rejection
         *  message when two constraints claim one lever, so the modeler sees both.
         *  A state-dependent total is not evaluated here — at construction there is no
         *  state to evaluate it against.
         */
        fun describe(): String {
            val rel = if (equality) "==" else "<="
            val rhs = if (stateDependent) "a state-dependent total" else totalFn().toString()
            return "sum(${names.joinToString(", ")}) $rel $rhs"
        }
    }
    internal val jointDecls = mutableListOf<JointDecl>()

    /**
     *  Constraints where some but not all levers declared a unit, so `build()`'s consistency
     *  check saw only part of the sum. Reported by [unitCoverage] rather than rejected: a
     *  partial declaration is a legitimate half-step, and refusing it would make `unit`
     *  all-or-nothing, which is the ceremony §4.2.4 declined.
     */
    internal val partiallyUnitedConstraints = mutableListOf<Int>()

    /**
     *  What the declaration says about units, and what it therefore could not check (§4.2.4).
     *
     *  A field that documents an invariant nothing enforces is the fault D.10 names, and the
     *  honest answer to "is `unit` load-bearing?" is *partly* — it is checked where units
     *  combine, printed where a value is reported, and available to a rule that wants to
     *  require one. This makes the remaining gap countable instead of rhetorical.
     */
    fun unitCoverage(): UnitCoverage = UnitCoverage(
        observationsDeclared = observationDecls.count { it.unit != null },
        observations = observationDecls.size,
        leversDeclared = leverDecls.count { it.unit != null },
        levers = leverDecls.size,
        constraintsChecked = jointDecls.indices.count { k ->
            k !in partiallyUnitedConstraints &&
                jointDecls[k].names.all { n -> leverDecls.first { it.name == n }.unit != null }
        },
        constraintsPartlyChecked = partiallyUnitedConstraints.size,
        constraints = jointDecls.size
    )

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
        observations = observationDecls.map { ObservationDescriptor(it.name, unit = it.unit) },
        levers = leverDecls.map {
            LeverDescriptor(
                name = it.name,
                domain = it.domain,
                modelLowerLimit = it.modelLowerLimit,
                modelUpperLimit = it.modelUpperLimit,
                lowerBound = it.lowerBound,
                upperBound = it.upperBound,
                kind = it.kind,
                stateDependent = it.stateDependent,
                levels = it.levels,
                unit = it.unit
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
    private var myPolicy: PolicyIfc = NeutralPolicy

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

    /**
     *  Which of the two events at a coinciding instant runs first (§4.6.4, G.9 row 10).
     *
     *  §4.6.4's analysis is a CONSEQUENCE of `epochPriority` sorting ahead of this element's
     *  `warmUpPriority`, not a fact about the design. Since both are settable, an ordering
     *  the document asserts could be inverted by declaring a number and nothing would say
     *  so. Declaring the intent lets `build()` check the numbers against it.
     */
    internal var warmUpOrdering: WarmUpOrdering = WarmUpOrdering.EPOCH_FIRST
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

    /**
     *  The priority this element's epoch events carry (§4.6.2, G.9 row 12).
     *
     *  Two elements whose epochs coincide and whose priorities are equal execute in the
     *  order their events were scheduled, which is their declaration order. That is
     *  deterministic and reproducible, and it is **not** a contract anyone should rely on
     *  for correctness: an element that must act before another should say so with a
     *  different priority rather than by being declared first.
     */
    val declaredEpochPriority: Int get() = epochPriority

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

    /** Do nothing, as each lever declared it (§8.2.3). */
    internal fun neutralAction(): DoubleArray =
        DoubleArray(leverDecls.size) { leverDecls[it].neutralValue() }

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

        // Step 6 — decide and act. The context is open only for the duration of the call:
        // reading it afterwards throws rather than answering about a later epoch (§4.5.3).
        // `finally`, so a rule that throws still leaves no live context behind.
        val view = ctx.open(time, time - lastEpochTime, myEpochCount)
        val action = try {
            myPolicy.action(s, view)
        } catch (e: Throwable) {
            myLastTermination = TerminationSource.POLICY_ERROR
            throw e
        } finally {
            ctx.close()
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

    /**
     *  §4.4.4's repair, applied only where it means something.
     *
     *  Clamping is `coerceIn` — numeric proximity — which presumes the domain is ORDERED.
     *  A `CATEGORICAL` lever's values are level indices standing for labels with no order,
     *  so coercing a request for level 9 into `fast` is not repair: it substitutes a
     *  category the rule never asked for. Such a lever is left untouched, so the re-prepare
     *  rejects it and the modeler is told rather than quietly obeyed (G.9 row 9).
     */
    fun clamp(action: DoubleArray): DoubleArray =
        DoubleArray(action.size) { i ->
            val d = decls[i]
            if (d.domain == LeverDomain.CATEGORICAL) return@DoubleArray action[i]
            val r = d.feasibleRange()
            if (r.isEmpty()) Double.NaN else action[i].coerceIn(r.start, r.endInclusive)
        }

    /**
     *  The declared unit, formatted for a message, or "" if none was declared (§4.2.4).
     *
     *  Every number this class reports about a lever goes through here. A magnitude that
     *  looks wrong — 480 where 8 was meant — reads differently with "minutes" next to it than
     *  with nothing, and a violation message is the one place a units mistake reliably
     *  surfaces at all, since the library cannot detect one that never violates anything
     *  (G.9 row 7).
     */
    private fun LeverDecl.u(): String = if (unit == null) "" else " $unit"

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
                    "[${range.start}, ${range.endInclusive}]${d.u()}. No value is available (§4.4.6.3)."
            } else if (v < range.start || v > range.endInclusive) {
                val why = when {
                    d.domain == LeverDomain.CATEGORICAL ->
                        " — the declared levels are ${d.levels}. Clamping does not apply to an " +
                            "unordered domain, so CLAMP_THEN_REJECT rejects here (§4.4.4)"
                    d.stateDependent -> " (the state-dependent set, inside the envelope " +
                        "[${d.modelLowerLimit}, ${d.modelUpperLimit}]${d.u()})"
                    else -> ""
                }
                violations += "'${d.name}' = $v${d.u()} is outside " +
                    "[${range.start}, ${range.endInclusive}]${d.u()}$why."
            } else if (d.domain == LeverDomain.INTEGER && v != Math.rint(v)) {
                violations += "'${d.name}' = $v${d.u()} is not integral, but the lever's domain is INTEGER."
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
            // build() has already established that the summed levers agree on a unit, so
            // there is one unit for the sum and for the total, and naming it is honest.
            val cu = c.names.firstNotNullOfOrNull { n -> decls.first { it.name == n }.unit }
                ?.let { " $it" } ?: ""
            if (c.equality) {
                if (Math.abs(sum - total) > 1e-9) {
                    violations += "Sum of ${c.names} is $sum$cu; $what requires exactly $total$cu."
                }
            } else {
                if (sum > total + 1e-9) {
                    violations += "Sum of ${c.names} is $sum$cu; $what allows at most $total$cu."
                }
            }
        }
        if (violations.isNotEmpty()) return PreparedAction.Invalid(violations)

        // Plan. For a SETTING, a step whose target equals its source is ELIDED, not written:
        // writing a value back is not a no-op in KSL (TWResponse.assignValue collects an
        // observation and notifies observers regardless of whether the value changed).
        //
        // For a TRANSACTION the elision is not merely unnecessary, it is WRONG — two
        // consecutive orders of the same size are two orders, and skipping the second loses
        // one. §8.2.2 measured that: 5.83 orders per replication, 1.5%, gone with no error.
        // A transaction now has no reader at all (`Neutral.Value` carries none), so `from` is
        // NaN and the guard cannot fire; the kind test states the rule rather than relying on
        // that coincidence.
        val steps = mutableListOf<ActionPlan.Step>()
        for ((i, d) in decls.withIndex()) {
            val from = element.catalog.actuator(d.name).let { a ->
                if (a is StatefulLeverActuator) a.currentValue() else Double.NaN
            }
            val to = action[i]
            if (d.kind == LeverKind.SETTING && !from.isNaN() && from == to) continue
            steps += ActionPlan.Step(d.name, from, to, element.catalog.actuator(d.name)!!)
        }
        // Decreases before increases (§4.4): frees capacity before committing it. The ordering
        // is defined over SETTINGS; a transaction has no `from` to take a difference against,
        // so it keys 0.0 and — the sort being stable — transactions keep their declaration
        // order among the neutral moves (§4.4, §8.2.3).
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
 *  §4.5.3. The element's decision state, and the factory for the per-epoch views handed to
 *  rules. The constant half — names, constraints, narrowed bounds — is held here once and
 *  shared by every view, which is what the original "one instance, reused" design was for.
 *
 *  **The aliasing hazard is closed here rather than documented** (G.9 row 6). §4.5.3 said
 *  retaining a context is a bug and nothing prevented it, and the failure it invites is
 *  silent: a rule that stashes the context and reads `simulationTime` later gets a
 *  well-formed number belonging to a different decision.
 *
 *  What a rule receives is an [EpochContext] stamped with the generation it was minted for.
 *  Every epoch-scoped read compares that stamp against the generation currently live and
 *  throws [StaleDecisionContextException] if they differ. A bare "is an epoch open?" flag
 *  would not do: it catches a read after the run, but a context stashed at epoch 1 and read
 *  during epoch 5 would find an epoch open and answer about epoch 5. The stamp is what makes
 *  the two cases distinguishable, and it is why the counter is on the view rather than here.
 *
 *  The cost is one small object per decision, plus the [ActionSet] that belongs to it. That
 *  is the trade the original design was avoiding, and it buys a named failure in place of a
 *  wrong answer.
 */
internal class MutableDecisionContext(private val element: DecisionElement) {

    // ---- The declared shape. Constant; computed once; shared by every view. Retaining
    // these is legitimate, so nothing guards them.
    val elementName: String = element.name
    val modelName: String = element.model.name
    val observationNames: List<String> = element.observationDecls.map { it.name }
    val leverNames: List<String> = element.leverDecls.map { it.name }
    val observationUnits: List<String?> = element.observationDecls.map { it.unit }
    val leverUnits: List<String?> = element.leverDecls.map { it.unit }
    val constraints: List<JointConstraint> = element.jointConstraints.toList()

    // ---- Liveness. `generation` counts decisions; `live` is the one being served, or -1.
    private var generation: Long = 0L
    private var live: Long = -1L

    var simulationTime: Double = 0.0
        private set
    var intervalSinceLastEpoch: Double = 0.0
        private set
    var epochIndex: Int = 0
        private set

    val owner: DecisionElement get() = element

    internal fun check(stamp: Long, member: String) {
        if (stamp != live) throw StaleDecisionContextException(elementName, member, generation - stamp)
    }

    /** Mint the view for this decision. */
    internal fun open(now: Double, sinceLast: Double, index: Int): DecisionContext {
        generation++
        live = generation
        simulationTime = now
        intervalSinceLastEpoch = sinceLast
        epochIndex = index
        return EpochContext(this, generation)
    }

    internal fun close() {
        live = -1L
    }
}

/**
 *  What a rule is handed: the element's decision state, stamped with the generation this
 *  view was minted for (§4.5.3, G.9 row 6).
 *
 *  The declared-shape members delegate without checking — they are constant for the life of
 *  the element, so a rule that keeps the lever names is doing nothing wrong. Everything that
 *  means something different at the next epoch checks first.
 */
internal class EpochContext(
    private val state: MutableDecisionContext,
    private val stamp: Long
) : DecisionContext {

    private val element get() = state.owner

    private fun live(member: String) = state.check(stamp, member)

    // ---- Epoch-scoped.
    override val simulationTime: Double
        get() { live("simulationTime"); return state.simulationTime }
    override val intervalSinceLastEpoch: Double
        get() { live("intervalSinceLastEpoch"); return state.intervalSinceLastEpoch }
    override val epochIndex: Int
        get() { live("epochIndex"); return state.epochIndex }
    override val remainingRunLength: Double
        get() { live("remainingRunLength"); return element.model.lengthOfReplication - state.simulationTime }
    override val replicationId: Int
        get() { live("replicationId"); return element.model.currentReplicationId }

    // ---- The declared shape. Constant; unguarded.
    override val elementName: String get() = state.elementName
    override val modelName: String get() = state.modelName
    override val observationNames: List<String> get() = state.observationNames
    override val leverNames: List<String> get() = state.leverNames
    override val observationUnits: List<String?> get() = state.observationUnits
    override val leverUnits: List<String?> get() = state.leverUnits
    override val constraints: List<JointConstraint> get() = state.constraints

    override val leverBounds: List<ClosedFloatingPointRange<Double>>
        get() = element.leverDecls.map { it.lowerBound..it.upperBound }

    /**
     *  The total governing this lever **as it stands now**. Once a budget can itself be a
     *  state (§4.4.6.1) the declared total and the current total are different numbers, and
     *  a policy allocating within the budget needs the current one — the declared envelope
     *  is still available through [constraints]. Returning the declared value here would
     *  hand every allocating rule an upper bound and call it the budget.
     *
     *  Epoch-scoped for exactly that reason: a state-dependent total read after the epoch is
     *  a number about a different state.
     *
     *  There is at most one such constraint: `build()` rejects a lever named by two
     *  (G.9 row 3), because this accessor returns one number and could otherwise return
     *  whichever was declared first. So `firstOrNull` here is `theOnlyOneOrNull`.
     */
    override fun budgetTotal(leverIndex: Int): Double? {
        live("budgetTotal")
        val name = leverNames[leverIndex]
        val d = element.jointDecls.firstOrNull { it.names.contains(name) } ?: return null
        return d.totalFn()
    }

    // ---- The feasible set 𝒳(s) as an object, §4.4.6.5. It carries this view's stamp too:
    // 𝒳(s) is a function of the state, so a retained ActionSet is the same hazard, and
    // guarding only the getter that hands it out would leave the door open.
    private val myActions: ActionSet = ElementActionSet(element) { m -> live("actions.$m") }

    override val actions: ActionSet
        get() { live("actions"); return myActions }

    override val currentAction: DoubleArray
        get() {
            live("currentAction")
            return DoubleArray(element.leverDecls.size) { i ->
                val a = element.catalog.actuator(element.leverDecls[i].name)
                if (a is StatefulLeverActuator) a.currentValue() else Double.NaN
            }
        }

    override val neutralAction: DoubleArray
        get() { live("neutralAction"); return element.neutralAction() }
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
    fun observe(source: ResponseIfc, unit: String? = null) = observe(source, source.name, unit)

    fun observe(source: ResponseIfc, alias: String, unit: String? = null) {
        // ResponseIfc carries ValueIfc, NOT GetValueIfc: KSL has two interfaces declaring
        // `val value: Double` and they are unrelated. The catalog is typed on GetValueIfc,
        // so a response must be adapted. See §8.1.
        element.observationDecls += ObservationDecl(alias, GetValueIfc { source.value }, unit)
    }

    fun observe(name: String, unit: String? = null, source: GetValueIfc) {
        element.observationDecls += ObservationDecl(name, source, unit)
    }

    // Each returns the declared lever's identity, for use by budget/atMost and by
    // DecisionElement.narrow. Generic in the owner so the setter receiver resolves.
    //
    // [neutral] is REQUIRED and says what this lever does when a rule declines to act on it
    // (§8.2.3). `Neutral.Current { … }` makes it a SETTING and carries the reader; the reader
    // is not a separate optional argument, because a setting without one cannot answer
    // `currentAction` and cannot participate in §6.2's Level-2 argument. `Neutral.Value(0.0)`
    // makes it a TRANSACTION, for which there is nothing to read and doing nothing is an
    // action with a declared amount.
    fun <T : ModelElement> lever(
        owner: T,
        limits: IntRange,
        neutral: Neutral<T>,
        alias: String? = null,
        unit: String? = null,
        bounds: (T.() -> ClosedFloatingPointRange<Double>)? = null,
        set: T.(Double) -> Unit
    ): LeverRef = declare(
        owner, LeverDomain.INTEGER, limits.first.toDouble(), limits.last.toDouble(), null, alias,
        unit, bounds, neutral, set
    )

    fun <T : ModelElement> lever(
        owner: T,
        limits: ClosedFloatingPointRange<Double>,
        neutral: Neutral<T>,
        alias: String? = null,
        unit: String? = null,
        bounds: (T.() -> ClosedFloatingPointRange<Double>)? = null,
        set: T.(Double) -> Unit
    ): LeverRef = declare(
        owner, LeverDomain.CONTINUOUS, limits.start, limits.endInclusive, null, alias,
        unit, bounds, neutral, set
    )

    fun <T : ModelElement> lever(
        owner: T,
        levels: List<String>,
        neutral: Neutral<T>,
        alias: String? = null,
        unit: String? = null,
        bounds: (T.() -> ClosedFloatingPointRange<Double>)? = null,
        set: T.(Double) -> Unit
    ): LeverRef = declare(
        owner, LeverDomain.CATEGORICAL, 0.0, (levels.size - 1).toDouble(), levels, alias,
        unit, bounds, neutral, set
    )

    private fun <T : ModelElement> declare(
        owner: T,
        domain: LeverDomain,
        lower: Double,
        upper: Double,
        levels: List<String>?,
        alias: String?,
        unit: String?,
        bounds: (T.() -> ClosedFloatingPointRange<Double>)?,
        neutral: Neutral<T>,
        set: T.(Double) -> Unit
    ): LeverRef {
        require(lower <= upper) { "Lever limits for '${alias ?: owner.name}' are unordered: [$lower, $upper]" }
        val name = alias ?: owner.name
        require(element.leverDecls.none { it.name == name }) { "Lever '$name' is declared twice." }
        // The reader and the kind both come out of `neutral`, which is why they can no
        // longer disagree. A CATEGORICAL transaction is refused: doing nothing to an
        // unordered lever is not a number, and `Neutral.Value` would have to name a level
        // index, which is the same category error §4.4.4's clamp refuses to make.
        if (domain == LeverDomain.CATEGORICAL && neutral is Neutral.Value) {
            throw IllegalArgumentException(
                "Lever '$name' is CATEGORICAL and declares Neutral.Value(${neutral.amount}). A " +
                    "categorical lever's values are labels with no arithmetic, so a neutral " +
                    "AMOUNT means nothing — the same category error §4.4.4's clamp refuses to " +
                    "make. Declare Neutral.Current { … } naming the level in force (§8.2.3)."
            )
        }
        element.leverDecls += LeverDecl(
            name = name,
            owner = owner,
            domain = domain,
            kind = when (neutral) {
                is Neutral.Current -> LeverKind.SETTING
                is Neutral.Value -> LeverKind.TRANSACTION
            },
            modelLowerLimit = lower,
            modelUpperLimit = upper,
            levels = levels,
            write = { v -> owner.set(v) },
            read = when (neutral) {
                is Neutral.Current -> ({ neutral.read(owner) })
                is Neutral.Value -> null
            },
            neutralValue = when (neutral) {
                is Neutral.Current -> ({ neutral.read(owner) })
                is Neutral.Value -> ({ neutral.amount })
            },
            boundsFn = if (bounds == null) null else ({ owner.bounds() }),
            unit = unit
        )
        return LeverRef(name)
    }

    fun batchLever(vararg levers: LeverRef, applyAll: (DoubleArray) -> Unit): Nothing =
        throw NotDeclarableYetException("batchLever", "M1 step 6", "§4.4.5")

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

    /**
     *  Declaring a reward is **not yet carried**, and it fails here rather than later.
     *  An earlier version returned a `RewardRef` and let the failure surface at
     *  `estimand` — so a modeler could declare a reward that nothing consumed and find
     *  out much later (G.9 row 11).
     */
    fun reward(
        source: ResponseIfc, rate: Double,
        sense: RewardSense = RewardSense.COST, alias: String? = null
    ): Nothing = throw NotDeclarableYetException("reward", "M2", "§4.2.5")

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

    /**
     *  Which runs first when an epoch coincides with this element's warm-up (§4.6.4).
     *  `build()` checks [epochPriority] against the element's `warmUpPriority` and rejects
     *  a combination that contradicts what is declared here.
     */
    var warmUpOrdering: WarmUpOrdering
        get() = element.warmUpOrdering
        set(value) { element.warmUpOrdering = value }

    fun maxEpochs(n: Int) { element.myMaxEpochs = n }
    fun terminalWhen(condition: () -> Boolean) { element.terminalCondition = condition }

    var feasibility: FeasibilityPolicy
        get() = element.feasibilityPolicy
        set(value) { element.feasibilityPolicy = value }

    var policy: PolicyIfc? = null

    fun captureTo(factory: (RunProvenance) -> TransitionSink): Nothing =
        throw NotDeclarableYetException("captureTo", "M3", "§4.8")

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
        // §4.2.4 / G.9 row 7: a joint constraint SUMS its levers, so summing levers measured
        // in different things is an arithmetic error, not a modelling preference. This is the
        // one place the library can check a unit against something, because it is the one
        // place units are combined. Levers that declare no unit are skipped — `unit` is
        // optional, and an optional field cannot be the basis of a mandatory check.
        for ((k, c) in element.jointDecls.withIndex()) {
            val declared = c.names.mapNotNull { n ->
                element.leverDecls.first { it.name == n }.unit?.let { u -> n to u }
            }
            val distinct = declared.map { it.second }.distinct()
            require(distinct.size <= 1) {
                "The constraint `${c.describe()}` sums levers measured in different units: " +
                    declared.joinToString(", ") { "${it.first} in ${it.second}" } +
                    ". A sum of quantities in different units is not a quantity, so no total " +
                    "can be right for it. Declare one unit for all of them, or express the " +
                    "intent as ${distinct.size} separate constraints (§4.2.4)."
            }
            if (distinct.size == 1 && declared.size < c.names.size) {
                // Not an error: `unit` is optional and a partial declaration is a legitimate
                // half-step. But it does mean the check above only covered part of the sum.
                element.partiallyUnitedConstraints += k
            }
        }
        // §4.4.6.2 / G.9 row 3: one lever, one joint total. `budgetTotal` returns a single
        // number, so a lever named by two constraints hands an allocating rule whichever
        // was declared first and gives it no way to act on the other. Refuse at
        // construction, naming both — that is what §4.2.6 promises everywhere else.
        for (n in declared) {
            val owning = element.jointDecls.filter { n in it.names }
            require(owning.size <= 1) {
                "Lever '$n' is named by ${owning.size} joint constraints: " +
                    owning.joinToString("; ") { it.describe() } +
                    ". A lever has one budget — `budgetTotal` returns a single number, so a " +
                    "rule allocating '$n' would see only the first of these and could not " +
                    "honour the rest. Express the intent as one constraint over '$n', or " +
                    "split '$n' into one lever per constraint (§4.4.6.2)."
            }
        }
        // §4.6.4 / G.9 row 10: the declared ordering must match the numbers that produce it.
        val epochP = element.epochPriority
        val warmP = element.warmUpPriority
        when (element.warmUpOrdering) {
            WarmUpOrdering.EPOCH_FIRST -> require(epochP < warmP) {
                "Element '${element.name}' declares warmUpOrdering = EPOCH_FIRST, but its epoch " +
                    "priority ($epochP) does not sort ahead of its warm-up priority ($warmP), so " +
                    "the epoch at a coinciding instant would run AFTER the warm-up. Lower the " +
                    "epoch priority, or declare WARM_UP_FIRST if that is what you mean (§4.6.4)."
            }
            WarmUpOrdering.WARM_UP_FIRST -> require(epochP > warmP) {
                "Element '${element.name}' declares warmUpOrdering = WARM_UP_FIRST, but its epoch " +
                    "priority ($epochP) sorts ahead of its warm-up priority ($warmP), so the epoch " +
                    "would run BEFORE the warm-up (§4.6.4)."
            }
        }
        element.buildCatalog()
        element.bind()
        element.policy = policy!!
        return element
    }
}
