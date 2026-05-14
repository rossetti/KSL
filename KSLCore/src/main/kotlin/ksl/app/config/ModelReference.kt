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

/**
 * Serialisable pointer to a model source.
 *
 * [ModelReference] stores only string data; it contains no live object references and is
 * safe to persist and transport.  Resolving a reference to a live
 * [ksl.simulation.Model] is done at configuration-application time by
 * [RunConfiguration.buildModel].
 *
 * Two variants:
 * - [ByProviderId] — looks the model up in a caller-supplied [ksl.simulation.ModelProviderIfc]
 * - [ByJar]        — loads the model's [ksl.simulation.ModelBuilderIfc] from a JAR file via
 *                    [ksl.utilities.io.JARModelBuilder]
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
     * [RunConfiguration.buildModel].
     *
     * @property providerId the key passed to [ksl.simulation.ModelProviderIfc.provideModel]
     */
    @Serializable
    @SerialName("byProviderId")
    data class ByProviderId(val providerId: String) : ModelReference() {
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
        val jarPath: String,
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
        val bundleId: String,
        val modelId: String
    ) : ModelReference() {
        init {
            require(bundleId.isNotBlank()) { "bundleId must be non-blank" }
            require(modelId.isNotBlank()) { "modelId must be non-blank" }
        }
    }
}
