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
package ksl.utilities.random.robj

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.ParametersIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.SampleIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.NewAntitheticInstanceIfc

/** A DPopulation is a population of doubles that can be sampled from and permuted.
 * @author rossetti
 * @param elements the elements to sample from
 * @param streamNumber the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class DPopulation(
    elements: DoubleArray,
    streamNumber: Int = 0,
    private val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RandomIfc, SampleIfc, ParametersIfc, IdentityIfc by Identity(name), NewAntitheticInstanceIfc {

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    private val rnStream: RNStreamIfc = streamProvider.rnStream(streamNumber)

    override val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    private var myElements: DoubleArray = elements.copyOf()

    /**
     *  A copy of the elements of the original population of elements
     */
    val elements: DoubleArray
        get() = myElements.copyOf()

    /** Returns a new instance of the population with the same parameters
     * but an independent generator
     *
     * @return Returns a new instance of the population with the same parameters
     * but a different random stream
     */
    fun instance(): DPopulation {
        return DPopulation(myElements, 0, streamProvider, name)
    }

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): DPopulation {
        return DPopulation(myElements, streamNumber, rnStreamProvider, name)
    }

    /** Creates a new array that contains a randomly sampled values without replacement
     * from the existing population.
     *
     * @param sampleSize the number to sample
     * @return the sampled array
     */
    fun sampleWithoutReplacement(sampleSize: Int): DoubleArray {
        val anArray = myElements.copyOf()
        KSLRandom.sampleWithoutReplacement(anArray, sampleSize, rnStream)
        return anArray.take(sampleSize).toDoubleArray()
    }

    /** Creates a new array that contains a random permutation of the population
     *
     * @return a new array that contains a random permutation of the population
     */
    val permutation: DoubleArray
        get() = sampleWithoutReplacement(myElements.size)

    /** Causes the population to form a new permutation,
     * The ordering of the elements in the population will be changed.
     */
    fun permute() {
        KSLRandom.permute(myElements, rnStream)
    }

    /** Returns the value at the supplied index
     *
     * @param index must be &gt; 0 and less than size() - 1
     * @return the value at the supplied index
     */
    operator fun get(index: Int): Double {
        return myElements[index]
    }

    /** Sets the element at the supplied index to the supplied value
     *
     * @param index an index into the array
     * @param value the value to set
     */
    operator fun set(index: Int, value: Double) {
        myElements[index] = value
    }

    /** Returns the number of elements in the population
     *
     * @return the size of the population
     */
    fun size(): Int {
        return myElements.size
    }

    /**
     * @return Gets a copy of the population array, in its current state
     */
    override fun parameters(): DoubleArray {
        return myElements.copyOf()
    }

    /**
     *
     * @param params Copies the values from the supplied array to the population array
     */
    override fun parameters(params: DoubleArray) {
        require(params.isNotEmpty()) { "The element array had no elements." }
        myElements = params.copyOf()
    }

    /** Returns a randomly selected element from the population.  All
     * elements are equally likely.
     * @return the randomly selected element
     */
    override val value: Double
        get() = sample()

    /**
     * The randomly generated value. Each value
     * will be different
     * @return the randomly generated value, same as using property value
     */
    override fun value(): Double = value

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }

    override fun antitheticInstance(): DPopulation {
        return instance(streamNumber = -streamNumber, streamProvider)
    }

    override fun sample(): Double {
        return myElements[randomIndex]
    }

    /** Returns a random index into the population (assuming elements numbered starting at zero)
     *
     * @return a random index
     */
    val randomIndex: Int
        get() = rnStream.randInt(0, myElements.size - 1)

    override fun toString(): String {
        val sb = StringBuilder()
        for (i in myElements.indices) {
            sb.append("Element(")
            sb.append(i)
            sb.append(") = ")
            sb.append(myElements[i])
            sb.append(System.lineSeparator())
        }
        return sb.toString()
    }

    fun contentToString() : String {
        return myElements.contentToString()
    }
}