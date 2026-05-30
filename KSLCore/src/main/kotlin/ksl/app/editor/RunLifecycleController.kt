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

/**
 *  Substrate-level run-lifecycle bookkeeping shared by every
 *  configuration-shaped app: the "has the user edited the document
 *  since the last successful run" flag, plus the typed last-terminal-
 *  result holder it cross-flows with.
 *
 *  Pre-decomposition, each of the four app controllers (Single,
 *  Scenario, Experiment, Simopt) reimplemented this with the same
 *  mechanics — a `MutableStateFlow<Boolean>` that every editing
 *  mutator flips to `true`, paired with a `MutableStateFlow<R?>`
 *  that the run-completion path populates and that structural edits
 *  invalidate.  Only the name (`editedSinceLastSim` vs
 *  `editedSinceLastRun`) and the result type (`RunResult` vs a
 *  tighter subtype) differ.  This type captures that shared pair so
 *  each app controller composes one instance rather than re-declare
 *  four near-identical flows.
 *
 *  ## What this owns
 *
 *  - [editedSinceLastRun] — `true` after the first edit following a
 *    run / load / reset; cleared by [markRunCompleted],
 *    [clearEditedSinceLastRun], or [reset].
 *  - [lastResult] — the typed last terminal result, or `null` after
 *    invalidation / reset.
 *  - Idempotent mutators that flip those flows in the five
 *    semantically distinct ways an app controller needs them
 *    flipped: [markEdited], [markRunCompleted], [setLastResult],
 *    [clearEditedSinceLastRun], [reset].
 *
 *  ## What this deliberately does NOT own
 *
 *  - **App-specific staleness flags** like Simopt's
 *    `modelAwareStale`.  Those track orthogonal concerns (pre-run
 *    validation) and stay on the host.
 *  - **Side-effect fan-out on edit** — refreshers for step
 *    completion, validation, etc. are app-specific orchestration.
 *    Host controllers call [markEdited] / [setLastResult] and then
 *    fan their own side effects.
 *  - **The host's public flag name.**  Three of the four current
 *    apps expose `editedSinceLastSim`; Simopt exposes
 *    `editedSinceLastRun`.  The substrate uses the cleaner `Run`
 *    term internally; hosts re-point their existing public property
 *    to [editedSinceLastRun] as a name-preserving alias.
 *
 *  ## Semantics of the five mutators
 *
 *  | Method | `editedSinceLastRun` | `lastResult` | Typical caller |
 *  |---|---|---|---|
 *  | [markEdited]                | → `true`    | unchanged | every editing mutator |
 *  | [markRunCompleted]          | → `false`   | → result  | terminal run-completion callback |
 *  | [setLastResult]             | unchanged   | → value   | structural-edit invalidation; transform-and-replace |
 *  | [clearEditedSinceLastRun]   | → `false`   | unchanged | load whose loaded state matches the file (but caller wants result to survive) |
 *  | [reset]                     | → `false`   | → `null`  | new document / reset / load |
 *
 *  ## Generic over the result type
 *
 *  Single / Scenario / Experiment compose
 *  `RunLifecycleController<RunResult>`; Simopt composes
 *  `RunLifecycleController<RunResult.OptimizationCompleted>`.  The
 *  substrate never touches the result's internals — only the typed
 *  reference — so each host keeps its existing payload typing.
 *
 *  Substrate-level API — usable by any UI shell.  Plain class —
 *  no background work owned, no scope, not [AutoCloseable].
 */
class RunLifecycleController<R : Any> {

    private val myEditedSinceLastRun = MutableStateFlow(false)
    /**
     *  `true` when the document has been edited since the last
     *  successful run (or load / reset).  Cleared by
     *  [markRunCompleted], [clearEditedSinceLastRun], and [reset].
     */
    val editedSinceLastRun: StateFlow<Boolean> = myEditedSinceLastRun.asStateFlow()

    private val myLastResult = MutableStateFlow<R?>(null)
    /**
     *  The most recent terminal run result, or `null` after
     *  invalidation / reset / before any run has completed.
     *  Populated by [markRunCompleted] and [setLastResult].
     */
    val lastResult: StateFlow<R?> = myLastResult.asStateFlow()

    /**
     *  Idempotently mark the document edited since the last run.
     *  No-op when already `true` (no spurious StateFlow emission).
     *  Hosts fan their own side effects (last-result invalidation
     *  for structural edits, validation refresh, model-aware
     *  staleness flag, etc.) off this call; this method only flips
     *  the edited-since-last-run flag.
     */
    fun markEdited() {
        if (!myEditedSinceLastRun.value) myEditedSinceLastRun.value = true
    }

    /**
     *  Record a terminal run completion: bind [result] as
     *  [lastResult] AND clear [editedSinceLastRun] in a single
     *  call.  The two flags MUST move together at run-completion
     *  time (a stale "edited since last run" badge would mislead),
     *  which is why this is one method rather than two.
     */
    fun markRunCompleted(result: R) {
        myLastResult.value = result
        myEditedSinceLastRun.value = false
    }

    /**
     *  Set [lastResult] directly without touching the edited flag.
     *  Use for:
     *  - structural-edit invalidation: `setLastResult(null)` after
     *    the host has called [markEdited] separately
     *  - transform-and-replace: read [lastResult].value, derive a
     *    new value, and write it back (e.g. Scenario's
     *    `withoutScenario`)
     *  - load-restored result with an independently-set edited flag
     *
     *  Passing `null` clears the result.  Idempotent at the
     *  StateFlow level: writing the current value is a no-op.
     */
    fun setLastResult(result: R?) {
        myLastResult.value = result
    }

    /**
     *  Clear [editedSinceLastRun] without touching [lastResult].
     *  Use when the document has been loaded or its edit-tracking
     *  baseline has been re-established, but the host wants to keep
     *  whatever result is currently in [lastResult] (e.g. restoring
     *  a session that includes a prior result and a clean edit
     *  state).
     */
    fun clearEditedSinceLastRun() {
        myEditedSinceLastRun.value = false
    }

    /**
     *  Reset to a fresh state: [lastResult] = `null`,
     *  [editedSinceLastRun] = `false`.  Called by the app
     *  controller's `newDocument` / `resetConfiguration` /
     *  `loadConfiguration` entry points after the per-app state has
     *  been reset.
     */
    fun reset() {
        myLastResult.value = null
        myEditedSinceLastRun.value = false
    }
}
