/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.moda

import ksl.utilities.moda.AggregationMethod
import ksl.utilities.moda.ModaWarning
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

/**
 *  Tests for running a written-down study end to end.
 *
 *  The study either produces a result that means what it says, or produces no result and an
 *  explanation. Most of what is checked here is the second half of that: the ways a study can fail
 *  to be runnable all have to stop it before any numbers exist, because numbers get believed.
 */
class ModaRunnerTest {

    private val directory: Path = createTempDirectory("moda-runner")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun inlineDocument(): ModaDocument = ModaDocument(
        name = "Siting",
        metrics = listOf(
            MetricSpec("Cost", weight = 2.0, upperLimit = 100.0),
            MetricSpec("Delay", weight = 1.0, upperLimit = 60.0)
        ),
        alternatives = listOf("North", "South", "East"),
        source = ModaSourceReference.InlineScores(
            mapOf(
                "North" to mapOf("Cost" to 20.0, "Delay" to 50.0),
                "South" to mapOf("Cost" to 50.0, "Delay" to 30.0),
                "East" to mapOf("Cost" to 80.0, "Delay" to 10.0)
            )
        )
    )

    // ------------------------------------------------------------------------------------------
    // A study that runs
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a sound study produces a result over every alternative")
    fun aSoundStudyProducesAResultOverEveryAlternative() {
        val result = ModaRunner().run(inlineDocument())
        val completed = assertIs<ModaRunResult.Completed>(result, "the study did not run: $result")
        assertTrue(completed.allAlternativesIncluded)
        assertContentEquals(listOf("North", "South", "East"), completed.snapshot.alternatives)
        assertContentEquals(listOf("Cost", "Delay"), completed.snapshot.metrics.map { it.name })
        assertTrue(completed.snapshot.primaryRecommendation in completed.snapshot.alternatives)
        for (alternative in completed.snapshot.alternatives) {
            assertTrue(completed.snapshot.overallValues[alternative]!!.isFinite())
        }
    }

    /**
     *  A model matches metrics by identity, so a study that built its metrics more than once would
     *  quietly lose every alternative. Running the same document twice and getting the same answer
     *  is what shows the metrics used for the scores are the ones the model holds.
     */
    @Test
    @DisplayName("running the same study twice gives the same answer")
    fun runningTheSameStudyTwiceGivesTheSameAnswer() {
        val runner = ModaRunner()
        val first = assertIs<ModaRunResult.Completed>(runner.run(inlineDocument()))
        val second = assertIs<ModaRunResult.Completed>(runner.run(inlineDocument()))
        assertEquals(first.snapshot, second.snapshot)
        assertTrue(first.snapshot.alternatives.isNotEmpty(), "no alternatives survived, so this proves nothing")
    }

    @Test
    @DisplayName("the weights the study declares are the weights it uses")
    fun theWeightsTheStudyDeclaresAreTheWeightsItUses() {
        val completed = assertIs<ModaRunResult.Completed>(ModaRunner().run(inlineDocument()))
        assertEquals(2.0 / 3.0, completed.snapshot.metric("Cost")!!.weight, 1.0e-12)
        assertEquals(1.0 / 3.0, completed.snapshot.metric("Delay")!!.weight, 1.0e-12)
    }

    @Test
    @DisplayName("the study's chosen methods are the ones recorded")
    fun theStudySChosenMethodsAreTheOnesRecorded() {
        val document = inlineDocument().copy(
            rankingMethod = "Fractional",
            aggregationMethod = "FIRST_RANK_COUNT"
        )
        val completed = assertIs<ModaRunResult.Completed>(ModaRunner().run(document))
        assertEquals("Fractional", completed.snapshot.rankingMethod)
        assertEquals(AggregationMethod.FIRST_RANK_COUNT, completed.snapshot.aggregationMethod)
    }

    @Test
    @DisplayName("holding the limits as declared leaves them unfitted")
    fun holdingTheLimitsAsDeclaredLeavesThemUnfitted() {
        val document = inlineDocument().copy(rescalePolicy = RescalePolicy.NONE)
        val completed = assertIs<ModaRunResult.Completed>(ModaRunner().run(document))
        val cost = completed.snapshot.metric("Cost")!!
        assertTrue(!cost.domainWasRescaled, "the limits were fitted under a policy that forbids it")
        assertEquals(0.0, cost.effectiveLowerLimit)
        assertEquals(100.0, cost.effectiveUpperLimit)
    }

    /**
     *  Fitting moves the limits to sit around the scores that turned up, and with few alternatives
     *  it deliberately leaves room beyond them, on the grounds that three observations are poor
     *  evidence of where the true extremes lie. So a fitted range can be wider than the declared
     *  one rather than narrower, and the declared range is a starting point rather than a bound.
     *  What has to hold is that the range used contains every score it is used on.
     */
    @Test
    @DisplayName("fitting the limits moves them to sit around the scores")
    fun fittingTheLimitsMovesThemToSitAroundTheScores() {
        // Declared wider than the scores reach. Where the declared limits are tight enough that the
        // fitting would reach past them, they hold it back instead, which is covered where the
        // fitting itself is tested.
        val roomy = inlineDocument().copy(
            metrics = listOf(
                MetricSpec("Cost", weight = 2.0, upperLimit = 1000.0),
                MetricSpec("Delay", weight = 1.0, upperLimit = 1000.0)
            )
        )
        val completed = assertIs<ModaRunResult.Completed>(ModaRunner().run(roomy))
        val cost = completed.snapshot.metric("Cost")!!
        assertTrue(cost.domainWasRescaled, "the limits were not fitted under the fitting policy")
        assertTrue(
            cost.effectiveLowerLimit != 0.0 || cost.effectiveUpperLimit != 1000.0,
            "the fitted limits are the declared ones"
        )
        val realized = completed.snapshot.alternatives.map { completed.snapshot.scores[it]!!["Cost"]!! }
        for (score in realized) {
            assertTrue(
                score >= cost.effectiveLowerLimit && score <= cost.effectiveUpperLimit,
                "the range used excludes a score it was used on: $score not in " +
                        "[${cost.effectiveLowerLimit}, ${cost.effectiveUpperLimit}]"
            )
        }
        for (alternative in completed.snapshot.alternatives) {
            val value = completed.snapshot.values[alternative]!!["Cost"]!!
            assertTrue(value in 0.0..1.0, "a value fell outside its range: $value")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Studies that do not run
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a study naming a value function nobody supplied does not run")
    fun aStudyNamingAValueFunctionNobodySuppliedDoesNotRun() {
        val document = inlineDocument().copy(
            metrics = listOf(
                MetricSpec("Cost", valueFunctionId = "sigmoid", upperLimit = 100.0),
                MetricSpec("Delay", upperLimit = 60.0)
            )
        )
        val invalid = assertIs<ModaRunResult.Invalid>(ModaRunner().run(document))
        assertTrue(invalid.errors.any { it.message.contains("sigmoid") }, "${invalid.errors}")
        assertTrue(
            invalid.errors.any { it.message.contains(ValueFunctionRegistry.LINEAR) },
            "the error does not say what could have been named"
        )
    }

    @Test
    @DisplayName("a study whose data cannot be reached does not run")
    fun aStudyWhoseDataCannotBeReachedDoesNotRun() {
        val document = inlineDocument().copy(
            source = ModaSourceReference.DelimitedFile(
                directory.resolve("absent.csv").toString(), "alternative", listOf("Cost", "Delay")
            )
        )
        val invalid = assertIs<ModaRunResult.Invalid>(ModaRunner().run(document))
        assertTrue(invalid.errors.any { it.element == "source" }, "${invalid.errors}")
    }

    /**
     *  An alternative missing a score cannot be compared with one that has them all, so it is left
     *  out. If that leaves too few to compare, the study has not produced a result and must not
     *  look as though it has.
     */
    @Test
    @DisplayName("a study left with too few alternatives to compare does not produce a result")
    fun aStudyLeftWithTooFewAlternativesToCompareDoesNotProduceAResult() {
        val document = inlineDocument().copy(
            alternatives = listOf("North", "South"),
            source = ModaSourceReference.InlineScores(
                mapOf(
                    "North" to mapOf("Cost" to 20.0, "Delay" to 50.0),
                    "South" to mapOf("Cost" to 50.0)
                )
            )
        )
        val invalid = assertIs<ModaRunResult.Invalid>(ModaRunner().run(document))
        assertTrue(
            invalid.errors.any { it.message.contains("South") },
            "the error does not say which alternative fell out: ${invalid.errors}"
        )
    }

    @Test
    @DisplayName("an alternative missing a score is left out and said to be")
    fun anAlternativeMissingAScoreIsLeftOutAndSaidToBe() {
        val document = inlineDocument().copy(
            source = ModaSourceReference.InlineScores(
                mapOf(
                    "North" to mapOf("Cost" to 20.0, "Delay" to 50.0),
                    "South" to mapOf("Cost" to 50.0, "Delay" to 30.0),
                    "East" to mapOf("Cost" to 80.0)
                )
            )
        )
        val completed = assertIs<ModaRunResult.Completed>(ModaRunner().run(document))
        assertContentEquals(listOf("North", "South"), completed.snapshot.alternatives)
        assertTrue(!completed.allAlternativesIncluded)
        assertEquals(listOf(MissingScore("East", "Delay")), completed.missing)
    }

    @Test
    @DisplayName("things worth remarking on are carried through to a study that ran")
    fun thingsWorthRemarkingOnAreCarriedThroughToAStudyThatRan() {
        val document = inlineDocument().copy(
            metrics = listOf(MetricSpec("Cost", weight = 2.0), MetricSpec("Delay", weight = 1.0, upperLimit = 60.0))
        )
        val completed = assertIs<ModaRunResult.Completed>(ModaRunner().run(document))
        assertTrue(
            completed.issues.any { it.severity == Severity.WARNING && it.element == "metrics.Cost" },
            "the unbounded metric was not remarked on: ${completed.issues}"
        )
    }

    @Test
    @DisplayName("a metric nothing separates is reported on the result")
    fun aMetricNothingSeparatesIsReportedOnTheResult() {
        val document = inlineDocument().copy(
            source = ModaSourceReference.InlineScores(
                mapOf(
                    "North" to mapOf("Cost" to 20.0, "Delay" to 30.0),
                    "South" to mapOf("Cost" to 50.0, "Delay" to 30.0),
                    "East" to mapOf("Cost" to 80.0, "Delay" to 30.0)
                )
            )
        )
        val completed = assertIs<ModaRunResult.Completed>(ModaRunner().run(document))
        assertTrue(completed.snapshot.metric("Delay")!!.hadTiedScores)
        assertTrue(
            completed.snapshot.warnings.any { it is ModaWarning.TiedScores && it.metric == "Delay" },
            "${completed.snapshot.warnings}"
        )
    }

    // ------------------------------------------------------------------------------------------
    // Elicited weights
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("weights obtained by asking someone are the ones used")
    fun weightsObtainedByAskingSomeoneAreTheOnesUsed() {
        val document = inlineDocument().copy(
            rescalePolicy = RescalePolicy.FIXED,
            elicitation = ElicitationSpec(
                order = listOf("Cost", "Delay"),
                ratings = mapOf("Cost" to 100.0, "Delay" to 100.0),
                elicitedAgainst = mapOf(
                    "Cost" to ElicitedRangeSpec(0.0, 100.0),
                    "Delay" to ElicitedRangeSpec(0.0, 60.0)
                )
            )
        )
        val completed = assertIs<ModaRunResult.Completed>(ModaRunner().run(document))
        // Equal swings, so equal weights, in spite of the document declaring 2 to 1.
        assertEquals(0.5, completed.snapshot.metric("Cost")!!.weight, 1.0e-12)
        assertEquals(0.5, completed.snapshot.metric("Delay")!!.weight, 1.0e-12)
    }

    @Test
    @DisplayName("elicited weights in a study that would refit its limits do not run")
    fun elicitedWeightsInAStudyThatWouldRefitItsLimitsDoNotRun() {
        val document = inlineDocument().copy(
            rescalePolicy = RescalePolicy.FROM_SCORES,
            elicitation = ElicitationSpec(
                order = listOf("Cost", "Delay"),
                ratings = mapOf("Cost" to 100.0, "Delay" to 50.0),
                elicitedAgainst = mapOf(
                    "Cost" to ElicitedRangeSpec(0.0, 100.0),
                    "Delay" to ElicitedRangeSpec(0.0, 60.0)
                )
            )
        )
        val invalid = assertIs<ModaRunResult.Invalid>(ModaRunner().run(document))
        assertTrue(invalid.errors.any { it.element == "elicitation" }, "${invalid.errors}")
    }

    // ------------------------------------------------------------------------------------------
    // Reading scores from a file
    // ------------------------------------------------------------------------------------------

    private fun writeScores(fileName: String, text: String): Path {
        val path = directory.resolve(fileName)
        Files.writeString(path, text)
        return path
    }

    @Test
    @DisplayName("a study reads its scores from a delimited file")
    fun aStudyReadsItsScoresFromADelimitedFile() {
        writeScores(
            "scores.csv",
            """
            alternative,Cost,Delay
            North,20,50
            South,50,30
            East,80,10
            """.trimIndent()
        )
        val document = inlineDocument().copy(
            source = ModaSourceReference.DelimitedFile("scores.csv", "alternative", listOf("Cost", "Delay"))
        )
        val runner = ModaRunner(resolver = ModaSourceResolver(documentLocation = directory))
        val completed = assertIs<ModaRunResult.Completed>(runner.run(document))
        assertContentEquals(listOf("North", "South", "East"), completed.snapshot.alternatives)
        assertEquals(20.0, completed.snapshot.scores["North"]!!["Cost"])
    }

    /**
     *  A relative path means relative to the document, so a study and its data can be moved or
     *  committed together.
     */
    @Test
    @DisplayName("a relative path is read against wherever the study is")
    fun aRelativePathIsReadAgainstWhereverTheStudyIs() {
        val nested = directory.resolve("study")
        Files.createDirectories(nested.resolve("data"))
        Files.writeString(
            nested.resolve("data/scores.csv"),
            "alternative,Cost,Delay\nNorth,20,50\nSouth,50,30\nEast,80,10\n"
        )
        val document = inlineDocument().copy(
            source = ModaSourceReference.DelimitedFile("data/scores.csv", "alternative", listOf("Cost", "Delay"))
        )
        val runner = ModaRunner(resolver = ModaSourceResolver(documentLocation = nested.resolve("study.toml")))
        val completed = assertIs<ModaRunResult.Completed>(runner.run(document))
        assertEquals(3, completed.snapshot.alternatives.size)
    }

    @Test
    @DisplayName("a file using another separator, with a quoted field, is read correctly")
    fun aFileUsingAnotherSeparatorWithAQuotedFieldIsReadCorrectly() {
        writeScores(
            "scores.tsv",
            "alternative;Cost;Delay\n\"North;ish\";20;50\nSouth;50;30\nEast;80;10\n"
        )
        val document = inlineDocument().copy(
            alternatives = listOf("North;ish", "South", "East"),
            source = ModaSourceReference.DelimitedFile(
                "scores.tsv", "alternative", listOf("Cost", "Delay"), Delimiter.SEMICOLON
            )
        )
        val runner = ModaRunner(resolver = ModaSourceResolver(documentLocation = directory))
        val completed = assertIs<ModaRunResult.Completed>(runner.run(document))
        assertTrue(
            "North;ish" in completed.snapshot.alternatives,
            "a quoted field containing the separator was split: ${completed.snapshot.alternatives}"
        )
    }

    /**
     *  A cell that is empty or not a number is missing rather than zero. Reading it as zero would
     *  quietly make an alternative look excellent on a metric where smaller is better.
     */
    @Test
    @DisplayName("a cell that is not a number is missing rather than zero")
    fun aCellThatIsNotANumberIsMissingRatherThanZero() {
        writeScores(
            "scores.csv",
            "alternative,Cost,Delay\nNorth,20,50\nSouth,50,30\nEast,n/a,10\n"
        )
        val document = inlineDocument().copy(
            source = ModaSourceReference.DelimitedFile("scores.csv", "alternative", listOf("Cost", "Delay"))
        )
        val runner = ModaRunner(resolver = ModaSourceResolver(documentLocation = directory))
        val completed = assertIs<ModaRunResult.Completed>(runner.run(document))
        assertEquals(listOf(MissingScore("East", "Cost")), completed.missing)
        assertTrue("East" !in completed.snapshot.alternatives)
    }

    @Test
    @DisplayName("a study can read scores from something registered for it")
    fun aStudyCanReadScoresFromSomethingRegisteredForIt() {
        val provider = ModaSourceProviderIfc { parameters ->
            val offset = parameters["offset"]?.toDouble() ?: 0.0
            ModaSourceIfc { alternatives, metrics ->
                ScoreTable(
                    alternatives.associateWith { name ->
                        metrics.associateWith { offset + name.length.toDouble() }
                    },
                    emptyList()
                )
            }
        }
        val document = inlineDocument().copy(
            source = ModaSourceReference.RegisteredProvider("lengths", mapOf("offset" to "1.0"))
        )
        val runner = ModaRunner(
            resolver = ModaSourceResolver(providers = mapOf("lengths" to provider))
        )
        val completed = assertIs<ModaRunResult.Completed>(runner.run(document))
        assertEquals(6.0, completed.snapshot.scores["North"]!!["Cost"])
    }
}
