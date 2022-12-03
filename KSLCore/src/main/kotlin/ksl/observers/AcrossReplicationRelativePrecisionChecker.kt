package ksl.observers

import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.statistic.DEFAULT_CONFIDENCE_LEVEL

class AcrossReplicationRelativePrecisionChecker(response: ResponseCIfc, desiredRelativePrecision: Double = 1.0, name: String? = null) :
    ModelElementObserver(name) {
    init {
        require(desiredRelativePrecision > 0.0) {"The desired half-width must be > 0"}
    }
    private val myResponse = response as Response

    init {
        myResponse.attachModelElementObserver(this)
    }

    var confidenceLevel = DEFAULT_CONFIDENCE_LEVEL
        set(value) {
            require((0.0 < value) && (value < 1.0)){"The confidence level must be within (0, 1)"}
            field = value
        }

    var desiredRelativePrecision = desiredRelativePrecision
        set(value) {
            require(value > 0.0) {"The desired half-width must be > 0"}
            field = value
        }

    override fun afterReplication(modelElement: ModelElement) {
        if (modelElement.model.currentReplicationNumber <= 2){
            return
        }
        val statistic = myResponse.myAcrossReplicationStatistic
        val hw = statistic.halfWidth(confidenceLevel)
        val xbar = statistic.average
        if (hw <= desiredRelativePrecision*xbar){
            modelElement.model.endSimulation("Relative precision = ($desiredRelativePrecision) condition met for response ${myResponse.name}")
        }
    }
}