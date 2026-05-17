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

import net.peanuuutz.tomlkt.Toml

/**
 * TOML codec for [RunConfiguration].
 *
 * Uses `tomlkt`, which integrates directly with `kotlinx-serialization`.  The same
 * `@Serializable` types — including those with custom serializers such as
 * [ksl.controls.experiments.DurationSerializer] in [ksl.controls.experiments.ExperimentRunParameters]
 * — work identically for both TOML and JSON without any additional DTO layer.
 *
 * TOML is the preferred format for hand-authored configuration files because it supports
 * comments, section headers (`[modelReference]`, `[tracingConfig]`), and array-of-tables
 * syntax (`[[rvOverrides]]`) that is cleaner than equivalent JSON.
 *
 * ## Example output snippet
 *
 * ```toml
 * [modelReference]
 * type = "byProviderId"
 * providerId = "MM1"
 *
 * [experimentRunParameters]
 * experimentName = "Experiment1"
 * numberOfReplications = 10
 * lengthOfReplication = 500.0
 * # ...
 *
 * [[rvOverrides]]
 * rvName    = "MM1:ServiceTime"
 * paramName = "mean"
 * value     = 2.0
 * ```
 */
object RunConfigurationToml {

    /**
     * Codec configured with `explicitNulls = false` so nullable fields
     * that are `null` are omitted from the encoded output entirely.
     * This keeps hand-edited configurations tidy: an unedited
     * `ExperimentRunOverrides` has 12 null fields, all of which would
     * otherwise appear under `[scenarios.runOverrides]` as
     * `field = null` lines and confuse a user reading the file.  With
     * this setting they vanish.  Decode-time semantics are
     * symmetrical — missing fields take the property's declared
     * default (which is `null` for every override field).
     */
    private val myToml = Toml {
        explicitNulls = false
    }

    /** Serialises [config] to a TOML string. */
    fun encode(config: RunConfiguration): String =
        myToml.encodeToString(RunConfiguration.serializer(), config)

    /** Deserialises a [RunConfiguration] from a TOML string produced by [encode]. */
    fun decode(text: String): RunConfiguration =
        myToml.decodeFromString(RunConfiguration.serializer(), text)
}
