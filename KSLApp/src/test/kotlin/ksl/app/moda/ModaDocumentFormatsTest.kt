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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Tests for keeping a study in a file.
 *
 *  A study is worth writing down only if reading it back gives the same study. Both formats are
 *  checked against the same documents, since the point of describing the document once is that
 *  neither format can drift from the other.
 */
class ModaDocumentFormatsTest {

    private fun inlineDocument(): ModaDocument = ModaDocument(
        name = "Warehouse siting",
        metrics = listOf(
            MetricSpec(
                name = "Cost",
                direction = "SmallerIsBetter",
                weight = 3.0,
                lowerLimit = 0.0,
                upperLimit = 500_000.0,
                unitsOfMeasure = "dollars",
                description = "Annual operating cost"
            ),
            MetricSpec(
                name = "Service",
                direction = "BiggerIsBetter",
                weight = 1.0,
                lowerLimit = 0.0,
                upperLimit = 100.0,
                valueFunctionId = ValueFunctionRegistry.LOGISTIC,
                parameters = mapOf(
                    ValueFunctionRegistry.LOCATION to 50.0,
                    ValueFunctionRegistry.SCALE to 12.5
                )
            )
        ),
        alternatives = listOf("North", "South", "East"),
        source = ModaSourceReference.InlineScores(
            mapOf(
                "North" to mapOf("Cost" to 120_000.0, "Service" to 80.0),
                "South" to mapOf("Cost" to 90_000.0, "Service" to 60.0),
                "East" to mapOf("Cost" to 150_000.0, "Service" to 95.0)
            )
        ),
        rescalePolicy = RescalePolicy.FROM_SCORES
    )

    private fun fileDocument(): ModaDocument = ModaDocument(
        name = "From a file",
        metrics = listOf(
            MetricSpec("Cost", upperLimit = 1000.0),
            MetricSpec("Delay", upperLimit = 100.0)
        ),
        alternatives = listOf("A", "B"),
        source = ModaSourceReference.DelimitedFile(
            path = "scores.csv",
            alternativeColumn = "alternative",
            metricColumns = listOf("Cost", "Delay"),
            delimiter = Delimiter.SEMICOLON
        ),
        rescalePolicy = RescalePolicy.NONE,
        rankingMethod = "Fractional",
        aggregationMethod = "FIRST_RANK_COUNT"
    )

    // ------------------------------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study written as TOML reads back as the same study`() {
        val original = inlineDocument()
        assertEquals(original, ModaDocumentFormats.fromToml(ModaDocumentFormats.toToml(original)))
    }

    @Test
    fun `a study written as JSON reads back as the same study`() {
        val original = inlineDocument()
        assertEquals(original, ModaDocumentFormats.fromJson(ModaDocumentFormats.toJson(original)))
    }

    @Test
    fun `a study reading from a file round-trips in both formats`() {
        val original = fileDocument()
        assertEquals(original, ModaDocumentFormats.fromToml(ModaDocumentFormats.toToml(original)))
        assertEquals(original, ModaDocumentFormats.fromJson(ModaDocumentFormats.toJson(original)))
    }

    @Test
    fun `a study carrying elicited weights round-trips in both formats`() {
        val original = inlineDocument().copy(
            rescalePolicy = RescalePolicy.FIXED,
            elicitation = ElicitationSpec(
                order = listOf("Cost", "Service"),
                ratings = mapOf("Cost" to 100.0, "Service" to 40.0),
                elicitedAgainst = mapOf(
                    "Cost" to ElicitedRangeSpec(0.0, 500_000.0),
                    "Service" to ElicitedRangeSpec(0.0, 100.0)
                ),
                adjustableRanges = emptyList()
            )
        )
        assertEquals(original, ModaDocumentFormats.fromToml(ModaDocumentFormats.toToml(original)))
        assertEquals(original, ModaDocumentFormats.fromJson(ModaDocumentFormats.toJson(original)))
    }

    /**
     *  Each kind of source has to survive the trip as the kind it was, since which one it is
     *  decides how the study finds its data.
     */
    @Test
    fun `every kind of source survives both formats as the kind it was`() {
        val sources = listOf(
            ModaSourceReference.InlineScores(mapOf("A" to mapOf("Cost" to 1.0))),
            ModaSourceReference.DelimitedFile("data/scores.tsv", "name", listOf("Cost"), Delimiter.TAB),
            ModaSourceReference.KslDatabase(DatabaseConnectionRef("results"), listOf("exp1"), listOf("Cost")),
            ModaSourceReference.RetainedRun("run-17"),
            ModaSourceReference.RegisteredProvider("my-provider", mapOf("k" to "v"))
        )
        for (source in sources) {
            val document = inlineDocument().copy(source = source)
            assertEquals(
                source, ModaDocumentFormats.fromToml(ModaDocumentFormats.toToml(document)).source,
                "TOML did not preserve ${source::class.simpleName}"
            )
            assertEquals(
                source, ModaDocumentFormats.fromJson(ModaDocumentFormats.toJson(document)).source,
                "JSON did not preserve ${source::class.simpleName}"
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // What the written form looks like
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a written study says which version wrote it`() {
        val json = ModaDocumentFormats.toJson(inlineDocument())
        assertTrue(
            json.contains("\"schemaVersion\": ${ModaDocument.SCHEMA_VERSION}"),
            "the version is missing from the written study: $json"
        )
    }

    /**
     *  Someone editing a study by hand should not have to delete lines for settings they never
     *  chose, so optional things they left out stay out.
     */
    @Test
    fun `settings nobody chose are left out of the hand-editable form`() {
        val plain = ModaDocument(
            name = "Plain",
            metrics = listOf(MetricSpec("Cost", upperLimit = 10.0), MetricSpec("Delay", upperLimit = 10.0)),
            alternatives = listOf("A", "B"),
            source = ModaSourceReference.InlineScores(
                mapOf("A" to mapOf("Cost" to 1.0, "Delay" to 2.0), "B" to mapOf("Cost" to 3.0, "Delay" to 4.0))
            )
        )
        val toml = ModaDocumentFormats.toToml(plain)
        assertTrue(!toml.contains("unitsOfMeasure"), "an unset optional field was written out: $toml")
        assertTrue(!toml.contains("elicitation"), "an absent elicitation was written out: $toml")
        // And it still reads back as the same study.
        assertEquals(plain, ModaDocumentFormats.fromToml(toml))
    }

    @Test
    fun `a reader ignores settings it does not know about`() {
        val json = ModaDocumentFormats.toJson(inlineDocument())
            .replaceFirst("{", "{\"someLaterSetting\": 42,")
        assertEquals(inlineDocument(), ModaDocumentFormats.fromJson(json))
    }

    // ------------------------------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study written to a file reads back from it in either format`() {
        val directory: Path = createTempDirectory("moda-doc")
        try {
            for (extension in listOf("toml", "json")) {
                val path = directory.resolve("study.$extension")
                ModaDocumentFormats.write(inlineDocument(), path)
                assertTrue(Files.exists(path))
                assertEquals(inlineDocument(), ModaDocumentFormats.read(path), "failed for .$extension")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a file that is not in a format studies are kept in is refused by name`() {
        val directory: Path = createTempDirectory("moda-doc")
        try {
            val path = directory.resolve("study.yaml")
            val error = assertFailsWith<IllegalArgumentException> {
                ModaDocumentFormats.write(inlineDocument(), path)
            }
            assertTrue(error.message!!.contains("yaml"), "the error does not say what it was given")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
