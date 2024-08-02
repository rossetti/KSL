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

package ksl.utilities.random

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariableIfc

/** Extension functions and other functions for working with arrays
 * @author rossetti@uark.edu
 */

/** Permutes the array in place. The array is changed.
 *
 * @param stream the stream to use for randomness
 */
fun DoubleArray.permute(stream: RNStreamIfc = KSLRandom.defaultRNStream()): DoubleArray {
    KSLRandom.permute(this, stream)
    return this
}

/** Permutes the array in place. The array is changed.
 *
 * @param streamNum the stream number to use for randomness
 */
fun DoubleArray.permute(streamNum: Int): DoubleArray {
    return this.permute(KSLRandom.rnStream(streamNum))
}

/** Permutes the array in place. The array is changed.
 *
 * @param stream the stream to use for randomness
 */
fun IntArray.permute(stream: RNStreamIfc = KSLRandom.defaultRNStream()): IntArray {
    KSLRandom.permute(this, stream)
    return this
}

/** Permutes the array in place. The array is changed.
 *
 * @param streamNum the stream number to use for randomness
 */
fun IntArray.permute(streamNum: Int): IntArray {
    return this.permute(KSLRandom.rnStream(streamNum))
}

/** Permutes the array in place. The array is changed.
 *
 * @param stream the stream to use for randomness
 */
fun BooleanArray.permute(stream: RNStreamIfc = KSLRandom.defaultRNStream()): BooleanArray {
    KSLRandom.permute(this, stream)
    return this
}

/** Permutes the array in place. The array is changed.
 *
 * @param streamNum the stream number to use for randomness
 */
fun BooleanArray.permute(streamNum: Int): BooleanArray {
    return this.permute(KSLRandom.rnStream(streamNum))
}

/** Permutes the array in place. The array is changed.
 *
 * @param stream the stream to use for randomness
 */
fun <T> Array<T>.permute(stream: RNStreamIfc = KSLRandom.defaultRNStream()): Array<T> {
    KSLRandom.permute(this, stream)
    return this
}

/** Permutes the array in place. The array is changed.
 *
 * @param streamNum the stream number to use for randomness
 */
fun <T> Array<T>.permute(streamNum: Int): Array<T> {
    return this.permute(KSLRandom.rnStream(streamNum))
}

/** Permutes the array in place. The array is changed.
 *
 * @param stream the stream to use for randomness
 */
fun <T> MutableList<T>.permute(stream: RNStreamIfc = KSLRandom.defaultRNStream()): MutableList<T> {
    KSLRandom.permute(this, stream)
    return this
}

/** Permutes the array in place. The array is changed.
 *
 * @param streamNum the stream number to use for randomness
 */
fun <T> MutableList<T>.permute(streamNum: Int): MutableList<T> {
    return this.permute(KSLRandom.rnStream(streamNum))
}

/** Randomly samples an element from the array.
 *
 * @param stream the stream to use for randomness
 */
fun DoubleArray.sample(stream: RNStreamIfc = KSLRandom.defaultRNStream()): Double {
    require(this.isNotEmpty()) {"Cannot sample from an empty array!"}
    return this[stream.randInt(0, this.size - 1)]
}

/** Randomly samples sampleSize elements from the array, returning a new array
 *
 * @param sampleSize the size of the sample must be 1 or more
 * @return a new array containing the sample
 */
fun DoubleArray.sample(sampleSize: Int, stream: RNStreamIfc = KSLRandom.defaultRNStream()): DoubleArray {
    require(this.isNotEmpty()) {"Cannot sample from an empty array!"}
    require(sampleSize > 0) {"The sample size must be > 0"}
    val arr = DoubleArray(sampleSize)
    for(i in arr.indices){
        arr[i] = this.sample(stream)
    }
    return arr
}

/** Randomly samples an element from the array.
 *
 * @param stream the stream to use for randomness
 */
fun IntArray.sample(stream: RNStreamIfc = KSLRandom.defaultRNStream()): Int {
    require(this.isNotEmpty()) {"Cannot sample from an empty array!"}
    return this[stream.randInt(0, this.size - 1)]
}

/** Randomly samples sampleSize elements from the array, returning a new array
 *
 * @param sampleSize the size of the sample must be 1 or more
 * @return a new array containing the sample
 */
fun IntArray.sample(sampleSize: Int, stream: RNStreamIfc = KSLRandom.defaultRNStream()): IntArray {
    require(this.isNotEmpty()) {"Cannot sample from an empty array!"}
    require(sampleSize > 0) {"The sample size must be > 0"}
    val arr = IntArray(sampleSize)
    for(i in arr.indices){
        arr[i] = this.sample(stream)
    }
    return arr
}

/** Randomly samples an element from the array.
 *
 * @param stream the stream to use for randomness
 */
fun BooleanArray.sample(stream: RNStreamIfc = KSLRandom.defaultRNStream()): Boolean {
    require(this.isNotEmpty()) {"Cannot sample from an empty array!"}
    return this[stream.randInt(0, this.size - 1)]
}

/** Randomly samples sampleSize elements from the array, returning a new array
 *
 * @param sampleSize the size of the sample must be 1 or more
 * @return a new array containing the sample
 */
fun BooleanArray.sample(sampleSize: Int, stream: RNStreamIfc = KSLRandom.defaultRNStream()): BooleanArray {
    require(this.isNotEmpty()) {"Cannot sample from an empty array!"}
    require(sampleSize > 0) {"The sample size must be > 0"}
    val arr = BooleanArray(sampleSize)
    for(i in arr.indices){
        arr[i] = this.sample(stream)
    }
    return arr
}

/** Randomly samples an element from the array.
 *
 * @param stream the stream to use for randomness
 */
fun <T> Array<T>.sample(stream: RNStreamIfc = KSLRandom.defaultRNStream()): T {
    require(this.isNotEmpty()) {"Cannot sample from an empty array!"}
    return this[stream.randInt(0, this.size - 1)]
}

/** Randomly samples sampleSize elements from the array, returning a new MutableList holding the same type.
 *  For now, caller can use toTypedArray() on the mutable list to get back an array at the call site.
 *
 * @param sampleSize the size of the sample must be 1 or more
 * @return a new array containing the sample
 */
fun <T> Array<T>.sample(sampleSize: Int, stream: RNStreamIfc = KSLRandom.defaultRNStream()): MutableList<T> {
    require(this.isNotEmpty()) {"Cannot sample from an empty array!"}
    require(sampleSize > 0) {"The sample size must be > 0"}
    return this.toMutableList().sample(sampleSize, stream)
    //TODO I would like to return an Array<T> but can't
    // https://stackoverflow.com/questions/41941102/instantiating-generic-array-in-kotlin
//    val arr = mutableListOf<T>().apply{
//        repeat(sampleSize){
//            this[it] = sample(stream)
//        }
//    }
//    return arr
}

/** Randomly samples an element from the list.
 *
 * @param stream the stream to use for randomness
 */
fun <T> MutableList<T>.sample(stream: RNStreamIfc = KSLRandom.defaultRNStream()): T {
    require(this.isNotEmpty()) {"Cannot sample from an empty list!"}
    return this[stream.randInt(0, this.size - 1)]
}

/** Randomly samples sampleSize elements from the list, returning a new list
 * The elements in the list may repeat (sampling with replacement)
 *
 * @param sampleSize the size of the sample must be 1 or more
 * @return a new array containing the sample
 */
fun <T> MutableList<T>.sample(sampleSize: Int, stream: RNStreamIfc = KSLRandom.defaultRNStream()): MutableList<T> {
    require(sampleSize > 0) {"The sample size must be > 0"}
    val list = mutableListOf<T>()
    for(i in 1..sampleSize){
        list.add(this.sample(stream))
    }
    return list
}

/**
 *  Returns an array that holds a sample from each individual
 *  random variable in the collection.
 */
fun Collection<RVariableIfc>.sample(): DoubleArray {
    val array = DoubleArray(this.size)
    for((i, element) in this.withIndex()){
        array[i] = element.value
    }
    return array
}

/**
 *  Returns an array that holds a sample of size [sampleSize] from each individual
 *  random variable in the collection.
 */
fun Collection<RVariableIfc>.sample(sampleSize: Int = 1): Array<DoubleArray> {
    val array = mutableListOf<DoubleArray>()
    for(element in this){
        array.add(element.sample(sampleSize))
    }
    return array.toTypedArray()
}

/**
 *  Returns an array that holds a sample from each individual
 *  random variable in the list.
 */
fun List<RVariableIfc>.sample(): DoubleArray {
    return DoubleArray(this.size) { this[it].value }
}

/**
 *  Returns a matrix that holds a sample of size [sampleSize] from each individual
 *  random variable in the list.
 */
fun List<RVariableIfc>.sample(sampleSize: Int = 1): Array<DoubleArray> {
    return Array(this.size) { this[it].sample(sampleSize) }
}