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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 *  Substrate-level document-lifecycle bookkeeping shared by every
 *  configuration-shaped app: which file backs the in-memory document
 *  (or `null` for an unsaved fresh document) and whether the in-memory
 *  state differs from that file.
 *
 *  Pre-decomposition, each of the four app controllers (Single,
 *  Scenario, Experiment, Simopt) reimplemented this with identical
 *  mechanics — `currentFile: StateFlow<Path?>` + `isDirty:
 *  StateFlow<Boolean>` + a `markDirty()` private mutator that flips
 *  the flag.  This type captures that shared pair so each app
 *  controller can compose one rather than re-declare four.
 *
 *  ## What this owns
 *
 *  - The file-binding flow ([currentFile]).
 *  - The dirty-flag flow ([isDirty]).
 *  - Idempotent mutators that flip those flows in the four
 *    semantically distinct ways an app controller needs them
 *    flipped: [markDirty], [markSaved], [bindFile], [clearDirty],
 *    [reset].
 *
 *  ## What this deliberately does NOT own
 *
 *  - **`editedSinceLastRun` / "stale result" flags.**  These
 *    cross-flow with the run lifecycle and stay with each app
 *    controller (or move to a substrate `RunLifecycleController`
 *    in a later sub-phase).
 *  - **Side-effect fan-out on dirty flip** — refreshers for step
 *    completion, validation, model-aware staleness, etc. are
 *    app-specific orchestration.  Host controllers call
 *    [markDirty] and then fan their own side effects.
 *  - **Config-payload typing.**  This controller never touches
 *    the config object — only the path and the boolean.  The
 *    load/save methods that produce or consume a typed config live
 *    on the app controller, which delegates to this type for the
 *    file/dirty bookkeeping at the appropriate moments.
 *
 *  ## Semantics of the five mutators
 *
 *  | Method | `currentFile` | `isDirty` | Typical caller |
 *  |---|---|---|---|
 *  | [markDirty] | unchanged | → `true` | every editing mutator |
 *  | [markSaved] | → `path`  | → `false` | after a successful save |
 *  | [bindFile]  | → `path`  | unchanged | after a load whose mismatched / warning state the caller wants to keep flagged |
 *  | [clearDirty]| unchanged | → `false` | after a load whose loaded state matches the file |
 *  | [reset]     | → `null`  | → `false` | New document / reset to fresh |
 *
 *  Substrate-level API — usable by any UI shell.  Plain class —
 *  no background work owned, no scope, not [AutoCloseable].
 */
class DocumentLifecycleController {

    private val myCurrentFile = MutableStateFlow<Path?>(null)
    /**
     *  Path of the file currently bound to the in-memory document,
     *  or `null` when the document has never been saved or has been
     *  [reset].  Updated by [markSaved], [bindFile], and [reset].
     */
    val currentFile: StateFlow<Path?> = myCurrentFile.asStateFlow()

    private val myIsDirty = MutableStateFlow(false)
    /**
     *  `true` when the in-memory document differs from the file on
     *  disk (or, when no file is bound, from a fresh document).
     *  Updated by [markDirty], [markSaved], [clearDirty], and
     *  [reset].
     */
    val isDirty: StateFlow<Boolean> = myIsDirty.asStateFlow()

    /**
     *  Idempotently mark the document dirty.  No-op when already
     *  dirty (no spurious StateFlow emission).  Hosts fan their own
     *  side effects (e.g. an `editedSinceLastRun` flag flip,
     *  validation refresh, last-result clearing) off this call;
     *  this method only flips the dirty flag.
     */
    fun markDirty() {
        if (!myIsDirty.value) myIsDirty.value = true
    }

    /**
     *  Bind [path] as the file backing this document AND mark the
     *  document clean.  Called after a successful Save or Save As —
     *  the in-memory state now matches what's on disk at [path].
     */
    fun markSaved(path: Path) {
        myCurrentFile.value = path
        myIsDirty.value = false
    }

    /**
     *  Bind [path] as the file backing this document without
     *  changing the dirty flag.  Use this when the load path has
     *  produced an in-memory state that may legitimately differ
     *  from the file (e.g. a legacy-decode that surfaces a warning,
     *  or an Open whose decoded state was intentionally edited
     *  before binding).  Hosts that load-and-immediately-clean call
     *  [bindFile] then [clearDirty] — or call [markSaved] which
     *  combines both.
     */
    fun bindFile(path: Path) {
        myCurrentFile.value = path
    }

    /**
     *  Clear the dirty flag without changing the bound file.  Use
     *  after [bindFile] when the loaded state matches the file
     *  exactly, or whenever the host wants to assert "the in-memory
     *  state is now equivalent to whatever the bound file holds."
     */
    fun clearDirty() {
        myIsDirty.value = false
    }

    /**
     *  Reset to a fresh, unbound, clean document.  Called by the
     *  app controller's `newDocument` / `resetConfiguration`
     *  entry point after the per-app state has been reset.
     */
    fun reset() {
        myCurrentFile.value = null
        myIsDirty.value = false
    }
}
