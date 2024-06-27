package ksl.examples.general.utilities.fitting

import ksl.utilities.random.rvariable.*
import kotlin.time.TimeSource

enum class ExpType {FULL, SCREENING}
enum class RVCases {G, W, LN, M, GB, PT5}

val gamma = listOf(
    GammaRV(2.0, 2.0),
    GammaRV(3.0, 2.0),
    GammaRV(5.0, 1.0),
    GammaRV(9.0, 0.5),
    GammaRV(7.5, 1.1),
    GammaRV(0.5, 1.0)
)

val weibull = listOf(
    WeibullRV(0.5, 1.0),
    WeibullRV(0.8, 1.0),
    WeibullRV(1.25, 1.0),
    WeibullRV(1.5, 1.0),
    WeibullRV(1.75, 1.0),
    WeibullRV(2.0, 1.0),
)

val logNormal = listOf(
    LognormalRV(0.5, 0.5),
    LognormalRV(0.5, 1.0),
    LognormalRV(1.5, 1.0),
    LognormalRV(2.0, 1.0),
    LognormalRV(4.0, 1.0),
    LognormalRV(6.0, 2.0),
)

val misc = listOf(
    UniformRV(5.0, 15.0),
    TriangularRV(5.0, 10.0, 15.0),
    TriangularRV(5.0, 7.5, 15.0),
    TriangularRV(5.0, 13.5, 15.0),
    NormalRV(10.0, 2.0),
    ExponentialRV(10.0)
)

val generalizedBeta = listOf(
    GeneralizedBetaRV(0.5, 0.5, min = 5.0, max = 15.0),
    GeneralizedBetaRV(5.0, 1.0, min = 5.0, max = 15.0),
    GeneralizedBetaRV(1.0, 3.0, min = 5.0, max = 15.0),
    GeneralizedBetaRV(2.0, 2.0, min = 5.0, max = 15.0),
    GeneralizedBetaRV(2.0, 5.0, min = 5.0, max = 15.0),
)

val pearsonType5 = listOf(
    PearsonType5RV(7.0, 15.0),
    PearsonType5RV(3.0, 8.0),
    PearsonType5RV(10.0, 45.0),
    PearsonType5RV(3.0, 20.0),
)

fun buildCases(rvCases: Set<RVCases>, sampleSizes: Set<Int>, numSamples: Int = 200) : List<DFTestCase> {
    val rvList: MutableList<List<ParameterizedRV>> = mutableListOf()
    for(rvType in rvCases) {
        when(rvType) {
            RVCases.G -> {
                rvList.add(gamma)
            }
            RVCases.W -> {
                rvList.add(weibull)
            }
            RVCases.LN -> {
                rvList.add(logNormal)
            }
            RVCases.M -> {
                rvList.add(misc)
            }
            RVCases.GB -> {
                rvList.add(generalizedBeta)
            }
            RVCases.PT5 -> {
                rvList.add(pearsonType5)
            }
        }
    }
    val caseList = mutableListOf<DFTestCase>()
    for (list in rvList) {
        for (rv in list) {
            val case = DFTestCase(rv, sampleSizes, numSamples)
            caseList.add(case)
        }
    }
    return caseList
}

fun setUpSampleSizes(
    expType: ExpType,
    low:Int = 20,
    high:Int = 400,
    max: Int = 2000,
    stepSize: Int = 20)
        : Set<Int> {
    return when (expType) {
        ExpType.FULL -> {
            (low..max step stepSize).toSet()
        }
        ExpType.SCREENING -> {
            setOf(low, high)
        }
    }
}

fun main(){
    val allRVs = RVCases.entries.toSet()
//    val subSet = setOf(RVCases.G)
    val eType = ExpType.FULL
//    val eType = ExpType.SCREENING
//    val testCases = buildCases(allRVs, setUpSampleSizes(eType))
    val testCases = buildCases(allRVs, setUpSampleSizes(eType))
    val dfExperiment = DFExperiment("${eType}", testCases)
    dfExperiment.messageOutput = true
    println("Running experiments...")
    val mark = TimeSource.Monotonic.markNow()
    dfExperiment.runCases()
    val elapsed = mark.elapsedNow()
    println("Completed experiments!")
    println("Elapsed time: $elapsed")
}

