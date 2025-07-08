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

package ksl.utilities.random.rvariable

import ksl.utilities.random.rvariable.parameters.*
import ksl.utilities.random.rvariable.parameters.AR1NormalRVParameters
import ksl.utilities.random.rvariable.parameters.BernoulliRVParameters
import ksl.utilities.random.rvariable.parameters.BetaRVParameters
import ksl.utilities.random.rvariable.parameters.BinomialRVParameters
import kotlin.reflect.KClass

interface RVParametersTypeIfc {
    /**
     *  the parameters for this type of random variable
     */
    val rvParameters: RVParameters

    val parametrizedRVClass: KClass<out ParameterizedRV>
}

/**
 * Represents the different types of random variables (RVs) available in the simulation framework.
 *
 * Each enum constant corresponds to a specific random variable type and provides an implementation
 * of its parameters via the `rvParameters` property. This enum is used to map random variable classes
 * to their respective types and facilitate their usage in modeling and simulation processes.
 */
enum class RVType(rvClass: KClass<out ParameterizedRV>) : RVParametersTypeIfc {

    Bernoulli(BernoulliRV::class) {
        override val rvParameters: RVParameters
            get() = BernoulliRVParameters()
    },
    Beta(BetaRV::class) {
        override val rvParameters: RVParameters
            get() = BetaRVParameters()
    },
    ChiSquared(ChiSquaredRV::class) {
        override val rvParameters: RVParameters
            get() = ChiSquaredRVParameters()
    },
    Binomial(BinomialRV::class) {
        override val rvParameters: RVParameters
            get() = BinomialRVParameters()
    },
    Constant(ConstantRV::class) {
        override val rvParameters: RVParameters
            get() = ConstantRVParameters()
    },
    DUniform(DUniformRV::class) {
        override val rvParameters: RVParameters
            get() = DUniformRVParameters()
    },
    Exponential(ExponentialRV::class) {
        override val rvParameters: RVParameters
            get() = ExponentialRVParameters()
    },
    Gamma(GammaRV::class) {
        override val rvParameters: RVParameters
            get() = GammaRVParameters()
    },
    GeneralizedBeta(GeneralizedBetaRV::class) {
        override val rvParameters: RVParameters
            get() = GeneralizedBetaRVParameters()
    },
    Geometric(GeometricRV::class) {
        override val rvParameters: RVParameters
            get() = GeometricRVParameters()
    },
    Hyper2Exponential(Hyper2ExponentialRV::class){
        override val rvParameters: RVParameters
            get() = Hyper2ExponentialRVParameters()
    },
    JohnsonB(JohnsonBRV::class) {
        override val rvParameters: RVParameters
            get() = JohnsonBRVParameters()
    },
    Laplace(LaplaceRV::class) {
        override val rvParameters: RVParameters
            get() = LaplaceRVParameters()
    },
    Logistic(LogisticRV::class) {
        override val rvParameters: RVParameters
            get() = LogisticRVParameters()
    },
    LogLogistic(LogLogisticRV::class) {
        override val rvParameters: RVParameters
            get() = LogLogisticRVParameters()
    },
    Lognormal(LognormalRV::class) {
        override val rvParameters: RVParameters
            get() = LognormalRVParameters()
    },
    NegativeBinomial(NegativeBinomialRV::class) {
        override val rvParameters: RVParameters
            get() = NegativeBinomialRVParameters()
    },
    Normal(NormalRV::class) {
        override val rvParameters: RVParameters
            get() = NormalRVParameters()
    },
    TruncatedNormal(TruncatedNormalRV::class) {
        override val rvParameters: RVParameters
            get() = TruncatedNormalRVParameters()
    },
    PearsonType5(PearsonType5RV::class) {
        override val rvParameters: RVParameters
            get() = PearsonType5RVParameters()
    },
    PearsonType6(PearsonType6RV::class) {
        override val rvParameters: RVParameters
            get() = PearsonType6RVParameters()
    },
    Poisson(PoissonRV::class) {
        override val rvParameters: RVParameters
            get() = PoissonRVParameters()
    },
    PWCEmpirical(PWCEmpiricalRV::class){
        override val rvParameters: RVParameters
            get() = PWCEmpiricalRVParameters()

    },
    ShiftedGeometric(ShiftedGeometricRV::class) {
        override val rvParameters: RVParameters
            get() = ShiftedGeometricRVParameters()
    },
    Triangular(TriangularRV::class) {
        override val rvParameters: RVParameters
            get() = TriangularRVParameters()
    },
    Uniform(UniformRV::class) {
        override val rvParameters: RVParameters
            get() = UniformRVParameters()
    },
    Weibull(WeibullRV::class) {
        override val rvParameters: RVParameters
            get() = WeibullRVParameters()
    },
    DEmpirical(DEmpiricalRV::class) {
        override val rvParameters: RVParameters
            get() = DEmpiricalRVParameters()
    },
    Empirical(EmpiricalRV::class) {
        override val rvParameters: RVParameters
            get() = EmpiricalRVParameters()
    },
    AR1Normal(AR1NormalRV::class) {
        override val rvParameters: RVParameters
            get() = AR1NormalRVParameters()
    };

    override val parametrizedRVClass: KClass<out ParameterizedRV> = rvClass

    companion object {

        /**
         *  The set of all possible random variable types for the KSL
         */
        val RVTYPE_SET: Set<RVType> = enumValues<RVType>().toSet()

        /**
         *  a mapping from parameterized random variables to their RVType (random
         *  variable type enum)
         */
        val classToTypeMap: Map<KClass<out ParameterizedRV>, RVType> = mapOf(
            BernoulliRV::class to Bernoulli,
            BetaRV::class to Beta,
            ChiSquaredRV::class to ChiSquared,
            BinomialRV::class to Binomial,
            DUniformRV::class to DUniform,
            ExponentialRV::class to Exponential,
            GammaRV::class to Gamma,
            DEmpiricalRV::class to DEmpirical,
            GeneralizedBetaRV::class to GeneralizedBeta,
            GeometricRV::class to Geometric,
            Hyper2ExponentialRV::class to Hyper2Exponential,
            JohnsonBRV::class to JohnsonB,
            LaplaceRV::class to Laplace,
            LogisticRV::class to Logistic,
            LogLogisticRV::class to LogLogistic,
            LognormalRV::class to Lognormal,
            NegativeBinomialRV::class to NegativeBinomial,
            NormalRV::class to Normal,
            PearsonType5RV::class to PearsonType5,
            PearsonType6RV::class to PearsonType6,
            PoissonRV::class to Poisson,
            PWCEmpiricalRV::class to PWCEmpirical,
            ShiftedGeometricRV::class to ShiftedGeometric,
            TruncatedNormalRV::class to TruncatedNormal,
            TriangularRV::class to Triangular,
            UniformRV::class to Uniform,
            WeibullRV::class to Weibull,
            EmpiricalRV::class to Empirical,
            AR1NormalRV::class to AR1Normal,
            ConstantRV::class to Constant,
        )

    }


}