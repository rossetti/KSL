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
 * JSON codec for [ModelRunTemplate].
 *
 * This mirrors [RunConfigurationJson] so nested or standalone model template
 * documents use the same serialization settings as the rest of the app-layer
 * configuration framework.
 */
object ModelRunTemplateJson {

    private val myJson = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    /** Serializes [template] to a pretty-printed JSON string. */
    fun encode(template: ModelRunTemplate): String =
        myJson.encodeToString(ModelRunTemplate.serializer(), template)

    /** Deserializes a [ModelRunTemplate] from a JSON string produced by [encode]. */
    fun decode(text: String): ModelRunTemplate =
        myJson.decodeFromString(ModelRunTemplate.serializer(), text)
}
