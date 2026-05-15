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

package ksl.app.swing.common.runcontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import javax.swing.Action

/**
 * Central "edit actions are disabled while a run is active" policy
 * from scenario workflow §10.  Each editor that wants edit
 * affordances to defer to the running posture creates one guard,
 * registers its actions, and stops worrying about the
 * `addObserver`-on-every-action ceremony.
 *
 * Semantics:
 *  - When the supplied [runningFlow] transitions to `true`, each
 *    registered action's *current* `isEnabled` value is captured
 *    and the action is disabled.
 *  - When [runningFlow] returns to `false`, each action is
 *    restored to its captured pre-running enabled value.  An
 *    action that was already disabled before the run stays
 *    disabled.
 *  - Actions registered *while* `runningFlow` is `true` are
 *    captured as enabled = current state and immediately disabled;
 *    the captured state is used when the flow next returns to
 *    `false`.
 *
 * Lifecycle is owned by [scope].  Cancelling the scope detaches
 * the guard; registered actions retain their then-current enabled
 * state (no restoration on cancel).
 */
class RunningPostureGuard(
    private val runningFlow: StateFlow<Boolean>,
    scope: CoroutineScope
) {

    private data class Entry(val action: Action, var preRunEnabled: Boolean)

    private val entries: MutableList<Entry> = mutableListOf()

    /** Registers [action] under the guard. */
    fun register(action: Action) {
        val running = runningFlow.value
        val capture = action.isEnabled
        entries.add(Entry(action, capture))
        if (running) action.isEnabled = false
    }

    /** Convenience varargs form of [register]. */
    fun register(vararg actions: Action) {
        for (action in actions) register(action)
    }

    /**
     * Forgets every registered action without restoring state.
     * Call on document close.
     */
    fun unregisterAll() {
        entries.clear()
    }

    /** Test-only: how many actions are registered. */
    internal fun registeredCountForTest(): Int = entries.size

    init {
        scope.launch(Dispatchers.Swing) {
            runningFlow
                .onEach { running ->
                    if (running) {
                        for (entry in entries) {
                            entry.preRunEnabled = entry.action.isEnabled
                            entry.action.isEnabled = false
                        }
                    } else {
                        for (entry in entries) {
                            entry.action.isEnabled = entry.preRunEnabled
                        }
                    }
                }
                .collect { /* no-op terminal */ }
        }
    }
}
