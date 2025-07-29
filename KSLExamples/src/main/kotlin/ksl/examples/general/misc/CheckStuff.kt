package ksl.examples.general.misc

import ksl.modeling.entity.Conveyor
import ksl.utilities.distributions.Weibull
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.*
import ksl.utilities.statistic.CachedHistogram
import ksl.utilities.statistic.Statistic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import ksl.modeling.entity.CapacityItemData
import ksl.modeling.entity.CapacityScheduleData
import ksl.simopt.evaluator.EstimatedResponse
import ksl.utilities.KSLArrays
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.statistic.OptimizationType

fun main(){
//    val twos = IntArray(10){ (2).ipow(it+3).toInt() }
//    println(twos.joinToString())
//    testPearson()
//    testWeibull()

//    val example = "House     Of The Dragon"
//    val withOutSpaces = example.replace("\\p{Zs}+".toRegex(), "_")
//    println(withOutSpaces)
 //   val lt1 = ConstantRV(10.0)
//    val lt2 = ShiftedGeometricRV(probOfSuccess = 0.2, streamNum = 1)
//    simulateDemandDuringLeadTime(1000, lt2)

//    testBoxMuller()
//      serializing()
    testMRound()

 //   testRV()
 //   testAI()
}



fun testAI(){

    // Create a Normal Random Variable with mean = 20.0, variance = 4.0
    val normalRandomVariable = NormalRV(20.0, 4.0)

    // Create an instance of the Statistic class to collect data
    val statistic = Statistic("Normal Distribution Stats")

    // Generate 100 random values and collect statistics on them
    repeat(100) {
        // Get a value from the normal distribution
        val value = normalRandomVariable.value

        // Collect this value in our statistic object
        statistic.collect(value)
    }

    // Output the collected statistics
    println(statistic)

    // You can also generate a report using StatisticReporter if desired
    val reporter = StatisticReporter(mutableListOf(statistic))
    println(reporter.summaryReport())

}

fun testRV(){

    val rvp = RNStreamProvider()
    val ad: RVariableIfc = ExponentialRV(1.0, 1)
    val ad2 : RVariableIfc = ExponentialRV(1.0, 1, rvp)

    for (i in 1..5){
        println("ad = ${ad.value}    ad2 = ${ad2.value}")
    }


}

fun testMRound(){
    //val x = 3.0459
    val x = 3.549
    //val x = Math.PI
    val g = 0.25
    val r = KSLMath.gRound(x, g)
    println("x=$x g=$g r=$r")
}

fun serializing(){
    val array = doubleArrayOf(1.0, 5.0, -3.0)

    val Json = Json { prettyPrint = true }
    val encoding = Json.encodeToString(DoubleArraySerializer(),  array)
    println(encoding)

    val ct = Conveyor.Type.ACCUMULATING
    val ctEncoded = Json.encodeToString(serializer<Conveyor.Type>(), ct)
    println(ctEncoded)

    val list = listOf(67.0, 33.0, 111.0)
    val listEncoded = Json.encodeToString(serializer<List<Double>>(),  list)
    println(listEncoded)

    val i1 = CapacityItemData(2, 30.0)
    val i2 = CapacityItemData(1, 60.0)
    val i3 = CapacityItemData(3, 90.0)
    val list1 = listOf(i1, i2, i3)
    val cd = CapacityScheduleData(capacityItems = list1)

    println(cd)
    println()
    println(Json.encodeToString(cd))
    println()
    println("Coded to JSON")
    println(cd.toJson())

    val decoded = Json.decodeFromString<CapacityScheduleData>(cd.toJson())
    println()
    println("Decoded")
    println(decoded)
}

fun testBoxMuller(n: Int = 1000){
    val u = UniformRV(streamNum = 1)
//    val h = CachedHistogram()
    val data = mutableListOf<Double>()
    for (i in 1..n) {
        val u1 = u.value
        val u2 = u.value
        val x1 = cos(2.0*PI*u2)*sqrt(-2.0*ln(u1))
//        h.collect(x1)
        data.add(x1)
    }
//    println(h)
//    h.currentHistogram.histogramPlot().showInBrowser()
    val pdf = PDFModeler(data.toDoubleArray())
    val results = pdf.showAllResultsInBrowser()
    pdf.showAllGoodnessOfFitSummariesInBrowser(results)
}

fun chunky(){
    val r = 1..40
    val chunks: List<List<Int>> = r.chunked(39)
    println(chunks)

    println(1..39)
}

fun testPearson(){
    val rv = PearsonType5RV(shape=3.0, scale=8.0)
    val data = rv.sample(2000)
    val ch = CachedHistogram(data)
    val plot = ch.histogramPlot()
    plot.showInBrowser()
}

fun testWeibull(){
    val rv = WeibullRV(shape=3.0, scale=8.0)
    val data = rv.sample(2000)
    val  shape1 = Weibull.initialShapeEstimate(data)
    println("1 shape = $shape1")
    val  shape2 = Weibull.approximateInitialShapeEstimate(data)
    println("2 shape = $shape2")
//    val ch = CachedHistogram(data)
//    val plot = ch.histogramPlot()
//    plot.showInBrowser()

}

fun leadTimeDemand(leadTime: RVariableIfc, demand: RVariableIfc) : Double {
    var sum = 0.0
    val T = leadTime.value.toInt()
    for (i in 1..T){
        sum = sum + demand.value
    }
    return sum
}

fun simulateDemandDuringLeadTime(sampleSize: Int, leadTime: RVariableIfc){
    val stat = Statistic("LTD Stats")
    val probStat = Statistic("LTD >= 10")
    val d = DEmpiricalRV(doubleArrayOf(4.0, 5.0, 6.0, 7.0, 8.0),
        doubleArrayOf(0.1, 0.4, 0.75, 0.85, 1.0), 2)
    for(i in 1..sampleSize){
        val ltd = leadTimeDemand(leadTime, d)
        stat.collect(ltd)
        probStat.collect(ltd >= 65.0)
    }
    val r = StatisticReporter(mutableListOf(stat, probStat))
    println(r.halfWidthSummaryReport())
}