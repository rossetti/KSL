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

package ksl.app.bundle

import ksl.simulation.CatalogValidation
import ksl.simulation.ModelDescriptor

/**
 * Validates a loaded bundle against a set of structural and semantic rules,
 * returning a [ValidationReport].
 *
 * This is the headless validator: it returns a plain data report with no UI or
 * console coupling, so the same call backs `kslpkg validate`, the Bundle
 * Workbench's health banner (via a thin adapter), and any future service host.
 * It lives in `ksl.app.bundle` and reads only public surfaces of the loaded
 * bundle and its descriptors.
 *
 * Checks performed:
 *  - **bundleId** is non-blank and uses only safe identifier characters. A
 *    reverse-DNS-style id is a documented convention, not enforced here.
 *  - each **modelId** is a safe path segment (it names a directory under
 *    `META-INF/ksl/models/`).
 *  - each model's **builder** resolves and is instantiable. For a manifest-driven
 *    bundle this reflectively loads the `builderClass` named in `bundle.toml`, so a
 *    missing class, a wrong-type class, or one lacking a public no-arg constructor
 *    is reported precisely — even when an embedded `descriptor.json` would
 *    otherwise mask it.
 *  - each model's **`supportedApps`** claims are cross-checked against the
 *    extracted descriptor (e.g. SIMOPT needs numeric inputs and a response;
 *    EXPERIMENT needs at least two numeric factors) — replacing the prior
 *    author honor system.
 *  - any in-JAR **`catalog.toml`** resolves against the descriptor (delegated to
 *    [CatalogValidation]).
 */
object BundleValidation {

    /** Severity of a [Finding]. */
    enum class Severity { ERROR, WARNING, INFO }

    /**
     * One validation finding.
     *
     * @param severity   ERROR (the bundle is malformed), WARNING (suspect but
     *                   loadable), or INFO (advisory)
     * @param locus      where the problem is, e.g. `"bundleId"` or
     *                   `"<bundleId>/<modelId>"`
     * @param message    a human-readable explanation
     * @param suggestion an optional fix hint
     */
    data class Finding(
        val severity: Severity,
        val locus: String,
        val message: String,
        val suggestion: String? = null,
    )

    /** The ordered findings from validating one bundle. */
    data class ValidationReport(val findings: List<Finding>) {
        val errorCount: Int get() = findings.count { it.severity == Severity.ERROR }
        val warningCount: Int get() = findings.count { it.severity == Severity.WARNING }
        val infoCount: Int get() = findings.count { it.severity == Severity.INFO }

        /** True when there are no ERROR findings (warnings/info are tolerated). */
        val isClean: Boolean get() = errorCount == 0
    }

    /** Safe characters for a bundleId or a modelId path segment. */
    private val SAFE_ID = Regex("^[A-Za-z0-9._-]+$")

    /** Validates [loaded] and returns the findings. Never throws for content problems. */
    fun validate(loaded: LoadedBundle): ValidationReport {
        val findings = mutableListOf<Finding>()
        val bundle = loaded.bundle
        val bid = bundle.bundleId

        when {
            bid.isBlank() ->
                findings += Finding(Severity.ERROR, "bundleId", "bundleId is blank")
            !SAFE_ID.matches(bid) ->
                findings += Finding(
                    Severity.ERROR, "bundleId",
                    "bundleId '$bid' contains characters outside [A-Za-z0-9._-]"
                )
        }

        if (bundle.models.isEmpty()) {
            findings += Finding(Severity.WARNING, bid, "bundle declares no models")
        }

        for (model in bundle.models) {
            val locus = "$bid/${model.modelId}"
            val mid = model.modelId
            if (mid.isBlank() || mid == "." || mid == ".." || !SAFE_ID.matches(mid)) {
                findings += Finding(
                    Severity.ERROR, locus,
                    "modelId '$mid' is not a safe path segment",
                    suggestion = "use [A-Za-z0-9._-] with no slashes or whitespace"
                )
            }

            val builderOk = checkBuilder(model, locus, findings)

            val descriptor = try {
                loaded.descriptorFor(model.modelId)
            } catch (e: Exception) {
                // When the builder itself is the root cause it was already reported
                // precisely above; avoid a duplicate, less-specific finding here.
                if (builderOk) {
                    findings += Finding(Severity.ERROR, locus, "failed to extract descriptor: ${e.message}")
                }
                continue
            }

            checkSupportedApps(model.supportedApps, descriptor, locus, findings)
            checkCatalog(loaded, model.modelId, descriptor, locus, findings)
        }

        return ValidationReport(findings)
    }

    /**
     * Verifies the model's builder can be obtained (instantiation only, no build).
     * For a `ManifestBackedModel` this exercises the reflective load of the
     * manifest's `builderClass`. Returns `true` when the builder resolved.
     */
    private fun checkBuilder(
        model: KSLBundledModel,
        locus: String,
        findings: MutableList<Finding>,
    ): Boolean =
        try {
            model.builder()
            true
        } catch (e: Exception) {
            findings += Finding(
                Severity.ERROR, locus,
                "model builder is not usable: ${e.message}",
                suggestion = "the builderClass must name a ModelBuilderIfc with a public no-arg " +
                    "constructor (or a Kotlin object)"
            )
            false
        }

    private fun checkSupportedApps(
        apps: Set<KSLAppKind>,
        descriptor: ModelDescriptor,
        locus: String,
        findings: MutableList<Finding>,
    ) {
        if (apps.isEmpty()) {
            findings += Finding(Severity.WARNING, locus, "model declares no supported apps")
            return
        }
        val numericInputs = descriptor.inputNames.size
        val responses = descriptor.responseNames.size
        if (KSLAppKind.SIMOPT in apps) {
            if (numericInputs == 0) findings += Finding(
                Severity.WARNING, locus, "claims SIMOPT but exposes no numeric inputs to optimize"
            )
            if (responses == 0) findings += Finding(
                Severity.WARNING, locus, "claims SIMOPT but exposes no responses usable as an objective"
            )
        }
        if (KSLAppKind.EXPERIMENT in apps && numericInputs < 2) {
            findings += Finding(
                Severity.WARNING, locus,
                "claims EXPERIMENT but exposes fewer than two numeric factors ($numericInputs)"
            )
        }
    }

    private fun checkCatalog(
        loaded: LoadedBundle,
        modelId: String,
        descriptor: ModelDescriptor,
        locus: String,
        findings: MutableList<Finding>,
    ) {
        val authored = loaded.inJarCatalog(modelId) ?: return
        for (p in CatalogValidation.validate(authored, descriptor)) {
            val severity = when (p.severity) {
                CatalogValidation.Severity.ERROR -> Severity.ERROR
                CatalogValidation.Severity.WARNING -> Severity.WARNING
            }
            findings += Finding(severity, "$locus catalog.toml '${p.subject}'", p.message)
        }
    }

}
