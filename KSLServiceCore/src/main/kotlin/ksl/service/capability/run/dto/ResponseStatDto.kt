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

package ksl.service.capability.run.dto

import kotlinx.serialization.Serializable

/**
 * The headline across-replication statistic for one response, projected from
 * the database-row-shaped `AcrossRepStatTableData`.
 *
 * The projection deliberately drops that type's DB surrogate keys
 * (`id`, `element_id_fk`, `sim_run_id_fk`) and its long statistical tail
 * (kurtosis, lag-1 correlation, von Neumann, …), keeping only the nine fields
 * a client reads to answer a question. The tail can be promoted into this DTO
 * later behind a `detail` flag as an all-nullable additive change (Phase 7
 * strategic plan §9.1 — the inline-vs-artifact policy).
 */
@Serializable
data class ResponseStatDto(
    val name: String,
    val count: Double? = null,
    val average: Double? = null,
    val stdDev: Double? = null,
    val stdErr: Double? = null,
    val halfWidth: Double? = null,
    val confLevel: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    /**
     * Sufficient statistics over the per-replication observations: the sum
     * (`Σx`) and the deviation sum of squares (`Σ(x−x̄)²`). Together with [count]
     * (and [min]/[max]) these let two disjoint runs of the same model be pooled
     * into the exact combined statistic — the basis of incremental-replication
     * caching, which keeps the per-response summary bounded (no per-replication
     * arrays). Carried from `AcrossRepStatTableData.sum_of_obs` / `dev_ssq`.
     */
    val sum: Double? = null,
    val deviationSumOfSquares: Double? = null,
)
