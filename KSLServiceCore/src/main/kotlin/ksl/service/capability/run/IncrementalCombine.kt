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

package ksl.service.capability.run

import ksl.service.capability.run.dto.ResponseStatDto
import ksl.service.capability.run.dto.RunResultDto
import ksl.utilities.distributions.StudentT
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Combines a cached *N*-replication result with a freshly-run "next *M−N*"
 * result into the exact *M*-replication result — the heart of incremental-
 * replication caching (Option B: sufficient statistics).
 *
 * Soundness rests on two facts established by the substrate study:
 * 1. **Replication *i* is deterministic in the substream index *i*, independent
 *    of N** (each `Model` owns its own `RNStreamProvider`; substream-per-rep with
 *    `advanceNextSubStreamOption`). So a cached run over substreams `0..N-1` and a
 *    top-up over substreams `N..M-1` together cover exactly the same replications
 *    a monolithic M-rep run would.
 * 2. The across-replication summary already carries the **sufficient statistics**
 *    `(count, sum, deviationSumOfSquares)` per response, so the union sample's
 *    mean and variance reconstruct *exactly* via Chan's parallel combination —
 *    no per-replication arrays (which the substrate deliberately keeps out of the
 *    default result for large response/replication counts).
 *
 * Note: this is **not** `EstimatedResponse.merge`, which pools the *within-group*
 * variance for ranking & selection and drops the between-group mean term. Here we
 * need the variance of the *union* sample, which keeps that term.
 */
object IncrementalCombine {

    /**
     * Chan's parallel combination of two disjoint samples of the same response.
     * Reconstructs the union sample's mean, variance, and (Student-t) half-width
     * exactly — equal to a monolithic run up to floating-point accumulation.
     */
    fun responses(a: ResponseStatDto, b: ResponseStatDto): ResponseStatDto {
        val nA = a.count ?: return b
        val nB = b.count ?: return a
        val sumA = a.sum ?: error("response '${a.name}' lacks sufficient statistics (sum)")
        val sumB = b.sum ?: error("response '${b.name}' lacks sufficient statistics (sum)")
        val m2A = a.deviationSumOfSquares ?: error("response '${a.name}' lacks sufficient statistics (deviationSumOfSquares)")
        val m2B = b.deviationSumOfSquares ?: error("response '${b.name}' lacks sufficient statistics (deviationSumOfSquares)")

        val n = nA + nB
        val sum = sumA + sumB
        val mean = sum / n
        val delta = (sumB / nB) - (sumA / nA)
        // M2_combined = M2_A + M2_B + delta^2 * nA * nB / n   (Chan/Welford merge)
        val m2 = m2A + m2B + delta * delta * nA * nB / n
        val variance = if (n > 1.0) m2 / (n - 1.0) else Double.NaN
        val stdDev = sqrt(variance)
        val stdErr = if (n >= 1.0) stdDev / sqrt(n) else Double.NaN
        val level = a.confLevel ?: 0.95
        val halfWidth = if (n > 1.0) {
            StudentT.invCDF(n - 1.0, 1.0 - (1.0 - level) / 2.0) * stdErr
        } else {
            Double.NaN
        }
        return ResponseStatDto(
            name = a.name,
            count = n,
            average = mean,
            // A pooled single observation has no dispersion (NaN); null it out — NaN is not a valid JSON
            // number and would fail MCP output-schema validation downstream.
            stdDev = stdDev.takeIf { it.isFinite() },
            stdErr = stdErr.takeIf { it.isFinite() },
            halfWidth = halfWidth.takeIf { it.isFinite() },
            confLevel = level,
            min = minOrNull(a.min, b.min),
            max = maxOrNull(a.max, b.max),
            sum = sum,
            deviationSumOfSquares = m2,
        )
    }

    /**
     * Combines two `Completed` results (a cached one and its top-up) into the
     * combined result, matching responses by name. The summary's replication
     * counts become the total; other summary fields are taken from [cached]
     * (run metadata, not result content).
     */
    fun completed(cached: RunResultDto.Completed, topUp: RunResultDto.Completed): RunResultDto.Completed {
        val topByName = topUp.responses.associateBy { it.name }
        val merged = cached.responses.map { c ->
            topByName[c.name]?.let { responses(c, it) } ?: c
        }
        val total = (cached.summary.completedReplications) + (topUp.summary.completedReplications)
        return RunResultDto.Completed(
            summary = cached.summary.copy(
                requestedReplications = total,
                completedReplications = total,
                endTime = topUp.summary.endTime,
            ),
            responses = merged,
            artifacts = cached.artifacts,
        )
    }

    private fun minOrNull(a: Double?, b: Double?): Double? =
        if (a != null && b != null) min(a, b) else a ?: b

    private fun maxOrNull(a: Double?, b: Double?): Double? =
        if (a != null && b != null) max(a, b) else a ?: b
}
