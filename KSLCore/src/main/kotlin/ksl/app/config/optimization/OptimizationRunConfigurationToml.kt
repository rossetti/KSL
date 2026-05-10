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

package ksl.app.config.optimization

import net.peanuuutz.tomlkt.Toml

/**
 * TOML codec for [OptimizationRunConfiguration].
 *
 * Uses `tomlkt`, which integrates directly with `kotlinx-serialization`.
 * The same `@Serializable` types serve both JSON and TOML.
 *
 * TOML is the preferred format for hand-authored optimization
 * configurations because it supports comments, table-header syntax, and
 * array-of-tables syntax that read more cleanly than JSON for nested
 * structures.
 *
 * Sealed-class polymorphism is encoded via the standard
 * `kotlinx-serialization` discriminator pattern; tomlkt emits the
 * discriminator as a `type = "..."` key on the table representing the
 * sealed value.  This applies to [SolverSpec], [TemperatureSpec],
 * [CoolingScheduleSpec], and [CESamplerSpec].
 */
object OptimizationRunConfigurationToml {

    private val myToml = Toml

    /** Serialises [config] to a TOML string. */
    fun encode(config: OptimizationRunConfiguration): String =
        myToml.encodeToString(OptimizationRunConfiguration.serializer(), config)

    /** Deserialises an [OptimizationRunConfiguration] from a TOML string
     *  produced by [encode]. */
    fun decode(text: String): OptimizationRunConfiguration =
        myToml.decodeFromString(OptimizationRunConfiguration.serializer(), text)
}
