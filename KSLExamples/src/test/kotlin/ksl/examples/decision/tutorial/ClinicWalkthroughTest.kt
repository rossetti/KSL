package ksl.examples.decision.tutorial

import ksl.modeling.decision.descriptor.RewardSense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Part III of the tutorial prints claims. A printed claim nobody checks is how a tutorial
 *  starts lying, so each of them is checked here.
 *
 *  The walkthrough is run **once** for the whole class — four arms of a 20-replication
 *  clinic is the expensive part — and the tests read its result.
 */
class ClinicWalkthroughTest {

    private companion object {
        val demo: ClinicWalkthroughResult = runClinicWalkthrough(reps = 20)
    }

    /**
     *  The headline claim of Part III, and the reason the part exists.
     *
     *  The profit is built from patients processed and queue lengths. Mean time in the
     *  system is a quantity it never reads, and `StaffingPolicyBenchmarkTest` establishes
     *  3/5 as the M/M/c optimum on it. If the composite agrees, it is measuring the clinic;
     *  if it disagreed, one of the two would be wrong and it would matter which.
     */
    @Test
    fun theCompositeObjectiveAgreesWithAMeasureItNeverReads() {
        println()
        println("best by profit = ${demo.bestByProfit}; best by system time = ${demo.bestBySystemTime}")
        assertEquals("static 3/5", demo.bestBySystemTime,
            "3/5 is the M/M/c optimum for this clinic; if the independent measure no longer " +
                "says so, the fixture changed and the comparison below means nothing")
        assertEquals(demo.bestBySystemTime, demo.bestByProfit,
            "the profit must rank the arms the way mean system time does. A disagreement is " +
                "a finding, not a formatting problem: one of the two is wrong")
    }

    /** The ranking is by more than noise, or it is not a ranking. */
    @Test
    fun theBestArmBeatsDoingNothingByMoreThanTheIntervals() {
        val best = demo.profits.getValue("static 3/5")
        val bestHw = demo.halfWidths.getValue("static 3/5")
        val idle = demo.profits.getValue("static 4/4")
        val idleHw = demo.halfWidths.getValue("static 4/4")
        println()
        println("3/5 = %.1f ± %.1f, 4/4 = %.1f ± %.1f".format(best, bestHw, idle, idleHw))
        assertTrue(best - idle > bestHw + idleHw,
            "reallocating one server must be visibly better than leaving the staff alone, " +
                "by more than the two intervals together: $best vs $idle")
    }

    /**
     *  The over-cut arm must go NEGATIVE.
     *
     *  A composite that stayed positive everywhere would be revenue wearing a costume — the
     *  waiting charges would be decoration on a number the decision cannot move, since
     *  throughput here is arrival-limited.
     */
    @Test
    fun starvingTriageMakesTheProfitNegative() {
        val overCut = demo.profits.getValue("static 2/6")
        println()
        println("2/6 profit = %.1f".format(overCut))
        assertTrue(overCut < 0.0,
            "cutting triage to 2 should make waiting outweigh revenue: $overCut")
    }

    /** The mixed-sense declaration: positive rates throughout, direction carried by `sense`. */
    @Test
    fun everyRateIsWrittenPositiveAndTheSenseCarriesTheDirection() {
        println()
        demo.rewardTerms.forEach { (n, rate, sense) -> println("  $n rate=$rate sense=$sense") }
        assertEquals(3, demo.rewardTerms.size, "one revenue and two waiting charges")
        assertEquals(1, demo.rewardTerms.count { it.third == RewardSense.REWARD })
        assertEquals(2, demo.rewardTerms.count { it.third == RewardSense.COST })
        assertTrue(demo.rewardTerms.all { it.second > 0.0 },
            "every rate is written as a positive number in the units the modeler thinks in; " +
                "the sense carries the direction, which is the whole point of having one")
    }

    /**
     *  The adaptive rule reaches the static optimum without being told what it is.
     *
     *  This is the claim Part III's failure story rests on — the fixed version of a rule
     *  that once produced a mean system time of 187 against an optimum of 19.
     */
    @Test
    fun theProportionalRuleReachesTheStaticOptimumOnTheIndependentMeasure() {
        val proportional = demo.systemTimes.getValue("proportional")
        val optimum = demo.systemTimes.getValue("static 3/5")
        println()
        println("proportional = %.2f, static optimum = %.2f".format(proportional, optimum))
        assertTrue(proportional - optimum < 1.0,
            "the rule allocating on time-averaged busy units should land essentially on the " +
                "static optimum ($optimum); it reached $proportional. The version that " +
                "allocated on queue length reached 187, which is what the part is about")
    }
}
