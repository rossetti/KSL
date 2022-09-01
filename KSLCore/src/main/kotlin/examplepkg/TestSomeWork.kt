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

fun main() {
//    testStatistics()
    testSequence()
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
