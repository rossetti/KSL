package ksl.modeling.decision

import ksl.modeling.decision.descriptor.*
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.ResponseIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc

/**
 *  The identity of a declared lever. A lever is (target, limits, domain, write), so its
 *  identity is its own — not that of the element it writes, which may back several levers.
 *  Pure data: holding one confers no access to anything.
 *
 *  **It names its element as well as itself, and that is not decoration.** A ref used to be a
 *  bare declared name, and §7.3.1 step 8 required that a ref from one element cannot narrow
 *  another. Nothing enforced it: two elements declaring the same alias — which is exactly what
 *  two instances of one subsystem produce (§4.1.9) — let one element's reference silently
 *  narrow the other's lever, and the call did something other than what it appeared to say.
 *  D.15 found this shape once already: a reference that identifies less than it looks like it
 *  does.
 *
 *  Identity is by *name* rather than by object, for the reason §4.2.2 gives — names come from
 *  the model and the descriptor is keyed on them — and element names are unique within a model
 *  (`Model.addToModelElementMap`), so the pair is a key. Across two models it deliberately is
 *  not: a ref reads "lever `staff` of element `Review`", which is what B.12's late binding
 *  would want to resolve against a freshly built model.
 */
data class LeverRef internal constructor(val elementName: String, val declaredName: String)

/**
 *  The identity of a declared reward term. A term is (source, kind, rate, sense), so its
 *  identity is its own — one source may back several terms at different rates or senses.
 *
 *  Carries its element for the same reason [LeverRef] does, and pre-emptively: D.16 records
 *  that the lever/reward pair has produced the same defect on both sides five times, and
 *  fixing one of them alone is how that keeps happening.
 */
data class RewardRef internal constructor(val elementName: String, val declaredName: String)

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
    internal val rewardDecls = mutableListOf<RewardDecl>()

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
        rewards = rewardDecls.map {
            RewardDescriptor(it.name, it.sourceRef, it.kind, it.declaredRate, it.sense)
        },
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

            // §4.1.3.1. EVERYTHING THAT CAN FAIL HAPPENS FIRST, and the irreversible act happens
            // last. `configure` rejects a policy whose shape does not match the declaration —
            // FixedPolicy checks its arity there — and this used to run after the incumbent had
            // been closed and replaced, so a refused assignment left the element holding the rule
            // that had just refused it, with the previous rule closed and unrecoverable. The
            // caller saw an exception that reads as "nothing happened".
            if (value is ShapeAwarePolicyIfc) value.configure(descriptor())

            // §4.7. Replacement is the one moment the element is definitively finished with a
            // policy it was handed, so it is the one moment it closes one. §4.9's k-policy
            // comparison assigns k rules to one element in a loop, and leaving each of the k-1
            // superseded rules unclosed would leak whatever they hold.
            //
            // Assigning the SAME policy back is not a replacement and must not close it — that
            // reads as a no-op at the call site and would otherwise release a live resource.
            if (value !== myPolicy) (myPolicy as? ManagedPolicyIfc)?.close()
            myPolicy = value
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
        return LeverRef(this.name, hits.single().name)
    }

    /** Resolve a declared lever by name. */
    fun leverRef(declaredName: String): LeverRef {
        leverDecls.firstOrNull { it.name == declaredName }
            ?: throw BindingException(declaredName, leverDecls.map { it.name })
        return LeverRef(this.name, declaredName)
    }

    /**
     *  Resolve a ref against *this* element, refusing one that belongs to another (§7.3.1 step 8).
     *
     *  The element check comes first and is separate from the name check, because the two are
     *  different mistakes with different repairs: a name this element does not declare is a typo
     *  or a stale alias, and a ref from a sibling element is a call that would otherwise have
     *  narrowed the wrong lever without saying so.
     */
    private fun declOf(lever: LeverRef): LeverDecl {
        if (lever.elementName != this.name) {
            throw BindingException(
                "${lever.elementName}:${lever.declaredName}",
                leverDecls.map { "${this.name}:${it.name}" },
                "That reference was issued by decision element '${lever.elementName}' and this is " +
                    "'${this.name}'. Ask this element for its own: leverRef(\"${lever.declaredName}\") " +
                    "or leverFor(target)."
            )
        }
        return leverDecls.firstOrNull { it.name == lever.declaredName }
            ?: throw BindingException(lever.declaredName, leverDecls.map { it.name })
    }

    fun narrow(lever: LeverRef, limits: IntRange) {
        requireNotRunning("narrow")
        narrow(lever, limits.first.toDouble()..limits.last.toDouble())
    }

    fun narrow(lever: LeverRef, limits: ClosedFloatingPointRange<Double>) {
        requireNotRunning("narrow")
        val d = declOf(lever)
        // §7.3.1 step 3: non-integral limits on an INTEGER domain. The declaration path cannot
        // produce them — `lever(owner, IntRange)` takes integers — but this overload can, and the
        // consequence is two accessors of one lever disagreeing: `limitsOf` truncates 1.5 to 1
        // while `prepare` computes feasibility against 1.5 and rejects an action of 1. That is the
        // disagreement §4.4.6.2 goes out of its way to prevent between `contains` and `prepare`.
        //
        // Rejected rather than rounded inward, for §4.3.3's reason: rounding is clamping's cousin,
        // and a narrowing that quietly becomes a different narrowing is the thing this call refuses
        // to do.
        if (d.domain == LeverDomain.INTEGER &&
            (limits.start != Math.rint(limits.start) || limits.endInclusive != Math.rint(limits.endInclusive))
        ) {
            throw NarrowingException(
                "Cannot narrow '${d.name}' to [${limits.start}, ${limits.endInclusive}]: the lever's " +
                    "domain is INTEGER and those bounds are not integral. `limitsOf` would report " +
                    "[${limits.start.toInt()}, ${limits.endInclusive.toInt()}] while the element " +
                    "accepted a different set, so the two would disagree (§4.3.3). Narrow with an " +
                    "IntRange, or state integral bounds."
            )
        }
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

    /**
     *  Resolve the reward term declared over [source]. Symmetric with [leverFor], including its
     *  failure modes: a source backing several terms is ambiguous, and D.16 records that fixing
     *  one half of the lever/reward pair alone is how the same defect keeps recurring.
     */
    fun rewardFor(source: ResponseCIfc): RewardRef {
        val hits = rewardDecls.filter { it.source.name == source.name }
        if (hits.isEmpty()) throw BindingException(source.name, rewardDecls.map { it.name })
        if (hits.size > 1) throw AmbiguousLeverException(source.name, hits.map { it.name })
        return RewardRef(this.name, hits.single().name)
    }

    fun rewardRef(declaredName: String): RewardRef {
        rewardDecls.firstOrNull { it.name == declaredName }
            ?: throw BindingException(declaredName, rewardDecls.map { it.name })
        return RewardRef(this.name, declaredName)
    }

    /**
     *  A rate is a parameter (§4.1.3), so it is replication-initial and re-signed on assignment —
     *  the sense was fixed at declaration and only the magnitude is being set here.
     */
    fun rewardRate(term: RewardRef, rate: Double) {
        requireNotRunning("rewardRate")
        if (term.elementName != this.name) {
            throw BindingException(
                "${term.elementName}:${term.declaredName}",
                rewardDecls.map { "${this.name}:${it.name}" },
                "That reference was issued by decision element '${term.elementName}' and this is " +
                    "'${this.name}'. Ask this element for its own: rewardRef(\"${term.declaredName}\")."
            )
        }
        val i = rewardDecls.indexOfFirst { it.name == term.declaredName }
        if (i < 0) throw BindingException(term.declaredName, rewardDecls.map { it.name })
        val d = rewardDecls[i]
        rewardDecls[i] = RewardDecl(
            name = d.name, kind = d.kind,
            signedRate = if (d.sense == RewardSense.COST) -rate else rate,
            declaredRate = rate, sense = d.sense, source = d.source, sourceRef = d.sourceRef
        )
    }

    private var myPolicyLabel: String? = null
    /** Labels this rule in trajectories and reports. Defaults to the policy's class name. */
    var policyLabel: String
        get() = myPolicyLabel ?: (myPolicy::class.simpleName ?: "policy")
        set(value) { requireNotRunning("policyLabel"); myPolicyLabel = value }

    // ---- Observation ------------------------------------------------------------
    /**
     *  §4.10.1: `"<name>:TotalReward"`, an **ordinary `Response`** so that
     *  `ReplicationDataCollector` and `model.print()` find it with no special case (§4.9).
     *
     *  One observation per replication, published where the episode ends — at step 5 if it ends
     *  early, otherwise at `replicationEnded()`.
     *
     *  **It exists only when a reward is declared**, and the first attempt got this wrong. Creating
     *  it eagerly seemed tidier — the report's shape would then not depend on the declaration — and
     *  it broke §6.2 Level 2 immediately: a decision element under `NeutralPolicy` must reproduce
     *  the unmodified model, and the arm carrying the element suddenly had one response the other
     *  did not. The reasoning was backwards. The report's shape *should* follow the declaration,
     *  because declaring a reward is asking for a number, and an element that declares none must
     *  remain invisible.
     */
    private var myEstimand: Response? = null

    val estimand: ResponseCIfc
        get() = myEstimand ?: throw IllegalStateException(
            "'${this.name}' declares no reward, so there is nothing for its estimand to report. " +
                "Declare one with reward(source, rate) (§4.2.5). The response is created only " +
                "when a reward is, so that an element without one stays invisible to §6.2's " +
                "Level-2 comparison."
        )

    internal fun createEstimand() {
        if (rewardDecls.isNotEmpty() && myEstimand == null) {
            myEstimand = Response(this, name = "${this.name}:TotalReward")
        }
    }

    /** Reward accrued this replication since the warm-up, in reward units (COST already negated). */
    private var accruedReward: Double = 0.0
    private var estimandPublished: Boolean = false

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

    internal lateinit var rewards: RewardBinding
        private set

    /** What the decision at the previous epoch started, waiting for its successor state. */
    private class Pending(
        val state: DoubleArray,
        /** What was written to the levers, not what the rule asked for (§4.8.3). */
        val action: DoubleArray,
        /** What the rule asked for, kept only when it differs from [action]. */
        val proposedAction: DoubleArray?,
        /** Which levers had an empty feasible set at the decision (§4.4.6.3). */
        val unavailable: BooleanArray?,
        val time: Double,
        val epochIndex: Int
    )
    private var pending: Pending? = null

    internal var sinkFactory: ((RunProvenance) -> TransitionSink)? = null
    private var sink: TransitionSink = ksl.sdm.capture.NullSink

    internal fun bind() {
        binding = DefaultActionBinding(this)
        ctx = MutableDecisionContext(this)
        rewards = RewardBinding(rewardDecls.toList())
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
        // Step 1 — observe. ONE read, serving both the successor state of the transition that is
        // completing and the state of the decision about to be made: a catalog entry backed by a
        // computed lambda need not be pure, so reading twice could disagree with itself.
        val s = readObservations()

        // Step 2 — close the interval. One read of every reward source, differenced against the
        // baseline and adopted as the new one. `null` means there was no baseline — the first
        // epoch of an episode, or one invalidated by warm-up — which is NOT a reward of zero.
        val intervalReward = rewards.closeInterval()
        if (intervalReward != null) accruedReward += intervalReward

        // Step 3 — classify the ending. Before the emit, because `terminated` and `truncated` are
        // fields of the row step 4 writes (§4.6.3).
        val terminal = terminalCondition?.invoke() == true
        val ending: TerminationSource? = when {
            terminal -> TerminationSource.NATURAL
            myEpochCount >= myMaxEpochs -> TerminationSource.MAX_EPOCHS
            else -> null
        }

        // Step 4 — emit the pending transition, or discard it. Three reasons to discard, and they
        // are separate facts rather than one: no predecessor at all, an interval whose reward is
        // not measurable, and an interval with no duration (§4.8.3).
        emitPending(successor = s, reward = intervalReward, ending = ending)

        // Step 5 — stop if the episode ended. The replication continues; the decision sequence
        // does not, so nothing is decided and nothing is scheduled.
        if (ending != null) {
            myLastTermination = ending
            publishEstimand(ending)
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
        // The plan that was actually applied. §4.8.3: a transition records what was WRITTEN, not
        // what was asked for. The two differ whenever CLAMP_THEN_REJECT repaired the request or a
        // lever's feasible set was empty, and a trajectory whose action column did not produce its
        // reward column is not a trajectory.
        val applied: ActionPlan = when (val prepared = binding.prepare(action)) {
            is PreparedAction.Ready -> prepared.plan.also { binding.apply(it) }
            is PreparedAction.Invalid -> {
                if (myFeasibilityPolicy == FeasibilityPolicy.CLAMP_THEN_REJECT) {
                    val clamped = binding.clamp(action)
                    when (val second = binding.prepare(clamped)) {
                        is PreparedAction.Ready -> second.plan.also { binding.apply(it) }
                        is PreparedAction.Invalid -> throw ActionValidationException(second.violations)
                    }
                } else {
                    throw ActionValidationException(prepared.violations)
                }
            }
        }

        // Step 7 — carry forward and schedule. The proposed vector is kept only when it differs
        // from what was written, so a non-null `proposedAction` is a positive signal that the
        // request was repaired rather than a column that repeats `action` on every row.
        val appliedVector = applied.applied
        val proposed = if (action.contentEquals(appliedVector)) null else action.copyOf()
        pending = Pending(s, appliedVector.copyOf(), proposed, applied.unavailable?.copyOf(),
            time, myEpochCount)
        myEpochCount++
        lastEpochTime = time
        scheduleNextEpoch()
    }

    /**
     *  §4.10.2.1. Per-rule accounting for [emitPending], so that the emission truth table is a
     *  *checked* property rather than one re-derived by reading two functions.
     *
     *  Counters accumulate over the element's lifetime, which is one model's, so they are
     *  per-experiment totals across replications. Nothing resets them: a fresh `Model` gives a
     *  fresh element, and that is the scope the table is stated at.
     *
     *  Internal rather than public. This is an accounting of an internal branch, not a published
     *  contract, and §6.1 keeps new public API to what a modeler actually needs.
     */
    internal class EmissionCensus {
        var attempts: Int = 0
        var emitted: Int = 0
        var noPredecessor: Int = 0
        var noBaseline: Int = 0
        var zeroLength: Int = 0

        val discards: Int get() = noPredecessor + noBaseline + zeroLength

        override fun toString(): String =
            "attempts=$attempts emitted=$emitted noPredecessor=$noPredecessor " +
                "noBaseline=$noBaseline zeroLength=$zeroLength"
    }

    internal val census = EmissionCensus()

    /**
     *  §4.10.2 step 4. Write the completed transition, or discard it for one of three reasons.
     *
     *  A discarded transition is not an error and not a row of zeros: a row carrying no duration
     *  carries no information and would bias any per-interval average (§4.8.3, D.8), and a row
     *  whose reward could not be measured would report a number the model never produced.
     *
     *  **The order of the three tests is attributive, not merely procedural** (§4.10.2.1). They
     *  short-circuit, so when more than one predicate holds the first one recorded is the reason.
     *  This is why discard 2 never fires: `rewards.invalidate()` is called at exactly two sites,
     *  `initialize()` and `warmUp()`, and both clear `pending` in the same breath, so there is no
     *  reachable state in which a transition is pending while its baseline is not. It is kept as a
     *  guard on that two-site invariant, and `EmissionTruthTableTest` fails if it ever fires —
     *  which is the point of keeping an unreachable branch rather than deleting it.
     */
    private fun emitPending(successor: DoubleArray, reward: Double?, ending: TerminationSource?) {
        census.attempts++
        val p = pending ?: run { census.noPredecessor++; return }   // discard 1: no predecessor
        pending = null
        if (reward == null) {                     // discard 2: baseline invalidated, or none yet
            census.noBaseline++
            return
        }
        val tau = time - p.time
        if (tau <= 0.0) {                         // discard 3: zero-length (§4.8.3)
            census.zeroLength++
            return
        }
        census.emitted++
        val record = TransitionRecord(
            elementName = this.name,
            replicationId = model.currentReplicationId,
            epochIndex = p.epochIndex,
            time = time,
            tau = tau,
            state = p.state,
            action = p.action,
            proposedAction = p.proposedAction,
            leverUnavailable = p.unavailable,
            reward = reward,
            successorState = successor,
            terminated = ending == TerminationSource.NATURAL,
            truncated = ending != null && ending != TerminationSource.NATURAL,
            source = ending
        )
        sink.write(record)
        // The hook §4.5.4.1 declares and §8.2.9 measured was never called. It is called here:
        // a LookaheadPolicy holding a LearnableValueApproximationIfc becomes an adaptive rule by
        // forwarding this, which is one class rather than a new concept.
        (myPolicy as? ManagedPolicyIfc)?.onTransition(record)
    }

    /**
     *  One observation of the estimand per replication, wherever the episode ends. Guarded because
     *  both step 5 and `replicationEnded()` can be the end, and a `Response` assigned twice would
     *  contribute two observations to the across-replication statistic.
     */
    private fun publishEstimand(source: TerminationSource) {
        if (estimandPublished) return
        estimandPublished = true
        myEstimand?.value = accruedReward
        (myPolicy as? ManagedPolicyIfc)?.afterEpisode(model.currentReplicationId, source)
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
    override fun beforeExperiment() {
        // §4.7. A managed policy acquires its per-experiment resources here, paired with the
        // afterExperiment() below on every run — so a model simulated three times sets the policy
        // up three times rather than leaving runs 2 and 3 to use what run 1 tore down.
        (myPolicy as? ManagedPolicyIfc)?.beforeExperiment()

        // The sink is opened once per experiment, with the provenance a row needs in order to be
        // written without a live Model (§4.8.2). The descriptor is computed here rather than
        // stored, so it cannot be stale (§4.1.5).
        val factory = sinkFactory
        if (factory != null) {
            sink = factory(
                RunProvenance(
                    modelName = model.name,
                    experimentName = model.experimentName,
                    elementName = this.name,
                    policyLabel = policyLabel,
                    descriptor = descriptor()
                )
            )
        }
    }

    override fun initialize() {
        myEpochCount = 0
        myLastTermination = null
        lastEpochTime = 0.0
        calendarIndex = 0
        pending = null
        accruedReward = 0.0
        estimandPublished = false
        // §4.10.3: initialize() must NOT read reward sources. It runs in model-element
        // construction order, so reading a sibling's accumulated value here would make the
        // baseline depend on declaration order. It starts invalid and is taken at the first
        // epoch instead — which costs one discarded transition and buys order-independence.
        rewards.invalidate()
        (myPolicy as? ManagedPolicyIfc)?.beforeEpisode(model.currentReplicationId)
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
        // §4.6.4. Discard the transition in flight and invalidate the baseline — the interval
        // straddling the warm-up is half pre-warm-up and reporting it would credit the estimand
        // with reward the run is meant to forget. Read nothing else: the epoch at this instant,
        // if any, has ALREADY run, because epochPriority sorts ahead of warmUpPriority and
        // build() checks that against the declared warmUpOrdering (§4.6.4.1).
        pending = null
        rewards.invalidate()
        accruedReward = 0.0
    }

    override fun replicationEnded() {
        // §4.10.3: close the final transition over the partial interval. `RUN_LENGTH` unless the
        // episode already ended, in which case step 5 recorded why and emitted its own row.
        if (myLastTermination == null) {
            val reward = rewards.closeInterval()
            if (reward != null) accruedReward += reward
            myLastTermination = TerminationSource.RUN_LENGTH
            emitPending(readObservations(), reward, TerminationSource.RUN_LENGTH)
        }
        publishEstimand(myLastTermination ?: TerminationSource.RUN_LENGTH)
    }

    override fun afterExperiment() {
        // §4.7. The element closes what the element OPENED. It opened the sink, through the
        // factory in beforeExperiment(), so it closes the sink. It did not open the policy — the
        // user constructed it and assigned it — so the policy gets its per-experiment teardown
        // here and its close() only when replaced.
        //
        // This used to call policy.close(), which made a second model.simulate() run against a
        // policy whose resources had been released: measured at twelve uses after close on a
        // two-run model, silently. A sweep and simulation optimization (B.5) both re-run one
        // model, so that is the ordinary case rather than an exotic one.
        //
        // Both teardowns are attempted and a failure in one does not prevent the other.
        val failures = mutableListOf<Throwable>()
        runCatching { (myPolicy as? ManagedPolicyIfc)?.afterExperiment() }
            .exceptionOrNull()?.let { failures += it }
        runCatching { sink.close() }.exceptionOrNull()?.let { failures += it }
        sink = ksl.sdm.capture.NullSink
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
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
            // An empty set is left alone rather than mapped to NaN. `prepare` resolves such a
            // lever to its neutral (§4.4.6.3) and raises no violation, so there is nothing here to
            // repair; the earlier NaN made the re-prepare reject the value clamping had just
            // produced, which turned "no action was possible" into a dead replication.
            if (r.isEmpty()) action[i] else action[i].coerceIn(r.start, r.endInclusive)
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
        // §4.4.6.3. The resolved value of every lever, which is what the rule asked for except
        // where its feasible set was empty and the lever therefore takes its declared neutral.
        // An empty set is NOT a violation: it means no action was possible, which is a fact about
        // the epoch rather than a fault in the rule. This is what the section has always said; the
        // first implementation classified it alongside NaN and killed the replication, and the
        // exception it threw cited the section by number.
        val resolved = action.copyOf()
        var unavailable: BooleanArray? = null

        for ((i, d) in decls.withIndex()) {
            val v = action[i]
            // §4.3.3: envelope ∩ narrowed ∩ 𝒳(s), re-evaluated at every epoch.
            val range = d.feasibleRange()
            if (range.isEmpty()) {
                // Nothing to choose from. Take the neutral, record that it was forced, and do not
                // judge what the rule asked for — there was no value it could have named.
                val flags = unavailable ?: BooleanArray(decls.size).also { unavailable = it }
                flags[i] = true
                resolved[i] = d.neutralValue()
                continue
            }
            if (v.isNaN()) {
                violations += "'${d.name}' received NaN."
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
            // Over the RESOLVED values, not the requested ones: a constraint is a statement about
            // what the model will hold, and a lever forced to its neutral contributes the neutral.
            val sum = c.names.sumOf { n ->
                val i = index[n] ?: return PreparedAction.Invalid(listOf("Constraint names unknown lever '$n'."))
                resolved[i]
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
            val to = resolved[i]
            if (d.kind == LeverKind.SETTING && !from.isNaN() && from == to) continue
            steps += ActionPlan.Step(d.name, from, to, element.catalog.actuator(d.name)!!)
        }
        // Decreases before increases (§4.4): frees capacity before committing it. The ordering
        // is defined over SETTINGS; a transaction has no `from` to take a difference against,
        // so it keys 0.0 and — the sort being stable — transactions keep their declaration
        // order among the neutral moves (§4.4, §8.2.3).
        steps.sortBy { if (it.from.isNaN()) 0.0 else it.to - it.from }
        return PreparedAction.Ready(ActionPlan(steps, resolved, unavailable))
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
        return LeverRef(element.name, name)
    }

    fun batchLever(vararg levers: LeverRef, applyAll: (DoubleArray) -> Unit): Nothing =
        throw NotDeclarableYetException("batchLever", "M1 step 6", "§4.4.5")

    /**
     *  Names for a constraint, refusing a ref that belongs to a different element.
     *
     *  `build()` already rejects a name this element does not declare, which catches a foreign ref
     *  whose alias is unique. It does *not* catch one whose alias happens to match — the case
     *  §4.1.9 makes ordinary, since two instances of a subsystem declare the same aliases — and
     *  that is the one where the constraint would have been built over the right name for the
     *  wrong reason.
     */
    private fun namesOf(levers: Array<out LeverRef>): List<String> {
        for (l in levers) {
            require(l.elementName == element.name) {
                "Constraint names lever '${l.declaredName}' of decision element " +
                    "'${l.elementName}', but this is '${element.name}'. A constraint may only " +
                    "join levers of the element that declares it (§4.4.1)."
            }
        }
        return levers.map { it.declaredName }
    }

    fun budget(vararg levers: LeverRef, total: Double) {
        val names = namesOf(levers)
        element.jointConstraints += SumEquals(names, total)
        element.jointDecls += DecisionElement.JointDecl(true, names, { total }, false)
    }

    fun atMost(vararg levers: LeverRef, total: Double) {
        val names = namesOf(levers)
        element.jointConstraints += SumAtMost(names, total)
        element.jointDecls += DecisionElement.JointDecl(false, names, { total }, false)
    }

    /**
     *  A budget that is itself a state (§4.4.6.1) — "ship no more than is on hand". The
     *  descriptor records [envelope] as the declared total and flags the constraint
     *  state-dependent, because a serialized descriptor cannot carry a lambda.
     */
    fun budget(vararg levers: LeverRef, envelope: Double, total: () -> Double) {
        val names = namesOf(levers)
        element.jointConstraints += SumEquals(names, envelope)
        element.jointDecls += DecisionElement.JointDecl(true, names, total, true)
    }

    fun atMost(vararg levers: LeverRef, envelope: Double, total: () -> Double) {
        val names = namesOf(levers)
        element.jointConstraints += SumAtMost(names, envelope)
        element.jointDecls += DecisionElement.JointDecl(false, names, total, true)
    }

    /**
     *  Declare a reward term: a rate times an accumulated quantity, differenced between epochs
     *  (§4.2.5).
     *
     *  **The source type is `ResponseCIfc`, not `ResponseIfc`.** Only the former carries
     *  `withinReplicationStatistic`, which is what "accumulated" means for a response — so a
     *  source that cannot answer is refused by the compiler rather than at construction. See the
     *  `Counter` overload: it needs one because a counter accumulates somewhere else entirely.
     *
     *  [kind] is normally omitted and inferred from the source. Stating it turns an assumption
     *  about the source's type into a claim `build()` checks.
     */
    fun reward(
        source: ResponseCIfc, rate: Double,
        sense: RewardSense = RewardSense.COST, alias: String? = null,
        kind: RewardKind? = null
    ): RewardRef = declareReward(source, source.name, rate, sense, alias, kind)

    /** As above, for a `Counter` — whose accumulation is its `value` (§4.2.5). */
    fun reward(
        source: CounterCIfc, rate: Double,
        sense: RewardSense = RewardSense.COST, alias: String? = null,
        kind: RewardKind? = null
    ): RewardRef = declareReward(source, source.name, rate, sense, alias, kind)

    private fun declareReward(
        source: Any, sourceName: String, rate: Double,
        sense: RewardSense, alias: String?, kind: RewardKind?
    ): RewardRef {
        val name = alias ?: sourceName
        require(element.rewardDecls.none { it.name == name }) { "Reward term '$name' is declared twice." }
        checkRewardKind(kind, source, name)
        element.rewardDecls += RewardDecl(
            name = name,
            kind = inferRewardKind(source),
            // §4.2.5: the sign is applied ONCE, here, so nothing downstream flips it again.
            signedRate = if (sense == RewardSense.COST) -rate else rate,
            declaredRate = rate,
            sense = sense,
            source = rewardSourceFor(source) { element.time },
            sourceRef = sourceRefFor(source)
        )
        return RewardRef(element.name, name)
    }

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

    /**
     *  Where this element's transitions go (§4.8.2). The factory is called once per experiment,
     *  at `beforeExperiment()`, with the provenance a row needs to be written without a live
     *  `Model` — which is what lets a sink be tested standalone or write from another thread.
     */
    fun captureTo(factory: (RunProvenance) -> TransitionSink) {
        element.sinkFactory = factory
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
        element.createEstimand()
        element.bind()
        element.policy = policy!!
        return element
    }
}
