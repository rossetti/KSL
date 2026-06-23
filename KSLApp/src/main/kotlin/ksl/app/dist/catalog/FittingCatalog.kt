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

package ksl.app.dist.catalog

import ksl.app.dist.config.DistributionKind
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.estimators.BetaMOMParameterEstimator
import ksl.utilities.distributions.fitting.estimators.BinomialMOMParameterEstimator
import ksl.utilities.distributions.fitting.estimators.BinomialMaxParameterEstimator
import ksl.utilities.distributions.fitting.estimators.ExponentialMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.GammaMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.GammaMOMParameterEstimator
import ksl.utilities.distributions.fitting.estimators.GeneralizedBetaMOMParameterEstimator
import ksl.utilities.distributions.fitting.estimators.LaplaceMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.LogisticMOMParameterEstimator
import ksl.utilities.distributions.fitting.estimators.LognormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.NegBinomialMOMParameterEstimator
import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc
import ksl.utilities.distributions.fitting.estimators.PearsonType5MLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.PoissonMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.TriangularParameterEstimator
import ksl.utilities.distributions.fitting.estimators.UniformParameterEstimator
import ksl.utilities.distributions.fitting.estimators.WeibullMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.WeibullPercentileParameterEstimator
import ksl.utilities.distributions.fitting.scoring.AdjustedPPCorrelationScoringModel
import ksl.utilities.distributions.fitting.scoring.AdjustedQQCorrelationScoringModel
import ksl.utilities.distributions.fitting.scoring.AkaikeInfoCriterionScoringModel
import ksl.utilities.distributions.fitting.scoring.AndersonDarlingScoringModel
import ksl.utilities.distributions.fitting.scoring.BayesianInfoCriterionScoringModel
import ksl.utilities.distributions.fitting.scoring.ChiSquaredScoringModel
import ksl.utilities.distributions.fitting.scoring.CramerVonMisesScoringModel
import ksl.utilities.distributions.fitting.scoring.KSScoringModel
import ksl.utilities.distributions.fitting.scoring.MallowsL2ScoringModel
import ksl.utilities.distributions.fitting.scoring.PDFScoringModel
import ksl.utilities.distributions.fitting.scoring.PPCorrelationScoringModel
import ksl.utilities.distributions.fitting.scoring.PPSSEScoringModel
import ksl.utilities.distributions.fitting.scoring.ParameterMSEModel
import ksl.utilities.distributions.fitting.scoring.QQCorrelationScoringModel
import ksl.utilities.distributions.fitting.scoring.QQSSEScoringModel
import ksl.utilities.distributions.fitting.scoring.SquaredErrorScoringModel
import ksl.utilities.random.rvariable.RVParametersTypeIfc

/**
 * Stable-ID registry of every parameter estimator, scoring model, and
 * distribution family currently reachable from the fitting subsystem.
 *
 * IDs are wire-safe (lowercase, hyphen-separated) and decouple serializable
 * configurations and DTOs from KSL class names. Front-ends, CLIs, and
 * agents enumerate capabilities through this catalog rather than through
 * reflection.
 *
 * Defaults mirror PDFModeler's own opinion exactly: `defaultEstimatorIds`
 * for CONTINUOUS returns the union of `PDFModeler.nonRestrictedEstimators`
 * and `PDFModeler.positiveRestrictedEstimators`; `defaultScoringModelIds`
 * returns the IDs of `PDFModeler.defaultScoringModels`. Estimators and
 * scoring models outside those defaults are still registered (and
 * resolvable by ID) but opted into explicitly.
 *
 * The discrete entries are listed for catalog completeness; the
 * continuous-only fitting runner will reject them via the validator
 * until the discrete (PMF) path lands.
 */
object FittingCatalog {

    private val estimatorsById: Map<String, EstimatorDescriptor> = buildEstimators()
    private val scoringModelsById: Map<String, ScoringModelDescriptor> = buildScoringModels()
    private val familiesById: Map<String, DistributionFamilyDescriptor> = buildFamilies()
    private val rvTypeToFamilyId: Map<RVParametersTypeIfc, String> =
        familiesById.values.associate { it.rvType to it.id }

    /** All registered estimators in catalog order. */
    val estimators: List<EstimatorDescriptor>
        get() = estimatorsById.values.toList()

    /** All registered scoring models in catalog order. */
    val scoringModels: List<ScoringModelDescriptor>
        get() = scoringModelsById.values.toList()

    /** All registered distribution families in catalog order. */
    val families: List<DistributionFamilyDescriptor>
        get() = familiesById.values.toList()

    /** Looks up an estimator descriptor by its catalog ID; null when unknown. */
    fun estimatorOrNull(id: String): EstimatorDescriptor? = estimatorsById[id]

    /** Looks up an estimator descriptor by its catalog ID; throws when unknown. */
    fun estimator(id: String): EstimatorDescriptor =
        estimatorOrNull(id) ?: error("Unknown estimator id: '$id'")

    /** Looks up a scoring model descriptor by its catalog ID; null when unknown. */
    fun scoringModelOrNull(id: String): ScoringModelDescriptor? = scoringModelsById[id]

    /** Looks up a scoring model descriptor by its catalog ID; throws when unknown. */
    fun scoringModel(id: String): ScoringModelDescriptor =
        scoringModelOrNull(id) ?: error("Unknown scoring model id: '$id'")

    /** Looks up a family descriptor by its catalog ID; null when unknown. */
    fun familyOrNull(id: String): DistributionFamilyDescriptor? = familiesById[id]

    /**
     * Maps an `RVParametersTypeIfc` back to its catalog family ID, or null
     * when the type is not represented by any registered estimator.
     */
    fun familyIdFor(rvType: RVParametersTypeIfc): String? = rvTypeToFamilyId[rvType]

    /**
     * Default estimator IDs for the given kind:
     *  - CONTINUOUS — the union of PDFModeler.nonRestrictedEstimators and
     *    PDFModeler.positiveRestrictedEstimators
     *  - DISCRETE — every registered discrete estimator
     */
    fun defaultEstimatorIds(kind: DistributionKind): Set<String> = when (kind) {
        DistributionKind.CONTINUOUS -> pdfModelerContinuousDefaultIds
        DistributionKind.DISCRETE -> estimatorsById.values
            .filter { it.kind == DistributionKind.DISCRETE }
            .map { it.id }
            .toSet()
    }

    /**
     * Default scoring model IDs, mirroring PDFModeler.defaultScoringModels.
     * Scoring is currently used only on the continuous path.
     */
    fun defaultScoringModelIds(): Set<String> = pdfModelerScoringDefaultIds

    /**
     * Resolves estimator IDs to KSL instances suitable for `PDFModeler.estimateParameters`.
     * Caller is responsible for validating the IDs and the kind first.
     */
    internal fun instantiateEstimators(ids: Iterable<String>): Set<ParameterEstimatorIfc> =
        ids.map { estimator(it).factory() }.toSet()

    /**
     * Resolves scoring model IDs to fresh KSL instances suitable for
     * `PDFModeler` construction. Always creates new instances per fit.
     */
    internal fun instantiateScoringModels(ids: Iterable<String>): Set<PDFScoringModel> =
        ids.map { scoringModel(it).factory() }.toSet()

    // ----- registry construction --------------------------------------------

    private fun buildEstimators(): Map<String, EstimatorDescriptor> {
        val list = listOf(
            cont("uniform", "Uniform", UniformParameterEstimator) { UniformParameterEstimator },
            cont("triangular", "Triangular", TriangularParameterEstimator) { TriangularParameterEstimator },
            cont("normal-mle", "Normal (MLE)", NormalMLEParameterEstimator) { NormalMLEParameterEstimator },
            cont("exponential-mle", "Exponential (MLE)", ExponentialMLEParameterEstimator) { ExponentialMLEParameterEstimator },
            cont("lognormal-mle", "Lognormal (MLE)", LognormalMLEParameterEstimator) { LognormalMLEParameterEstimator },
            cont("laplace-mle", "Laplace (MLE)", LaplaceMLEParameterEstimator) { LaplaceMLEParameterEstimator },
            cont("logistic-mom", "Logistic (MOM)", LogisticMOMParameterEstimator) { LogisticMOMParameterEstimator },
            cont("gamma-mle", "Gamma (MLE)", GammaMLEParameterEstimator()) { GammaMLEParameterEstimator() },
            cont("gamma-mom", "Gamma (MOM)", GammaMOMParameterEstimator) { GammaMOMParameterEstimator },
            cont("weibull-mle", "Weibull (MLE)", WeibullMLEParameterEstimator()) { WeibullMLEParameterEstimator() },
            cont("weibull-percentile", "Weibull (percentile)", WeibullPercentileParameterEstimator()) { WeibullPercentileParameterEstimator() },
            cont("beta-mom", "Beta (MOM)", BetaMOMParameterEstimator()) { BetaMOMParameterEstimator() },
            cont("generalized-beta-mom", "Generalized Beta (MOM)", GeneralizedBetaMOMParameterEstimator) { GeneralizedBetaMOMParameterEstimator },
            cont("pearson5-mle", "Pearson Type 5 (MLE)", PearsonType5MLEParameterEstimator()) { PearsonType5MLEParameterEstimator() },
            disc("poisson-mle", "Poisson (MLE)", PoissonMLEParameterEstimator) { PoissonMLEParameterEstimator },
            disc("binomial-mom", "Binomial (MOM)", BinomialMOMParameterEstimator) { BinomialMOMParameterEstimator },
            disc("binomial-max", "Binomial (max)", BinomialMaxParameterEstimator) { BinomialMaxParameterEstimator },
            disc("negbinomial-mom", "Negative Binomial (MOM)", NegBinomialMOMParameterEstimator) { NegBinomialMOMParameterEstimator },
        )
        val map = LinkedHashMap<String, EstimatorDescriptor>(list.size)
        for (d in list) {
            require(d.id !in map) { "duplicate estimator id '${d.id}' in catalog" }
            map[d.id] = d
        }
        return map
    }

    private fun cont(
        id: String,
        displayName: String,
        sample: ParameterEstimatorIfc,
        factory: () -> ParameterEstimatorIfc
    ): EstimatorDescriptor = EstimatorDescriptor(
        id = id,
        displayName = displayName,
        kind = DistributionKind.CONTINUOUS,
        rvType = sample.rvType,
        familyId = familyIdFromRvType(sample.rvType),
        checksRange = sample.checkRange,
        factory = factory
    )

    private fun disc(
        id: String,
        displayName: String,
        sample: ParameterEstimatorIfc,
        factory: () -> ParameterEstimatorIfc
    ): EstimatorDescriptor = EstimatorDescriptor(
        id = id,
        displayName = displayName,
        kind = DistributionKind.DISCRETE,
        rvType = sample.rvType,
        familyId = familyIdFromRvType(sample.rvType),
        checksRange = sample.checkRange,
        factory = factory
    )

    private fun buildScoringModels(): Map<String, ScoringModelDescriptor> {
        val list = listOf(
            sm("bic", "Bayesian Info Criterion") { BayesianInfoCriterionScoringModel() },
            sm("aic", "Akaike Info Criterion") { AkaikeInfoCriterionScoringModel() },
            sm("anderson-darling", "Anderson-Darling") { AndersonDarlingScoringModel() },
            sm("cramer-von-mises", "Cramer-von Mises") { CramerVonMisesScoringModel() },
            sm("ks", "Kolmogorov-Smirnov") { KSScoringModel() },
            sm("chi-squared", "Chi-squared") { ChiSquaredScoringModel() },
            sm("mallows-l2", "Mallows L2") { MallowsL2ScoringModel() },
            sm("squared-error", "Squared error") { SquaredErrorScoringModel() },
            sm("qq-correlation", "Q-Q correlation") { QQCorrelationScoringModel() },
            sm("qq-sse", "Q-Q SSE") { QQSSEScoringModel() },
            sm("adjusted-qq-correlation", "Adjusted Q-Q correlation") { AdjustedQQCorrelationScoringModel() },
            sm("pp-correlation", "P-P correlation") { PPCorrelationScoringModel() },
            sm("pp-sse", "P-P SSE") { PPSSEScoringModel() },
            sm("adjusted-pp-correlation", "Adjusted P-P correlation") { AdjustedPPCorrelationScoringModel() },
            sm("parameter-mse", "Parameter MSE") { ParameterMSEModel() },
        )
        val map = LinkedHashMap<String, ScoringModelDescriptor>(list.size)
        for (d in list) {
            require(d.id !in map) { "duplicate scoring model id '${d.id}' in catalog" }
            map[d.id] = d
        }
        return map
    }

    private fun sm(
        id: String,
        displayName: String,
        factory: () -> PDFScoringModel
    ): ScoringModelDescriptor = ScoringModelDescriptor(id, displayName, factory)

    private fun buildFamilies(): Map<String, DistributionFamilyDescriptor> {
        val map = LinkedHashMap<String, DistributionFamilyDescriptor>()
        for (e in estimatorsById.values) {
            map.getOrPut(e.familyId) {
                DistributionFamilyDescriptor(
                    id = e.familyId,
                    displayName = familyDisplayName(e.familyId),
                    kind = e.kind,
                    rvType = e.rvType
                )
            }
        }
        return map
    }

    // ----- naming -----------------------------------------------------------

    private fun familyIdFromRvType(rvType: RVParametersTypeIfc): String {
        // KSL convention: RVType is an enum implementing RVParametersTypeIfc,
        // so the variant name ("Normal", "GeneralizedBeta", "NegativeBinomial")
        // is the natural family handle. Lowercased with no separator keeps IDs
        // short and stable. Non-enum implementers fall back to toString().
        val raw = (rvType as? Enum<*>)?.name ?: rvType.toString()
        return raw.lowercase()
    }

    private fun familyDisplayName(familyId: String): String =
        familyId.replaceFirstChar { it.uppercase() }

    // ----- default sets (resolved against the registry) ---------------------

    private val pdfModelerContinuousDefaultIds: Set<String> by lazy {
        val defaults = PDFModeler.allEstimators.map { it.rvType }.toSet()
        estimatorsById.values
            .filter { it.kind == DistributionKind.CONTINUOUS && it.rvType in defaults }
            // Keep one estimator per family, in catalog order, matching the
            // estimator class that PDFModeler uses by default.
            .filter { e -> PDFModeler.allEstimators.any { it::class == e.factory()::class } }
            .map { it.id }
            .toSet()
    }

    private val pdfModelerScoringDefaultIds: Set<String> by lazy {
        val defaultClasses = PDFModeler.defaultScoringModels.map { it::class }.toSet()
        scoringModelsById.values
            .filter { it.factory()::class in defaultClasses }
            .map { it.id }
            .toSet()
    }
}
