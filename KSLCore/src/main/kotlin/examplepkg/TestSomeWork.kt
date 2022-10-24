/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package examplepkg

import ksl.utilities.KSLArrays
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.random.permute
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.DUniformRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.DoubleArraySaver
import ksl.utilities.statistic.Statistic
import kotlin.coroutines.intrinsics.*
import kotlin.coroutines.*

fun main() {
//    testStatistics()
//    testSequence()

    val gen = anotherGenerator(10)
    println(gen.next(Unit)) // 10
    println(gen.next(Unit)) // 11
}

// note that the 'this' of the generate lambda is the GeneratorBuilder that has the scope limited
// to only the yield functions
// the another generator function returns a Generator and I do not see any launch or other coroutine functions
// to call it.
// need to think about start coroutine versus create coroutine

fun anotherGenerator(i: Int): Generator<Int, Unit> = generate<Int, Unit> {
    yield(i + 1)
    yield(i + 2)
    yield(i + 3)
}


interface Generator<out T, in R> {
    fun next(param: R): T? // returns `null` when generator is over
}

@RestrictsSuspension
interface GeneratorBuilder<in T, R> {
    suspend fun yield(value: T): R
    suspend fun yieldAll(generator: Generator<T, R>, param: R)
}

fun <T, R> generate(block: suspend GeneratorBuilder<T, R>.(R) -> Unit): Generator<T, R> {
    val coroutine = GeneratorCoroutine<T, R>()
    val initial: suspend (R) -> Unit = { result -> block(coroutine, result) }
    coroutine.nextStep = { param -> initial.startCoroutine(param, coroutine) }
    return coroutine
}

// Generator coroutine implementation class
internal class GeneratorCoroutine<T, R>: Generator<T, R>, GeneratorBuilder<T, R>, Continuation<Unit> {
    lateinit var nextStep: (R) -> Unit
    private var lastValue: T? = null
    private var lastException: Throwable? = null

    // Generator<T, R> implementation

    override fun next(param: R): T? {
        nextStep(param)
        lastException?.let { throw it }
        return lastValue
    }

    // GeneratorBuilder<T, R> implementation

    override suspend fun yield(value: T): R = suspendCoroutineUninterceptedOrReturn { cont ->
        lastValue = value
        nextStep = { param -> cont.resume(param) }
        COROUTINE_SUSPENDED
    }

    override suspend fun yieldAll(generator: Generator<T, R>, param: R): Unit = suspendCoroutineUninterceptedOrReturn sc@ { cont ->
        lastValue = generator.next(param)
        if (lastValue == null) return@sc Unit // delegated coroutine does not generate anything -- resume
        nextStep = { param ->
            lastValue = generator.next(param)
            if (lastValue == null) cont.resume(Unit) // resume when delegate is over
        }
        COROUTINE_SUSPENDED
    }

    // Continuation<Unit> implementation

    override val context: CoroutineContext get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
        result
            .onSuccess { lastValue = null }
            .onFailure { lastException = it }
    }
}

val seq: Sequence<Int> = sequence<Int> {
    println("Generating first")
    yield(1)
    println("Generating second")
    yield(2)
    println("Generating third")
    yield(3)
    println("Done")
}

fun testSequence() {
    for (num in seq) {
        println("The next number is $num")
    }
}

public fun testStatistics() {
    val rv = ExponentialRV(10.0)
    val sample = rv.sample(100)
    val s = Statistic()
    val saver = DoubleArraySaver()
    s.attachObserver(saver)
    for (x in sample) {
//        s.collect(x > 8.0)
        s.collect(x)
    }
    println(s)
    println(s.statisticData())
    println()
    println(s.summaryStatisticsHeader)
    println(s.summaryStatistics)

    saver.write(KSL.out)
}

public fun testSomeThings() {
    val a: DoubleArray = DoubleArray(0)
    a.forEach { println(it) }
    val b: Array<Double> = a.toTypedArray()
    b.forEach { println(it) }

    val rv = ExponentialRV(10.0)

    for (i in 1..10) {
        println(rv.value)
    }
    println(rv.previousValue)

    val xs = rv.sample(20)

    val r: IntRange = 1..20

    val du = DUniformRV(r)
    for (i in 1..10) {
        println(du.value)
    }

    val s = rv.sample(5)
    println(s.contentToString())
    s.permute()
    println()

    println(s.contentToString())

    val crv = ConstantRV(10.0)
    println(crv)
    println(crv.value)
    crv.constVal = 12.0
    println(crv)
    println(crv.value)
    crv.constVal = 22.0
    println(crv)
    println(crv.value)

    val ar = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0),
        doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0)
    )

    var nr = KSLArrays.copy2DArray(ar)

    for (array in nr) {
        for (value in array) {
            print("$value ")
        }
        println()
    }

    KSL.out.println("This is some stuff!")

    for (array in nr) {
        for (value in array) {
            KSL.out.print("$value ")
        }
        KSL.out.println()
    }
//    var outDir = OutputDirectory("someDir", "someFile")
//    outDir.out.println("Some other stuff")
//    outDir.createSubDirectory("another directory")
    KSL.logger.info { "Some info message" }
    KSL.logger.warn { "Some warn message" }
    KSL.logger.debug { "Some debug message" }
    KSL.logger.error { "Some error message" }

    KSLFileUtil.logger.error { "error to report" }
    KSLFileUtil.logger.info { "info to report" }

}
