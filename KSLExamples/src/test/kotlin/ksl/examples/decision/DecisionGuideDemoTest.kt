package ksl.examples.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  M1 acceptance criteria 5, 7 and 8 — the demonstration must *perform the behavior its
 *  documentation claims*, construct the element idiomatically, and show sink lifetime.
 *
 *  The demonstration prints its findings, and printing is not evidence. This test runs it and
 *  checks the four claims it makes, so that a change which quietly breaks one of them fails the
 *  build instead of producing a slightly different wall of text that nobody rereads.
 */
class DecisionGuideDemoTest {

    private val demo by lazy { runDecisionGuideDemo() }

    /** Claim: the element derives a description matching what the model declared. */
    @Test
    fun theDescriptionMatchesWhatTheModelDeclared() {
        assertEquals(listOf("Room:Position", "Room:Backorders"), demo.observationNames,
            "declaration order is vector order, so this list IS the meaning of observation[i]. " +
                "If it changes, the (s, S) rule reading observation[0] reads something else")
        assertEquals(listOf("OrderQty"), demo.leverNames)
    }

    /**
     *  Claim: every (s, S) rule beats doing nothing.
     *
     *  This is the comparison the guide tells a reader to make first, so the demonstration has to
     *  survive it. The estimand is a COST negated once at declaration, so larger is better.
     */
    @Test
    fun everyOrderingRuleBeatsTheDoNothingArm() {
        val doNothing = demo.scores.getValue("do nothing")
        val rules = demo.scores.filterKeys { it != "do nothing" }

        println()
        println("do nothing: %.3f".format(doNothing))
        rules.forEach { (k, v) -> println("%-12s %.3f  (%+.3f)".format(k, v, v - doNothing)) }

        assertTrue(rules.isNotEmpty(), "the study ran no rules besides the control")
        assertTrue(rules.values.all { it > doNothing },
            "an ordering rule lost to NeutralPolicy. Either the demonstration's rules are wrong " +
                "or the estimand's sign is, and both are exactly what the do-nothing arm exists " +
                "to expose: $rules against $doNothing")
    }

    /**
     *  Claim: the grid brackets an optimum, and the ranking is real rather than noise.
     *
     *  The demonstration says out loud whether its best arm is interior or at an edge, and an edge
     *  result reduces its conclusion to "better than everything tried". That is honest but it is
     *  not a comparison worth teaching from, so the demonstration is required to bracket — and the
     *  winner is required to beat both neighbours by more than the two confidence half-widths
     *  together, or the ordering it prints is noise wearing a table.
     */
    @Test
    fun theBestRuleIsAnInteriorPointAndItsMarginSurvivesTheConfidenceIntervals() {
        val arms = demo.orderingArms
        val best = arms.maxByOrNull { demo.scores.getValue(it) }!!
        val i = arms.indexOf(best)
        println()
        arms.forEach { println("%-10s %12.1f ± %.1f%s".format(
            it, demo.scores.getValue(it), demo.halfWidths.getValue(it), if (it == best) "   <- best" else "")) }

        assertTrue(i > 0 && i < arms.size - 1,
            "the best arm '$best' is at an edge of the grid $arms, so the grid does not bracket " +
                "an optimum and the demonstration cannot claim more than 'better than everything " +
                "tried'. Widen the grid past it")

        for (neighbour in listOf(arms[i - 1], arms[i + 1])) {
            val margin = demo.scores.getValue(best) - demo.scores.getValue(neighbour)
            val noise = demo.halfWidths.getValue(best) + demo.halfWidths.getValue(neighbour)
            println("  '%s' beats '%s' by %.1f against a combined half-width of %.1f".format(
                best, neighbour, margin, noise))
            assertTrue(margin > noise,
                "'$best' beats '$neighbour' by $margin, which is inside the combined confidence " +
                    "half-width of $noise. The demonstration would be teaching a ranking it cannot " +
                    "distinguish from sampling error — run more replications or widen the spacing")
        }
    }

    /**
     *  Claim: the element opens a sink per experiment and closes it per experiment.
     *
     *  The demonstration simulates the same model **twice**, which is the case that matters: two
     *  experiments must produce two opened sinks and two closed ones, and the second run must work
     *  at all — a policy closed at the end of the first experiment would have made it fail.
     */
    @Test
    fun theElementOpensAndClosesOneSinkPerExperiment() {
        println()
        println("sinks opened: ${demo.sinksOpened}, closed: ${demo.sinksClosed}")
        assertEquals(2, demo.sinksOpened,
            "the demonstration runs two experiments, so it must open two sinks — one per " +
                "experiment is the lifetime the element promises")
        assertEquals(demo.sinksOpened, demo.sinksClosed,
            "every sink the element opened must be closed; a sink left open is a file handle a " +
                "study leaks once per experiment")
    }

    /** Claim: the run recorded transitions worth looking at. */
    @Test
    fun theCapturedTrajectoryIsNotEmpty() {
        assertTrue(demo.capturedRows > 0,
            "the demonstration printed a trajectory table with nothing in it, so step 4 " +
                "demonstrates nothing")
    }
}
