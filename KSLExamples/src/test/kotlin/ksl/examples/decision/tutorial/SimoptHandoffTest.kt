package ksl.examples.decision.tutorial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Part VI's claims, checked — including the two that are traps rather than results.
 *
 *  The part exists to show that a parameterized rule needs **no adapter** to reach
 *  `ksl.simopt`: its parameters are ordinary controls and its objective is an ordinary
 *  `Response`. The tests below check that this is literally true rather than nearly true,
 *  and they check the two ways a reader could get the right pipeline and the wrong answer.
 */
class SimoptHandoffTest {

    private companion object {
        val demo: SimoptHandoffResult = runSimoptHandoffDemo(
            smallBudget = 30, largeBudget = 120, replicationsPerEvaluation = 20)
    }

    /**
     *  The rule's parameters and the element's timing are all discovered by the ordinary
     *  control walk. Nothing in `ksl.modeling.decision` participates.
     */
    @Test
    fun theRulesParametersAreOrdinaryModelControls() {
        println()
        println("controls: ${demo.controlKeys}")
        assertEquals(
            listOf("OrderRule.s", "OrderRule.sDelta", "Room:Review.epochInterval", "Room:Review.maxEpochs"),
            demo.controlKeys,
            "the rule's two parameters must appear as controls keyed elementName.propertyName, " +
                "and so must the element's own timing — WHEN to decide is a parameter too"
        )
    }

    /**
     *  The hand grid brackets an optimum in `s`.
     *
     *  Part II teaches this check and Part VI reuses it, so the grid must actually pass it.
     *  A grid whose best point sat at an endpoint would make the later comparison against
     *  the search meaningless — "better than everything tried" is not "better than the grid".
     */
    @Test
    fun theHandGridBracketsAnOptimum() {
        println()
        println("grid best at s=${demo.gridBestS}, estimand ${demo.gridBestScore}")
        assertTrue(demo.gridBracketsAnOptimum,
            "the swept grid must have its best at an INTERIOR point, or Part VI compares the " +
                "search against an edge rather than against an optimum")
    }

    /** Every rule beats doing nothing, and not by a little: that arm diverges. */
    @Test
    fun everyOrderingRuleBeatsDoingNothing() {
        println()
        println("do nothing = %.0f; grid best = %.0f; search = %.0f".format(
            demo.doNothingScore, demo.gridBestScore, demo.largeBudgetScore))
        assertTrue(demo.doNothingScore < demo.gridBestScore * 100,
            "with nothing ever ordered, backorders accumulate without bound, so that arm " +
                "should not merely lose — it should diverge: ${demo.doNothingScore}")
    }

    /**
     *  **Effort is the currency.** The small-budget search does not beat a well-chosen grid;
     *  the larger-budget one does. That ordering is the lesson of Part VI, and it is the
     *  same lesson the simopt tutorial teaches with Rosenbrock.
     */
    @Test
    fun moreBudgetFindsABetterRuleAndTheSmallBudgetDoesNotBeatTheGrid() {
        println()
        println("grid  = %.1f".format(demo.gridBestScore))
        println("30 it = %.1f".format(demo.smallBudgetScore))
        println("120 it= %.1f  at (s=%.0f, S=%.0f)".format(
            demo.largeBudgetScore, demo.largeBudgetS, demo.largeBudgetLevel))

        assertTrue(demo.largeBudgetScore > demo.smallBudgetScore,
            "four times the budget must find a better rule, or the part's claim about effort " +
                "is not supported by its own numbers")
        assertTrue(demo.largeBudgetScore > demo.gridBestScore,
            "given enough budget the search must beat the hand grid — it varies both " +
                "parameters and the grid held one fixed")
        assertTrue(demo.smallBudgetScore < demo.gridBestScore,
            "and on the SMALL budget it must NOT beat the grid. If it did, Part VI's honest " +
                "moment would be a fiction and the reader would learn the wrong lesson")
    }

    /**
     *  The search lands in the region a 35-point exhaustive scan identified: `s` near 8 and
     *  an order-up-to level near 10. Checked because "it improved" is compatible with
     *  "it improved toward the wrong place".
     */
    @Test
    fun theSearchLandsWhereAnExhaustiveScanSaysTheOptimumIs() {
        println()
        println("found s=%.0f, S=%.0f".format(demo.largeBudgetS, demo.largeBudgetLevel))
        assertTrue(demo.largeBudgetS in 4.0..12.0,
            "a scan over s in 0..20 puts the optimum near 8; the search found ${demo.largeBudgetS}")
        assertTrue(demo.largeBudgetLevel in 6.0..16.0,
            "and the order-up-to level near 10; the search found ${demo.largeBudgetLevel}")
        assertTrue(demo.largeBudgetLevel >= demo.largeBudgetS,
            "S = s + sDelta with sDelta >= 0, so the level can never fall below the reorder " +
                "point. That is the reparameterization doing its job, and it is the reason " +
                "the search box contains no meaningless corners")
    }
}
