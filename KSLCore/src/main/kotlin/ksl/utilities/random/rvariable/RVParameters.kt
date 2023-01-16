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

import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.reflect.KClass


abstract class RVParameters {
    enum class DataType(private val clazz: KClass<*>) {
        DOUBLE(Double::class), INTEGER(Int::class), DOUBLE_ARRAY(DoubleArray::class);

        /**
         * @return the class associated with this type
         */
        fun asClass(): KClass<*> {
            return clazz
        }
    }

    lateinit var className: String
        protected set

    lateinit var type: RVType
        protected set

    /**
     * The Map that hold the parameters as pairs
     * key = name of parameter
     * value = value of the parameter as a Double
     */
    private val doubleParameters: MutableMap<String, Double> = HashMap()

    /**
     * The Map that hold the parameters as pairs
     * key = name of parameter
     * value = value of the parameter as an Integer
     */
    private val integerParameters: MutableMap<String, Int> = HashMap()

    /**
     * The Map that hold the parameters as pairs
     * key = name of parameter
     * value = value of the parameter as a double[]
     */
    private val doubleArrayParameters: MutableMap<String, DoubleArray> = HashMap()

    /**
     * A map to keep track of parameter names and their types
     */
    private val dataTypes: MutableMap<String, DataType> = HashMap()

    init {
        fillParameters()
    }

    protected abstract fun fillParameters()

    private fun addParameterName(name: String, type: DataType) {
        require(!dataTypes.containsKey(name)) { "The parameter $name already exists." }
        dataTypes[name] = type
    }

    /**
     * @param parameterName the name of the parameter, must not be null, must not already have been added
     * @param value         the value of the parameter
     */
    protected fun addDoubleParameter(parameterName: String, value: Double) {
        addParameterName(parameterName, DataType.DOUBLE)
        doubleParameters[parameterName] = value
    }

    /**
     * @param parameterName the name of the parameter, must not be null, must not already have been added
     * @param value         the value of the parameter
     */
    protected fun addIntegerParameter(parameterName: String, value: Int) {
        addParameterName(parameterName, DataType.INTEGER)
        integerParameters[parameterName] = value
    }

    /**
     * @param parameterName the name of the parameter, must not be null, must not already have been added
     * @param value         the value of the parameter
     */
    protected fun addDoubleArrayParameter(parameterName: String, value: DoubleArray) {
        addParameterName(parameterName, DataType.DOUBLE_ARRAY)
        doubleArrayParameters[parameterName] = value
    }

    /**
     * Checks if the supplied key is contained in the parameters
     *
     * @param name the name of the parameter
     * @return true if is has the named parameter
     */
    fun containsParameter(name: String): Boolean {
        return dataTypes.containsKey(name)
    }

    /**
     * Checks if key is not defined as a parameter
     *
     * @param key name of the parameter
     */
    protected fun checkKey(key: String) {
        require(containsParameter(key)) { "The supplied key is not associated with a parameter value" }
    }

    /**
     * @return an unmodifiable Set view of the parameter names
     */
    val parameterNames: Set<String>
        get() = (dataTypes.keys)

    /**
     * Can be used to determine which of the getXParameter(String key) methods to call
     *
     * @param name the name of the parameter
     * @return the Class type of the parameter
     */
    fun parameterDataType(name: String): DataType? {
        return dataTypes[name]
    }

    /**
     * Gets the value associated with the supplied parameterName as a double.  If the parameterName is null
     * or there is no parameter for the supplied parameterName, then an exception occurs
     *
     * @param parameterName the name of the parameter
     * @return the value of the parameter
     */
    fun doubleParameter(parameterName: String): Double {
        checkKey(parameterName)
        return doubleParameters[parameterName]!!
    }

    /**
     * A convenience method to change the named parameter to the supplied value.
     * This will work with either double or integer parameters.
     * Integer parameters are coerced to the rounded up value of the supplied double,
     * provided that the integer can hold the supplied value.  If the named
     * parameter is not associated with the parameters, then no change occurs.
     * In other words, the action fails, silently by returning false.
     *
     * @param parameterName the name of the parameter to change
     * @param value the value to change to
     * @return true if changed, false if no change occurred
     */
    fun changeParameter(parameterName: String, value: Double): Boolean {
        if (!containsParameter(parameterName)) {
            return false
        }
        // either double, integer or double array
        // try double first, then integer
        if (doubleParameters.containsKey(parameterName)) {
            changeDoubleParameter(parameterName, value)
            return true
        } else if (integerParameters.containsKey(parameterName)) {
            val iValue: Int = KSLMath.toIntValue(value)
            changeIntegerParameter(parameterName, iValue)
            return true
        }
        // must be double[] array, cannot do the setting, just return false
        return false
    }

    /**
     * Changes the value associated with the parameterName to the supplied value.  If the parameterName is null
     * or there is no parameter for the supplied parameterName, then an exception occurs
     *
     * @param parameterName parameterName with which the value is to be associated
     * @param value         the value to be associated with parameterName
     * @return the previous value that was associated with the parameterName
     */
    fun changeDoubleParameter(parameterName: String, value: Double): Double {
        checkKey(parameterName)
        return doubleParameters.put(parameterName, value)!!
    }

    /**
     * Gets the value associated with the supplied parameterName as a double[].  If the parameterName is null
     * or there is no parameter for the supplied parameterName, then an exception occurs
     *
     * @param parameterName the name of the parameter
     * @return a copy of the associated double[] is returned
     */
    fun doubleArrayParameter(parameterName: String): DoubleArray {
        checkKey(parameterName)
        val value = doubleArrayParameters[parameterName]
        return value!!.copyOf()
    }

    /**
     * Returns the size (array length) of the DoubleArray parameter. If the parameterName is null
     * or there is no parameter for the supplied parameterName, then an exception occurs
     *
     * @param parameterName the name of the parameter
     * @return the size of the array
     */
    fun doubleArrayParameterSize(parameterName: String): Int {
        checkKey(parameterName)
        return doubleArrayParameters[parameterName]!!.size
    }

    /**
     * Changes the value associated with the parameterName to the supplied value.  If the parameterName is null
     * or there is no parameter for the supplied parameterName, then an exception occurs.
     *
     *
     * The supplied array is copied.
     *
     * @param parameterName parameterName with which the double[] value is to be associated
     * @param value         the double[] value to be associated with parameterName, cannot be null, must be same size as original double[]
     * @return the previous double[] value that was associated with the parameterName
     */
    fun changeDoubleArrayParameter(parameterName: String, value: DoubleArray): DoubleArray? {
        checkKey(parameterName)
        val tmp = value.copyOf()
        return doubleArrayParameters.put(parameterName, tmp)
    }

    /**
     * Gets the value associated with the supplied parameterName. If the parameterName is null
     * or there is no parameter for the supplied parameterName, then an exception occurs.
     *
     * @param parameterName the name of the parameter
     * @return the value of the parameter
     */
    fun integerParameter(parameterName: String): Int {
        checkKey(parameterName)
        return integerParameters[parameterName]!!
    }

    /**
     * Changes the value of the parameterName to the supplied value.  If the parameterName is null
     * or there is no parameter for the supplied parameterName, then an exception occurs.
     *
     * @param parameterName the name of the parameter
     * @param value         the value of the parameter
     * @return the previous value that was associated with the parameterName
     */
    fun changeIntegerParameter(parameterName: String, value: Int): Int {
        checkKey(parameterName)
        return integerParameters.put(parameterName, value)!!
    }

    /**
     * @return an instance of the random variable based on the current parameter parameters,
     * with a new stream
     */
    fun createRVariable(): RVariableIfc {
        return createRVariable(KSLRandom.nextRNStream())
    }

    /**
     * @param streamNumber a number representing the desired stream based on the RNStreamProvider
     * @return an instance of the random variable based on the current parameter parameters using the designated
     * stream number
     */
    fun createRVariable(streamNumber: Int): RVariableIfc {
        return createRVariable(KSLRandom.rnStream(streamNumber))
    }

    /**
     * Returns true if at least one double[] parameter has been set
     *
     * @return true if it has at least one double[] parameter
     */
    fun hasDoubleArrayParameter(): Boolean {
        return doubleArrayParameters.isNotEmpty()
    }

    /**
     *
     * @return true if it has at least one double parameter
     */
    fun hasDoubleParameters(): Boolean {
        return doubleParameters.isNotEmpty()
    }

    /**
     * Returns true if at least one Integer parameter has been set
     *
     * @return true if it has at least one integer parameter
     */
    fun hasIntegerParameter(): Boolean {
        return integerParameters.isNotEmpty()
    }

    /**
     * Returns an unmodifiable Set of the parameter's names
     * for Double Parameters
     *
     * @return the unmodifiable set
     */
    val doubleParameterNames: Set<String>
        get() = (doubleParameters.keys)

    /**
     * Returns an unmodifiable Set of the parameter's names
     * for double[] Parameters
     *
     * @return the unmodifiable set
     */
    val doubleArrayParameterNames: Set<String>
        get() = (doubleArrayParameters.keys)

    /**
     * Returns an unmodifiable Set of the parameter's names
     * for Integer Parameters
     *
     * @return the unmodifiable set
     */
    val integerParameterNames: Set<String>
        get() = (integerParameters.keys)

    /**
     * @param rnStream the stream to use
     * @return an instance of the random variable based on the current parameter parameters
     */
    abstract fun createRVariable(rnStream: RNStreamIfc): RVariableIfc

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("RV Type = ").append(type)
        sb.append(System.lineSeparator())
        sb.append("Double Parameters ")
        sb.append(doubleParameters.toString())
        sb.append(System.lineSeparator())
        sb.append("Integer Parameters ")
        sb.append(integerParameters.toString())
        sb.append(System.lineSeparator())
        sb.append("Double Array Parameters ")
        sb.append("{")
        for (key in doubleArrayParameters.keys) {
            sb.append(System.lineSeparator())
            sb.append(key).append(" = ").append(doubleArrayParameters[key].contentToString())
        }
        sb.append("}")
        sb.append(System.lineSeparator())
        return sb.toString()
    }
    //    public String toJSON() {
    //        Gson gson = new GsonBuilder().setPrettyPrinting().create();
    //        return gson.toJson(this);
    //    }
    /**
     * Copies from the supplied parameters into this parameters
     *
     * @param rvParameters the parameters to copy from
     */
    fun copyFrom(rvParameters: RVParameters) {
        require(!(type !== rvParameters.type)) { "Cannot copy into with different parameter types" }
        if (this == rvParameters) {
            return
        }
        // not equal copy over, will have same keys
        doubleParameters.putAll(rvParameters.doubleParameters)
        integerParameters.putAll(rvParameters.integerParameters)
        for ((key, value) in rvParameters.doubleArrayParameters) {
            changeDoubleArrayParameter(key, value)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RVParameters) return false
        val that = other
        if (className != that.className) return false
        if (type !== that.type) return false
        if (doubleParameters != that.doubleParameters) return false
        if (integerParameters != that.integerParameters) return false
        if (dataTypes != that.dataTypes) return false
        // need to handle doubleArrayParameters differently because it contains double[]
        // must have the same keys
        if (doubleArrayParameters.keys != that.doubleArrayParameters.keys) return false
        // ok, same keys, now check the values for each key
        for ((key, thisData) in doubleArrayParameters) {
            val thatData = that.doubleArrayParameters[key]
            if (!thisData.contentEquals(thatData)) return false
        }
        // all keys the same, all data the same, everything is the same
        return true
    }

    override fun hashCode(): Int {
        val list: MutableList<Any?> = ArrayList()
        list.add(className)
        list.add(type)
        list.add(doubleParameters)
        list.add(integerParameters)
        list.add(dataTypes)
        for ((key, thisData) in doubleArrayParameters) {
            list.add(key)
            list.add(thisData)
        }
        //TODO check on why Any?
        val objects: Array<Any?> = list.toTypedArray()
        return objects.contentHashCode()
    }

    internal class WeibullRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("shape", 1.0)
            addDoubleParameter("scale", 1.0)
            className = RVType.Weibull.parametrizedRVClass.simpleName!!
            type = (RVType.Weibull)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val scale = doubleParameter("scale")
            val shape = doubleParameter("shape")
            return WeibullRV(shape, scale, rnStream)
        }
    }

    internal class UniformRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("min", 0.0)
            addDoubleParameter("max", 1.0)
            className = RVType.Uniform.parametrizedRVClass.simpleName!!
            type = (RVType.Uniform)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val min = doubleParameter("min")
            val max = doubleParameter("max")
            return UniformRV(min, max, rnStream)
        }
    }

    internal class TriangularRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("min", 0.0)
            addDoubleParameter("mode", 0.5)
            addDoubleParameter("max", 1.0)
            type = (RVType.Triangular)
            className = RVType.Triangular.parametrizedRVClass.simpleName!!
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val mode = doubleParameter("mode")
            val min = doubleParameter("min")
            val max = doubleParameter("max")
            return TriangularRV(min, mode, max, rnStream)
        }
    }

    internal class ShiftedGeometricRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("probOfSuccess", 0.5)
            className = RVType.ShiftedGeometric.parametrizedRVClass.simpleName!!
            type = (RVType.ShiftedGeometric)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val probOfSuccess = doubleParameter("probOfSuccess")
            return ShiftedGeometricRV(probOfSuccess, rnStream)
        }
    }

    internal class PoissonRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("mean", 1.0)
            className = RVType.Poisson.parametrizedRVClass.simpleName!!
            type = (RVType.Poisson)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val mean = doubleParameter("mean")
            return PoissonRV(mean, rnStream)
        }
    }

    internal class PearsonType6RVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("alpha1", 2.0)
            addDoubleParameter("alpha2", 3.0)
            addDoubleParameter("beta", 1.0)
            className = RVType.PearsonType6.parametrizedRVClass.simpleName!!
            type = (RVType.PearsonType6)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val alpha1 = doubleParameter("alpha1")
            val alpha2 = doubleParameter("alpha2")
            val beta = doubleParameter("beta")
            return PearsonType6RV(alpha1, alpha2, beta, rnStream)
        }
    }

    internal class PearsonType5RVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("shape", 1.0)
            addDoubleParameter("scale", 1.0)
            className = RVType.PearsonType5.parametrizedRVClass.simpleName!!
            type = (RVType.PearsonType5)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val scale = doubleParameter("scale")
            val shape = doubleParameter("shape")
            return PearsonType5RV(shape, scale, rnStream)
        }
    }

    internal class NormalRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("mean", 0.0)
            addDoubleParameter("variance", 1.0)
            className = RVType.Normal.parametrizedRVClass.simpleName!!
            type = (RVType.Normal)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val mean = doubleParameter("mean")
            val variance = doubleParameter("variance")
            return NormalRV(mean, variance, rnStream)
        }
    }

    internal class NegativeBinomialRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("probOfSuccess", 0.5)
            addIntegerParameter("numSuccesses", 1)
            className = RVType.NegativeBinomial.parametrizedRVClass.simpleName!!
            type = (RVType.NegativeBinomial)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val probOfSuccess = doubleParameter("probOfSuccess")
            val numSuccesses = doubleParameter("numSuccesses")
            return NegativeBinomialRV(probOfSuccess, numSuccesses, rnStream)
        }
    }

    internal class LognormalRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("mean", 1.0)
            addDoubleParameter("variance", 1.0)
            className = RVType.Lognormal.parametrizedRVClass.simpleName!!
            type = (RVType.Lognormal)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val mean = doubleParameter("mean")
            val variance = doubleParameter("variance")
            return LognormalRV(mean, variance, rnStream)
        }
    }

    internal class LogLogisticRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("shape", 1.0)
            addDoubleParameter("scale", 1.0)
            className = RVType.LogLogistic.parametrizedRVClass.simpleName!!
            type = (RVType.LogLogistic)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val scale = doubleParameter("scale")
            val shape = doubleParameter("shape")
            return LogLogisticRV(shape, scale, rnStream)
        }
    }

    internal class LaplaceRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("mean", 0.0)
            addDoubleParameter("scale", 1.0)
            className = RVType.Laplace.parametrizedRVClass.simpleName!!
            type = (RVType.Laplace)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val scale = doubleParameter("scale")
            val mean = doubleParameter("mean")
            return LaplaceRV(mean, scale, rnStream)
        }
    }

    internal class JohnsonBRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("alpha1", 0.0)
            addDoubleParameter("alpha2", 1.0)
            addDoubleParameter("min", 0.0)
            addDoubleParameter("max", 1.0)
            className = RVType.JohnsonB.parametrizedRVClass.simpleName!!
            type = (RVType.JohnsonB)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val alpha1 = doubleParameter("alpha1")
            val alpha2 = doubleParameter("alpha2")
            val min = doubleParameter("min")
            val max = doubleParameter("max")
            return JohnsonBRV(alpha1, alpha2, min, max, rnStream)
        }
    }

    internal class GeometricRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("probOfSuccess", 0.5)
            className = RVType.Geometric.parametrizedRVClass.simpleName!!
            type = (RVType.Geometric)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val probOfSuccess = doubleParameter("probOfSuccess")
            return GeometricRV(probOfSuccess, rnStream)
        }
    }

    internal class GeneralizedBetaRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("alpha1", 1.0)
            addDoubleParameter("alpha2", 1.0)
            addDoubleParameter("min", 0.0)
            addDoubleParameter("max", 1.0)
            className = RVType.GeneralizedBeta.parametrizedRVClass.simpleName!!
            type = (RVType.GeneralizedBeta)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val alpha1 = doubleParameter("alpha1")
            val alpha2 = doubleParameter("alpha2")
            val min = doubleParameter("min")
            val max = doubleParameter("max")
            return GeneralizedBetaRV(alpha1, alpha2, min, max, rnStream)
        }
    }

    internal class GammaRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("shape", 1.0)
            addDoubleParameter("scale", 1.0)
            className = RVType.Gamma.parametrizedRVClass.simpleName!!
            type = (RVType.Gamma)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val scale = doubleParameter("scale")
            val shape = doubleParameter("shape")
            return GammaRV(shape, scale, rnStream)
        }
    }

    internal class ExponentialRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("mean", 1.0)
            className = RVType.Exponential.parametrizedRVClass.simpleName!!
            type = (RVType.Exponential)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val mean = doubleParameter("mean")
            return ExponentialRV(mean, rnStream)
        }
    }

    internal class EmpiricalRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleArrayParameter("population", DoubleArray(1))
            className = RVType.Empirical.parametrizedRVClass.simpleName!!
            type = (RVType.Empirical)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val population = doubleArrayParameter("population")
            return EmpiricalRV(population, rnStream)
        }
    }

    internal class DUniformRVParameters : RVParameters() {
        override fun fillParameters() {
            addIntegerParameter("min", 0)
            addIntegerParameter("max", 1)
            className = RVType.DUniform.parametrizedRVClass.simpleName!!
            type = (RVType.DUniform)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val min = integerParameter("min")
            val max = integerParameter("max")
            return DUniformRV(min, max, rnStream)
        }
    }

    internal class DEmpiricalRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleArrayParameter("values", doubleArrayOf(0.0, 1.0))
            addDoubleArrayParameter("cdf", doubleArrayOf(0.5, 1.0))
            className = RVType.DEmpirical.parametrizedRVClass.simpleName!!
            type = (RVType.DEmpirical)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val values = doubleArrayParameter("values")
            val cdf = doubleArrayParameter("cdf")
            return DEmpiricalRV(values, cdf, rnStream)
        }
    }

    internal class ConstantRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("value", 1.0)
            className = RVType.Constant.parametrizedRVClass.simpleName!!
            type = (RVType.Constant)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val value = doubleParameter("value")
            return ConstantRV(value)
        }
    }

    internal class ChiSquaredRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("dof", 1.0)
            className = RVType.ChiSquared.parametrizedRVClass.simpleName!!
            type = (RVType.ChiSquared)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val dof = doubleParameter("dof")
            return ChiSquaredRV(dof, rnStream)
        }
    }

    internal class BinomialRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("probOfSuccess", 0.5)
            addIntegerParameter("numTrials", 2)
            className = RVType.Binomial.parametrizedRVClass.simpleName!!
            type = (RVType.Binomial)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val probOfSuccess = doubleParameter("probOfSuccess")
            val numTrials = integerParameter("numTrials")
            return BinomialRV(probOfSuccess, numTrials, rnStream)
        }
    }

    internal class BetaRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("alpha1", 1.0)
            addDoubleParameter("alpha2", 1.0)
            className = RVType.Beta.parametrizedRVClass.simpleName!!
            type = (RVType.Beta)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val alpha1 = doubleParameter("alpha1")
            val alpha2 = doubleParameter("alpha2")
            return BetaRV(alpha1, alpha2, rnStream)
        }
    }

    internal class BernoulliRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("probOfSuccess", 0.5)
            className = RVType.Bernoulli.parametrizedRVClass.simpleName!!
            type = (RVType.Bernoulli)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val probOfSuccess = doubleParameter("probOfSuccess")
            return BernoulliRV(probOfSuccess, rnStream)
        }
    }

    internal class AR1NormalRVParameters : RVParameters() {
        override fun fillParameters() {
            addDoubleParameter("mean", 0.0)
            addDoubleParameter("variance", 1.0)
            addDoubleParameter("correlation", 0.0)
            className = RVType.AR1Normal.parametrizedRVClass.simpleName!!
            type = (RVType.AR1Normal)
        }

        override fun createRVariable(rnStream: RNStreamIfc): RVariableIfc {
            val mean = doubleParameter("mean")
            val variance = doubleParameter("variance")
            val correlation = doubleParameter("variance")
            return AR1NormalRV(mean, variance, correlation, rnStream)
        }
    }
}