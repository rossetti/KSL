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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 * Serialisable pointer to a model source.
 *
 * [ModelReference] stores only string data; it contains no live object references and is
 * safe to persist and transport.  Resolving a reference to a live
 * [ksl.simulation.Model] is done at configuration-application time by
 * `RunConfiguration.buildModel`.
 *
 * Four variants:
 * - [ByProviderId]        — looks the model up in a caller-supplied [ksl.simulation.ModelProviderIfc]
 * - [ByJar]               — loads the model's [ksl.simulation.ModelBuilderIfc] from a JAR file via
 *                           [ksl.utilities.io.JARModelBuilder]
 * - [ByBundleAndModelId]  — resolves a `(bundleId, modelId)` pair against a `BundleModelProvider`
 * - [Embedded]            — marker that the model is supplied in-process by the originating
 *                           framework (Single-app); the saved file is intentionally non-portable
 *                           and consuming apps surface a distinguished error
 *
 * ## Serialised form
 *
 * The sealed hierarchy uses the standard `kotlinx-serialization` class discriminator.
 * The `@SerialName` annotations on each subclass produce short, human-readable type
 * tags in both JSON and TOML:
 *
 * ```toml
 * [modelReference]
 * type = "byProviderId"
 * providerId = "MM1"
 * ```
 *
 * ```json
 * "modelReference": { "type": "byProviderId", "providerId": "MM1" }
 * ```
 */
@Serializable
sealed class ModelReference {

    /**
     * References a model registered in a [ksl.simulation.ModelProviderIfc] by its
     * identifier string.  The provider itself must be supplied at run time via
     * `RunConfiguration.buildModel`.
     *
     * @property providerId the key passed to [ksl.simulation.ModelProviderIfc.provideModel]
     */
    @Serializable
    @SerialName("byProviderId")
    data class ByProviderId(
        @TomlComment(
            "String (required, non-blank). The id passed to\n" +
            "ModelProviderIfc.provideModel() at run time.  Use when the\n" +
            "consuming app supplies a provider keyed on a flat id space."
        )
        val providerId: String
    ) : ModelReference() {
        init {
            require(providerId.isNotBlank()) { "providerId must be non-blank" }
        }
    }

    /**
     * References a model whose [ksl.simulation.ModelBuilderIfc] is loaded from a JAR file
     * via [ksl.utilities.io.JARModelBuilder].
     *
     * @property jarPath          file-system path to the JAR; resolved at run time
     * @property builderClassName fully qualified name of the [ksl.simulation.ModelBuilderIfc]
     *                            class inside the JAR; `null` → the first implementing class
     *                            found in the JAR is used (see [ksl.utilities.io.JARModelBuilder])
     */
    @Serializable
    @SerialName("byJar")
    data class ByJar(
        @TomlComment(
            "String (required). Filesystem path to a JAR containing a\n" +
            "ModelBuilderIfc implementation.  Resolved at run time."
        )
        val jarPath: String,

        @TomlComment(
            "String (optional). Fully-qualified class name inside the\n" +
            "JAR.  Omit to use the first ModelBuilderIfc implementation\n" +
            "the JAR loader finds (the typical case)."
        )
        val builderClassName: String? = null
    ) : ModelReference() {
        init {
            require(jarPath.isNotBlank()) { "jarPath must be non-blank" }
            require(builderClassName == null || builderClassName.isNotBlank()) {
                "builderClassName must be non-blank when non-null"
            }
        }
    }

    /**
     * References a model unambiguously by the pair of its enclosing bundle
     * and the model identifier within that bundle.  Use when a document
     * references multiple bundles and a scenario must select exactly one
     * of them (the `byProviderId` variant relies on flat-`modelId` lookup
     * which is ambiguous in a multi-bundle document).
     *
     * The runtime resolves this reference by calling
     * `ksl.app.bundle.BundleModelProvider.provideModel(bundleId, modelId)`
     * against the provider built from the document's `bundleRefs`.
     *
     * @property bundleId the enclosing bundle's `KSLModelBundle.bundleId`;
     *                    must be non-blank and must match a `BundleRef`
     *                    declared at the document level
     * @property modelId  the `KSLBundledModel.modelId` within that bundle;
     *                    must be non-blank
     */
    @Serializable
    @SerialName("byBundleAndModelId")
    data class ByBundleAndModelId(
        @TomlComment(
            "String (required, non-blank). The KSLModelBundle's bundleId\n" +
            "as declared by the bundle JAR.  Must match a bundleId in the\n" +
            "document's [[bundleRefs]] list."
        )
        val bundleId: String,

        @TomlComment(
            "String (required, non-blank). The model identifier within\n" +
            "the bundle (KSLBundledModel.modelId)."
        )
        val modelId: String
    ) : ModelReference() {
        init {
            require(bundleId.isNotBlank()) { "bundleId must be non-blank" }
            require(modelId.isNotBlank()) { "modelId must be non-blank" }
        }
    }

    /**
     * Marker that the originating application held its model in-process
     * (as Kotlin code, not as a JAR or bundle) and the saved configuration
     * therefore does not contain a portable pointer to it.  Written by the
     * Single-app framework; resolved at run time by handing the framework's
     * own [ksl.simulation.ModelProviderIfc] — keyed on [modelName] — to the
     * orchestrator.
     *
     * Consuming apps that do not host the originating model surface a
     * distinguished validation error (`MODEL_REFERENCE_EMBEDDED_NOT_RESOLVABLE`)
     * rather than the generic "id not found" reported for an unresolved
     * [ByProviderId].  The intent of the variant is to make cross-app
     * inspection of saved configurations honest: the document records
     * "the author embedded the model" rather than implying a portable
     * provider lookup.
     *
     * @property modelName the simulation model's `Model.name`; also the id
     *                     the framework's [ksl.simulation.ModelProviderIfc]
     *                     uses to find the model at run time
     */
    @Serializable
    @SerialName("embedded")
    data class Embedded(
        @TomlComment(
            "String (required, non-blank). The simulation Model.name as\n" +
            "set by the in-process author.  Marks that the model lived\n" +
            "as Kotlin code at save time and is not portable to a host\n" +
            "that doesn't carry the same code; consuming apps surface a\n" +
            "distinguished validation error on unresolvable embedded refs."
        )
        val modelName: String
    ) : ModelReference() {
        init {
            require(modelName.isNotBlank()) { "modelName must be non-blank" }
        }
    }
}
