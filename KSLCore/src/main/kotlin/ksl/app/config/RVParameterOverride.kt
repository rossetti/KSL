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

package ksl.app.config

import kotlinx.serialization.Serializable

/**
 * A human-authored override for a single random-variable parameter.
 *
 * Unlike [ksl.utilities.random.rvariable.parameters.RVParameterData] — which is a
 * full database-style DTO carrying runtime-derived metadata (`clazzName`, `elementId`,
 * `dataType`) that a config-file author cannot know — this type holds only the three
 * fields that need to be specified to override an RV's parameter.
 *
 * [RunConfiguration.buildModel] groups a list of overrides by [rvName] and applies them
 * via [ksl.utilities.random.rvariable.parameters.RVParameterSetter.changeParameters],
 * which accepts the `Map<rvName, Map<paramName, value>>` form that
 * [ksl.simulation.ModelDescriptor.rvParameterMap] also produces.
 *
 * ## Example (TOML)
 *
 * ```toml
 * [[rvOverrides]]
 * rvName    = "MM1:ServiceTime"
 * paramName = "mean"
 * value     = 2.0
 * ```
 *
 * @property rvName    name of the [ksl.modeling.variable.RandomVariable] as registered in the model
 * @property paramName parameter name (e.g. `"mean"` for [ksl.utilities.random.rvariable.ExponentialRV])
 * @property value     new parameter value
 */
@Serializable
data class RVParameterOverride(
    val rvName: String,
    val paramName: String,
    val value: Double
)
