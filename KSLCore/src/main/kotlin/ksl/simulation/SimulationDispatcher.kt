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

package ksl.simulation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Holds the bounded [CoroutineDispatcher] used for concurrent simulation execution.
 *
 * Simulations are CPU-intensive.  Running more concurrent simulations than available
 * processors degrades throughput rather than improving it.  [default] is therefore
 * capped at [Runtime.availableProcessors].
 *
 * To override the parallelism limit — for example when running on a machine with many
 * cores but fewer are available to the JVM process, or in a test environment — replace
 * [default] before launching any concurrent simulations:
 *
 * ```kotlin
 * SimulationDispatcher.default = Dispatchers.IO.limitedParallelism(4)
 * ```
 *
 * The replacement must itself be bounded; the caller is responsible for ensuring the
 * limit is appropriate for the target hardware.
 */
object SimulationDispatcher {

    /**
     * The number of processors used to bound [default].
     * Exposed for inspection and test verification.
     */
    val availableProcessors: Int = Runtime.getRuntime().availableProcessors()

    /**
     * Bounded dispatcher for concurrent simulation execution.
     * Defaults to [Dispatchers.IO] limited to [availableProcessors].
     * May be replaced before concurrent simulations are launched.
     */
    var default: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(availableProcessors)
}
