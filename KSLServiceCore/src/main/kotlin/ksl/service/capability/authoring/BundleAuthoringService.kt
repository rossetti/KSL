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

package ksl.service.capability.authoring

import kotlinx.serialization.Serializable
import ksl.app.bundle.BundleAuthoringSession
import ksl.app.bundle.BundleValidation
import ksl.app.bundle.KSLAppKind
import ksl.simulation.ModelCatalog
import ksl.simulation.ModelDescriptor
import ksl.simulation.NominatedInput
import ksl.simulation.NominatedInputKind
import ksl.simulation.NominatedOutput
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import java.nio.file.Path

// ─── Candidates (server → LLM): the raw material to annotate ────────────────────────

/** Every model an LLM can author in a builders JAR, with the discovery errors it could not. */
@Serializable
data class BundleCandidates(
    val models: List<ModelCandidate>,
    val discoveryErrors: List<String> = emptyList(),
)

/** One discovered model's annotatable inputs + outputs. [defaultModelId] is the id used unless the
 *  authoring request overrides it; [builderClass] is the stable key the request matches on. */
@Serializable
data class ModelCandidate(
    val builderClass: String,
    val defaultModelId: String,
    val inputs: List<InputCandidate>,
    val outputs: List<String>,
)

/** A single annotatable input, with the context an LLM needs to name it well: the current value,
 *  bounds/allowed-values/type, the `@KSLControl` comment, the owning element, and (for an RV
 *  parameter) the distribution class. */
@Serializable
data class InputCandidate(
    val key: String,
    val kind: String,
    val currentValue: String? = null,
    val lowerBound: Double? = null,
    val upperBound: Double? = null,
    val allowedValues: List<String>? = null,
    val typeHint: String? = null,
    val comment: String? = null,
    val element: String? = null,
    val elementPath: List<String> = emptyList(),
    val rvClass: String? = null,
)

// ─── Authoring request (LLM → server): the composed catalog + classification + identity ─────────

@Serializable
data class BundleAuthoringRequest(
    val identity: BundleIdentity,
    val models: List<ModelAuthoring> = emptyList(),
)

@Serializable
data class BundleIdentity(
    val bundleId: String,
    val displayName: String? = null,
    val description: String? = null,
    val version: String? = null,
    val kslApiVersion: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class ModelAuthoring(
    /** Matches a candidate's [ModelCandidate.builderClass]. */
    val builderClass: String,
    val modelId: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val include: Boolean = true,
    /** KSLAppKind names — SINGLE / SCENARIO / EXPERIMENT / SIMOPT. */
    val supportedApps: List<String> = listOf("SINGLE", "SCENARIO", "EXPERIMENT"),
    val catalog: CatalogAuthoring = CatalogAuthoring(),
)

/** The catalog metadata — display names/descriptions/units for the nominated inputs/outputs, in
 *  priority (list) order. */
@Serializable
data class CatalogAuthoring(
    val inputs: List<InputAnnotation> = emptyList(),
    val outputs: List<OutputAnnotation> = emptyList(),
)

@Serializable
data class InputAnnotation(val key: String, val displayName: String? = null, val description: String? = null, val unit: String? = null)

@Serializable
data class OutputAnnotation(val name: String, val displayName: String? = null, val description: String? = null, val unit: String? = null)

/** The result of an assemble attempt: [written] is the bundle path on success, null when validation
 *  failed (see [report]); [report] carries all findings either way. */
data class AssembleOutcome(val written: Path?, val report: BundleValidation.ValidationReport)

/**
 * The headless bundle-authoring capability for the server: discover a builders JAR's models as
 * annotatable candidates, then apply an LLM-authored payload (catalog + classification + identity)
 * and validate / assemble a bundle. A thin, stateless adapter over
 * [ksl.app.bundle.BundleAuthoringSession] — the server is plumbing; the LLM is the authoring
 * intelligence (the naming / describing / unit / classification).
 */
class BundleAuthoringService {

    /** The annotatable candidates for every model discoverable in [buildersJar]. */
    fun candidates(buildersJar: Path): BundleCandidates {
        val session = BundleAuthoringSession.open(buildersJar)
        val models = session.models.map { draft ->
            ModelCandidate(
                builderClass = draft.builderClass,
                defaultModelId = draft.modelId,
                inputs = inputCandidates(draft.descriptor),
                outputs = draft.descriptor.responseNames.toList(),
            )
        }
        val errors = session.discoveryErrors.map { "${it.builderClass}: ${it.error ?: "failed to build"}" }
        return BundleCandidates(models, errors)
    }

    /** Applies [request] and returns the validation findings **without writing** — a dry run. */
    fun preview(buildersJar: Path, request: BundleAuthoringRequest): BundleValidation.ValidationReport {
        val session = BundleAuthoringSession.open(buildersJar)
        val exclude = apply(session, request)
        return session.validate(exclude)
    }

    /** Applies [request], validates, and (only if there are no ERROR findings) assembles the bundle at
     *  [output] or the default `<stem>-bundle.jar`. */
    fun assemble(buildersJar: Path, request: BundleAuthoringRequest, output: Path? = null, force: Boolean = false): AssembleOutcome {
        val session = BundleAuthoringSession.open(buildersJar)
        val exclude = apply(session, request)
        val report = session.validate(exclude)
        if (report.errorCount > 0) return AssembleOutcome(written = null, report = report)
        val out = output ?: session.defaultOutputPath()
        session.assemble(out, force = force, excludeModelIds = exclude)
        return AssembleOutcome(written = out, report = report)
    }

    /** Applies the authored payload onto the session's mutable drafts (mirrors [BundleAuthoringSession]
     *  `openExisting`'s overlay), returning the set of excluded model ids. */
    private fun apply(session: BundleAuthoringSession, request: BundleAuthoringRequest): Set<String> {
        val id = request.identity
        session.bundleId = id.bundleId
        id.displayName?.let { session.displayName = it }
        id.description?.let { session.description = it }
        session.version = id.version
        session.kslApiVersion = id.kslApiVersion
        session.author = id.author
        session.homepage = id.homepage
        session.license = id.license
        session.tags.clear(); session.tags.addAll(id.tags)

        val exclude = linkedSetOf<String>()
        for (m in request.models) {
            val draft = session.models.firstOrNull { it.builderClass == m.builderClass } ?: continue
            m.modelId?.let { draft.modelId = it }
            m.displayName?.let { draft.displayName = it }
            m.description?.let { draft.description = it }
            val apps = m.supportedApps.mapNotNull { runCatching { KSLAppKind.valueOf(it) }.getOrNull() }
            if (apps.isNotEmpty()) { draft.supportedApps.clear(); draft.supportedApps.addAll(apps) }
            draft.catalog = m.catalog.toModelCatalog(draft.descriptor)
                .takeIf { it.nominatedInputs.isNotEmpty() || it.nominatedOutputs.isNotEmpty() }
            if (!m.include) exclude.add(draft.modelId)
        }
        return exclude
    }

    private fun inputCandidates(d: ModelDescriptor): List<InputCandidate> = buildList {
        d.controls.numericControls.forEach {
            add(InputCandidate(it.keyName, "NUMERIC_CONTROL", it.value.toString(), it.lowerBound, it.upperBound,
                comment = it.comment.ifBlank { null }, element = it.elementName, elementPath = it.elementPath))
        }
        d.controls.stringControls.forEach {
            add(InputCandidate(it.keyName, "STRING_CONTROL", it.value, allowedValues = it.allowedValues.ifEmpty { null },
                comment = it.comment.ifBlank { null }, element = it.elementName, elementPath = it.elementPath))
        }
        d.controls.jsonControls.forEach {
            add(InputCandidate(it.keyName, "JSON_CONTROL", it.jsonValue, typeHint = it.typeHint,
                comment = it.comment.ifBlank { null }, element = it.elementName, elementPath = it.elementPath))
        }
        d.rvParameterData.forEach {
            add(InputCandidate(
                "${it.rvName}${RVParameterSetter.rvParamConCatChar}${it.paramName}", "RV_PARAMETER",
                it.paramValue.toString(), element = it.parentElementName ?: it.rvName,
                elementPath = it.elementPath, rvClass = it.clazzName,
            ))
        }
    }
}

/** Projects the authored catalog into a KSLCore [ModelCatalog], deriving each input's kind from the
 *  descriptor (the kind is re-derived on load anyway; this keeps the in-memory catalog consistent). */
private fun CatalogAuthoring.toModelCatalog(d: ModelDescriptor): ModelCatalog {
    val numeric = d.controls.numericControls.map { it.keyName }.toSet()
    val string = d.controls.stringControls.map { it.keyName }.toSet()
    val json = d.controls.jsonControls.map { it.keyName }.toSet()
    fun kindOf(key: String): NominatedInputKind = when (key) {
        in numeric -> NominatedInputKind.NUMERIC_CONTROL
        in string -> NominatedInputKind.STRING_CONTROL
        in json -> NominatedInputKind.JSON_CONTROL
        else -> NominatedInputKind.RV_PARAMETER
    }
    return ModelCatalog(
        nominatedInputs = inputs.map { NominatedInput(it.key, kindOf(it.key), it.displayName, it.description, it.unit) },
        nominatedOutputs = outputs.map { NominatedOutput(it.name, it.displayName, it.description, it.unit) },
    )
}
