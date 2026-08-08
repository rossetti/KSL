package ksl.modeling.decision

import ksl.modeling.decision.descriptor.CounterRef
import ksl.modeling.decision.descriptor.ResponseRef
import ksl.modeling.decision.descriptor.RewardKind
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.decision.descriptor.SourceRef
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TimeWeightedIfc

/**
 *  §4.2.5. A reward term is a **rate times an accumulated quantity, differenced between epochs**,
 *  and this file is the whole of that (M1 step 5b).
 *
 *  Two things it settles that the specification got wrong for several revisions.
 *
 *  **What "accumulated" reads is not one accessor.** §4.2.5 said all three kinds read `weightedSum`
 *  from the source's within-replication statistic and differed only in what the weights mean. True
 *  of `Response` and `TWResponse`; false of `Counter`, which has no within-replication statistic at
 *  all — `CounterCIfc` is `ValueIfc` plus `acrossReplicationStatistic`, and the running total is
 *  `value`. [RewardSourceCIfc.accumulated] is the abstraction that makes the difference invisible
 *  downstream; `RewardSourceAccessorTest` pins all three against real objects.
 *
 *  **The declaration cannot name a source that has no accumulation.** The DSL used to take a
 *  `ResponseIfc`, which carries neither `withinReplicationStatistic` nor a `Counter`'s total — the
 *  same class of mistake as §8.1.1's defect 2, where the design assumed `ResponseIfc` was a
 *  `GetValueIfc`. It now takes a [ResponseCIfc] or a [CounterCIfc], so a source that cannot answer
 *  is refused by the compiler rather than at construction.
 */
internal class RewardDecl(
    val name: String,
    val kind: RewardKind,
    /**
     *  The declared rate with its **sign already applied** (§4.2.5). A `COST` is negated once,
     *  here, so that the element, capture and comparison all deal in reward, maximized, and a
     *  mixed declaration of costs and revenues combines without the modeler tracking signs.
     */
    val signedRate: Double,
    val declaredRate: Double,
    val sense: RewardSense,
    val source: RewardSourceCIfc,
    val sourceRef: SourceRef
)

/**
 *  The reward half of §4.10.2's epoch loop: one read per epoch, serving both the interval that
 *  closed and the interval about to open.
 *
 *  The baseline is **the state of the accumulation at the previous epoch**, and it can be invalid —
 *  at the first epoch of an episode, and after a warm-up discards it (§4.6.4). An invalid baseline
 *  is not zero: there is no measurable interval, which is a different fact from an interval whose
 *  reward happened to be zero, and [closeInterval] returns `null` rather than `0.0` so the caller
 *  can tell them apart. §4.10.2 step 4 discards a transition on exactly that distinction.
 */
internal class RewardBinding(val decls: List<RewardDecl>) {

    private var baseline: DoubleArray? = null

    val hasBaseline: Boolean get() = baseline != null

    /** §4.6.4. Called from `warmUp()`; the next epoch takes a fresh baseline and reports nothing. */
    fun invalidate() {
        baseline = null
    }

    /**
     *  §4.10.2 step 2. Read every source **once**, difference against the baseline, and adopt the
     *  same read as the new baseline.
     *
     *  One read, not two: the interval that just ended and the interval about to begin share one
     *  boundary, and reading twice would let them disagree — a source backed by a computed lambda
     *  need not be pure.
     *
     *  @return the interval's reward, or `null` if there was no baseline to difference against.
     */
    fun closeInterval(): Double? {
        val now = DoubleArray(decls.size) { decls[it].source.accumulated() }
        val previous = baseline
        baseline = now
        if (previous == null) return null
        var total = 0.0
        for (i in decls.indices) total += decls[i].signedRate * (now[i] - previous[i])
        return total
    }
}

/**
 *  Adapt a KSL response or counter to the one thing a reward needs of it.
 *
 *  The `when` is the only place in the design that knows the three sources accumulate differently,
 *  which is the point of having [RewardSourceCIfc] at all.
 */
internal fun rewardSourceFor(source: Any, now: () -> Double): RewardSourceCIfc = when (source) {
    // Checked before ResponseCIfc: a TWResponse is one, and its weights are durations.
    is TimeWeightedIfc -> object : RewardSourceCIfc {
        override val name: String = source.name
        override fun value(): Double = source.value

        /**
         *  **The area banked so far, plus the area still in flight.**
         *
         *  `TimeWeightedStatistic.collect(obs, time)` banks the previous value's area only when a
         *  NEW value arrives, so `weightedSum` lags by whatever has accrued since `timeOfChange`.
         *  For a quantity that is read every epoch and changes rarely — on-hand inventory, a
         *  capacity, a queue that is empty for a shift — the lag is the whole interval, and every
         *  reward would be reported as zero until the value happened to move.
         *
         *  §4.10.4's timeline matrix found this on its first run, and it is the fourth time an
         *  assumption about a KSL statistic has survived review and failed a test (§8.1.1). Worth
         *  noting how narrowly `RewardSourceAccessorTest` missed it: that test read `accumulated()`
         *  after the replication ended, by which point KSL has flushed the final area, so it
         *  asserted the right numbers for the wrong reason. It now reads mid-run.
         */
        override fun accumulated(): Double =
            (source as ResponseCIfc).withinReplicationStatistic.weightedSum +
                source.value * (now() - source.timeOfChange)
    }
    is CounterCIfc -> object : RewardSourceCIfc {
        override val name: String = source.name
        override fun value(): Double = source.value
        // A Counter has NO within-replication statistic. Its running total IS its value.
        override fun accumulated(): Double = source.value
    }
    is ResponseCIfc -> object : RewardSourceCIfc {
        override val name: String = source.name
        override fun value(): Double = source.value
        // Unit weights, so weightedSum is the plain sum of the values observed.
        override fun accumulated(): Double = source.withinReplicationStatistic.weightedSum
    }
    else -> throw RewardKindException(
        "'$source' is not a reward source. A reward accumulates over a Response, a TWResponse, " +
            "or a Counter (§4.2.5)."
    )
}

/** Which accumulation the source actually provides (§4.1.2.1: the kind is inferred, not chosen). */
internal fun inferRewardKind(source: Any): RewardKind = when (source) {
    is TimeWeightedIfc -> RewardKind.TIME_INTEGRAL
    is CounterCIfc -> RewardKind.COUNTER_TOTAL
    is ResponseCIfc -> RewardKind.OBSERVATION_SUM
    else -> throw RewardKindException("'$source' is not a reward source (§4.2.5).")
}

internal fun sourceRefFor(source: Any): SourceRef = when (source) {
    is CounterCIfc -> CounterRef(source.name)
    is ResponseCIfc -> ResponseRef(source.name)
    else -> throw RewardKindException("'$source' is not a reward source (§4.2.5).")
}

/**
 *  §4.2.5: *"Declaring `TIME_INTEGRAL` against a plain `Response` fails at construction, naming
 *  both the declared kind and what was found."*
 *
 *  The kind is inferred when it is not stated, so this fires only when a modeler states one — which
 *  is the only way it can be wrong. Stating it is worth allowing: it turns an assumption about a
 *  source's type into a claim the build checks, and a source swapped from a `TWResponse` to a
 *  `Response` in the model then fails here rather than producing a reward curve in the wrong units.
 */
internal fun checkRewardKind(declared: RewardKind?, source: Any, name: String) {
    val found = inferRewardKind(source)
    if (declared != null && declared != found) {
        throw RewardKindException(
            "Reward term '$name' declares $declared, but '${(source as? ResponseCIfc)?.name ?: name}' " +
                "is a ${source::class.simpleName}, whose accumulation is $found. " +
                (if (declared == RewardKind.TIME_INTEGRAL)
                    "A time integral needs a TWResponse: a plain Response has no duration weights, " +
                        "so the rate would be per observation while the declaration says per unit time. "
                else "") +
                "Omit the kind to take the one the source provides (§4.2.5)."
        )
    }
}
