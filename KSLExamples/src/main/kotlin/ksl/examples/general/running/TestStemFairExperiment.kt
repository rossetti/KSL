package ksl.examples.general.running

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.controls.experiments.DesignedExperiment
import ksl.controls.experiments.Factor
import ksl.controls.experiments.FactorialDesign
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.ResourceWithQCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.*
import org.jetbrains.kotlinx.dataframe.api.print

class StemFairMixer(
    parent: ModelElement,
    name: String? = null
) :
    ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(2.0, 1)
    private val myNameTagTimeRV =
        RandomVariable(this, UniformRV((15.0 / 60.0), (45.0 / 60.0), 2), name = "${parent.name}:TagT")
    private val myWanderingTimeRV =
        RandomVariable(this, TriangularRV(15.0, 20.0, 45.0, 3), name = "${parent.name}:WanderingT")
    private val myTalkWithJHBunt = RandomVariable(this, ExponentialRV(6.0, 4), name = "${parent.name}:TalkJBH")
    private val myTalkWithMalMart = RandomVariable(this, ExponentialRV(3.0, 5), name = "${parent.name}:TalkWalmart")
    private val myDecideToWander = RandomVariable(this, BernoulliRV(0.5, 6), name = "${parent.name}:DecidetoW")
    private val myDecideToLeave = RandomVariable(this, BernoulliRV(0.1, 7), name = "${parent.name}:DecideLeave")

    private val myOverallSystemTime = Response(this, "OverallSystemTime")
    private val mySystemTimeNW = Response(this, "NonWanderSystemTime")
    private val mySystemTimeW = Response(this, "WanderSystemTime")
    private val mySystemTimeL = Response(this, "LeaverSystemTime")
    private val myNumInSystem = TWResponse(this, "NumInSystem")


    private val myJHBuntRecruiters: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "JHBuntR")
    val jhBuntRecruiters: ResourceWithQCIfc
        get() = myJHBuntRecruiters

    private val myMalWartRecruiters: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "MalWartR")
    val malWartRecruiters: ResourceWithQCIfc
        get() = myMalWartRecruiters

    private val generator = EntityGenerator(::Student, myTBArrivals, myTBArrivals)

    private inner class Student : Entity() {
        private val isWanderer = myDecideToWander.value.toBoolean()
        private val isLeaver = myDecideToLeave.value.toBoolean()

        val stemFairProcess = process {
            myNumInSystem.increment()
            delay(myNameTagTimeRV)
            if (isWanderer) {
                delay(myWanderingTimeRV)
                if (isLeaver) {
                    departMixer(this@Student)
                    return@process
                }
            }
            val mw = seize(myMalWartRecruiters)
            delay(myTalkWithMalMart)
            release(mw)
            val jhb = seize(myJHBuntRecruiters)
            delay(myTalkWithJHBunt)
            release(jhb)
            departMixer(this@Student)
        }

        private fun departMixer(departingStudent: Student) {
            myNumInSystem.decrement()
            val st = time - departingStudent.createTime
            myOverallSystemTime.value = st
            if (isWanderer) {
                mySystemTimeW.value = st
                if (isLeaver) {
                    mySystemTimeL.value = st
                }
            } else {
                mySystemTimeNW.value = st
            }

        }
    }
}

fun main() {
   // printControlsAndRVParameters1()
    simulateFactorialDesign1()
}

fun buildModel1(): Model {
    val m = Model("Stem_Fair_Test")
    m.lengthOfReplication = 6.0 * 60.0
    m.numberOfReplications = 400
    val SF = StemFairMixer(m, name = "STEMF")
    return m
}

fun printControlsAndRVParameters1() {
    val m = buildModel1()
    val controls = m.controls()
    val rvp = m.rvParameterSetter
    val pm = rvp.flatParametersAsDoubles
    for ((k, v) in pm) {
        println("$k: $v")
    }
    println("Controls")
    println(controls)
    println()
    println("RV Parameters")
    println(rvp)
}

fun simulateFactorialDesign1() {
    val fA = Factor("WalServer", doubleArrayOf(2.0, 3.0, 4.0))
    val fB = Factor("JBHServer", doubleArrayOf(2.0, 3.0, 4.0))

    val fC = Factor("TalkJBHTime", doubleArrayOf(6.0, 7.0))
    val fD = Factor("TalkWalmarttime", doubleArrayOf(0.3, 0.4))

    val fE = Factor("Tagtimemin", doubleArrayOf(0.5, 0.75))

    val factors1 = mapOf(
        fA to "MalWartR.initialCapacity",
        fB to "JHBuntR.initialCapacity",

        fC to "Stem_Fair_Test:TalkJBH_PARAM_mean",
        fD to "Stem_Fair_Test:TalkWalmart_PARAM_mean",

        fE to "Stem_Fair_Test:TagT_PARAM_min"
    )
    val m1 = buildModel1()

    val controls = m1.controls()
    val cm = controls.asMap()
    println("Controls")
    for((k, v) in cm) {
        println("$k: $v")
    }
    println()
    println("RV Parameters")
    val rvp = m1.rvParameterSetter
    val pm = rvp.flatParametersAsDoubles
    for ((k, v) in pm) {
        println("$k: $v")
    }

    println()

    val fd1 = FactorialDesign(factors1.keys)
    val de1 = DesignedExperiment("FactorDesignTest", m1, factors1, fd1)

    println("Design points being simulated")
    val df = fd1.designPointsAsDataframe()

    df.print(rowsLimit = 36)
    println()
    de1.simulate(numRepsPerDesignPoint = 3)
    println("Simulation of the design is completed")

//    println("Design point info")
//    val dpi = de.replicatedDesignPointInfo()
//    dpi.print(rowsLimit = 36)

    println()
    println("Replicated design points")
    val df2 = de1.replicatedDesignPointsAsDataFrame()

    df2.print(rowsLimit = 36)
    println()

    println("Responses as a data frame")
    val df3 = de1.responseAsDataFrame("System Time")
    df3.print(rowsLimit = 36)

    println()
    val df4 = de1.replicatedDesignPointsWithResponse("System Time")
    df4.print(rowsLimit = 36)

    println()
    val df5 = de1.replicatedDesignPointsWithResponses()
    df5.print(rowsLimit = 36)

    println()
    val df6 = de1.replicatedDesignPointsWithResponses(coded = true)
    df6.print(rowsLimit = 36)

    de1.resultsToCSV()
}

