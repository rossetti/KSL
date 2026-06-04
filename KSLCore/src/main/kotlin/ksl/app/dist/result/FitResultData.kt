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

package ksl.app.dist.result

import kotlinx.serialization.Serializable
import ksl.app.dist.config.DistributionKind

/**
 * Wire-safe machine result for one distribution-fitting job over one dataset
 * — the contract a CLI / REST / MCP client consumes, and the payload carried
 * by a completed async fit. The payload is bounded and independent of sample
 * size: it carries estimated parameters, goodness-of-fit, full MODA scoring,
 * bootstrap summaries, and a data summary, but **no plot-data series** (a
 * client reconstructs plots from its own raw data plus the returned fitted
 * distribution — see the design plan).
 *
 * `recommendedFamilyId` is the family ID of the top-ranked successful fit, or
 * null when no estimator produced a usable result. `fits` is sorted best-first
 * (rank 1 first) with failed attempts at the bottom.
 *
 * `histogram`, `scoring`, and `bootstrapFamilyFrequency` are populated by the
 * result extractor in a later phase; they are null until then.
 */
@Serializable
data class FitResultData(
    val datasetName: String,
    val kind: DistributionKind,
    val empProbConvention: EmpProbConvention = EmpProbConvention.CONTINUITY1,
    val dataSummary: DataSummaryDTO,
    val fits: List<DistributionFitDTO>,
    val recommendedFamilyId: String?,
    val histogram: HistogramDTO? = null,
    val scoring: ModaResultDTO? = null,
    val bootstrapFamilyFrequency: Map<String, Int>? = null
)
