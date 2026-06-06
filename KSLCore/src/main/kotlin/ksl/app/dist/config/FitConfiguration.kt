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
    val dataSource: ksl.app.dist.config.DataSourceReference,
    val kind: DistributionKind = DistributionKind.CONTINUOUS,
    val estimatorIds: Set<String> = emptySet(),
    val scoringModelIds: Set<String> = emptySet(),
    val automaticShifting: Boolean = true,
    val rankingMethod: RankingMethod = RankingMethod.ORDINAL,
    val evaluationMethod: EvaluationMethod = EvaluationMethod.SCORING,
    val bootstrap: BootstrapConfig? = null,
    /**
     * When true, the runner renders the standard PDF/PMF modeling report to an
     * HTML string and carries it on the result (`FitResultData.standardReportHtml`).
     * Defaults to false so remote/programmatic callers keep a bounded payload;
     * front-ends that want the canonical report opt in.
     */
    val includeStandardReport: Boolean = false
)
