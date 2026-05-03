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

package ksl.app.session

/**
 * Typed representation of errors that can occur during a simulation run.
 *
 * [Runner] translates caught exceptions into one of these variants and emits
 * [RunEvent.RunFailed] so that a GUI or downstream consumer can respond to the
 * specific failure kind without inspecting raw exception types.
 */
sealed class KSLRuntimeError {

    /**
     * Failure during model construction or initial setup — before any
     * replications execute. Primarily used in Phase 3+ when [Runner] builds
     * the model from a `RunConfiguration`; in Phase 1 the caller provides a
     * pre-built model, so this is unlikely but kept for completeness.
     */
    data class ModelBuildError(
        val message: String,
        val cause: Throwable
    ) : KSLRuntimeError()

    /**
     * An unexpected exception was thrown during replication execution.
     *
     * @param simTime the simulation clock value at the time of failure
     * @param replicationNumber the replication that was executing when the
     *        error occurred (0 if failure happened before any replication ran)
     * @param cause the underlying exception
     */
    data class ExecutiveError(
        val simTime: Double,
        val replicationNumber: Int,
        val cause: Throwable
    ) : KSLRuntimeError()

    /**
     * The run could not proceed due to an invalid or inconsistent configuration.
     * Used by Phase 3 pre-flight validation; defined here so the error hierarchy
     * is complete from Phase 1 onward.
     */
    data class ConfigurationError(val message: String) : KSLRuntimeError()
}
