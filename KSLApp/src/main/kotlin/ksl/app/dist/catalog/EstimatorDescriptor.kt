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

package ksl.app.dist.catalog

import ksl.app.dist.config.DistributionKind
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc
import ksl.utilities.random.rvariable.RVParametersTypeIfc

/**
 * Self-describing record for one parameter-estimation algorithm registered
 * with the fitting catalog. The `id` is the stable, wire-safe handle that
 * configurations and DTOs use; the `factory` materializes a fresh KSL
 * estimator instance on demand.
 *
 * `checksRange` mirrors `ParameterEstimatorIfc.checkRange` and indicates
 * whether the estimator requires a positive-domain shift check before
 * estimation; it is the same flag PDFModeler consults to decide whether
 * automatic shifting applies for this estimator.
 *
 * `kind` partitions estimators between the continuous (PDF) and discrete
 * (PMF) fitting paths; the validator rejects cross-kind requests.
 *
 * `familyId` is the catalog ID for the distribution family this estimator
 * targets, derived from its `rvType`. Two estimators (e.g. method-of-
 * moments and maximum-likelihood for Gamma) can share a family.
 */
data class EstimatorDescriptor(
    val id: String,
    val displayName: String,
    val kind: DistributionKind,
    val rvType: RVParametersTypeIfc,
    val familyId: String,
    val checksRange: Boolean,
    val factory: () -> ParameterEstimatorIfc
)
