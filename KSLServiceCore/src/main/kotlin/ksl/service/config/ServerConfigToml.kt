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

package ksl.service.config

import net.peanuuutz.tomlkt.Toml

/**
 * TOML codec for [ServerConfig], using the same `tomlkt` the KSLCore config
 * codecs use (so the server config reads and writes like every other KSL
 * document). `explicitNulls = false` omits unset optional fields (e.g. an
 * absent `bundles.dir`) rather than emitting `dir = null`; `ignoreUnknownKeys`
 * keeps an older or newer file forward/backward compatible.
 */
object ServerConfigToml {

    private val myToml = Toml {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** Serializes [config] to TOML, prefixed with a human-oriented banner. */
    fun encode(config: ServerConfig): String =
        DOCUMENT_HEADER + myToml.encodeToString(ServerConfig.serializer(), config)

    /** Deserializes a [ServerConfig] from TOML; missing sections take defaults. */
    fun decode(text: String): ServerConfig =
        myToml.decodeFromString(ServerConfig.serializer(), text)

    private val DOCUMENT_HEADER: String = """
        # ────────────────────────────────────────────────────────────────────────────
        #  KSL Server Configuration  (~/.ksl/config.toml)
        # ────────────────────────────────────────────────────────────────────────────
        #
        #  Configures the KSL MCP and REST servers. Every setting has a default, so
        #  this whole file is optional. Precedence is: environment variable >
        #  this file > built-in default — so an operator can override one setting
        #  via env without editing the file.
        #
        #    [bundles]   The watched bundle directory and poll interval (the dynamic
        #                catalog: drop a JAR there and its models load with no restart).
        #    [cache]     The result-cache directory and retention caps (results are
        #                content-addressed; oldest are evicted past maxDiskEntries).
        #    [server]    Listen ports and the concurrent-job limit.
        #
        #  A leading "~" in a path is expanded to the user home.
        #
        # ────────────────────────────────────────────────────────────────────────────

        """.trimIndent()
}
