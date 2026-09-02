package ksl.modeling.decision

import ksl.modeling.decision.descriptor.EpochProvenance
import ksl.modeling.decision.descriptor.TerminationSource

/**
 *  §4.8.3. The atom of recorded experience.
 *
 *  It lives here, with the element that PRODUCES it, rather than in ksl.modeling.decision.capture,
 *  which consumes it. ManagedPolicyIfc.onTransition takes one, and a policy interface
 *  cannot depend on the capture package without inverting the layering of E.1.
 *
 *  **[action] is what was written to the levers**, which is not always what the rule asked for.
 *  Under `CLAMP_THEN_REJECT` a request outside a lever's bounds is repaired before it is applied,
 *  and a lever whose feasible set is empty takes its declared neutral (§4.4.6.3). In both cases
 *  the rule's own vector is kept in [proposedAction], and [action] carries the values that
 *  actually moved the model. The alternative — recording the request — makes the action column
 *  something that did not cause the reward column beside it, which is wrong in exactly the case a
 *  learner most needs right and gives no sign that it is wrong.
 *
 *  Not a `data class`, deliberately. A generated `equals` compares array properties by
 *  **reference**, so two records with identical contents are unequal and a `Set` keeps both, while
 *  the generated `hashCode` content-hashes the same arrays — the two halves disagree about what
 *  identity means. Comparison, deduplication and value assertions all need content equality, so
 *  both are written out below.
 */
class TransitionRecord(
    /**
     *  Which element produced the row. A sink instance can serve several decision elements
     *  (§4.1.9) — `captureTo { sink }` over a captured variable is the ordinary idiom — and
     *  without this their rows interleave with nothing to tell them apart.
     */
    val elementName: String,
    val replicationId: Int,
    val epochIndex: Int,
    /** The successor observation's time: the END of the interval this row covers. */
    val time: Double,
    /** The interval's duration. Never zero — a zero-length transition is discarded (§4.10.2.1). */
    val tau: Double,
    /** The observation vector the policy was given, in declaration order. */
    val state: DoubleArray,
    /** What was WRITTEN to the levers, in declaration order. */
    val action: DoubleArray,
    /** What the policy returned, iff it differs from [action]. Null means it got what it asked for. */
    val proposedAction: DoubleArray? = null,
    /**
     *  Per lever, in declaration order: was its feasible set empty at the decision, so that it took
     *  its declared neutral rather than anything the rule chose (§4.4.6.3)? Null when every lever
     *  had something to choose from. This is what distinguishes *the rule chose to do nothing* from
     *  *the rule had nothing to choose*.
     */
    val leverUnavailable: BooleanArray? = null,
    /** Accrued over the interval, with COST terms already negated (§4.2.5). */
    val reward: Double,
    /** The observation vector given at the next decision. */
    val successorState: DoubleArray,
    val terminated: Boolean,
    val truncated: Boolean,
    /** WHY it ended, not just that it did (§4.6.3). */
    val source: TerminationSource? = null,
    /**
     *  Why the decision at the START of this interval was taken — the label its caller supplied.
     *
     *  The *starting* epoch's, not the closing one's, because everything else on this row that
     *  describes a decision — [state], [action], [proposedAction] — belongs to that epoch. A row whose
     *  reason described a different decision from its action would be worse than a row with no reason.
     */
    val reason: String = "",
    /**
     *  Where [state] was read: at the caller's call site, or in the element's own deferred event.
     *
     *  Recorded rather than left to be inferred. Under caller-owned timing the interval length is
     *  very nearly a giveaway for which entry point was used, and inference from a near-giveaway is
     *  how a subtle bias enters a learner. A consumer that wants only states the library guaranteed
     *  consistent filters on `DEFERRED`; one that does not, at least knows its dataset is mixed.
     */
    val provenance: EpochProvenance = EpochProvenance.IMMEDIATE
) {
    /** True iff the rule's request was repaired or overridden before it was applied. */
    val wasRepaired: Boolean get() = proposedAction != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransitionRecord) return false
        return elementName == other.elementName &&
            replicationId == other.replicationId &&
            epochIndex == other.epochIndex &&
            time == other.time &&
            tau == other.tau &&
            state.contentEquals(other.state) &&
            action.contentEquals(other.action) &&
            proposedAction.contentEqualsNullable(other.proposedAction) &&
            leverUnavailable.contentEqualsNullable(other.leverUnavailable) &&
            reward == other.reward &&
            successorState.contentEquals(other.successorState) &&
            terminated == other.terminated &&
            truncated == other.truncated &&
            source == other.source &&
            reason == other.reason &&
            provenance == other.provenance
    }

    override fun hashCode(): Int {
        var r = elementName.hashCode()
        r = 31 * r + replicationId
        r = 31 * r + epochIndex
        r = 31 * r + time.hashCode()
        r = 31 * r + tau.hashCode()
        r = 31 * r + state.contentHashCode()
        r = 31 * r + action.contentHashCode()
        r = 31 * r + (proposedAction?.contentHashCode() ?: 0)
        r = 31 * r + (leverUnavailable?.contentHashCode() ?: 0)
        r = 31 * r + reward.hashCode()
        r = 31 * r + successorState.contentHashCode()
        r = 31 * r + terminated.hashCode()
        r = 31 * r + truncated.hashCode()
        r = 31 * r + (source?.hashCode() ?: 0)
        r = 31 * r + reason.hashCode()
        r = 31 * r + provenance.hashCode()
        return r
    }

    override fun toString(): String =
        "TransitionRecord(element=$elementName, rep=$replicationId, epoch=$epochIndex, " +
            "time=$time, tau=$tau, state=${state.toList()}, action=${action.toList()}, " +
            "proposedAction=${proposedAction?.toList()}, " +
            "leverUnavailable=${leverUnavailable?.toList()}, reward=$reward, " +
            "successorState=${successorState.toList()}, terminated=$terminated, " +
            "truncated=$truncated, source=$source, reason=$reason, " +
            "provenance=$provenance)"
}

private fun DoubleArray?.contentEqualsNullable(other: DoubleArray?): Boolean =
    if (this == null || other == null) this == null && other == null else this.contentEquals(other)

private fun BooleanArray?.contentEqualsNullable(other: BooleanArray?): Boolean =
    if (this == null || other == null) this == null && other == null else this.contentEquals(other)
