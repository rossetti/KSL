/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.animation.app

/**
 * The state of a workflow stage, for the guided Capture → Run → Layout → Replay flow (9F.5).
 *  - [DONE]: the stage has produced a valid result (captured spec valid, a trace exists, a valid layout).
 *  - [AVAILABLE]: the stage can be worked now (the normal "you may proceed" state).
 *  - [BLOCKED]: the stage cannot do its job yet because an upstream output is missing (e.g. Replay with no
 *    trace). Soft — the tab is never disabled; an in-tab empty-state explains what's needed.
 */
enum class StageState { DONE, AVAILABLE, BLOCKED }

/**
 * A pure snapshot of the workflow's progress — derived from a few booleans so it is trivially testable and
 * holds no Swing or controller state. The frame renders it as tab check-marks and a "next step" banner.
 */
data class WorkflowStatus(
    val capture: StageState,
    val run: StageState,
    val layout: StageState,
    val replay: StageState,
    val nextStep: String
)

/**
 * Derives the [WorkflowStatus] from the observable facts:
 *  - [captureValid]: the capture spec validates against the inventory.
 *  - [hasTrace]: at least one `.atf` exists (just produced or already on disk).
 *  - [hasLayout]: at least one layout exists (active or saved).
 *  - [layoutValid]: the active layout validates (vacuously true when there is none).
 *
 * Ordering is advisory: every stage stays reachable. The `nextStep` points at the most useful next action
 * — fix capture if it's invalid, else run if there's no trace, else offer layout/replay.
 */
fun deriveWorkflowStatus(
    captureValid: Boolean,
    hasTrace: Boolean,
    hasLayout: Boolean,
    layoutValid: Boolean
): WorkflowStatus = WorkflowStatus(
    capture = if (captureValid) StageState.DONE else StageState.AVAILABLE,
    run = if (hasTrace) StageState.DONE else StageState.AVAILABLE,
    layout = if (hasLayout && layoutValid) StageState.DONE else StageState.AVAILABLE,
    replay = if (hasTrace) StageState.AVAILABLE else StageState.BLOCKED,
    nextStep = when {
        !captureValid -> "Fix the capture selection on the Capture tab."
        !hasTrace -> "Run a simulation on the Run tab to produce a trace."
        !hasLayout -> "Author a layout on the Layout tab — or replay now with Quick view."
        else -> "Replay your trace on the Replay tab; pair it with any layout."
    }
)
