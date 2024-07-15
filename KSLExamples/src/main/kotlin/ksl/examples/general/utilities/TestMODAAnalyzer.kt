package ksl.examples.general.utilities

import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.observers.ReplicationDataCollector
import ksl.simulation.Model
import ksl.utilities.Interval
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.moda.MODAAnalyzer
import ksl.utilities.moda.MODAAnalyzerData
import ksl.utilities.moda.Metric
import ksl.utilities.moda.MetricIfc
import org.jetbrains.kotlinx.dataframe.api.print
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

fun main(){
    val model = Model("MODA Analyzer Testing")
    model.numberOfReplications = 10
    model.experimentName = "Two Workers"
    // add the model element to the main model
    val palletWorkCenter = PalletWorkCenter(model)

    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)
    val db = kslDatabaseObserver.db
    // simulate the model
    model.simulate()
    model.print()

    model.experimentName = "Three Workers"
    palletWorkCenter.numWorkers = 3
    model.simulate()
    model.print()

//    val mcb = db.multipleComparisonAnalyzerFor(
//        listOf("Two Workers", "Three Workers"), palletWorkCenter.totalProcessingTime.name)
//    println(mcb)

    val eNames = setOf("Two Workers", "Three Workers")

    // need the response names for the MODA model
    // val rNames = setOf("Worker Utilization", "System Time", "Total Processing Time")
    // as num workers increases, utilization, system time, and total processing time should go down

    val util = Metric("Worker Utilization", domain = Interval(0.0, 1.0))
    util.direction = MetricIfc.Direction.BiggerIsBetter
    val sysTime = Metric("System Time")
    val tpt = Metric("Total Processing Time")
    val responseDefinitions = setOf(
        MODAAnalyzerData(util),
        MODAAnalyzerData(sysTime),
        MODAAnalyzerData(tpt),
    )

    val modaAnalyzer = MODAAnalyzer(eNames, responseDefinitions)
    modaAnalyzer.analyze(db.withinRepViewData())


    for(i in 1..10){
        println("Replication $i MODA")
        modaAnalyzer.modaByReplication[i]?.alternativeScoresAsDataFrame()?.print()
        println()
    }

    for(i in 1..10){
        println("Replication $i MODA")
        modaAnalyzer.modaByReplication[i]?.alternativeValuesAsDataFrame()?.print()
        println()
    }

    modaAnalyzer.overallValueByAlternative.toDataFrame().print()
}