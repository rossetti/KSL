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

package ksl.service.capability.fit

import kotlinx.serialization.json.Json
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.validation.FitConfigurationValidator
import ksl.app.validation.ValidationResult
import ksl.service.store.ResultStore

/**
 * The fit capability's document surface (Phase 8 Tier B): JSON codec, a
 * ready-to-edit scaffold, content key, and validation for a [FitConfiguration]
 * — the fit counterpart to `RunConfigurationJson` + `RunTemplates` +
 * `ResultKeys` + the run validator. Kept in the service layer (the substrate's
 * `FitConfiguration` is `@Serializable`, so no KSLCore codec is required) so the
 * MCP and REST transports author, validate, key, and run fit documents
 * identically.
 */
object FitDocuments {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    /** Serializes a [FitConfiguration] to JSON (the authored document). */
    fun encode(config: FitConfiguration): String =
        json.encodeToString(FitConfiguration.serializer(), config)

    /** Parses a [FitConfiguration] from JSON. */
    fun decode(text: String): FitConfiguration =
        json.decodeFromString(FitConfiguration.serializer(), text)

    /** The content key the result is cached under (the document is the request). */
    fun key(config: FitConfiguration): String = ResultStore.sha256("fit:" + encode(config))

    /** Validates a fit document (delegates to the substrate validator). */
    fun validate(config: FitConfiguration): ValidationResult =
        FitConfigurationValidator.validate(FitSpec.Single(config))

    /**
     * A ready-to-edit fit scaffold for [kind]: an inline data source the author
     * fills with observations, plus the catalog-default estimators (and scoring
     * models, for the continuous path). Edit the `dataset` values and submit.
     */
    fun template(kind: DistributionKind = DistributionKind.CONTINUOUS): FitConfiguration =
        FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("dataset" to DoubleArray(0))),
            kind = kind,
            estimatorIds = FittingCatalog.defaultEstimatorIds(kind),
            scoringModelIds = if (kind == DistributionKind.CONTINUOUS) FittingCatalog.defaultScoringModelIds() else emptySet(),
        )
}
