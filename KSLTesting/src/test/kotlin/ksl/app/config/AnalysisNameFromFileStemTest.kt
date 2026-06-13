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

package ksl.app.config

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  Tests for [analysisNameFromFileStem] — the `markSaved`
 *  once-at-default auto-fill helper extracted in Phase E.5.7.
 *
 *  Pins the substrate contract that Scenario / Experiment / Simopt
 *  all delegate to.
 */
class AnalysisNameFromFileStemTest {

    // ── Sentinel-gating ──────────────────────────────────────────────────

    @Test
    fun `returns null when currentName is not the sentinel`() {
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/mySim.toml"),
            currentName = "MyAnalysis"
        )
        assertNull(result,
            "Once the user has set a non-default name, save-as must " +
                "not silently rename their analysis.")
    }

    @Test
    fun `returns the stem when currentName equals the default sentinel`() {
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/mySim.toml"),
            currentName = "Untitled"
        )
        assertEquals("mySim", result)
    }

    @Test
    fun `accepts a custom sentinel`() {
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/mySim.toml"),
            currentName = "NEW",
            sentinel = "NEW"
        )
        assertEquals("mySim", result)
    }

    @Test
    fun `custom sentinel is exact-match - does not match default`() {
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/mySim.toml"),
            currentName = "Untitled",
            sentinel = "NEW"
        )
        assertNull(result)
    }

    // ── Stem extraction ──────────────────────────────────────────────────

    @Test
    fun `strips the final extension`() {
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/myExperiment.toml"),
            currentName = "Untitled"
        )
        assertEquals("myExperiment", result)
    }

    @Test
    fun `strips only the final extension for multi-dot names`() {
        // "config.backup.toml" → "config.backup".  substringBeforeLast
        // is the documented behavior.
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/config.backup.toml"),
            currentName = "Untitled"
        )
        assertEquals("config.backup", result)
    }

    @Test
    fun `returns the full name when there is no extension`() {
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/myExperiment"),
            currentName = "Untitled"
        )
        assertEquals("myExperiment", result)
    }

    @Test
    fun `ignores the parent directory`() {
        // Only path.fileName participates — the parent path is
        // irrelevant.
        val result = analysisNameFromFileStem(
            path = Path.of("/nested/path/to/file.toml"),
            currentName = "Untitled"
        )
        assertEquals("file", result)
    }

    // ── Blank-stem guarding ──────────────────────────────────────────────

    @Test
    fun `returns null when stem is blank (hidden file)`() {
        // ".config" → stem is "" (everything before the final '.')
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/.config"),
            currentName = "Untitled"
        )
        assertNull(result, "A hidden file like .config has a blank stem " +
            "and must not overwrite the default name with an empty string.")
    }

    // ── Sanitizer composition ────────────────────────────────────────────

    @Test
    fun `applies the sanitizer to the stem when supplied`() {
        // Mimics Simopt's pipeline: pipe the stem through
        // sanitizeAnalysisName so the saved name conforms to the
        // filesystem-safe alphabet.
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/my sim (v2).toml"),
            currentName = "Untitled",
            sanitizer = ::sanitizeAnalysisName
        )
        assertEquals("my_sim__v2_", result,
            "Simopt's sanitizer must coerce spaces and parentheses " +
                "into underscores.")
    }

    @Test
    fun `default sanitizer returns the stem unchanged`() {
        // Scenario / Experiment do NOT sanitize — they rely on the
        // identity sanitizer to keep the stem verbatim.
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/my sim (v2).toml"),
            currentName = "Untitled"
        )
        assertEquals("my sim (v2)", result,
            "The default identity sanitizer must leave the stem alone.")
    }

    @Test
    fun `sanitizer is not applied when currentName is not the sentinel`() {
        // Short-circuit: gating happens BEFORE the sanitizer runs,
        // so a non-sentinel currentName never invokes the sanitizer.
        var sanitizerCalled = false
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/mySim.toml"),
            currentName = "AlreadySet",
            sanitizer = { stem ->
                sanitizerCalled = true
                stem.uppercase()
            }
        )
        assertNull(result)
        assertEquals(false, sanitizerCalled,
            "Sanitizer must not run when the sentinel check short-circuits.")
    }

    @Test
    fun `sanitizer is not applied when stem is blank`() {
        // Short-circuit: blank-stem rejection happens BEFORE the
        // sanitizer runs.
        var sanitizerCalled = false
        val result = analysisNameFromFileStem(
            path = Path.of("/tmp/.config"),
            currentName = "Untitled",
            sanitizer = { stem ->
                sanitizerCalled = true
                stem
            }
        )
        assertNull(result)
        assertEquals(false, sanitizerCalled,
            "Sanitizer must not run when the blank-stem check rejects.")
    }
}
