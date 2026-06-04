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

package ksl.app.dist.validation

import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity

private const val CODE_DATA_SOURCE_EMPTY = "fit.dataSource.empty"
private const val CODE_DATASET_EMPTY = "fit.dataset.empty"
private const val CODE_UNKNOWN_ESTIMATOR = "fit.estimator.unknown"
private const val CODE_ESTIMATOR_KIND_MISMATCH = "fit.estimator.kindMismatch"
private const val CODE_UNKNOWN_SCORING_MODEL = "fit.scoringModel.unknown"
private const val CODE_BOOTSTRAP_SAMPLE_SIZE = "fit.bootstrap.sampleSize"
private const val CODE_BOOTSTRAP_LEVEL = "fit.bootstrap.level"
private const val CODE_DISCRETE_NON_INTEGER = "fit.discrete.nonInteger"
private const val CODE_BATCH_EMPTY = "fit.batch.empty"
private const val CODE_BATCH_DUPLICATE_NAME = "fit.batch.duplicateName"

private fun error(path: String, message: String, code: String): FieldError =
    FieldError(path = path, message = message, severity = ValidationSeverity.ERROR, code = code)

/**
 * Pure-data, pre-flight check of a fit spec. Surfaces problems as a
 * `ValidationResult` (reused from `ksl.app.validation`) so the same shape
 * front-ends already consume for simulation runs covers fitting too.
 *
 * This phase covers structural and capability-catalog checks only:
 *  - Inline data sources must be non-empty and contain no empty series.
 *  - Every estimator and scoring model ID must resolve in the catalog.
 *  - Estimator kinds must match the configuration's `kind`.
 *
 * Resolving file paths and database connections happens at run time and
 * surfaces as a separate runtime error, not as a validation error here.
 */
object FitConfigurationValidator {

    /** Validates one spec. */
    fun validate(spec: FitSpec): ValidationResult = when (spec) {
        is FitSpec.Single -> validate(spec.config, path = "config")
        is FitSpec.Batch -> validateBatch(spec)
    }

    private fun validateBatch(spec: FitSpec.Batch): ValidationResult {
        val errors = mutableListOf<FieldError>()
        if (spec.configs.isEmpty()) {
            errors += error("batch", "batch contains no configurations", CODE_BATCH_EMPTY)
            return ValidationResult(errors = errors.toList())
        }
        val seen = mutableSetOf<String>()
        spec.configs.forEach { named ->
            if (!seen.add(named.name)) {
                errors += error(
                    "batch[\"${named.name}\"]",
                    "duplicate batch entry name '${named.name}'",
                    CODE_BATCH_DUPLICATE_NAME
                )
            }
            errors += validate(named.config, path = "batch[\"${named.name}\"]").errors
        }
        return ValidationResult(errors = errors.toList())
    }

    private fun validate(config: FitConfiguration, path: String): ValidationResult {
        val errors = mutableListOf<FieldError>()
        validateDataSource(config.dataSource, "$path.dataSource", errors)
        validateEstimatorIds(config, path, errors)
        validateScoringModelIds(config, path, errors)
        validateBootstrap(config, path, errors)
        validateDiscreteData(config, "$path.dataSource", errors)
        return ValidationResult(errors = errors.toList())
    }

    private fun validateDataSource(
        ref: DataSourceReference,
        path: String,
        errors: MutableList<FieldError>
    ) {
        if (ref is DataSourceReference.Inline) {
            if (ref.datasets.isEmpty()) {
                errors += error(path, "inline reference contains no datasets", CODE_DATA_SOURCE_EMPTY)
                return
            }
            ref.datasets.forEach { (name, data) ->
                if (data.isEmpty()) {
                    errors += error(
                        "$path.datasets[\"$name\"]",
                        "dataset '$name' is empty",
                        CODE_DATASET_EMPTY
                    )
                }
            }
        }
        // DelimitedFile (and future Database / Generated) are validated at
        // import time so resolvability stays a runtime concern.
    }

    private fun validateEstimatorIds(
        config: FitConfiguration,
        path: String,
        errors: MutableList<FieldError>
    ) {
        config.estimatorIds.forEach { id ->
            val descriptor = FittingCatalog.estimatorOrNull(id)
            if (descriptor == null) {
                errors += error(
                    "$path.estimatorIds",
                    "unknown estimator id '$id'",
                    CODE_UNKNOWN_ESTIMATOR
                )
            } else if (descriptor.kind != config.kind) {
                errors += error(
                    "$path.estimatorIds",
                    "estimator '$id' is ${descriptor.kind} but configuration kind is ${config.kind}",
                    CODE_ESTIMATOR_KIND_MISMATCH
                )
            }
        }
    }

    private fun validateScoringModelIds(
        config: FitConfiguration,
        path: String,
        errors: MutableList<FieldError>
    ) {
        // Note: discrete fitting ignores scoring models, but supplying them is
        // not (yet) flagged as a warning — the warnings channel will be wired
        // through when the discrete path lands.
        config.scoringModelIds.forEach { id ->
            if (FittingCatalog.scoringModelOrNull(id) == null) {
                errors += error(
                    "$path.scoringModelIds",
                    "unknown scoring model id '$id'",
                    CODE_UNKNOWN_SCORING_MODEL
                )
            }
        }
    }

    private fun validateBootstrap(
        config: FitConfiguration,
        path: String,
        errors: MutableList<FieldError>
    ) {
        val bootstrap = config.bootstrap ?: return
        if (bootstrap.sampleSize <= 0) {
            errors += error(
                "$path.bootstrap.sampleSize",
                "bootstrap sample size must be > 0; was ${bootstrap.sampleSize}",
                CODE_BOOTSTRAP_SAMPLE_SIZE
            )
        }
        if (bootstrap.level <= 0.0 || bootstrap.level >= 1.0) {
            errors += error(
                "$path.bootstrap.level",
                "bootstrap confidence level must be in (0, 1); was ${bootstrap.level}",
                CODE_BOOTSTRAP_LEVEL
            )
        }
    }

    private fun validateDiscreteData(
        config: FitConfiguration,
        path: String,
        errors: MutableList<FieldError>
    ) {
        if (config.kind != DistributionKind.DISCRETE) return
        val ref = config.dataSource
        // Only inline data can be checked statically; file/database sources are
        // validated for integer-ness at import time.
        if (ref is DataSourceReference.Inline) {
            ref.datasets.forEach { (name, data) ->
                val offender = data.firstOrNull { kotlin.math.abs(it - Math.round(it)) > 1e-9 }
                if (offender != null) {
                    errors += error(
                        "$path.datasets[\"$name\"]",
                        "discrete fitting requires integer-valued data; '$name' contains $offender",
                        CODE_DISCRETE_NON_INTEGER
                    )
                }
            }
        }
    }
}
