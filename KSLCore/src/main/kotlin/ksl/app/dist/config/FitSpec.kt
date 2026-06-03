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

package ksl.app.dist.config

/**
 * Type-safe job spec for a distribution-fitting request, parallel to
 * `ksl.app.RunSpec`. Cardinality (single vs. batch) is encoded in the
 * variant rather than a flag, so a downstream session can dispatch by
 * exhaustive `when` without re-validating which fields are present.
 *
 * Only `Single` is supported in this phase; a `Batch` variant for many
 * datasets sharing one analysis configuration will land with the batch
 * orchestrator.
 */
sealed class FitSpec {

    /** Fit one dataset using one analysis configuration. */
    data class Single(val config: FitConfiguration) : FitSpec()
}
