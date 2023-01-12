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

import kotlin.reflect.KClass

/**
 * The set of pre-defined types of random variables
 */
enum class RVType(rvClass: KClass<out ParameterizedRV>) {

    Bernoulli(BernoulliRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.BernoulliRVParameters()
    },
    Beta(BetaRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.BetaRVParameters()
    },
    ChiSquared(ChiSquaredRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.ChiSquaredRVParameters()
    },
    Binomial(BinomialRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.BinomialRVParameters()
    },
    Constant(ConstantRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.ConstantRVParameters()
    },
    DUniform(DUniformRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.DUniformRVParameters()
    },
    Exponential(ExponentialRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.ExponentialRVParameters()
    },
    Gamma(GammaRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.GammaRVParameters()
    },
    GeneralizedBeta(GeneralizedBetaRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.GeneralizedBetaRVParameters()
    },
    Geometric(GeometricRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.GeometricRVParameters()
    },
    JohnsonB(JohnsonBRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.JohnsonBRVParameters()
    },
    Laplace(LaplaceRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.LaplaceRVParameters()
    },
    LogLogistic(LogLogisticRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.LogLogisticRVParameters()
    },
    Lognormal(LognormalRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.LognormalRVParameters()
    },
    NegativeBinomial(NegativeBinomialRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.NegativeBinomialRVParameters()
    },
    Normal(NormalRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.NormalRVParameters()
    },
    PearsonType5(PearsonType5RV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.PearsonType5RVParameters()
    },
    PearsonType6(PearsonType6RV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.PearsonType6RVParameters()
    },
    Poisson(PoissonRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.PoissonRVParameters()
    },
    ShiftedGeometric(ShiftedGeometricRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.ShiftedGeometricRVParameters()
    },
    Triangular(TriangularRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.TriangularRVParameters()
    },
    Uniform(UniformRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.UniformRVParameters()
    },
    Weibull(WeibullRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.WeibullRVParameters()
    },
    DEmpirical(DEmpiricalRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.DEmpiricalRVParameters()
    },
    Empirical(EmpiricalRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.EmpiricalRVParameters()
    },
    AR1Normal(AR1NormalRV::class) {
        override val rvParameters: RVParameters
            get() = RVParameters.AR1NormalRVParameters()
    };

    val parametrizedRVClass: KClass<out ParameterizedRV> = rvClass

    abstract val rvParameters: RVParameters

    companion object {

         val RVTYPE_SET : Set<RVType> = enumValues<RVType>().toSet()

//        val RVTYPE_SET: EnumSet<RVType> = EnumSet.of(
//            Bernoulli, Beta, ChiSquared, Binomial, Constant, DUniform, Exponential, Gamma, GeneralizedBeta, Geometric,
//            JohnsonB, Laplace, LogLogistic, Lognormal, NegativeBinomial, Normal, PearsonType5, PearsonType6,
//            Poisson, ShiftedGeometric, Triangular, Uniform, Weibull, DEmpirical, Empirical, AR1Normal
//        )

        val classToTypeMap: Map<KClass<out ParameterizedRV>, RVType> = mapOf(
            BernoulliRV::class to Bernoulli, BetaRV::class to Beta, ChiSquaredRV::class to ChiSquared,
            BinomialRV::class to Binomial, DUniformRV::class to DUniform, ExponentialRV::class to Exponential,
            GammaRV::class to Gamma, DEmpiricalRV::class to DEmpirical, GeneralizedBetaRV::class to GeneralizedBeta,
            GeometricRV::class to Geometric, JohnsonBRV::class to JohnsonB, LaplaceRV::class to Laplace,
            LogLogisticRV::class to LogLogistic, LognormalRV::class to Lognormal, NegativeBinomialRV::class to NegativeBinomial,
            NormalRV::class to Normal, PearsonType5RV::class to PearsonType5, PearsonType6RV::class to PearsonType6,
            PoissonRV::class to Poisson, TriangularRV::class to Triangular, UniformRV::class to Uniform,
            WeibullRV::class to Weibull, EmpiricalRV::class to Empirical, AR1NormalRV::class to AR1Normal,
            ConstantRV::class to Constant,
        )

    }


}