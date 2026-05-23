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

import ksl.app.config.ExecutionMode
import ksl.controls.experiments.CentralCompositeDesign
import ksl.controls.experiments.DesignPoint
import ksl.controls.experiments.DesignedExperiment
import ksl.controls.experiments.DesignedExperimentIfc
import ksl.controls.experiments.ExperimentalDesign
import ksl.controls.experiments.ExperimentalDesignIfc
import ksl.controls.experiments.Factor
import ksl.controls.experiments.FactorialDesign
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.dbutil.KSLDatabase
import java.nio.file.Path

/**
 *  Engine glue — build a runnable [DesignedExperimentIfc] from a
 *  serialised [ExperimentConfiguration].
 *
 *  Pure function (no I/O at construction time apart from what the
 *  substrate's `*DesignedExperiment` constructors do internally to
 *  set up the output directory and the SQLite database).  The
 *  hosting controller (Phase E3) resolves [ExperimentConfiguration.modelReference]
 *  to a [ModelBuilderIfc], computes the workspace-nested output
 *  directory, and opens a [KSLDatabase] per the document's
 *  database-policy before calling this function.
 *
 *  Returns either a [ParallelDesignedExperiment] (when
 *  [ExperimentConfiguration.executionMode] is `CONCURRENT`) or a
 *  [DesignedExperiment] (when `SEQUENTIAL`).  Both implement
 *  [DesignedExperimentIfc], which is what
 *  [ksl.app.orchestrator.ExperimentOrchestrator.submit] accepts.
 *
 *  **Stream policy applies under CONCURRENT only.**  The
 *  [ExperimentConfiguration.streamPolicy] field is read by this
 *  function only when building the parallel variant; sequential
 *  execution uses the underlying [ksl.simulation.Model]'s own
 *  stream defaults.  The hosting controller surfaces a warning when
 *  the user picks `SEQUENTIAL` + `commonRandomNumbers` to make the
 *  silent ignore visible.
 *
 *  @param modelBuilder    factory producing fresh `Model` instances
 *                         (CONCURRENT calls `build()` per design
 *                         point; SEQUENTIAL calls `build()` exactly
 *                         once for the shared model).
 *  @param pathToOutputDirectory  workspace-relative output dir for
 *                         the experiment's artifacts.
 *  @param kslDatabase     pre-opened `KSLDatabase` honouring the
 *                         document's database policy.  `null` lets
 *                         the substrate's constructor default kick in.
 *  @param name            display name for the experiment.  Defaults
 *                         to the sanitised analysis name from
 *                         `outputConfig.analysisName`.
 */
fun ExperimentConfiguration.toDesignedExperiment(
    modelBuilder: ModelBuilderIfc,
    pathToOutputDirectory: Path,
    kslDatabase: KSLDatabase? = null,
    name: String? = null
): DesignedExperimentIfc {
    val effectiveName = name
        ?: ksl.app.config.sanitizeAnalysisName(outputConfig.analysisName)

    val (engineFactors, factorByName) = buildEngineFactors()
    val factorSettings = buildFactorSettings(engineFactors, factorByName)
    val effectiveDesign = buildEffectiveDesign(engineFactors, factorByName)

    return when (executionMode) {
        ExecutionMode.CONCURRENT -> {
            val parallel = ParallelDesignedExperiment(
                name = effectiveName,
                modelBuilder = modelBuilder,
                factorSettings = factorSettings,
                design = effectiveDesign,
                modelConfiguration = null,
                pathToOutputDirectory = pathToOutputDirectory,
                kslDb = kslDatabase ?: KSLDatabase(
                    "${effectiveName}.db".replace(" ", "_"),
                    pathToOutputDirectory
                )
            )
            applyStreamPolicy(parallel)
        }
        ExecutionMode.SEQUENTIAL -> {
            val model = modelBuilder.build()
            // Mirror the workspace-nested output directory the
            // parallel constructor sets up for each per-point model;
            // the sequential model writes its kslOutput.txt and any
            // CSV / plot artifacts under the same root.
            model.outputDirectory = OutputDirectory(
                pathToOutputDirectory, outFileName = "kslOutput.txt"
            )
            DesignedExperiment(
                name = effectiveName,
                model = model,
                factorSettings = factorSettings,
                design = effectiveDesign,
                kslDb = kslDatabase ?: KSLDatabase(
                    "${effectiveName}.db".replace(" ", "_"),
                    model.outputDirectory.dbDir
                )
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────

/**
 *  Materialise an engine [Factor] for each [FactorSpec].  `TwoLevelFactor`
 *  is used when the document's design is two-level (fractional or
 *  central composite); otherwise the general [Factor] form takes the
 *  full level list.  Returns the factor list (in document order) and
 *  a name→Factor index for downstream binding work.
 */
private fun ExperimentConfiguration.buildEngineFactors(): Pair<List<Factor>, Map<String, Factor>> {
    val useTwoLevel = when (designSpec) {
        is DesignSpec.TwoLevelFractional, is DesignSpec.CentralComposite -> true
        else -> false
    }
    val factors = factors.map { spec ->
        if (useTwoLevel) {
            // Init validation guaranteed exactly 2 levels per factor
            // when the design is two-level; the low/high are levels[0]
            // and levels[1].  TwoLevelFactor is a subtype of Factor,
            // so the shared collection works.
            TwoLevelFactor(name = spec.name, low = spec.levels[0], high = spec.levels[1])
        } else {
            Factor(name = spec.name, values = spec.levels)
        }
    }
    val index = factors.associateBy { it.name }
    return factors to index
}

/**
 *  Build the `Map<Factor, String>` the substrate consumes.  The
 *  string is either a control key (`ControlBinding.Control`) or the
 *  `rvName.paramName` flat form (`ControlBinding.RVParameter`); the
 *  substrate's `Model.validateInputKeys` distinguishes them at
 *  submit time by probing the model's control + RV-parameter
 *  surfaces.
 */
private fun ExperimentConfiguration.buildFactorSettings(
    @Suppress("UNUSED_PARAMETER") engineFactors: List<Factor>,
    factorByName: Map<String, Factor>
): Map<Factor, String> = factors.associate { spec ->
    val factor = factorByName.getValue(spec.name)
    val inputKey = when (val b = spec.binding) {
        is ControlBinding.Control     -> b.controlKey
        is ControlBinding.RVParameter -> "${b.rvName}.${b.paramName}"
    }
    factor to inputKey
}

/**
 *  Build the [ExperimentalDesignIfc] the experiment will run.
 *
 *  Combines design-spec enumeration and per-point replication
 *  precedence into a single materialisation step.  The precedence
 *  for each design point's replication count (highest first) is:
 *
 *  1. Inline replication on a `ManualPointSpec` (Manual designs only).
 *  2. `ReplicationSpec.PerPoint.overrides[index]`.
 *  3. `ReplicationSpec.PerPoint.default`.
 *  4. `ReplicationSpec.Uniform.replications`.
 *
 *  Implementation strategy: every variant funnels through the same
 *  `ExperimentalDesign` shape with reps set at construction time.
 *  This avoids the trap where setting `numReplications` on iterator-
 *  returned `DesignPoint` instances doesn't persist because typed
 *  designs produce fresh iterators each call.
 *
 *  Centre-point augmentation for `FullFactorial` is NOT implemented
 *  in v1 — the substrate's `FactorialDesign` doesn't natively support
 *  it.  When `FullFactorial.centerPoints > 0` we leave a TODO and
 *  proceed without centre points; users wanting centre points should
 *  prefer `CentralComposite` (which has them natively) or `Manual`.
 *  See plan E2 follow-ups.
 */
private fun ExperimentConfiguration.buildEffectiveDesign(
    engineFactors: List<Factor>,
    factorByName: Map<String, Factor>
): ExperimentalDesignIfc {
    val factorSet: Set<Factor> = engineFactors.toSet()
    val rep = replications

    return when (val ds = designSpec) {
        is DesignSpec.FullFactorial -> {
            if (ds.centerPoints > 0) {
                // TODO(experiment-app): native centre-point augmentation
                //   for FullFactorial.  Substrate currently lacks it;
                //   the controller should surface a warning when the
                //   user picks FullFactorial.centerPoints > 0.
            }
            materialiseAsExperimentalDesign(
                factorSet = factorSet,
                pointsIterator = FactorialDesign(factorSet).iterator(),
                replications = rep
            )
        }
        is DesignSpec.TwoLevelFractional -> {
            val twoLevelSet: Set<TwoLevelFactor> =
                engineFactors.filterIsInstance<TwoLevelFactor>().toSet()
            require(twoLevelSet.size == engineFactors.size) {
                "TwoLevelFractional design requires every factor to be TwoLevelFactor — " +
                    "init validation should have caught this; this is a bug."
            }
            val twoLevelDesign = TwoLevelFactorialDesign(twoLevelSet)
            val relation = ds.definingRelations
                .map { generator -> generator.map { letter -> letter - 'A' + 1 }.toSet() }
                .toSet()
            materialiseAsExperimentalDesign(
                factorSet = factorSet,
                pointsIterator = twoLevelDesign.fractionalIterator(relation),
                replications = rep
            )
        }
        is DesignSpec.CentralComposite -> {
            val twoLevelSet: Set<TwoLevelFactor> =
                engineFactors.filterIsInstance<TwoLevelFactor>().toSet()
            require(twoLevelSet.size == engineFactors.size) {
                "CentralComposite design requires every factor to be TwoLevelFactor — " +
                    "init validation should have caught this; this is a bug."
            }
            val ccd = CentralCompositeDesign(
                factors = twoLevelSet,
                axialSpacing = ds.axialSpacing,
                numCenterReps = ds.centerPoints.coerceAtLeast(1)
            )
            // CCD's own numCenterReps controls the centre point's
            // replication count internally; we still need to apply
            // the document's [replications] precedence rule to the
            // factorial + axial points uniformly, so re-materialise
            // through the same code path.
            materialiseAsExperimentalDesign(
                factorSet = factorSet,
                pointsIterator = ccd.iterator(),
                replications = rep
            )
        }
        is DesignSpec.Manual -> {
            val exp = ExperimentalDesign(factorSet)
            for ((index, point) in ds.points.withIndex()) {
                val settings: Map<Factor, Double> = point.factorValues.mapKeys {
                    factorByName.getValue(it.key)
                }
                // Manual precedence: inline > PerPoint.overrides >
                // PerPoint.default > Uniform.replications.
                val numReps = point.replications
                    ?: effectiveRepsFor(index = index, rep = rep)
                exp.addDesignPoint(
                    settings = settings,
                    numReps = numReps,
                    enforceRange = false
                )
            }
            exp
        }
    }
}

/**
 *  Drain [pointsIterator] into a fresh [ExperimentalDesign] over
 *  [factorSet], assigning each point's `numReplications` according
 *  to the [replications] precedence rule (Uniform value, or per-
 *  index override).  Non-Manual designs use this — the inline-
 *  replications case only exists for Manual.
 */
private fun materialiseAsExperimentalDesign(
    factorSet: Set<Factor>,
    pointsIterator: Iterator<DesignPoint>,
    replications: ReplicationSpec
): ExperimentalDesign {
    val exp = ExperimentalDesign(factorSet)
    var index = 0
    while (pointsIterator.hasNext()) {
        val dp = pointsIterator.next()
        exp.addDesignPoint(
            settings = dp.settings,
            numReps = effectiveRepsFor(index = index, rep = replications),
            enforceRange = false
        )
        index++
    }
    return exp
}

/**
 *  Per-index replication count from [rep] only (does NOT consider
 *  inline `ManualPointSpec.replications` — Manual's call site
 *  applies that override before falling through to this function).
 *  Extracted for unit testing of the precedence rule.
 */
private fun effectiveRepsFor(index: Int, rep: ReplicationSpec): Int = when (rep) {
    is ReplicationSpec.Uniform  -> rep.replications
    is ReplicationSpec.PerPoint -> rep.overrides[index] ?: rep.default
}

/** Apply [ExperimentConfiguration.streamPolicy] to the parallel
 *  variant.  Returns the same instance for chaining (the substrate's
 *  fluent setters return `this`). */
private fun ExperimentConfiguration.applyStreamPolicy(
    experiment: ParallelDesignedExperiment
): ParallelDesignedExperiment = when (val sp = streamPolicy) {
    is StreamPolicy.Independent ->
        experiment.useIndependentRandomStreams(
            startingStreamAdvance = sp.startingStreamAdvance,
            streamAdvanceSpacing = sp.streamAdvanceSpacing
        )
    StreamPolicy.CommonRandomNumbers ->
        experiment.useCommonRandomNumbers()
}
