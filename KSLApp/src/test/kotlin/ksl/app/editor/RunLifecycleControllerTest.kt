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

package ksl.app.editor

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Substrate tests for [RunLifecycleController] — the
 *  edited-since-last-run + typed last-result bookkeeping that every
 *  configuration-shaped app controller composes after Phase E.5
 *  decomposition.
 *
 *  Backfilled in Phase E.5.5 — fifth sub-phase of controller
 *  decomposition.
 *
 *  Uses plain `String` as the generic result payload to keep the
 *  substrate test self-contained.  The host migrations in E.5.6
 *  parameterize with the project's real `RunResult` / tighter
 *  subtypes.
 */
class RunLifecycleControllerTest {

    private val resultA = "RUN_A"
    private val resultB = "RUN_B"

    // ── Initial state ────────────────────────────────────────────────────

    @Test
    fun `fresh controller has null lastResult and clean editedSinceLastRun`() {
        val c = RunLifecycleController<String>()
        assertNull(c.lastResult.value, "Fresh controller must have null lastResult.")
        assertEquals(false, c.editedSinceLastRun.value,
            "Fresh controller must have editedSinceLastRun = false.")
    }

    // ── markEdited ───────────────────────────────────────────────────────

    @Test
    fun `markEdited flips editedSinceLastRun to true`() {
        val c = RunLifecycleController<String>()
        c.markEdited()
        assertEquals(true, c.editedSinceLastRun.value)
    }

    @Test
    fun `markEdited does not touch lastResult`() {
        val c = RunLifecycleController<String>()
        c.setLastResult(resultA)
        c.markEdited()
        assertEquals(resultA, c.lastResult.value,
            "markEdited must not touch lastResult.")
        assertEquals(true, c.editedSinceLastRun.value)
    }

    @Test
    fun `markEdited is idempotent - repeated calls do not re-emit`() = runBlocking {
        // Verify the documented idempotent semantics: a second
        // markEdited when already edited does not produce a fresh
        // emission on the StateFlow (the value is unchanged so no
        // observer fires).
        val c = RunLifecycleController<String>()
        c.markEdited()
        val firstValue = c.editedSinceLastRun.value
        c.markEdited()
        c.markEdited()
        val secondValue = c.editedSinceLastRun.first()
        assertEquals(firstValue, secondValue,
            "Repeated markEdited calls must produce the same observed value.")
    }

    // ── markRunCompleted ─────────────────────────────────────────────────

    @Test
    fun `markRunCompleted sets lastResult and clears editedSinceLastRun`() {
        val c = RunLifecycleController<String>()
        c.markEdited()
        c.markRunCompleted(resultA)
        assertEquals(resultA, c.lastResult.value)
        assertEquals(false, c.editedSinceLastRun.value)
    }

    @Test
    fun `markRunCompleted overwrites a prior result`() {
        val c = RunLifecycleController<String>()
        c.markRunCompleted(resultA)
        c.markEdited()
        c.markRunCompleted(resultB)
        assertEquals(resultB, c.lastResult.value,
            "A new run-completion must overwrite the prior result.")
        assertEquals(false, c.editedSinceLastRun.value,
            "A new run-completion must clear edited-since-last-run.")
    }

    @Test
    fun `markRunCompleted from a clean state still binds the result`() {
        val c = RunLifecycleController<String>()
        c.markRunCompleted(resultA)
        assertEquals(resultA, c.lastResult.value)
        assertEquals(false, c.editedSinceLastRun.value,
            "Already-false editedSinceLastRun must remain false.")
    }

    // ── setLastResult ────────────────────────────────────────────────────

    @Test
    fun `setLastResult binds a value without changing editedSinceLastRun`() {
        val c = RunLifecycleController<String>()
        c.markEdited()
        c.setLastResult(resultA)
        assertEquals(resultA, c.lastResult.value)
        assertEquals(true, c.editedSinceLastRun.value,
            "setLastResult must not touch the edited flag.")
    }

    @Test
    fun `setLastResult with null clears the result without changing editedSinceLastRun`() {
        val c = RunLifecycleController<String>()
        c.markRunCompleted(resultA)
        c.markEdited()
        c.setLastResult(null)
        assertNull(c.lastResult.value, "setLastResult(null) must null out lastResult.")
        assertEquals(true, c.editedSinceLastRun.value,
            "setLastResult(null) must leave the edited flag alone.")
    }

    @Test
    fun `setLastResult supports transform-and-replace pattern`() {
        // Pattern: Scenario's withoutScenario reads the current
        // result, derives a modified value, and writes it back.
        val c = RunLifecycleController<String>()
        c.markRunCompleted(resultA)
        val current = c.lastResult.value
        c.setLastResult(current + "_modified")
        assertEquals("${resultA}_modified", c.lastResult.value)
    }

    // ── clearEditedSinceLastRun ──────────────────────────────────────────

    @Test
    fun `clearEditedSinceLastRun resets the flag without touching lastResult`() {
        val c = RunLifecycleController<String>()
        c.markRunCompleted(resultA)
        c.markEdited()
        c.clearEditedSinceLastRun()
        assertEquals(resultA, c.lastResult.value,
            "clearEditedSinceLastRun must not touch lastResult.")
        assertEquals(false, c.editedSinceLastRun.value)
    }

    // ── reset ────────────────────────────────────────────────────────────

    @Test
    fun `reset returns the controller to the fresh-controller state`() {
        val c = RunLifecycleController<String>()
        c.markRunCompleted(resultA)
        c.markEdited()
        c.reset()
        assertNull(c.lastResult.value, "reset must null out lastResult.")
        assertEquals(false, c.editedSinceLastRun.value,
            "reset must clear editedSinceLastRun.")
    }

    // ── StateFlow observability ──────────────────────────────────────────

    @Test
    fun `editedSinceLastRun and lastResult StateFlows emit the latest value to fresh collectors`() = runBlocking {
        val c = RunLifecycleController<String>()
        c.setLastResult(resultA)
        c.markEdited()
        // Fresh collectors pick up the current value immediately
        // — StateFlow semantics.
        assertEquals(resultA, c.lastResult.first())
        assertEquals(true, c.editedSinceLastRun.first())
    }

    // ── Composite scenarios ──────────────────────────────────────────────

    @Test
    fun `edit-then-invalidate-then-re-edit-then-run-completed matches the structural-edit cycle`() {
        // Pattern: structural edit flips edited + sets result to
        // null (Simopt markDirtyStructural).  Another edit keeps
        // both states.  A successful run binds the new result and
        // clears edited.
        val c = RunLifecycleController<String>()
        c.markRunCompleted(resultA)

        c.markEdited()
        c.setLastResult(null)
        assertNull(c.lastResult.value)
        assertTrue(c.editedSinceLastRun.value)

        c.markEdited()  // idempotent re-emit; still edited
        assertTrue(c.editedSinceLastRun.value)

        c.markRunCompleted(resultB)
        assertEquals(resultB, c.lastResult.value)
        assertEquals(false, c.editedSinceLastRun.value)
    }

    @Test
    fun `load-restores-result-only matches a clean-after-load case`() {
        // Pattern: load decodes the document and restores a saved
        // lastResult, then clears the edited flag because the
        // in-memory state matches the file.
        val c = RunLifecycleController<String>()
        c.markEdited()                       // pre-load synthetic edit
        c.setLastResult(resultA)             // load restores result
        c.clearEditedSinceLastRun()          // load asserts clean
        assertEquals(resultA, c.lastResult.value)
        assertEquals(false, c.editedSinceLastRun.value)
    }

    @Test
    fun `load-restores-edited-only matches a warning-state load case`() {
        // Pattern: load decodes the document but the result is
        // intentionally not restored, and the host wants the edited
        // flag flipped so the next run notes the load-induced
        // change (e.g. legacy-decode with field defaults).
        val c = RunLifecycleController<String>()
        c.setLastResult(null)
        c.markEdited()
        assertNull(c.lastResult.value)
        assertTrue(c.editedSinceLastRun.value,
            "After a warning-state load the edited flag must remain true.")
    }

    // ── Generic verification ─────────────────────────────────────────────

    @Test
    fun `controller is generic over the result payload type`() {
        // Verify the substrate works with a richer payload than
        // String (mimics the host case where R is a sealed
        // RunResult-or-subtype).
        data class Payload(val tag: String, val count: Int)

        val c = RunLifecycleController<Payload>()
        val p = Payload("done", 7)
        c.markRunCompleted(p)
        assertEquals(p, c.lastResult.value)
        assertEquals(false, c.editedSinceLastRun.value)

        c.markEdited()
        c.setLastResult(null)
        assertNull(c.lastResult.value)
        assertTrue(c.editedSinceLastRun.value)
    }
}
