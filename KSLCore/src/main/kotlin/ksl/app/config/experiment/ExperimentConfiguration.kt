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

package ksl.app.config.experiment

import kotlinx.serialization.Serializable
import ksl.app.config.BundleRef
import ksl.app.config.ExecutionMode
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.TracingConfig
import net.peanuuutz.tomlkt.TomlComment

/**
 *  Serialisable experiment document — the input directive for a
 *  `ksl.app.RunSpec.Experiment` submission once the Experiment app
 *  is wired in Phase E4.
 *
 *  The document binds ONE model (via [modelReference]) to a set of
 *  factors (see [factors]) and a design that enumerates points from
 *  those factors (see [designSpec]).  At submit time the engine glue
 *  (Phase E2) materialises a `ParallelDesignedExperiment` from this
 *  shape and hands it to the existing `ExperimentOrchestrator`.
 *
 *  Compare with `ksl.app.config.RunConfiguration`, which represents
 *  the Single / Scenario document shape (one or many user-authored
 *  scenarios per document, each picking its own model).  Experiment
 *  documents are factor-centric: the user authors O(k) factors and
 *  the design enumerates O(2ᵏ) — sometimes far more — points.
 *
 *  ## Validation
 *
 *  Structural validation runs at `init`:
 *  - At least one factor.
 *  - Factor names are unique (case-sensitive).
 *  - When [designSpec] is `TwoLevelFactorial` or `CentralComposite`:
 *      - Every factor has exactly 2 levels.
 *      - If the chosen `Fraction` is `Custom`, the defining relations
 *        pass [DefiningRelationValidator].
 *      - `CentralComposite` with rotatable axial spacing requires
 *        at least 2 factors.
 *
 *  Semantic validation (control bindings resolve against the model,
 *  factor levels lie within control ranges, group-theoretic validity
 *  of fractional generators) happens at submit time in Phase E2.
 *
 *  @property outputConfig    document-wide output settings — analysis
 *                            name, database toggle and policy, CSV
 *                            flags, report formats.  Reused verbatim
 *                            from [ksl.app.config.OutputConfig].
 *  @property modelReference  the single model this experiment runs.
 *  @property factors         the factors whose levels the design
 *                            explores.  At least one required.
 *  @property designSpec      how the engine enumerates design points
 *                            from [factors].  See [DesignSpec].
 *  @property replications    per-point replication strategy — uniform
 *                            or per-point overrides.  Defaults to
 *                            10 replications per point.
 *  @property executionMode   whether design points run sequentially
 *                            (one at a time) or concurrently (parallel
 *                            on the simulation dispatcher).  Defaults
 *                            to [ExecutionMode.CONCURRENT] —
 *                            experiments typically benefit from
 *                            parallel execution since design points
 *                            are independent.
 *  @property streamPolicy    random-stream policy across design
 *                            points.  Defaults to
 *                            [StreamPolicy.Independent].  Honoured
 *                            under CONCURRENT only; SEQUENTIAL uses
 *                            the model's own stream defaults.
 *  @property bundleRefs      optional list of bundle JARs the
 *                            [modelReference] depends on.  Reused
 *                            from [ksl.app.config.BundleRef].
 *  @property tracingConfig   animation / trace capture settings.
 *                            Reused from [ksl.app.config.TracingConfig].
 */
@Serializable
data class ExperimentConfiguration(
    @TomlComment(
        "Document-wide output settings.  Sets the analysis identity\n" +
        "(used as the output subdirectory and database file stem),\n" +
        "the database toggle and existing-file policy, the CSV flags,\n" +
        "and the report-format list.  See the [outputConfig] section\n" +
        "for the individual fields."
    )
    val outputConfig: OutputConfig = OutputConfig(),

    @TomlComment(
        "Identifies the single model this experiment runs.  Required.\n" +
        "Rendered as a [modelReference] sub-table with a 'type'\n" +
        "discriminator and one or more variant-specific keys."
    )
    val modelReference: ModelReference,

    @TomlComment(
        "The factors whose levels the design explores.  One TOML\n" +
        "[[factors]] entry per factor.  At least one factor required;\n" +
        "factor names must be unique within the document.  See the\n" +
        "[[factors]] section comments for the per-factor fields."
    )
    val factors: List<FactorSpec>,

    @TomlComment(
        "How the engine enumerates design points from [factors].\n" +
        "Sealed; rendered as a [designSpec] sub-table with a 'type'\n" +
        "discriminator + variant-specific keys.  Allowed values:\n" +
        "  type = 'fullFactorial'         (no fields)\n" +
        "  type = 'twoLevelFactorial'     fraction (full / half / custom)\n" +
        "  type = 'centralComposite'      axialSpacing, numFactorialReps?,\n" +
        "                                 numAxialReps?, numCenterReps?,\n" +
        "                                 factorialFraction?\n" +
        "  type = 'manual'                points (array of factor-value maps)"
    )
    val designSpec: DesignSpec,

    @TomlComment(
        "Per-design-point replication strategy.  Sealed; rendered as\n" +
        "a [replications] sub-table with a 'type' discriminator:\n" +
        "  type = 'uniform'   replications              (every point)\n" +
        "  type = 'perPoint'  default, overrides        (default + index→count map)\n" +
        "Defaults to uniform 10 replications per design point."
    )
    val replications: ReplicationSpec = ReplicationSpec.Uniform(10),

    @TomlComment(
        "Top-level string. Allowed values:\n" +
        "  'CONCURRENT' — run design points in parallel on the simulation\n" +
        "                 dispatcher (default).  Choose for large designs\n" +
        "                 on multi-core hosts.\n" +
        "  'SEQUENTIAL' — run design points one at a time in enumerated\n" +
        "                 order.\n" +
        "[streamPolicy] is honoured under CONCURRENT only.  Under\n" +
        "SEQUENTIAL the model's own stream defaults govern; a\n" +
        "SEQUENTIAL + commonRandomNumbers combination silently uses the\n" +
        "model defaults (the controller surfaces a warning)."
    )
    val executionMode: ExecutionMode = ExecutionMode.CONCURRENT,

    @TomlComment(
        "Random-stream policy across design points.  Sealed; rendered\n" +
        "as a [streamPolicy] sub-table with a 'type' discriminator:\n" +
        "  type = 'independent'           startingStreamAdvance?, streamAdvanceSpacing?\n" +
        "  type = 'commonRandomNumbers'   (no body)\n" +
        "Default: 'independent' (each point gets a fresh non-overlapping\n" +
        "stream block).  Common random numbers must be opted into\n" +
        "explicitly; it reduces variance for cross-point comparisons\n" +
        "but biases per-point variance estimates."
    )
    val streamPolicy: StreamPolicy = StreamPolicy.Independent(),

    @TomlComment(
        "Optional list of model-bundle JARs the modelReference\n" +
        "depends on.  Omit entirely (or leave empty) when the\n" +
        "reference is not bundle-backed."
    )
    val bundleRefs: List<BundleRef> = emptyList(),

    @TomlComment(
        "Animation / trace capture settings.  Defaults to OFF; safe\n" +
        "to omit unless you are running a traced model."
    )
    val tracingConfig: TracingConfig = TracingConfig()
) {
    init {
        require(factors.isNotEmpty()) {
            "ExperimentConfiguration must have at least one factor"
        }
        val names = factors.map { it.name }
        require(names.toSet().size == names.size) {
            "factor names must be unique within the document; duplicates in $names"
        }
        for (factor in factors) {
            require(factor.name.isNotBlank()) { "factor name must be non-blank" }
            require(factor.levels.size >= 2) {
                "factor '${factor.name}' must have at least 2 levels; got ${factor.levels.size}"
            }
            require(factor.levels.toSet().size == factor.levels.size) {
                "factor '${factor.name}' has duplicate level values: ${factor.levels}"
            }
        }
        validateDesignAgainstFactors()
    }

    /** Cross-validate [designSpec] against [factors].  Pulled out so
     *  the rules are co-located with their explanations. */
    private fun validateDesignAgainstFactors() {
        when (val ds = designSpec) {
            is DesignSpec.TwoLevelFactorial -> {
                requireTwoLevelFactors("twoLevelFactorial")
                validateFraction(ds.fraction, factors.size)
            }
            is DesignSpec.CentralComposite -> {
                requireTwoLevelFactors("centralComposite")
                if (ds.axialSpacing is AxialSpacing.Rotatable) {
                    require(factors.size >= 2) {
                        "centralComposite with rotatable axial spacing requires " +
                            "at least 2 factors; got ${factors.size}"
                    }
                }
            }
            is DesignSpec.Manual -> {
                val factorNames = factors.map { it.name }.toSet()
                for ((i, point) in ds.points.withIndex()) {
                    val keys = point.factorValues.keys
                    require(keys == factorNames) {
                        "manual point #${i + 1} key set $keys must match the " +
                            "document's factor names $factorNames"
                    }
                }
            }
            is DesignSpec.FullFactorial -> { /* no cross-validation */ }
        }
    }

    private fun requireTwoLevelFactors(designLabel: String) {
        val nonTwo = factors.filter { it.levels.size != 2 }
        require(nonTwo.isEmpty()) {
            "$designLabel designs require exactly 2 levels per factor; " +
                "violators: ${nonTwo.map { "${it.name}=${it.levels.size}" }}"
        }
    }

    private fun validateFraction(fraction: Fraction, numFactors: Int) {
        when (fraction) {
            Fraction.Full -> { /* nothing to check */ }
            is Fraction.HalfFraction -> {
                require(numFactors >= 2) {
                    "halfFraction requires at least 2 factors; got $numFactors"
                }
            }
            is Fraction.Custom -> {
                val p = fraction.words.size
                require(p in 1..(numFactors - 1)) {
                    "custom fraction word count p (${p}) must be in 1..(k-1); " +
                        "got k = $numFactors"
                }
                val syn = DefiningRelationValidator.validate(
                    fraction.words, numFactors, p
                )
                require(syn is DefiningRelationValidator.Result.Ok) {
                    "defining relation is invalid: " +
                        (syn as DefiningRelationValidator.Result.Invalid).errors.joinToString("; ")
                }
            }
        }
    }
}
