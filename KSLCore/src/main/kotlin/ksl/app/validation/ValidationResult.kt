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
 * Aggregated pre-run validation result.
 *
 * A configuration is valid when [errors] is empty. [warnings] do not block
 * execution, but should be surfaced by a GUI because they describe values that
 * will be ignored, clamped, or otherwise deserve attention.
 */
@Serializable
data class ValidationResult(
    val errors: List<FieldError> = emptyList(),
    val warnings: List<FieldError> = emptyList()
) {
    /** True when the configuration has no blocking validation errors. */
    val isValid: Boolean
        get() = errors.isEmpty()

    /** All validation messages, preserving errors before warnings. */
    val allIssues: List<FieldError>
        get() = errors + warnings

    /** Combines two validation results without changing issue order within each severity. */
    operator fun plus(other: ValidationResult): ValidationResult =
        ValidationResult(
            errors = errors + other.errors,
            warnings = warnings + other.warnings
        )
}
