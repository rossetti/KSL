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

import ksl.simulation.ModelCatalog
import ksl.simulation.ModelDescriptor
import net.peanuuutz.tomlkt.Toml

/**
 * TOML codec for [ModelCatalog] — the author-curated shortlist of a model's
 * headline inputs and outputs.
 *
 * This is the sibling of [RunConfigurationToml]: it uses `tomlkt` over the same
 * `@Serializable` types, so the resolved catalog round-trips through TOML with no
 * additional DTO layer. TOML is preferred for hand-authored catalog files because
 * it supports comments and the `[[nominatedInputs]]` / `[[nominatedOutputs]]`
 * array-of-tables syntax.
 *
 * ## Scope of the round-trip
 *
 * The codec round-trips the **resolved** [ModelCatalog] (the flat nominated-input
 * and nominated-output lists), *not* the imperative `Model.curateCatalog` DSL. The
 * lossless direction is DSL → resolved catalog → TOML (see [encodeFrom]); the
 * declarative subset of the DSL can be regenerated from TOML, but the DSL's
 * imperative curation (`denominate*`, predicate removal) is deliberately not
 * represented here.
 *
 * ## Example output snippet
 *
 * ```toml
 * [[nominatedInputs]]
 * key = "MM1Queue.numServers"
 * kind = "NUMERIC_CONTROL"
 * displayName = "Number of Servers"
 * unit = "servers"
 *
 * [[nominatedOutputs]]
 * name = "systemTime"
 * displayName = "Avg Time in System"
 * unit = "min"
 * ```
 */
object ModelCatalogToml {

    /**
     * Codec configured with `explicitNulls = false` so the optional
     * `displayName` / `description` / `unit` fields are omitted from the encoded
     * output when unset, keeping a hand-edited `catalog.toml` tidy. Decode is
     * symmetrical — a missing optional field takes its declared default (`null`).
     */
    private val myToml = Toml {
        explicitNulls = false
    }

    /** Serialises [catalog] to a TOML string, prefixed with [DOCUMENT_HEADER]. */
    fun encode(catalog: ModelCatalog): String =
        DOCUMENT_HEADER + myToml.encodeToString(ModelCatalog.serializer(), catalog)

    /** Deserialises a [ModelCatalog] from a TOML string produced by [encode]. */
    fun decode(text: String): ModelCatalog =
        myToml.decodeFromString(ModelCatalog.serializer(), text)

    /**
     * Convenience for the lossless DSL → TOML direction: encodes the catalog the
     * model developer authored via `Model.curateCatalog`, as captured in
     * [ModelDescriptor.catalog]. A model that nominated nothing (null catalog)
     * encodes as an empty catalog rather than failing.
     */
    fun encodeFrom(descriptor: ModelDescriptor): String =
        encode(descriptor.catalog ?: ModelCatalog())

    /**
     * Multi-line `#`-prefixed banner prepended to every encoded TOML file. Read by
     * humans editing the file; ignored by the decoder.
     */
    private val DOCUMENT_HEADER: String = """
        # ────────────────────────────────────────────────────────────────────────────
        #  KSL Model Catalog
        # ────────────────────────────────────────────────────────────────────────────
        #
        #  An author-curated shortlist of a model's most important inputs and outputs,
        #  layered over the model's full descriptor. Applications use it to surface the
        #  salient knobs first and pre-select headline results; it is optional and a
        #  consumer must not depend on its presence.
        #
        #  When this file ships inside a bundle JAR at
        #  META-INF/ksl/models/<modelId>/catalog.toml it is the authoritative catalog:
        #  the runtime loader overlays it onto the model descriptor, replacing any
        #  catalog baked into descriptor.json. Entries whose key/name no longer resolve
        #  against the model are dropped on load (with a warning), and each input's
        #  kind is re-derived from the model, so it need not be hand-maintained.
        #
        #  Layout:
        #    [[nominatedInputs]]   One table per nominated input. `key` is a control
        #                          keyName ("elementName.propertyName") or a flattened
        #                          RV-parameter key ("rvName.paramName"). `kind` is
        #                          re-derived on load; displayName/description/unit are
        #                          optional human labels.
        #    [[nominatedOutputs]]  One table per nominated output. `name` is a response
        #                          or counter name; displayName/description/unit optional.
        #
        #  List order conveys priority (first = most prominent).
        #
        #  Reference: https://rossetti.github.io/KSLBook/
        #
        # ────────────────────────────────────────────────────────────────────────────

        """.trimIndent()
}
