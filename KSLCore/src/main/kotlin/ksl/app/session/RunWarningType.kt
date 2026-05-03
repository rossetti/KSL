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
 * Typed warning conditions that [Runner] can detect before or during a run.
 *
 * Warnings are surfaced as [RunEvent.RunWarning] events emitted on the
 * [RunHandle.events] flow.  They do **not** prevent the run from proceeding;
 * they signal to the GUI or test driver that human attention may be warranted.
 */
sealed class RunWarningType {

    /**
     * The model's replication length is infinite and no wall-clock timeout has
     * been set (`maximumAllowedExecutionTimePerReplication == Duration.ZERO`).
     *
     * The simulation will run indefinitely unless a model element calls
     * `model.endSimulation()` or the executive is stopped internally.  Without
     * a timeout, the only way to stop the run externally is via
     * [RunHandle.cancel].
     *
     * @param modelIdentifier the [ksl.simulation.Model.modelIdentifier] of the
     *        affected model, for display in error messages
     */
    data class InfiniteHorizonNoTimeout(val modelIdentifier: String) : RunWarningType()
}
