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
 * Opt-in request for engine-side bootstrap of the fitted parameters. When a
 * `FitConfiguration` carries a non-null `BootstrapConfig`, the engine performs
 * the resampling and returns summary results only (estimate, bias, MSE,
 * standard error, and confidence intervals) — never the raw replicate arrays.
 * A null bootstrap config skips bootstrapping entirely.
 *
 * Every field surfaces a parameter of the engine's
 * `PDFModeler.bootStrapParameterEstimates` call, with the engine's own
 * defaults. Fixing `streamNumber` makes the bootstrap reproducible.
 *
 * @param sampleSize number of bootstrap resamples; must be > 0
 * @param level      confidence level for the reported intervals; in (0, 1)
 * @param streamNumber random-number stream number for reproducibility
 */
@Serializable
data class BootstrapConfig(
    val sampleSize: Int = 399,
    val level: Double = 0.95,
    val streamNumber: Int = 0
)
