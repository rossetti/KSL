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

package ksl.app.config.optimization

import net.peanuuutz.tomlkt.Toml

/**
 * TOML codec for [OptimizationRunConfiguration].
 *
 * Uses `tomlkt`, which integrates directly with `kotlinx-serialization`.
 * The same `@Serializable` types serve both JSON and TOML.
 *
 * TOML is the preferred format for hand-authored optimization
 * configurations because it supports comments, table-header syntax, and
 * array-of-tables syntax that read more cleanly than JSON for nested
 * structures.
 *
 * Sealed-class polymorphism is encoded via the standard
 * `kotlinx-serialization` discriminator pattern; tomlkt emits the
 * discriminator as a `type = "..."` key on the table representing the
 * sealed value.  This applies to [SolverSpec], [TemperatureSpec],
 * [CoolingScheduleSpec], [CESamplerSpec], and [PenaltyFunctionSpec].
 *
 * ## Output shape
 *
 * Encoded output begins with the multi-line [DOCUMENT_HEADER] banner
 * (a sequence of `#`-prefixed lines describing the file's layout and
 * editing rules); per-field `@TomlComment` annotations on the spec
 * data classes appear inline above each key.  Both layers of comments
 * are ignored by the decoder.
 *
 * The configured `Toml` instance sets `explicitNulls = false` so that
 * optional fields with `null` values are omitted from the encoded
 * output rather than rendered as `field = null` lines.  Decoding is
 * symmetric — a missing key takes the property's declared default.
 */
object OptimizationRunConfigurationToml {

    /**
     *  Configured TOML instance.  `explicitNulls = false` suppresses
     *  `key = null` lines on optional fields with null values; missing
     *  keys decode to the property's declared default.  Mirrors the
     *  setting on `ksl.app.config.RunConfigurationToml`.
     */
    private val myToml = Toml {
        explicitNulls = false
    }

    /** Serialises [config] to a TOML string, prefixed with [DOCUMENT_HEADER]. */
    fun encode(config: OptimizationRunConfiguration): String =
        DOCUMENT_HEADER + myToml.encodeToString(OptimizationRunConfiguration.serializer(), config)

    /** Deserialises an [OptimizationRunConfiguration] from a TOML string
     *  produced by [encode]. */
    fun decode(text: String): OptimizationRunConfiguration =
        myToml.decodeFromString(OptimizationRunConfiguration.serializer(), text)

    /**
     *  Multi-line `#`-prefixed banner prepended to every encoded TOML
     *  file.  Read by humans editing the file; ignored by the decoder.
     *  The per-property `@TomlComment` annotations on
     *  [OptimizationRunConfiguration] and its referenced types provide
     *  field-level documentation directly above each key.
     */
    private val DOCUMENT_HEADER: String = """
        # ────────────────────────────────────────────────────────────────────────────
        #  KSL Simulation-Optimization Configuration
        # ────────────────────────────────────────────────────────────────────────────
        #
        #  This file describes one simulation-optimization run.  It is read and
        #  written by the KSL SimOpt App and can also be edited by hand.
        #
        #  Document layout (top → bottom):
        #
        #    [output]           Document-wide output settings.  Analysis name (used
        #                       as the per-run subdirectory under <workspace>/output/).
        #    [model]            Model construction template.  Bundle + model id +
        #                       baseline controls and RV overrides (held CONSTANT
        #                       during the optimization).  Run-parameter overrides
        #                       (replication length, warm-up).
        #    [problem]          Optimization problem.  Objective response, optional
        #                       declared response names, indifference zone, granularity.
        #    [[problem.inputs]] Decision variables.  Each entry must declare finite
        #                       lowerBound < upperBound; granularity 0.0 means
        #                       continuous, 1.0 means integer-ordered.
        #    [[problem.linearConstraints]]   Linear constraints over decision vars.
        #    [[problem.responseConstraints]] Constraints on simulation responses.
        #    [problem.defaultLinearPenalty]   Problem-level penalty function defaults
        #    [problem.defaultResponsePenalty] (per-constraint overrides on each entry).
        #    [solver]           Algorithm choice and its parameters.  type = one of
        #                       'stochasticHillClimbing', 'simulatedAnnealing',
        #                       'crossEntropy', or 'rSpline'.  Set [solver.randomRestart]
        #                       to wrap the chosen algorithm in random-restart.
        #
        #  In-progress drafts: the [problem] and [solver] sections may be omitted
        #  entirely.  Saving from the GUI editor before all sections are authored
        #  produces such a draft; loading it restores the partial editor state.
        #  Submit-time consumers reject drafts with a clear error.
        #
        #    [evaluation]       Cross-cutting evaluator/solver settings (caches,
        #                       snapshot frequency, feasibility).
        #    [tracking]         Optional CSV / console trace settings.  When
        #                       enableCsvTrace = true the trace lands in
        #                       <workspace>/output/<analysisName>/optimization/.
        #
        #  Editing guidelines:
        #
        #   * String values use "double quotes".  Numbers and booleans are bare
        #     literals.  null is encoded by omitting the key (explicitNulls = false).
        #   * Saving from the app overwrites this file.  Your hand-edited comments
        #     WILL NOT be preserved; field VALUES are preserved verbatim.
        #   * To find each field's expected type, units, and accepted values, see
        #     the comment immediately above it.
        #
        #  Reference: https://rossetti.github.io/KSLBook/
        #
        # ────────────────────────────────────────────────────────────────────────────

        """.trimIndent()
}
