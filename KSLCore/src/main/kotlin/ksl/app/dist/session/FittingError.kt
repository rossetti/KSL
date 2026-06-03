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

import ksl.app.validation.ValidationResult

/**
 * Structured failure cause carried by a terminal failed FitResult.
 *
 * The current taxonomy is intentionally narrow: a pre-flight configuration
 * problem, a data-import problem, or anything else thrown by the fitting
 * runner. Later phases will widen this hierarchy as concrete failure
 * categories warrant distinct front-end treatment (per-estimator failures,
 * database-specific errors, etc.).
 */
sealed class FittingError {

    /** Short, user-facing message. */
    abstract val message: String

    /** Underlying exception when one exists; null for pure-data errors. */
    abstract val cause: Throwable?

    /**
     * Pre-flight validation rejected the submitted spec. The carried
     * `validationResult` lists each offending field so a front-end can
     * highlight inputs directly.
     */
    data class ConfigurationError(
        override val message: String,
        val validationResult: ValidationResult
    ) : FittingError() {
        override val cause: Throwable? = null
    }

    /**
     * Data import failed: file not found, layout mismatch, non-numeric
     * values, empty group, and so on. The cause is typically the
     * underlying `ImportException`.
     */
    data class ImportError(
        override val message: String,
        override val cause: Throwable?
    ) : FittingError()

    /**
     * Anything else thrown while running the fit: PDFModeler numeric
     * failure, a not-yet-supported feature guard, an unexpected JVM
     * exception. The cause carries the original throwable.
     */
    data class RuntimeError(
        override val message: String,
        override val cause: Throwable?
    ) : FittingError()
}
