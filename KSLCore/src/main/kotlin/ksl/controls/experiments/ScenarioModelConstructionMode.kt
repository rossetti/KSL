/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.controls.experiments

/**
 * Describes how a [Scenario] obtains the [ksl.simulation.Model] used for execution.
 */
enum class ScenarioModelConstructionMode {

    /**
     * The scenario delegates model creation to a [ksl.simulation.ModelBuilderIfc].
     *
     * This is the required construction style for concurrent execution. The builder
     * must return a fresh, independent model instance for each call to build().
     */
    MODEL_BUILDER,

    /**
     * The scenario wraps a pre-built model instance.
     *
     * This mode preserves legacy sequential behavior, but it is not safe for
     * [ConcurrentScenarioRunner] because multiple concurrent tasks would share
     * the same mutable model instance.
     */
    REUSED_MODEL_INSTANCE
}
