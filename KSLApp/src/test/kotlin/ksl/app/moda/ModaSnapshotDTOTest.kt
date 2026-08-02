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

import ksl.utilities.Interval
import ksl.utilities.moda.AdditiveMODAModel
import ksl.utilities.moda.AggregationMethod
import ksl.utilities.moda.LinearValueFunction
import ksl.utilities.moda.Metric
import ksl.utilities.moda.ModaSnapshot
import ksl.utilities.moda.Score
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Tests for writing a MODA result out and reading it back.
 *
 *  A result is worth writing out only if what comes back is the same result, and only if writing
 *  the same result twice produces the same bytes — otherwise results cannot be compared, cached,
 *  or checked for having changed.
 */
class ModaSnapshotDTOTest {

    private fun snapshot(
        aggregation: AggregationMethod = AggregationMethod.WEIGHTED_VALUE
    ): ModaSnapshot {
        val cost = Metric("Cost", Interval(0.0, 100.0))
        val delay = Metric("Delay", Interval(0.0, 100.0))
        delay.unitsOfMeasure = "minutes"
        delay.description = "Time waiting"
        val model = AdditiveMODAModel(
            mapOf(cost to LinearValueFunction(), delay to LinearValueFunction()),
            name = "Study"
        )
        model.defineAlternatives(
            mapOf(
                "Alpha" to listOf(Score(cost, 20.0), Score(delay, 80.0)),
                "Beta" to listOf(Score(cost, 50.0), Score(delay, 50.0)),
                "Gamma" to listOf(Score(cost, 80.0), Score(delay, 20.0))
            )
        )
        return ModaSnapshot.of(model, aggregation = aggregation)
    }

    @Test
    fun `a result read back is the result that was written`() {
        val original = snapshot()
        val json = ModaJson.encodeToString(original.toDTO())
        val restored = ModaJson.decodeFromString<ModaSnapshotDTO>(json).toSnapshot()
        assertEquals(original, restored, "the result changed on the way through")
    }

    @Test
    fun `a result carrying units, a description and warnings survives the trip`() {
        val tied = Metric("Tied", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(tied to LinearValueFunction()), name = "Tied study")
        model.defineAlternatives(
            mapOf("A" to listOf(Score(tied, 5.0)), "B" to listOf(Score(tied, 5.0)))
        )
        val original = ModaSnapshot.of(model)
        assertTrue(original.warnings.isNotEmpty(), "the study under test produced no warning to carry")

        val restored = ModaJson.decodeFromString<ModaSnapshotDTO>(
            ModaJson.encodeToString(original.toDTO())
        ).toSnapshot()
        assertEquals(original, restored)
        assertEquals(original.warnings, restored.warnings)
    }

    @Test
    fun `writing the same result twice produces the same bytes`() {
        val original = snapshot()
        val first = ModaJson.encodeToString(original.toDTO())
        repeat(5) {
            assertEquals(first, ModaJson.encodeToString(original.toDTO()), "encoding is not repeatable")
        }
        // And again from a separately built but identical study.
        assertEquals(first, ModaJson.encodeToString(snapshot().toDTO()), "two equal results encoded differently")
    }

    @Test
    fun `a result says which version wrote it`() {
        val json = ModaJson.encodeToString(snapshot().toDTO())
        assertTrue(
            json.contains("\"schemaVersion\":${ModaSnapshotDTO.SCHEMA_VERSION}"),
            "the version is missing from the written result: $json"
        )
    }

    @Test
    fun `a reader ignores fields it does not know about`() {
        // What a later version that had added a field would write.
        val json = ModaJson.encodeToString(snapshot().toDTO())
            .replaceFirst("{", "{\"someLaterField\":42,")
        val restored = ModaJson.decodeFromString<ModaSnapshotDTO>(json).toSnapshot()
        assertEquals(snapshot(), restored)
    }

    @Test
    fun `a result written by a later version is refused rather than misread`() {
        val dto = snapshot().toDTO().copy(aggregationMethod = "SOME_LATER_METHOD")
        val error = assertFailsWith<IllegalArgumentException> { dto.toSnapshot() }
        assertTrue(
            error.message!!.contains("SOME_LATER_METHOD"),
            "the error does not name what could not be understood"
        )
    }

    @Test
    fun `counting first ranks survives the trip as the method it was`() {
        val original = snapshot(AggregationMethod.FIRST_RANK_COUNT)
        val restored = ModaJson.decodeFromString<ModaSnapshotDTO>(
            ModaJson.encodeToString(original.toDTO())
        ).toSnapshot()
        assertEquals(AggregationMethod.FIRST_RANK_COUNT, restored.aggregationMethod)
        assertEquals(original.primaryRecommendation, restored.primaryRecommendation)
    }

    @Test
    fun `the order the study declared things in survives the trip`() {
        val restored = ModaJson.decodeFromString<ModaSnapshotDTO>(
            ModaJson.encodeToString(snapshot().toDTO())
        ).toSnapshot()
        assertEquals(listOf("Cost", "Delay"), restored.metrics.map { it.name })
        assertEquals(listOf("Alpha", "Beta", "Gamma"), restored.alternatives)
    }
}
