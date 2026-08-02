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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Tests for checking a study before running it.
 *
 *  Two properties matter throughout. Problems that make a study impossible are separated from ones
 *  that only suggest it does not say what was meant, so a study with only the latter still runs.
 *  And every problem names the part of the document it concerns, so a long list can be worked
 *  through rather than re-read against the document to find where each applies.
 */
class ModaDocumentValidatorTest {

    private val validator = ModaDocumentValidator()

    private fun soundDocument(): ModaDocument = ModaDocument(
        name = "Sound",
        metrics = listOf(
            MetricSpec("Cost", weight = 2.0, upperLimit = 100.0),
            MetricSpec("Delay", weight = 1.0, upperLimit = 60.0)
        ),
        alternatives = listOf("A", "B", "C"),
        source = ModaSourceReference.InlineScores(
            mapOf(
                "A" to mapOf("Cost" to 20.0, "Delay" to 30.0),
                "B" to mapOf("Cost" to 50.0, "Delay" to 20.0),
                "C" to mapOf("Cost" to 80.0, "Delay" to 10.0)
            )
        )
    )

    private fun errorsOf(document: ModaDocument): List<ValidationIssue> =
        validator.validate(document).filter { it.severity == Severity.ERROR }

    private fun warningsOf(document: ModaDocument): List<ValidationIssue> =
        validator.validate(document).filter { it.severity == Severity.WARNING }

    private fun assertErrorMentioning(document: ModaDocument, element: String, vararg fragments: String) {
        val errors = errorsOf(document)
        val matching = errors.filter { it.element == element }
        assertTrue(matching.isNotEmpty(), "no error against '$element'. Errors were: $errors")
        for (fragment in fragments) {
            assertTrue(
                matching.any { it.message.contains(fragment) },
                "no error against '$element' mentioning '$fragment'. Messages: ${matching.map { it.message }}"
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // A study that is fine
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a sound study has nothing wrong with it`() {
        assertTrue(errorsOf(soundDocument()).isEmpty(), "a sound study was rejected: ${errorsOf(soundDocument())}")
        assertTrue(validator.isRunnable(soundDocument()))
    }

    @Test
    fun `everything wrong is reported at once rather than one at a time`() {
        val broken = soundDocument().copy(
            name = "",
            alternatives = listOf("A"),
            metrics = listOf(MetricSpec("Cost", valueFunctionId = "nope", upperLimit = 100.0))
        )
        val elements = errorsOf(broken).map { it.element }.toSet()
        assertTrue(elements.contains("name"), "elements were $elements")
        assertTrue(elements.contains("alternatives"), "elements were $elements")
        assertTrue(elements.any { it.startsWith("metrics") }, "elements were $elements")
    }

    // ------------------------------------------------------------------------------------------
    // Things that make a study impossible
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a value function nobody supplied is named along with what is available`() {
        val document = soundDocument().copy(
            metrics = listOf(
                MetricSpec("Cost", valueFunctionId = "sigmoid", upperLimit = 100.0),
                MetricSpec("Delay", upperLimit = 60.0)
            )
        )
        assertErrorMentioning(document, "metrics.Cost", "sigmoid", ValueFunctionRegistry.LINEAR)
    }

    @Test
    fun `two metrics with the same name are refused`() {
        val document = soundDocument().copy(
            metrics = listOf(MetricSpec("Cost", upperLimit = 100.0), MetricSpec("Cost", upperLimit = 50.0))
        )
        assertErrorMentioning(document, "metrics", "Cost")
    }

    @Test
    fun `a study needs something to compare and something to compare it on`() {
        assertErrorMentioning(soundDocument().copy(alternatives = listOf("A")), "alternatives")
        assertErrorMentioning(soundDocument().copy(metrics = emptyList()), "metrics")
    }

    @Test
    fun `an alternative named twice is refused`() {
        assertErrorMentioning(soundDocument().copy(alternatives = listOf("A", "B", "A")), "alternatives", "A")
    }

    @Test
    fun `weights that could not count towards a result are refused`() {
        assertErrorMentioning(
            soundDocument().copy(
                metrics = listOf(
                    MetricSpec("Cost", weight = 0.0, upperLimit = 100.0),
                    MetricSpec("Delay", weight = 0.0, upperLimit = 60.0)
                )
            ),
            "metrics"
        )
        assertErrorMentioning(
            soundDocument().copy(
                metrics = listOf(
                    MetricSpec("Cost", weight = -1.0, upperLimit = 100.0),
                    MetricSpec("Delay", weight = 1.0, upperLimit = 60.0)
                )
            ),
            "metrics.Cost"
        )
    }

    @Test
    fun `limits that are not a range are refused`() {
        assertErrorMentioning(
            soundDocument().copy(
                metrics = listOf(
                    MetricSpec("Cost", lowerLimit = 100.0, upperLimit = 10.0),
                    MetricSpec("Delay", upperLimit = 60.0)
                )
            ),
            "metrics.Cost", "100.0"
        )
    }

    @Test
    fun `a method the reader does not know is named along with what it could be`() {
        assertErrorMentioning(soundDocument().copy(rankingMethod = "Sideways"), "rankingMethod", "Sideways", "Ordinal")
        assertErrorMentioning(
            soundDocument().copy(aggregationMethod = "VIBES"), "aggregationMethod", "VIBES", "WEIGHTED_VALUE"
        )
        assertErrorMentioning(
            soundDocument().copy(
                metrics = listOf(MetricSpec("Cost", direction = "Sideways", upperLimit = 100.0))
            ),
            "metrics.Cost", "Sideways"
        )
    }

    @Test
    fun `a study written by a later version is refused rather than misread`() {
        assertErrorMentioning(
            soundDocument().copy(schemaVersion = ModaDocument.SCHEMA_VERSION + 1),
            "schemaVersion", "later version"
        )
    }

    // ------------------------------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a file that is not there is caught before anything runs`() {
        val document = soundDocument().copy(
            source = ModaSourceReference.DelimitedFile(
                "definitely/not/here.csv", "alternative", listOf("Cost", "Delay")
            )
        )
        assertErrorMentioning(document, "source", "does not exist")
    }

    @Test
    fun `a source that is understood but not yet supported says so plainly`() {
        val database = soundDocument().copy(
            source = ModaSourceReference.KslDatabase(DatabaseConnectionRef("results"), listOf("e"), listOf("Cost"))
        )
        assertErrorMentioning(database, "source", "not available yet")

        val retained = soundDocument().copy(source = ModaSourceReference.RetainedRun("run-1"))
        assertErrorMentioning(retained, "source", "not available yet")
    }

    @Test
    fun `a provider nobody registered is named along with what is registered`() {
        assertErrorMentioning(
            soundDocument().copy(source = ModaSourceReference.RegisteredProvider("mine")),
            "source", "mine", "None are registered"
        )
    }

    @Test
    fun `a study that holds no scores for an alternative is caught before running`() {
        val document = soundDocument().copy(
            source = ModaSourceReference.InlineScores(mapOf("A" to mapOf("Cost" to 1.0, "Delay" to 2.0)))
        )
        assertErrorMentioning(document, "source", "B", "C")
    }

    @Test
    fun `a file with no column for one of the metrics is caught before running`() {
        val document = soundDocument().copy(
            source = ModaSourceReference.DelimitedFile("scores.csv", "alternative", listOf("Cost"))
        )
        // The missing file is also an error; what matters here is that the unmatched metric is one.
        assertErrorMentioning(document, "source", "Delay")
    }

    // ------------------------------------------------------------------------------------------
    // Elicited weights
    // ------------------------------------------------------------------------------------------

    private fun elicited(): ElicitationSpec = ElicitationSpec(
        order = listOf("Cost", "Delay"),
        ratings = mapOf("Cost" to 100.0, "Delay" to 50.0),
        elicitedAgainst = mapOf(
            "Cost" to ElicitedRangeSpec(0.0, 100.0),
            "Delay" to ElicitedRangeSpec(0.0, 60.0)
        )
    )

    @Test
    fun `elicited weights require limits that cannot move`() {
        val fromScores = soundDocument().copy(elicitation = elicited(), rescalePolicy = RescalePolicy.FROM_SCORES)
        assertErrorMentioning(fromScores, "elicitation", "FIXED")

        val none = soundDocument().copy(elicitation = elicited(), rescalePolicy = RescalePolicy.NONE)
        assertErrorMentioning(none, "elicitation", "FIXED")
    }

    @Test
    fun `elicited weights against limits the study no longer declares are refused`() {
        // The study's Cost range was edited after the weights were given against 0 to 100.
        val document = soundDocument().copy(
            metrics = listOf(
                MetricSpec("Cost", weight = 2.0, upperLimit = 250.0),
                MetricSpec("Delay", weight = 1.0, upperLimit = 60.0)
            ),
            elicitation = elicited(),
            rescalePolicy = RescalePolicy.FIXED
        )
        assertErrorMentioning(document, "elicitation", "Cost", "tied to the range")
    }

    @Test
    fun `elicited weights covering the study exactly are accepted`() {
        val document = soundDocument().copy(elicitation = elicited(), rescalePolicy = RescalePolicy.FIXED)
        assertTrue(errorsOf(document).isEmpty(), "a sound elicitation was rejected: ${errorsOf(document)}")
    }

    @Test
    fun `weights given for metrics the study does not have, or missing for ones it does, are refused`() {
        val extra = soundDocument().copy(
            rescalePolicy = RescalePolicy.FIXED,
            elicitation = elicited().copy(ratings = elicited().ratings + ("Risk" to 10.0))
        )
        assertErrorMentioning(extra, "elicitation", "Risk")

        val short = soundDocument().copy(
            rescalePolicy = RescalePolicy.FIXED,
            elicitation = elicited().copy(ratings = mapOf("Cost" to 100.0))
        )
        assertErrorMentioning(short, "elicitation", "Delay")
    }

    @Test
    fun `a fixed study with a limit that is not a real bound is refused`() {
        val document = soundDocument().copy(
            metrics = listOf(MetricSpec("Cost", weight = 1.0), MetricSpec("Delay", weight = 1.0, upperLimit = 60.0)),
            rescalePolicy = RescalePolicy.FIXED
        )
        assertErrorMentioning(document, "metrics.Cost", "unbounded")
    }

    // ------------------------------------------------------------------------------------------
    // Things worth remarking on that still run
    // ------------------------------------------------------------------------------------------

    @Test
    fun `an unbounded metric is remarked on without stopping the study`() {
        val document = soundDocument().copy(
            metrics = listOf(MetricSpec("Cost", weight = 1.0), MetricSpec("Delay", weight = 1.0, upperLimit = 60.0))
        )
        assertTrue(errorsOf(document).isEmpty())
        assertTrue(
            warningsOf(document).any { it.element == "metrics.Cost" },
            "an unbounded metric was not remarked on"
        )
        assertTrue(validator.isRunnable(document))
    }

    @Test
    fun `an absolute path is remarked on because the study will not travel`() {
        val document = soundDocument().copy(
            source = ModaSourceReference.DelimitedFile("/data/scores.csv", "alternative", listOf("Cost", "Delay"))
        )
        assertTrue(
            warningsOf(document).any { it.element == "source" && it.message.contains("absolute") },
            "an absolute path was not remarked on"
        )
    }

    @Test
    fun `more metrics than alternatives is remarked on without stopping the study`() {
        val document = soundDocument().copy(
            alternatives = listOf("A", "B"),
            metrics = listOf(
                MetricSpec("Cost", upperLimit = 100.0),
                MetricSpec("Delay", upperLimit = 60.0),
                MetricSpec("Risk", upperLimit = 10.0)
            ),
            source = ModaSourceReference.InlineScores(
                mapOf(
                    "A" to mapOf("Cost" to 20.0, "Delay" to 30.0, "Risk" to 1.0),
                    "B" to mapOf("Cost" to 50.0, "Delay" to 20.0, "Risk" to 5.0)
                )
            )
        )
        assertTrue(errorsOf(document).isEmpty(), "${errorsOf(document)}")
        assertTrue(warningsOf(document).any { it.element == "metrics" })
    }

    @Test
    fun `an issue reads as something that can be acted on`() {
        val issue = errorsOf(
            soundDocument().copy(
                metrics = listOf(MetricSpec("Cost", valueFunctionId = "nope", upperLimit = 100.0))
            )
        ).first { it.element == "metrics.Cost" }
        assertEquals(Severity.ERROR, issue.severity)
        assertTrue(issue.toString().contains("metrics.Cost"))
        assertFalse(issue.message.isBlank())
    }
}
