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
 * One entry in a `FitSpec.Batch`: a human-readable name paired with the
 * analysis configuration for that dataset. The name labels the entry in
 * batch results, events, and the cross-dataset summary report; for batches
 * produced by expanding a multi-dataset source it is the dataset name.
 */
@Serializable
data class NamedFitConfiguration(
    @TomlComment("String. Human-readable dataset name; labels this entry in results, events, and reports.")
    val name: String,
    @TomlComment("The fit configuration for this dataset (data source, distribution kind, estimators, scoring).")
    val config: FitConfiguration
)
