package ksl.observers.welch

import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import java.nio.file.Path
import java.util.*

/**
 * @param responseVariable the response to be observed
 * @param batchSize the batch size for batching or discretizing the data
 *
 */
class WelchFileObserver(responseVariable: Response, batchSize: Double) : ModelElementObserver() {
    private val myWelchDataFileCollector: WelchDataFileCollector

    /**
     * @param responseVariable the response to be observed
     * @param batchSize the batch size for batching or discretizing the data
     *
     */
    constructor(responseVariable: ResponseCIfc, batchSize: Double):this(responseVariable as Response, batchSize)

    init {
        Objects.requireNonNull<Any>(responseVariable, "The response variable cannot be null")
        val statType = if (responseVariable is TWResponse) {
            StatisticType.TIME_PERSISTENT
        } else {
            StatisticType.TALLY
        }
        val outDir: Path = responseVariable.model.outputDirectory.outDir
        val subDir: Path = outDir.resolve(responseVariable.name + "_Welch")
        myWelchDataFileCollector = WelchDataFileCollector(subDir, statType, responseVariable.name, batchSize)
        responseVariable.attachModelElementObserver(this)
    }

    override fun toString(): String {
        return myWelchDataFileCollector.toString()
    }

    fun createWelchDataFileAnalyzer(): WelchDataFileAnalyzer {
        return myWelchDataFileCollector.makeWelchDataFileAnalyzer()
    }

    val welchFileMetaDataBeanAsJson: String
        get() = myWelchDataFileCollector.welchFileMetaDataBeanAsJson

    override fun beforeExperiment(modelElement: ModelElement) {
        myWelchDataFileCollector.setUpCollector()
    }

    override fun beforeReplication(modelElement: ModelElement) {
        myWelchDataFileCollector.beginReplication()
    }

    override fun afterReplication(modelElement: ModelElement) {
        myWelchDataFileCollector.endReplication()
    }

    override fun update(modelElement: ModelElement) {
        val rv: Response = modelElement as Response
        myWelchDataFileCollector.collect(rv.time, rv.value)
    }

    override fun afterExperiment(modelElement: ModelElement) {
        myWelchDataFileCollector.cleanUpCollector()
    }

    companion object {
        fun createWelchFileObserver(responseVariable: Response): WelchFileObserver {
            return WelchFileObserver(responseVariable, 1.0)
        }

        fun createWelchFileObserver(responseVariable: TWResponse, deltaTInterval: Double = 10.0): WelchFileObserver {
            return WelchFileObserver(responseVariable, deltaTInterval)
        }
    }
}