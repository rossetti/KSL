package ksl.modeling.decision

import ksl.modeling.decision.descriptor.TerminationSource

/**
 *  STUB — Appendix E.2, §4.8.3. The atom of recorded experience.
 *
 *  It lives here, with the element that PRODUCES it, rather than in ksl.sdm.capture,
 *  which consumes it. ManagedPolicyIfc.onTransition takes one, and a policy interface
 *  cannot depend on the capture package without inverting the layering of E.1.
 */
data class TransitionRecord(
    val replicationId: Int,
    val epochIndex: Int,
    val time: Double,
    val tau: Double,
    val state: DoubleArray,
    val action: DoubleArray,
    val reward: Double,
    val successorState: DoubleArray,
    val terminated: Boolean,
    val truncated: Boolean,
    val source: TerminationSource? = null
)

