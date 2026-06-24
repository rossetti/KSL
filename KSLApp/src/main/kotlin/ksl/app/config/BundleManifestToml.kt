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
 * TOML codec for [BundleManifest] — the data-only description of a bundle JAR.
 *
 * Sibling of [ModelCatalogToml] and [RunConfigurationToml]: it uses `tomlkt` over
 * the `@Serializable` [BundleManifest] type, so the manifest round-trips through
 * TOML with no additional DTO layer. TOML is preferred for the manifest because it
 * supports comments and the `[[models]]` / `[[models.recipes]]` array-of-tables
 * syntax a human may want to read or hand-tweak.
 *
 * ## Example output snippet
 *
 * ```toml
 * bundleId      = "edu.uark.examples.mm1"
 * displayName   = "M/M/1 Queue Example"
 * version       = "1.0.0"
 * kslApiVersion = "1.2"
 *
 * [[models]]
 * modelId       = "MM1"
 * builderClass  = "ksl.examples.mm1.MM1Builder"
 * displayName   = "M/M/1 Queue"
 * supportedApps = ["SINGLE", "SCENARIO", "EXPERIMENT", "SIMOPT"]
 *
 *   [[models.recipes]]
 *   name = "light-load"
 *   kind = "RUN"
 *   path = "META-INF/ksl/models/MM1/run/light-load.toml"
 * ```
 */
object BundleManifestToml {

    /**
     * Codec configured with `explicitNulls = false` so optional identity fields
     * (`author` / `homepage` / `license`) are omitted from the encoded output when
     * unset, keeping a hand-edited `bundle.toml` tidy. Decode is symmetrical — a
     * missing optional field takes its declared default.
     */
    private val myToml = Toml {
        explicitNulls = false
    }

    /** Serialises [manifest] to a TOML string, prefixed with [DOCUMENT_HEADER]. */
    fun encode(manifest: BundleManifest): String =
        DOCUMENT_HEADER + myToml.encodeToString(BundleManifest.serializer(), manifest)

    /** Deserialises a [BundleManifest] from a TOML string produced by [encode]. */
    fun decode(text: String): BundleManifest =
        myToml.decodeFromString(BundleManifest.serializer(), text)

    /**
     * Multi-line `#`-prefixed banner prepended to every encoded TOML file. Read by
     * humans editing the file; ignored by the decoder.
     */
    private val DOCUMENT_HEADER: String = """
        # ────────────────────────────────────────────────────────────────────────────
        #  KSL Bundle Manifest
        # ────────────────────────────────────────────────────────────────────────────
        #
        #  The authoritative description of this bundle JAR. When this file is present
        #  at META-INF/ksl/bundle.toml the KSL runtime loader builds the bundle from it
        #  (a single reusable ManifestBackedBundle), so the JAR needs no compiled
        #  KSLModelBundle class and no META-INF/services registration.
        #
        #  Top-level keys are the bundle's identity. Each [[models]] table describes one
        #  packaged model: its modelId, the fully-qualified name of its ModelBuilderIfc
        #  implementation (builderClass), its display labels, the app kinds it supports,
        #  and any author-curated recipes ([[models.recipes]]) shipped alongside it.
        #
        #  Reference: https://rossetti.github.io/KSLBook/
        #
        # ────────────────────────────────────────────────────────────────────────────

        """.trimIndent()
}
