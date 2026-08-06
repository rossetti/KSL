package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.decision.descriptor.FeasibilityPolicy
import ksl.simulation.ModelElement

/** Compile-only checks of signatures asserted in the proposal. */
@Suppress("unused")
private class Checks(parent: ModelElement) {

    private val e = parent.decisionElement("Check") { policy = HoldCurrentPolicy }

    // Claim: one way to give the element a policy, whatever the policy needs.
    fun policyAssignment() {
        e.policy = HoldCurrentPolicy
        e.policy = FixedPolicy(doubleArrayOf(4.0, 4.0))   // configure() runs on assignment
    }

    // Claim (§4.1.4 of an earlier draft): a subsystem can re-export the policy by delegation.
    var reviewPolicy: PolicyIfc by e::policy

    // Claim (§4.1.2.2): a LeverRef identifies the lever, not its target.
    fun narrowByRef(r: LeverRef) { e.narrow(r, 1..7) }

    // Claim: parameterization setters are ordinary properties.
    fun parameters() {
        e.epochInterval = 240.0
        e.maxEpochs = 100
        e.feasibilityPolicy = FeasibilityPolicy.CLAMP_THEN_REJECT
    }

    // Claim: HoldCurrentPolicy is an ordinary PolicyIfc, not a special case.
    fun baselineIsAPolicy(ctx: DecisionContext): DoubleArray = HoldCurrentPolicy.action(doubleArrayOf(), ctx)
}
