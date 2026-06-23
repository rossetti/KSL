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
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Substrate tests for [DocumentLifecycleController] — the file +
 *  dirty bookkeeping that every configuration-shaped app controller
 *  composes after Phase E.5 decomposition.
 *
 *  Backfilled in Phase E.5.1 — first sub-phase of controller
 *  decomposition.
 */
class DocumentLifecycleControllerTest {

    private val somePath: Path = Path.of("/home/user/configs/baseline.toml")
    private val otherPath: Path = Path.of("/home/user/configs/variant.toml")

    // ── Initial state ────────────────────────────────────────────────────

    @Test
    fun `fresh controller has null currentFile and clean isDirty`() {
        val c = DocumentLifecycleController()
        assertNull(c.currentFile.value, "Fresh controller must have null currentFile.")
        assertEquals(false, c.isDirty.value, "Fresh controller must be clean.")
    }

    // ── markDirty ────────────────────────────────────────────────────────

    @Test
    fun `markDirty flips isDirty to true`() {
        val c = DocumentLifecycleController()
        c.markDirty()
        assertEquals(true, c.isDirty.value)
    }

    @Test
    fun `markDirty does not touch currentFile`() {
        val c = DocumentLifecycleController()
        c.markSaved(somePath)
        c.markDirty()
        assertEquals(somePath, c.currentFile.value,
            "markDirty must not change the bound file.")
        assertEquals(true, c.isDirty.value)
    }

    @Test
    fun `markDirty is idempotent - repeated calls do not re-emit`() = runBlocking {
        // Verify the documented idempotent semantics: a second markDirty
        // when already dirty does not produce a fresh emission on the
        // StateFlow (the value is unchanged so no observer fires).
        val c = DocumentLifecycleController()
        c.markDirty()
        val first = c.isDirty.value
        c.markDirty()
        c.markDirty()
        val second = c.isDirty.first()
        assertEquals(first, second,
            "Repeated markDirty calls must produce the same observed value.")
    }

    // ── markSaved ────────────────────────────────────────────────────────

    @Test
    fun `markSaved binds the file and clears isDirty`() {
        val c = DocumentLifecycleController()
        c.markDirty()
        c.markSaved(somePath)
        assertEquals(somePath, c.currentFile.value)
        assertEquals(false, c.isDirty.value)
    }

    @Test
    fun `markSaved overwrites a previously-bound file`() {
        val c = DocumentLifecycleController()
        c.markSaved(somePath)
        c.markDirty()
        c.markSaved(otherPath)
        assertEquals(otherPath, c.currentFile.value,
            "Save-As-to-different-path must update currentFile.")
        assertEquals(false, c.isDirty.value)
    }

    // ── bindFile ─────────────────────────────────────────────────────────

    @Test
    fun `bindFile sets currentFile without changing isDirty when dirty`() {
        val c = DocumentLifecycleController()
        c.markDirty()
        c.bindFile(somePath)
        assertEquals(somePath, c.currentFile.value)
        assertEquals(true, c.isDirty.value,
            "bindFile must not flip isDirty — caller decides separately.")
    }

    @Test
    fun `bindFile sets currentFile without changing isDirty when clean`() {
        val c = DocumentLifecycleController()
        c.bindFile(somePath)
        assertEquals(somePath, c.currentFile.value)
        assertEquals(false, c.isDirty.value,
            "bindFile on a clean controller must leave isDirty false.")
    }

    // ── clearDirty ───────────────────────────────────────────────────────

    @Test
    fun `clearDirty resets isDirty without touching currentFile`() {
        val c = DocumentLifecycleController()
        c.markSaved(somePath)
        c.markDirty()
        c.clearDirty()
        assertEquals(somePath, c.currentFile.value,
            "clearDirty must not touch currentFile.")
        assertEquals(false, c.isDirty.value)
    }

    // ── reset ────────────────────────────────────────────────────────────

    @Test
    fun `reset returns the controller to the fresh-controller state`() {
        val c = DocumentLifecycleController()
        c.markSaved(somePath)
        c.markDirty()
        c.reset()
        assertNull(c.currentFile.value, "reset must null out currentFile.")
        assertEquals(false, c.isDirty.value, "reset must clear isDirty.")
    }

    // ── StateFlow observability ──────────────────────────────────────────

    @Test
    fun `currentFile and isDirty StateFlows emit the latest value to fresh collectors`() = runBlocking {
        val c = DocumentLifecycleController()
        c.markSaved(somePath)
        c.markDirty()
        // A fresh collector picks up the current value immediately
        // — StateFlow semantics.
        assertEquals(somePath, c.currentFile.first())
        assertEquals(true, c.isDirty.first())
    }

    // ── Composite scenarios ──────────────────────────────────────────────

    @Test
    fun `load-bind-then-clear matches the loaded-state-equals-file case`() {
        // Pattern: load decodes the file, host calls bindFile(path)
        // then clearDirty() because the in-memory state matches the
        // file exactly.  Equivalent to markSaved(path).
        val c = DocumentLifecycleController()
        c.bindFile(somePath)
        c.clearDirty()
        assertEquals(somePath, c.currentFile.value)
        assertEquals(false, c.isDirty.value)
    }

    @Test
    fun `load-bind-and-stay-dirty matches the warning-state case`() {
        // Pattern: load decodes the file but the host detects a
        // mismatch (legacy format, missing fields filled with
        // defaults, etc.) and wants to leave the document marked
        // dirty so the next Save persists the corrected state.
        val c = DocumentLifecycleController()
        c.markDirty()       // synthetic warning-state setup
        c.bindFile(somePath)
        assertEquals(somePath, c.currentFile.value)
        assertTrue(c.isDirty.value,
            "After bindFile without clearDirty, the controller must " +
                "remain in whatever isDirty state the host had set.")
    }
}
