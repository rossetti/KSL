package ksl.examples.decision.tutorial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Part IV's claims, checked.
 *
 *  The part is built on one A/B — the same model, the same rule, two declarations — so the
 *  first two tests are the two halves of it. If either stopped holding, the part would be
 *  teaching something that is no longer true.
 */
class DepotWalkthroughTest {

    private companion object {
        val demo: DepotWalkthroughResult = runDepotWalkthrough(reps = 20)
    }

    /**
     *  Under the old declaration the element cannot see the state-dependent constraint, so
     *  an over-shipment reaches the model and the model absorbs it silently.
     */
    @Test
    fun theOldDeclarationLetsTheModelAbsorbImpossibleActions() {
        println()
        println("over-shipments absorbed: ${demo.overShipmentsUnderOldDeclaration}")
        assertTrue(demo.overShipmentsUnderOldDeclaration > 50,
            "the blind rule must actually over-ship many times, or the A/B has nothing in " +
                "it: ${demo.overShipmentsUnderOldDeclaration}")
    }

    /**
     *  Under the §4.4.6 declaration the same rule is refused, by name, before any lever is
     *  written — and the refusal cites the *state-dependent* total, not the constant truck.
     *  That distinction is the whole point: the truck bound is declarable under both
     *  designs, so a violation of it would prove nothing.
     */
    @Test
    fun theStateDependentDeclarationRefusesTheSameRuleInstead() {
        println()
        println("refusal: ${demo.refusalUnderNewDeclaration}")
        assertEquals("ActionValidationException", demo.refusalUnderNewDeclaration,
            "the element must refuse rather than let the model defend itself")
    }

    /** The cost function approximation beats the policy function approximation, and by how much. */
    @Test
    fun theCandidateScoringRuleBeatsTheDirectlyComputedOne() {
        val nothing = demo.costs.getValue("ship nothing")
        val pfa = demo.costs.getValue("proportional (PFA)")
        val cfa = demo.costs.getValue("greedy by cost (CFA)")
        println()
        println("nothing=%.2f  PFA=%.2f  CFA=%.2f  (CFA better by %.1f%%)".format(
            nothing, pfa, cfa, demo.cfaBeatsPfaByPercent))

        assertTrue(nothing > pfa * 10,
            "the do-nothing arm must be catastrophically worse, or the comparison among the " +
                "real rules is not the interesting one: $nothing vs $pfa")
        assertTrue(cfa < pfa,
            "serving the expensive region first must beat a proportional share, because the " +
                "shortage rates are 9/3/1 and a proportional share has nowhere to put them: " +
                "$cfa vs $pfa")
        assertTrue(demo.cfaBeatsPfaByPercent > 3.0,
            "the margin should be worth reporting, not noise: ${demo.cfaBeatsPfaByPercent}%")
    }

    /**
     *  Membership and violation are one predicate.
     *
     *  A rule that asked "is this legal?" and got one answer, then asked "what is wrong
     *  with it?" and got another, would contradict itself in front of a user.
     */
    @Test
    fun membershipAndViolationNeverDisagree() {
        println()
        println("${demo.feasibleSetProbes} probes, ${demo.feasibleSetDisagreements} disagreements")
        assertTrue(demo.feasibleSetProbes > 500,
            "the probe must run enough epochs to mean anything: ${demo.feasibleSetProbes}")
        assertEquals(0, demo.feasibleSetDisagreements,
            "`contains` and `violations` are one predicate asked two ways")
    }
}
