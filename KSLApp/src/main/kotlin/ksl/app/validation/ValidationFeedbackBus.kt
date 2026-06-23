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

package ksl.app.validation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the editor's latest [ValidationResult] and fans it out to
 * widget subscribers.  One bus per open document — the GUI's editor
 * view-model constructs a bus, calls [publish] every time it produces
 * a new result, and hands the bus to widgets that decorate
 * themselves from it.
 *
 * Lives in `KSLCore` because the routing helpers
 * ([issuesAtPath], [issuesAtOrBelow]) are pure logic; non-Swing
 * consumers (tests, CLI surfaces) can use them too.  Widgets in
 * `KSLAppSwingCommon` subscribe to [result] and re-render on emission.
 *
 * Thread-safety is inherited from [MutableStateFlow] — concurrent
 * [publish] calls are safe but publish ordering follows the JVM's
 * memory model (the StateFlow itself does not guarantee
 * happens-before across publishers).  In practice the editor
 * view-model is the sole publisher.
 */
class ValidationFeedbackBus(initial: ValidationResult = ValidationResult()) {

    private val myResult: MutableStateFlow<ValidationResult> = MutableStateFlow(initial)

    /** Observable validation state.  Read-only; publish via [publish]. */
    val result: StateFlow<ValidationResult> = myResult.asStateFlow()

    /** Replaces the current validation state with [next]. */
    fun publish(next: ValidationResult) {
        myResult.value = next
    }

    /**
     * Returns every issue ([ValidationResult.errors] +
     * [ValidationResult.warnings]) whose path exactly matches [path].
     * Used by per-field decorators such as `FieldErrorMarker`.
     */
    fun issuesAtPath(path: String): List<FieldError> =
        myResult.value.allIssues.filter { it.path == path }

    /**
     * Returns every issue whose path is at or below [prefix] under the
     * dotted-bracket path tree.  Used by per-row aggregators such as
     * `RowStatusIcon`.  Passing an empty prefix returns every issue.
     */
    fun issuesAtOrBelow(prefix: String): List<FieldError> =
        myResult.value.allIssues.filter { PathParser.isAtOrBelow(prefix, it.path) }
}
