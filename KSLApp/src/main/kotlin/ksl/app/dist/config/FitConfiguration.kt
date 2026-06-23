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

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 * Serializable description of one distribution-fitting analysis: where the
 * data comes from, what kind of distribution to fit, and which estimators
 * and scoring models to use.
 *
 * `estimatorIds` and `scoringModelIds` are catalog-stable IDs from the
 * fitting catalog; an empty set means "use the catalog defaults for `kind`."
 * `scoringModelIds` is ignored for DISCRETE configurations because PMF
 * goodness-of-fit ranks by chi-squared p-value, not by MODA scoring.
 *
 * `automaticShifting` applies only to the continuous path; it is ignored
 * for DISCRETE configurations.
 *
 * `bootstrap` is opt-in: a non-null `BootstrapConfig` requests engine-side
 * bootstrap of the fitted parameters (summaries returned); null skips it.
 * (The family-frequency bootstrap is a separate standalone analysis — see
 * `FittingRunner.familyFrequencyBootstrap` — not a fit option.)
 *
 * `rankingMethod` and `evaluationMethod` surface the engine's MODA-evaluation
 * parameters (rank-tie handling and the recommendation criterion); both
 * default to the engine defaults and apply to the continuous path only.
 */
@Serializable
data class FitConfiguration(
    @TomlComment("Where the data comes from: a delimited file, database table/query, or generated RV.")
    val dataSource: ksl.app.dist.config.DataSourceReference,
    @TomlComment("String. Distribution kind to fit: \"CONTINUOUS\" or \"DISCRETE\".")
    val kind: DistributionKind = DistributionKind.CONTINUOUS,
    @TomlComment(
        "List of catalog-stable estimator IDs to use. Empty selects the\n" +
        "catalog defaults for the chosen kind."
    )
    val estimatorIds: Set<String> = emptySet(),
    @TomlComment(
        "List of catalog-stable scoring-model IDs (continuous only). Empty\n" +
        "selects the catalog defaults; ignored for DISCRETE (which ranks by\n" +
        "chi-squared p-value)."
    )
    val scoringModelIds: Set<String> = emptySet(),
    @TomlComment("Boolean. Continuous only: when true, automatically shift the data before fitting.")
    val automaticShifting: Boolean = true,
    @TomlComment("String. Continuous only: rank-tie handling for MODA evaluation, e.g. \"ORDINAL\".")
    val rankingMethod: RankingMethod = RankingMethod.ORDINAL,
    @TomlComment("String. Continuous only: recommendation criterion for MODA evaluation, e.g. \"SCORING\".")
    val evaluationMethod: EvaluationMethod = EvaluationMethod.SCORING,
    @TomlComment(
        "Optional. When present, requests engine-side bootstrap of the fitted\n" +
        "parameters (summaries only). Omit to skip bootstrapping."
    )
    val bootstrap: BootstrapConfig? = null,
    @TomlComment(
        "Boolean. When true, the runner renders the standard PDF/PMF modeling\n" +
        "report to HTML and carries it on the result. Defaults to false to keep\n" +
        "programmatic payloads bounded; front-ends that want the report opt in."
    )
    val includeStandardReport: Boolean = false
)
