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
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.RunConfigurationToml
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.ExperimentConfigurationToml
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationRunConfigurationJson
import ksl.app.config.optimization.OptimizationRunConfigurationToml
import ksl.app.dist.config.FitConfiguration
import ksl.service.capability.fit.FitDocuments
import ksl.service.capability.run.ExperimentDocuments

/**
 * Format-tolerant decoding for the server transports: accepts a configuration
 * document as **either JSON or TOML** and decodes it to the typed configuration.
 *
 * Both formats deserialize to the same `@Serializable` configuration types via
 * `kotlinx-serialization`, so the two are interchangeable to a reader — only the
 * surface syntax differs. The MCP and REST servers route every document-accepting
 * endpoint (run / optimization / experiment / fit, in their `*_config`,
 * `validate_*`, and `preview_*` forms) through here, so a user can hand a `.toml`
 * file authored by a KSL desktop app straight to the server (or via an AI
 * assistant) without converting it to JSON first.
 *
 * Format is sniffed from the first meaningful (non-blank, non-`#`-comment) line:
 * a leading `{` or `[` is JSON, anything else is TOML. The sniff only picks which
 * codec to try first — on a parse failure the other codec is tried as a fallback,
 * so a mis-sniff is self-correcting. The error surfaced is the one from the format
 * the document appeared to be.
 *
 * The fit case decodes a [FitConfiguration] (the type `fit_config` accepts) from
 * TOML directly; note this is distinct from the distribution desktop app's
 * `AnalysisDocument` file (a multi-dataset, reference-based document) handled by
 * `ksl.app.dist.config.AnalysisDocumentToml`.
 */
object ConfigDocuments {

    /** TOML codec for a [FitConfiguration]; `explicitNulls = false` matches the other KSL TOML codecs. */
    private val fitToml = Toml { explicitNulls = false }

    /** Decodes a [RunConfiguration] from JSON or TOML text. */
    fun decodeRun(text: String): RunConfiguration =
        decode(text, RunConfigurationJson::decode, RunConfigurationToml::decode)

    /** Decodes an [OptimizationRunConfiguration] from JSON or TOML text. */
    fun decodeOptimization(text: String): OptimizationRunConfiguration =
        decode(text, OptimizationRunConfigurationJson::decode, OptimizationRunConfigurationToml::decode)

    /** Decodes an [ExperimentConfiguration] from JSON or TOML text. */
    fun decodeExperiment(text: String): ExperimentConfiguration =
        decode(text, ExperimentDocuments::decode, ExperimentConfigurationToml::decode)

    /** Decodes a [FitConfiguration] from JSON or TOML text. */
    fun decodeFit(text: String): FitConfiguration =
        decode(text, FitDocuments::decode) { fitToml.decodeFromString(FitConfiguration.serializer(), it) }

    /** True when [text] looks like JSON (its first meaningful line starts with `{` or `[`). */
    private fun looksJson(text: String): Boolean {
        val first = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return true // empty / comments-only: let the JSON decoder produce the error
        return first.startsWith("{") || first.startsWith("[")
    }

    /**
     * Tries the format the document appears to be first, falling back to the other.
     * Throws [IllegalArgumentException] carrying the primary format's message when both fail.
     */
    private fun <T> decode(text: String, decodeJson: (String) -> T, decodeToml: (String) -> T): T {
        val (primary, secondary) = if (looksJson(text)) decodeJson to decodeToml else decodeToml to decodeJson
        return try {
            primary(text)
        } catch (primaryFailure: Exception) {
            try {
                secondary(text)
            } catch (_: Exception) {
                throw IllegalArgumentException(primaryFailure.message ?: "could not parse the document as JSON or TOML")
            }
        }
    }
}
