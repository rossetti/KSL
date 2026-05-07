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
 * TOML codec for [ModelRunTemplate].
 *
 * TOML is useful for hand-authored model templates because it represents the
 * model reference, builder-level string configuration, run parameters, and
 * override lists with readable sections and array-of-table syntax.
 */
object ModelRunTemplateToml {

    private val myToml = Toml

    /** Serializes [template] to a TOML string. */
    fun encode(template: ModelRunTemplate): String =
        myToml.encodeToString(ModelRunTemplate.serializer(), template)

    /** Deserializes a [ModelRunTemplate] from a TOML string produced by [encode]. */
    fun decode(text: String): ModelRunTemplate =
        myToml.decodeFromString(ModelRunTemplate.serializer(), text)
}
