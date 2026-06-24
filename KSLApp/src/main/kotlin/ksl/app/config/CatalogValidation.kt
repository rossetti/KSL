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
import ksl.simulation.NominatedInputKind
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 * Validates a [ModelCatalog] against a [ModelDescriptor].
 *
 * The catalog's nominated keys/names are *references* into the descriptor's input
 * and output surfaces. This object checks those references and reports problems —
 * unresolved keys/names, and inputs whose declared [NominatedInputKind] disagrees
 * with the family the key actually resolves to.
 *
 * Validation is done against the descriptor's DTOs (`controls`, `responseNames`,
 * `rvParameterData`) rather than a live model, so it works on a catalog loaded from
 * a bundle JAR without instantiating the model. By design this lives in `ksl.app`
 * and does not modify or depend on the core catalog assembly in `ksl.simulation`.
 *
 * @see sanitize for producing a catalog with unresolved entries dropped and kinds
 *      re-derived — the form the runtime loader overlays onto a descriptor.
 */
object CatalogValidation {

    /** Severity of a [CatalogProblem]. */
    enum class Severity { ERROR, WARNING }

    /**
     * One problem found while validating a catalog against a descriptor.
     *
     * @param subject  the offending input key or output name
     * @param severity ERROR for an unresolved reference (the entry is dropped on
     *                 load); WARNING for an auto-correctable issue such as a kind
     *                 mismatch (the kind is re-derived on load)
     * @param message  a human-readable explanation, possibly with a suggestion
     */
    data class CatalogProblem(
        val subject: String,
        val severity: Severity,
        val message: String,
    )

    /**
     * Returns the problems found validating [catalog] against [descriptor]. An
     * empty list means every nominated input/output resolves and every declared
     * kind matches.
     */
    fun validate(catalog: ModelCatalog, descriptor: ModelDescriptor): List<CatalogProblem> {
        val s = Surfaces.of(descriptor)
        val problems = mutableListOf<CatalogProblem>()
        for (input in catalog.nominatedInputs) {
            val resolved = s.kindOf(input.key)
            when {
                resolved == null -> problems += CatalogProblem(
                    input.key, Severity.ERROR,
                    "No control or random-variable parameter named '${input.key}'." +
                            didYouMean(input.key, s.allInputKeys)
                )
                resolved != input.kind -> problems += CatalogProblem(
                    input.key, Severity.WARNING,
                    "Input '${input.key}' is a $resolved but the catalog declares " +
                            "${input.kind}; the kind will be corrected on load."
                )
            }
        }
        for (output in catalog.nominatedOutputs) {
            if (output.name !in s.responses) problems += CatalogProblem(
                output.name, Severity.ERROR,
                "No response or counter named '${output.name}'." +
                        didYouMean(output.name, s.responses)
            )
        }
        return problems
    }

    /**
     * Returns a copy of [catalog] safe to overlay onto [descriptor]: nominated
     * inputs/outputs that do not resolve are dropped, and each surviving input's
     * [NominatedInputKind] is re-derived from the descriptor (kind is a derived
     * property, never authoritative in the file). Order is preserved.
     */
    fun sanitize(catalog: ModelCatalog, descriptor: ModelDescriptor): ModelCatalog {
        val s = Surfaces.of(descriptor)
        val inputs = catalog.nominatedInputs.mapNotNull { input ->
            val kind = s.kindOf(input.key) ?: return@mapNotNull null
            if (kind == input.kind) input else input.copy(kind = kind)
        }
        val outputs = catalog.nominatedOutputs.filter { it.name in s.responses }
        return ModelCatalog(inputs, outputs)
    }

    /** The input/output key surfaces of a descriptor, precomputed for lookups. */
    private class Surfaces(
        val numeric: Set<String>,
        val string: Set<String>,
        val json: Set<String>,
        val rv: Set<String>,
        val responses: Set<String>,
    ) {
        val allInputKeys: Set<String> get() = numeric + string + json + rv

        fun kindOf(key: String): NominatedInputKind? = when (key) {
            in numeric -> NominatedInputKind.NUMERIC_CONTROL
            in string -> NominatedInputKind.STRING_CONTROL
            in json -> NominatedInputKind.JSON_CONTROL
            in rv -> NominatedInputKind.RV_PARAMETER
            else -> null
        }

        companion object {
            fun of(d: ModelDescriptor): Surfaces {
                val cc = RVParameterSetter.rvParamConCatChar
                return Surfaces(
                    numeric = d.controls.numericControls.mapTo(mutableSetOf()) { it.keyName },
                    string = d.controls.stringControls.mapTo(mutableSetOf()) { it.keyName },
                    json = d.controls.jsonControls.mapTo(mutableSetOf()) { it.keyName },
                    rv = d.rvParameterData.mapTo(mutableSetOf()) { "${it.rvName}$cc${it.paramName}" },
                    responses = d.responseNames,
                )
            }
        }
    }

    // ── "did you mean" suggestions (reimplemented here; not refactored out of
    //    the core ModelCatalogBuilder, to keep ksl.simulation untouched) ────────

    private fun didYouMean(target: String, candidates: Collection<String>): String {
        if (candidates.isEmpty()) return ""
        val threshold = maxOf(2, target.length / 3)
        val ranked = candidates
            .map { it to levenshtein(target, it) }
            .filter { it.second <= threshold }
            .sortedBy { it.second }
            .take(3)
            .map { it.first }
        return if (ranked.isEmpty()) "" else "  Did you mean ${ranked.joinToString(", ") { "'$it'" }}?"
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }
}
