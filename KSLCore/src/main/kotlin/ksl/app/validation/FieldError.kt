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

import kotlinx.serialization.Serializable

/**
 * A field-addressable validation issue.
 *
 * [path] uses a dotted, UI-friendly form such as
 * `experimentRunParameters.lengthOfReplication` or
 * `controls.string.Server.routingPolicy.value`. Collection entries use
 * bracket notation, for example `rvOverrides[0].paramName`.
 *
 * @property path dotted path to the offending field
 * @property message human-readable explanation
 * @property severity whether the issue blocks running
 * @property code stable machine-readable code for GUI mapping and tests
 */
@Serializable
data class FieldError(
    val path: String,
    val message: String,
    val severity: ValidationSeverity,
    val code: String
)
