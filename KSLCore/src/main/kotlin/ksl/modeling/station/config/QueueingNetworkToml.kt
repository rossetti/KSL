/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.station.config

import kotlinx.serialization.json.Json
import net.peanuuutz.tomlkt.Toml

/**
 * TOML codec for [QueueingNetworkSpec], mirroring the app-config codecs. TOML's
 * tables and array-of-table syntax make hand-authored network descriptions
 * readable.
 */
object QueueingNetworkToml {

    private val myToml = Toml

    /** Serializes [spec] to a TOML string. */
    fun encode(spec: QueueingNetworkSpec): String =
        myToml.encodeToString(QueueingNetworkSpec.serializer(), spec)

    /** Deserializes a [QueueingNetworkSpec] from a TOML string produced by [encode]. */
    fun decode(text: String): QueueingNetworkSpec =
        myToml.decodeFromString(QueueingNetworkSpec.serializer(), text)
}

/** JSON codec for [QueueingNetworkSpec]. */
object QueueingNetworkJson {

    private val myJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Serializes [spec] to a JSON string. */
    fun encode(spec: QueueingNetworkSpec): String =
        myJson.encodeToString(QueueingNetworkSpec.serializer(), spec)

    /** Deserializes a [QueueingNetworkSpec] from a JSON string. */
    fun decode(text: String): QueueingNetworkSpec =
        myJson.decodeFromString(QueueingNetworkSpec.serializer(), text)
}
