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
package ksl.utilities.statistic

import ksl.utilities.random.SampleIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A collection of Bootstrap instances to permit multidimensional bootstrapping.
 * Construction depends on a named mapping of double[] arrays that represent the
 * original samples.  A static create method also allows creation based on a mapping
 * to implementations of the SampleIfc.
 *
 * The name provided for each dataset (or sampler) should be unique and will be used
 * to identify the associated bootstrap results. We call this name a addFactor.
 *
 * @param name    the name of the instance
 * @param dataMap a map holding the name for each data set, names cannot be null and the arrays cannot be null
 */
class MultiBootstrap(name: String? = null, dataMap: Map<String, DoubleArray>) : RNStreamControlIfc {
    /**
     * The id of this object
     */
    protected val myId: Long

    /**
     * @return the name of the bootstrap
     */
    val name: String

    protected var myBootstraps: MutableMap<String, Bootstrap>

    init {
        myIdCounter_ = myIdCounter_ + 1
        myId = myIdCounter_
        if (name == null) {
            this.name = "MultiBootstrap:$id"
        } else {
            this.name = name
        }
        myBootstraps = LinkedHashMap()
        for ((key, value) in dataMap) {
            val bs = Bootstrap(value, key)
            myBootstraps[key] = bs
        }
    }

    /**
     * @return the identity is unique to this execution/construction
     */
    val id: Long
        get() = myId

    /**
     *
     * @return the number of factors in the multi-bootstrap
     */
    fun getNumberFactors(): Int {
        return myBootstraps.size
    }

    /**
     *
     * @return the names of the factors as a list
     */
    fun getFactorNames(): List<String> {
        return ArrayList(myBootstraps.keys)
    }

    /**
     * This method changes the underlying state of the Bootstrap instance by performing
     * the bootstrap sampling.
     *
     * @param numBootstrapSamples  the number of bootstrap samples to generate for each of the bootstraps, the
     * keys must match the keys in the original data map.  If the names do not match
     * then the bootstraps are not generated.
     * @param estimator            a function of the data
     * @param saveBootstrapSamples indicates that the statistics and data of each bootstrap generate should be saved
     */
    fun generateSamples(
        numBootstrapSamples: Map<String, Int>,
        estimator: BSEstimatorIfc = BSEstimatorIfc.Average(),
        saveBootstrapSamples: Boolean = false
    ) {
        for ((name1, n) in numBootstrapSamples) {
            if (n > 1) {
                val bootstrap = myBootstraps[name1]
                bootstrap!!.generateSamples(n, estimator, saveBootstrapSamples)
            }
        }
    }

    /** Gets a map with key = name, where name is the associated bootstrap name
     * and the value is the array holding the sample averages for each
     * bootstrap samples within the bootstrap
     *
     * @return a map of the sample averages
     */
    fun bootstrapSampleAverages(): Map<String, DoubleArray> {
        val map: MutableMap<String, DoubleArray> = LinkedHashMap()
        for (name in myBootstraps.keys) {
            val bootstrap = myBootstraps[name]
            val bootstrapSampleAverages = bootstrap!!.bootstrapSampleAverages
            map[name] = bootstrapSampleAverages
        }
        return map
    }

    /** Gets a map with key = name, where name is the associated bootstrap name
     * and the value is an array holding the sample variances for each
     * bootstrap samples within the bootstrap
     *
     * @return a map of the sample variances
     */
    fun bootstrapSampleVariances(): Map<String, DoubleArray> {
        val map: MutableMap<String, DoubleArray> = LinkedHashMap()
        for (name in myBootstraps.keys) {
            val bootstrap = myBootstraps[name]
            val bootstrapSampleVariances = bootstrap!!.bootstrapSampleVariances
            map[name] = bootstrapSampleVariances
        }
        return map
    }

    /** Gets a map with key = name, where name is the associated bootstrap name
     * and the value is List holding the sample data for each
     * bootstrap generate within the bootstrap.  The size of the list is the number
     * of bootstrap samples generated. Each element of the list is the data associated
     * with each generate.
     *
     * @return a map of the list of bootstrap data
     */
    fun bootstrapSampleData(): Map<String, List<DoubleArray>> {
        val map: MutableMap<String, List<DoubleArray>> = LinkedHashMap()
        for (name in myBootstraps.keys) {
            val bootstrap = myBootstraps[name]
            val list = bootstrap!!.dataForEachBootstrapSample
            map[name] = list
        }
        return map
    }

    /** Gets a map with key = name, where name is the associated bootstrap name
     * and the value is List holding a RVariableIfc representation for each
     * bootstrap generate within the bootstrap.  The size of the list is the number
     * of bootstrap samples generated. Each element of the list is a RVariableIfc
     * representation of the data with the bootstrap generate.
     *
     * @param useCRN if true the stream for every random variable is the same across the
     * bootstraps to facilitate common random number generation (CRN). If false
     * different streams are used for each created random variable
     * @return a map of the list of bootstrap random variable representations
     */
    fun bootstrapRandomVariables(useCRN: Boolean = true): Map<String, List<RVariableIfc>> {
        val map: MutableMap<String, List<RVariableIfc>> = LinkedHashMap()
        for (name in myBootstraps.keys) {
            val bootstrap = myBootstraps[name]
            val list: List<RVariableIfc> = bootstrap!!.empiricalRVForEachBootstrapSample(useCRN)
            map[name] = list
        }
        return map
    }

    /**
     *
     * @param name the name of the bootstrap
     * @param b the bootstrap generate number, b = 1, 2, ... to getNumBootstrapSamples()
     * @return the generated sample for the bth bootstrap, if no samples are saved then
     * the array returned is of zero length
     */
    fun bootstrapSampleData(name: String, b: Int): DoubleArray {
        val bs = myBootstraps[name] ?: return DoubleArray(0)
        return bs.dataForBootstrapSample(b)
    }

    /** Gets a map with key = name, where name is the associated bootstrap name
     * and the value is the sample data for the bth
     * bootstrap generate.  The size of the array is the size of the generated
     * bootstrap generate, the array may be of zero length if the samples were not saved
     *
     * @param b the bootstrap sample number, b = 1, 2, ... to getNumBootstrapSamples()
     * @return a map holding the bth bootstrap data for each bootstrap
     */
    fun bootstrapSampleData(b: Int): Map<String, DoubleArray> {
        val map: MutableMap<String, DoubleArray> = LinkedHashMap()
        for (name in myBootstraps.keys) {
            val bootstrap = myBootstraps[name]
            val data: DoubleArray = bootstrap!!.dataForBootstrapSample(b)
            map[name] = data
        }
        return map
    }

    /**
     * @param name the name of the Bootstrap to get
     * @return the Bootstrap associated with the name
     */
    fun bootstrap(name: String): Bootstrap? {
        return myBootstraps[name]
    }

    /**
     *
     * @return a list of all the bootstraps
     */
    fun bootstrapList(): List<Bootstrap> {
        return ArrayList(myBootstraps.values)
    }

    override fun toString(): String {
        return asString()
    }

    /**
     *
     * @return the bootstrap results as a string
     */
    fun asString(): String {
        val sb = StringBuilder()
        sb.append("MultiBootstrap Results for : ")
        sb.append(name)
        sb.append(System.lineSeparator())
        for (bs in myBootstraps.values) {
            sb.append(bs.asString())
            sb.append(System.lineSeparator())
        }
        return sb.toString()
    }

    /**
     * The resetStartStream method will position the RNG at the beginning of its
     * stream. This is the same location in the stream as assigned when the RNG
     * was created and initialized for all bootstraps
     */
    override fun resetStartStream() {
        for (bs in myBootstraps.values) {
            bs.resetStartStream()
        }
    }

    /**
     * Resets the position of the RNG at the start of the current substream
     * for all bootstraps
     */
    override fun resetStartSubStream() {
        for (bs in myBootstraps.values) {
            bs.resetStartSubStream()
        }
    }

    /**
     * Positions the RNG at the beginning of its next substream for all bootstraps
     */
    override fun advanceToNextSubStream() {
        for (bs in myBootstraps.values) {
            bs.advanceToNextSubStream()
        }
    }

    /**
     * False means at least one is false.
     *
     * @return true means on all bootstraps have antithetic option on
     */
    override var antithetic: Boolean
        get()  {
            var b = true
            for (bs in myBootstraps.values) {
                b = bs.antithetic
                if (b == false) {
                    return false
                }
            }
            return b
        }
        set(value) {
            for (bs in myBootstraps.values) {
                bs.antithetic = value
            }
        }

    override var advanceToNextSubStreamOption: Boolean
        get() {
            check(!myBootstraps.isEmpty()) { "There were no streams present" }
            val listIterator: Iterator<Bootstrap> = myBootstraps.values.iterator()
            var b: Boolean = listIterator.next().advanceToNextSubStreamOption
            while (listIterator.hasNext()) {
                b = b && listIterator.next().advanceToNextSubStreamOption
            }
            return b
        }
        set(value) {
            for (r in myBootstraps.values) {
                r.advanceToNextSubStreamOption = value
            }
        }


    override var resetStartStreamOption: Boolean
        get() {
            check(!myBootstraps.isEmpty()) { "There were no streams present" }
            val listIterator: Iterator<Bootstrap> = myBootstraps.values.iterator()
            var b = listIterator.next().resetStartStreamOption
            while (listIterator.hasNext()) {
                b = b && listIterator.next().resetStartStreamOption
            }
            return b
        }
        set(value) {
            for (r in myBootstraps.values) {
                r.resetStartStreamOption = value
            }
        }

    companion object {
        /**
         * A counter to count the number of created to assign "unique" ids
         */
        private var myIdCounter_: Long = 0

        /**
         * @param sampleSize the size of the original generate
         * @param samplerMap something to generate the original generate of the provided size
         * @return an instance of MultiBootstrap based on data generated from each generate
         */
        fun create(sampleSize: Int, samplerMap: Map<String, SampleIfc>): MultiBootstrap {
            return create(null, sampleSize, samplerMap)
        }

        /**
         * @param name       the name of the instance
         * @param sampleSize the sample size, all samplers have the same amount sampled
         * @param samplerMap something to generate the original generate of the provided size
         * @return an instance of MultiBootstrap based on data generated from each generate
         */
        fun create(name: String?, sampleSize: Int, samplerMap: Map<String, SampleIfc>): MultiBootstrap {
            require(sampleSize > 1) { "The generate size must be greater than 1" }
            val dataMap: MutableMap<String, DoubleArray> = LinkedHashMap()
            for ((dname, value) in samplerMap) {
                val data: DoubleArray = value.sample(sampleSize)
                dataMap[dname] = data
            }
            return MultiBootstrap(name, dataMap)
        }

        /**
         * @param name       the name of the instance
         * @param samplerMap the String of the map is the named identifier for the bootstrap, the entry of the map
         * is a pair (Integer, SamplerIfc) which represents the number to generate and the sampler
         * @return an instance of MultiBootstrap based on data generated from each generate
         */
        fun create(name: String? = null, samplerMap: Map<String, Map.Entry<Int, SampleIfc>>): MultiBootstrap {
            val dataMap: MutableMap<String, DoubleArray> = LinkedHashMap()
            for ((key, value1) in samplerMap) {
                val (sampleSize, sampler) = value1
                require(sampleSize > 1) { "The generate size must be greater than 1" }
                val data: DoubleArray = sampler.sample(sampleSize)
                dataMap[key] = data
            }
            return MultiBootstrap(name, dataMap)
        }
    }
}