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

package ksl.app.dist.session

import ksl.app.dist.result.FitReport

/**
 * Terminal outcome of a fitting job, returned by `FitHandle.result.await()`
 * and `submitAndAwaitBlocking`. Always resolves to exactly one variant; the
 * matching terminal `FitEvent` is the last event on the handle's flow.
 */
sealed class FitResult {

    /** The fit produced a usable `FitReport`. */
    data class Completed(val report: FitReport) : FitResult()

    /** The fit failed with a structured error. */
    data class Failed(val error: FittingError) : FitResult()

    /** The fit was cancelled before completion. */
    data class Cancelled(val reason: String) : FitResult()
}
