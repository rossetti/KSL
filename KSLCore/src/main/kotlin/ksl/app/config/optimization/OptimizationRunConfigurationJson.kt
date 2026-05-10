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

import kotlinx.serialization.json.Json

/**
 * JSON codec for [OptimizationRunConfiguration].
 *
 * Mirrors the configuration of [ksl.app.config.RunConfigurationJson]:
 *
 * - `prettyPrint = true` — human-readable output;
 * - `encodeDefaults = true` — optional fields always appear in the output
 *   so JSON files are self-documenting and round-trip stable across future
 *   default changes;
 * - `allowSpecialFloatingPointValues = true` — required because nested
 *   types (e.g. control bounds, replication length) may carry `±Infinity`.
 *
 * Sealed-class variants of [SolverSpec], [TemperatureSpec],
 * [CoolingScheduleSpec], and [CESamplerSpec] use kotlinx-serialization's
 * default polymorphic encoding with a `"type"` discriminator carrying the
 * `@SerialName` value of each subtype.
 */
object OptimizationRunConfigurationJson {

    private val myJson = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    /** Serialises [config] to a pretty-printed JSON string. */
    fun encode(config: OptimizationRunConfiguration): String =
        myJson.encodeToString(OptimizationRunConfiguration.serializer(), config)

    /** Deserialises an [OptimizationRunConfiguration] from a JSON string
     *  produced by [encode]. */
    fun decode(text: String): OptimizationRunConfiguration =
        myJson.decodeFromString(OptimizationRunConfiguration.serializer(), text)
}
