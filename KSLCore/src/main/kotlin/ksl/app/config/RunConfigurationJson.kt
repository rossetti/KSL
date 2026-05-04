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

import kotlinx.serialization.json.Json

/**
 * JSON codec for [RunConfiguration].
 *
 * Wraps the standard `kotlinx-serialization-json` serializer with settings appropriate
 * for run configuration documents:
 *
 * - `prettyPrint = true` — human-readable, reviewable output
 * - `encodeDefaults = true` — optional fields (e.g. [TracingConfig]) always appear in
 *   the output, making JSON files self-documenting and round-trip stable regardless of
 *   which defaults change in future versions
 * - `allowSpecialFloatingPointValues = true` — required because
 *   [ksl.controls.ControlData] bounds and
 *   [ksl.controls.experiments.ExperimentRunParameters.lengthOfReplication] can be `±Infinity`
 *
 * These settings mirror those used by [ksl.simulation.ModelDescriptor.toJson].
 */
object RunConfigurationJson {

    private val myJson = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    /** Serialises [config] to a pretty-printed JSON string. */
    fun encode(config: RunConfiguration): String =
        myJson.encodeToString(RunConfiguration.serializer(), config)

    /** Deserialises a [RunConfiguration] from a JSON string produced by [encode]. */
    fun decode(text: String): RunConfiguration =
        myJson.decodeFromString(RunConfiguration.serializer(), text)
}
