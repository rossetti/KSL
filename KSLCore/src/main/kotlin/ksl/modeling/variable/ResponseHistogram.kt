package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc

/**
 * The user can supply a histogram to use for the tabulation or a minimum data criteria.
 * If no minimum data criteria (theBreakPointMinDataSize) is supplied, then the histogram
 * is assumed to be configured properly to observe the response.  If a minimum data
 * criteria (greater than 0) is supplied, then the supplied histogram is used to collect
 * observations during the first replication in order to recommend desirable breakpoints.
 * The histogram and prior data are replaced when the new breakpoints are determined.
 *
 * The histogram tabulates all within replication observations regardless of replication.
 * That is, the histogram is based on every observation for every replication.  It observes
 * observations that may have been within a warmup period even if the modeler specifies
 * a warmup period.
 *
 * @param theResponse the response variable to form a histogram on
 * @param theHistogram the histogram to use to collect the observations
 * @param theBreakPointMinDataSize the minimum about of data needed in the first replication
 * to approximate good breakpoints. Supplying a value greater than 0 will cause the
 * histogram's breakpoints to be formed based on the observations of the first replication
 * @param name the name of the model element
 */
class ResponseHistogram(
    theResponse: Response,
    theHistogram: Histogram = Histogram(doubleArrayOf(0.0), "${theResponse.name}:Histogram"),
    theBreakPointMinDataSize: Int = 0,
    name: String? = "${theResponse.name}:Histogram"
) : ModelElement(theResponse, name) {

    val breakPointMinDataSize = theBreakPointMinDataSize
    private val response = theResponse
    private val myObserver = ResponseObserver()

    init {
        response.attachModelElementObserver(myObserver)
    }

    private var myHistogram: Histogram = theHistogram
    val histogram: HistogramIfc
        get() = myHistogram

    var selfConfigureBreakPoints: Boolean = (theBreakPointMinDataSize > 0)
        private set

    override fun beforeExperiment() {
        myHistogram.reset()
        selfConfigureBreakPoints = (breakPointMinDataSize > 0)
    }

    private inner class ResponseObserver : ModelElementObserver() {
        override fun update(modelElement: ModelElement) {
            // must be a response because only attached to responses
            val response = modelElement as Response
            myHistogram.collect(response.value)
            // user said to use data to set break points
            if (selfConfigureBreakPoints) {
                if (myHistogram.count >= breakPointMinDataSize) {
                    selfConfigureBreakPoints = false
                    // enough data to set break points
                    var breakPoints = Histogram.recommendBreakPoints(myHistogram)
                    breakPoints = Histogram.addPositiveInfinity(breakPoints)
                    myHistogram.reset()
                    myHistogram = Histogram(breakPoints, "${response.name}:Histogram")
                }
            }
        }
    }

    override fun afterExperiment() {
//        println(histogram)
    }
}