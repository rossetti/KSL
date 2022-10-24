/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
import ksl.utilities.NewInstanceIfc
import ksl.utilities.random.ParametersIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.SampleIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/** A DPopulation is a population of doubles that can be sampled from and permuted.
 * @author rossetti
 * @param elements the elements to sample from
 * @param stream the stream to use for sampling
 * @param name the name of the population, optional
 */
class DPopulation(
    elements: DoubleArray,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : RandomIfc, SampleIfc, ParametersIfc, NewInstanceIfc<DPopulation>, IdentityIfc by Identity(name){

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    override var rnStream: RNStreamIfc = stream

    private var myElements: DoubleArray = elements.copyOf()

    /** Returns a new instance of the population with the same parameters
     * but an independent generator
     *
     * @return Returns a new instance of the population with the same parameters
     * but a different random stream
     */
    override fun instance(): DPopulation {
        return DPopulation(myElements)
    }

    /** Returns a new instance of the random source with the same parameters
     * but an independent generator
     *
     * @param stream a random number stream, must not null.
     * @return Returns a new instance of the population with the same parameters
     * but a different random stream
     */
    fun instance(stream: RNStreamIfc): DPopulation {
        return DPopulation(myElements, stream)
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
        return anArray
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