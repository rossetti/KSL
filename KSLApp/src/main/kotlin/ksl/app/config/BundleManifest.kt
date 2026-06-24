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

import kotlinx.serialization.Serializable
import ksl.app.bundle.KSLAppKind

/**
 * The authoritative, data-only description of a KSL **bundle JAR**, shipped inside
 * the JAR at `ksl.app.bundle.BundleLayout.BUNDLE_TOML`
 * (`META-INF/ksl/bundle.toml`).
 *
 * A bundle JAR is produced by *enriching* a plain "builders JAR" — a JAR whose only
 * required content is one or more named `ksl.simulation.ModelBuilderIfc`
 * implementations (each with a public zero-argument constructor). The enrichment
 * tooling discovers those builders and writes this manifest; at runtime the loader
 * detects the manifest and constructs a single reusable
 * `ksl.app.bundle.ManifestBackedBundle` that *interprets* it. No per-bundle
 * `KSLModelBundle` class is compiled — bundle-ness is carried by this data.
 *
 * The manifest mirrors the `ksl.app.bundle.KSLModelBundle` SPI surface: the
 * top-level fields are the bundle's identity, and [models] supplies one entry per
 * packaged model (its `modelId`, the FQN of its builder class, its human labels,
 * and its supported app kinds).
 *
 * @param bundleId       globally unique, stable bundle identifier
 * @param displayName    human-readable bundle name
 * @param description    short bundle description
 * @param version        bundle-author's content version (semver encouraged)
 * @param kslApiVersion  major.minor of the KSL API the bundle was built against
 * @param author         optional bundle author or organisation
 * @param homepage       optional project/documentation URL
 * @param license        optional license identifier (e.g. an SPDX id)
 * @param tags           optional free-form tags for cataloging and search
 * @param models         the models packaged in this bundle
 */
@Serializable
data class BundleManifest(
    val bundleId: String,
    val displayName: String,
    val description: String,
    val version: String,
    val kslApiVersion: String,
    val author: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val tags: Set<String> = emptySet(),
    val models: List<ModelManifestEntry> = emptyList(),
)

/**
 * One model's entry in a [BundleManifest], mirroring the
 * `ksl.app.bundle.KSLBundledModel` surface.
 *
 * @param builderClass   FQN of a `ksl.simulation.ModelBuilderIfc` implementation
 *                       resolvable on the bundle JAR's classloader; it must expose
 *                       a public zero-argument constructor (or be a Kotlin `object`)
 *                       so the loader can instantiate it reflectively
 * @param modelId        stable, filesystem-safe id, unique within this bundle
 * @param displayName    human-readable model name
 * @param description    short model description
 * @param supportedApps  the app kinds this model claims to support
 */
@Serializable
data class ModelManifestEntry(
    val modelId: String,
    val builderClass: String,
    val displayName: String,
    val description: String = "",
    val supportedApps: Set<KSLAppKind> = emptySet(),
)
