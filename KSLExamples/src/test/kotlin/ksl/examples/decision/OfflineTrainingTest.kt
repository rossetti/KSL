package ksl.examples.decision

import ksl.modeling.decision.descriptor.LeverDomain
import ksl.sdm.capture.TrajectoryFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  The acceptance criterion for the off-line training path: **run → capture → read back with no
 *  live model → fit a rule → run the fitted rule → beat the do-nothing arm.**
 *
 *  Without this, the durable sink is a set of file writers nobody has shown to be useful. With it,
 *  "you can train off-line from captured decision processing" stops being an aspiration.
 *
 *  It is also the only test that exercises `TransitionRecord` against its *real* consumer — a
 *  learner reading rows out of a file — which was the argument for building capture before any
 *  release. It has already earned that: see
 *  [theFittedRuleIsRunnableBecauseTheLearnerAskedForTheDomain].
 */
class OfflineTrainingTest {

    private val demo by lazy { runOfflineTrainingDemo() }

    /** The criterion, stated plainly, with the margin checked against the confidence intervals. */
    @Test
    fun aRuleFittedFromCapturedRowsBeatsTheDoNothingArm() {
        val learned = demo.scores.keys.first { it.startsWith("learned") }
        val margin = demo.scores.getValue(learned) - demo.scores.getValue("do nothing")
        val noise = demo.halfWidths.getValue(learned) + demo.halfWidths.getValue("do nothing")

        println()
        demo.scores.forEach { (k, v) -> println("%-22s %14.2f ± %.2f".format(k, v, demo.halfWidths.getValue(k))) }
        println("margin over do-nothing: %.0f against a combined half-width of %.0f".format(margin, noise))

        assertTrue(margin > noise,
            "the fitted rule beat doing nothing by $margin, which is inside the combined " +
                "confidence half-width of $noise — that is not a result")
    }

    /**
     *  **The finding this test exists to have made.**
     *
     *  The first version of the learner returned a bucket midpoint of 7.5. The rule then ordered
     *  `7.5 − position` against an INTEGER lever and the model refused the epoch outright:
     *  *"'OrderQty' = 0.5 units is not integral, but the lever's domain is INTEGER."* The refusal is
     *  the design working — an infeasible action writes no lever — and it is the sharpest argument
     *  for writing the provenance beside the rows: a learner that cannot see the domain will fit a
     *  rule the model will not run.
     *
     *  The learner now asks. This asserts that it does.
     */
    @Test
    fun theFittedRuleIsRunnableBecauseTheLearnerAskedForTheDomain() {
        val f = demo.fitted
        println()
        println("fitted order-up-to = ${f.orderUpTo}, lever domain = ${f.domain}")

        assertEquals(LeverDomain.INTEGER, f.domain,
            "this model's order quantity is declared over an IntRange; if that changes, the " +
                "rounding below stops being the right behaviour and this test should notice")
        assertEquals(f.orderUpTo, Math.rint(f.orderUpTo),
            "the fitted level must be a whole number, because the lever's declared domain says so " +
                "— and a level of 7.5 is what crashed the run that motivated this assertion")
        assertTrue(f.orderUpTo > 0.0, "a fitted level of zero would order nothing, ever")
    }

    /** The evidence brackets an optimum, so the fit is a choice rather than an edge. */
    @Test
    fun theEvidenceBracketsAnOptimumRatherThanRunningOffTheEnd() {
        val evidence = demo.fitted.evidence
        val best = evidence.first().first
        val positions = evidence.map { it.first }.sorted()

        println()
        evidence.take(5).forEach { (bucket, reward, n) ->
            println("  order up to %6.1f  mean reward %10.2f  (%d rows)".format(bucket, reward, n))
        }

        assertTrue(best > positions.first() && best < positions.last(),
            "the best bucket $best is at an edge of $positions, so the exploration did not " +
                "bracket an optimum and the fitted level is only 'the best of what was tried'")
        assertTrue(demo.fitted.bucketsConsidered >= 3,
            "only ${demo.fitted.bucketsConsidered} buckets survived the minimum-rows filter; " +
                "there is not enough evidence here to call anything a fit")
    }

    /**
     *  The learner is off-line **structurally**, not by convention: its whole input is a path.
     *
     *  Checked by calling it again, in this test, on the file the demonstration left behind — with
     *  no `Model` anywhere in scope — and getting the same answer.
     */
    @Test
    fun theLearnerNeedsNothingButTheFile() {
        val dir = Files.createTempDirectory("ksl-offline-independent")
        val fresh = runOfflineTrainingDemo(dir)
        val rowsPath = dir.resolve("explore.sqlite")

        assertTrue(Files.exists(rowsPath), "the demonstration must leave its trajectory on disk")
        assertTrue(Files.exists(dir.resolve("explore.provenance.json")), "and its provenance")

        // No model, no element, no simulation. A path.
        val again = learnOrderUpToLevel(rowsPath)
        println()
        println("re-fitted from the file alone: ${again.orderUpTo} (demo got ${fresh.fitted.orderUpTo})")
        assertEquals(fresh.fitted.orderUpTo, again.orderUpTo,
            "fitting the same file twice must give the same rule; if it does not, the learner is " +
                "reading something other than the file")

        // And the file really is self-describing.
        TrajectoryFile(rowsPath).use { t ->
            assertEquals("Room:Review", t.provenance.elementName)
            assertTrue(t.rowCount > 0)
        }
    }

    /** A trajectory the learner cannot interpret must stop it, not be guessed at. */
    @Test
    fun theLearnerRefusesATrajectoryItCannotInterpret() {
        val dir = Files.createTempDirectory("ksl-offline-orphan")
        runOfflineTrainingDemo(dir)
        Files.delete(dir.resolve("explore.provenance.json"))

        val t = assertFailsWith<IllegalArgumentException> {
            learnOrderUpToLevel(dir.resolve("explore.sqlite"))
        }
        println()
        println("without provenance: ${t.message?.take(120)}")
        assertTrue(t.message!!.contains("provenance"))
    }
}
