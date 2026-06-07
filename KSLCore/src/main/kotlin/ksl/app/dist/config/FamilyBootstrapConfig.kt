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
 * Opt-in configuration for the family-frequency bootstrap analysis — a separate
 * (continuous-only) analysis that resamples the data [numSamples] times, re-runs
 * the full fit + evaluation on each resample, and tallies how often each family
 * is recommended. It quantifies recommendation/model-selection stability.
 *
 * The estimators, scoring models, evaluation method, and automatic shifting are
 * reused from the enclosing `FitConfiguration` (it bootstraps the same fitting
 * process). A positive [streamNumber] makes the analysis reproducible; `0`
 * draws the next stream. When this config is absent, the analysis is not run.
 */
@Serializable
data class FamilyBootstrapConfig(
    @TomlComment("Integer. Number of resamples used to tally family recommendation stability; must be greater than 0.")
    val numSamples: Int = 400,
    @TomlComment("Integer. Random-number stream number; a positive value makes the analysis reproducible.")
    val streamNumber: Int = 0
)
