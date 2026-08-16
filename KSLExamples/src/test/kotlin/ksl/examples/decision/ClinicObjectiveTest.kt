package ksl.examples.decision

import ksl.modeling.decision.FixedPolicy
import ksl.modeling.decision.NeutralPolicy
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.station.StationNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The clinic's **mixed-sense composite objective**, and the check that it means something.
 *
 *  `ClinicSubsystem` ran for the whole of M1 with no estimand at all: it declared observations,
 *  levers and a joint constraint, and nothing said what a good allocation *was*. It now declares a
 *  profit — one `REWARD` term for treated patients and two `COST` terms for waiting — which is the
 *  shape a real objective usually has, and the first place in the examples where `RewardSense.REWARD`
 *  is actually run.
 *
 *  **Declaring an objective is easy; declaring one that discriminates is the part worth testing.**
 *  So this does not stop at "the estimand exists". It checks that the composite ranks staffing
 *  allocations the way an independent measure does: `StaffingPolicyBenchmarkTest` establishes 3/5 as
 *  the M/M/c optimum on mean system time, a quantity this objective never looks at. If the composite
 *  agrees, it is measuring the clinic. If it disagreed, one of the two would be wrong and it would
 *  matter which.
 */
class ClinicObjectiveTest {

    private class Arm(val label: String, val mean: Double, val halfWidth: Double)

    private fun score(label: String, rule: PolicyIfc): Arm {
        val m = Model("ClinicObjective")
        val flow = StationNetwork(m, "ClinicFlow")
        val clinic = ClinicSubsystem(m, exit = flow.sink("Exit"), name = "Clinic")
        flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)
        clinic.shiftReview.policy = rule
        clinic.shiftReview.policyLabel = label
        m.numberOfReplications = 20
        m.lengthOfReplication = 43_200.0
        m.lengthOfReplicationWarmUp = 4_320.0
        m.simulate()
        val s = clinic.shiftReview.estimand.acrossReplicationStatistic
        return Arm(label, s.average, s.halfWidth)
    }

    /**
     *  The composite is a profit, so larger is better — and no arm of this study wrote a minus sign.
     *  The revenue rate is `+25`, both waiting rates are `+10`, and `sense` does the rest.
     */
    @Test
    fun theCompositeObjectiveRanksStaffingAllocationsTheWayAnIndependentMeasureDoes() {
        val arms = listOf(
            score("static 4/4", NeutralPolicy),
            score("3/5", FixedPolicy(doubleArrayOf(3.0, 5.0))),
            score("2/6", FixedPolicy(doubleArrayOf(2.0, 6.0)))
        )
        println()
        println("clinic profit = revenue(treated) − waiting; larger is better")
        arms.forEach { println("  %-14s %14.1f ± %.1f".format(it.label, it.mean, it.halfWidth)) }

        val static44 = arms.first { it.label == "static 4/4" }
        val best = arms.first { it.label == "3/5" }
        val overCut = arms.first { it.label == "2/6" }

        assertTrue(best.mean - static44.mean > best.halfWidth + static44.halfWidth,
            "3/5 is the M/M/c optimum that StaffingPolicyBenchmarkTest identifies on mean system " +
                "time — a quantity this objective never reads. The composite must see it too, by " +
                "more than the intervals: ${best.mean} vs ${static44.mean}")
        assertTrue(static44.mean - overCut.mean > static44.halfWidth + overCut.halfWidth,
            "cutting triage to 2 starves the first station and must be visibly worse than leaving " +
                "the staff alone: ${overCut.mean} vs ${static44.mean}")
        assertTrue(overCut.mean < 0.0,
            "the over-cut arm should be so bad that waiting outweighs revenue and the profit goes " +
                "NEGATIVE — a composite that stayed positive everywhere would be revenue wearing " +
                "a costume")
    }

    /** The declaration is mixed-sense, and the descriptor says so in the modeler's own numbers. */
    @Test
    fun theObjectiveIsDeclaredWithPositiveRatesAndASenseEach() {
        val m = Model("ClinicSurface")
        val flow = StationNetwork(m, "ClinicFlow")
        val clinic = ClinicSubsystem(m, exit = flow.sink("Exit"), name = "Clinic")
        flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)

        val rewards = clinic.shiftReview.descriptor().rewards
        println()
        rewards.forEach { println("  ${it.name}: rate=${it.rate} sense=${it.sense} kind=${it.kind}") }

        assertEquals(3, rewards.size, "one revenue and two waiting charges")
        assertEquals(1, rewards.count { it.sense == RewardSense.REWARD })
        assertEquals(2, rewards.count { it.sense == RewardSense.COST })
        assertTrue(rewards.all { it.rate > 0.0 },
            "every rate is written as a positive number in the units the modeler thinks in; the " +
                "sense carries the direction, which is the whole point of having one")
    }
}
