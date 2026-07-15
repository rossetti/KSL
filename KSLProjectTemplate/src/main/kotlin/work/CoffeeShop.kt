package work

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * A small but complete process-view model: customers arrive at a coffee shop,
 * wait for a barista, get served, and leave.
 *
 * Use this as the starting shape for your own model. Two conventions here matter
 * beyond this example:
 *
 * 1. State is private, exposed read-only through a `CIfc` interface, so only the
 *    model element can change it.
 * 2. A property annotated with [KSLControl] becomes a parameter the KSL desktop
 *    applications can override at run time, without recompiling the model.
 */
class CoffeeShop(
    parent: ModelElement,
    numBaristas: Int = 1,
    name: String? = null
) : ProcessModel(parent, name = name) {

    private val myBarista = ResourceWithQ(this, name = "Barista", capacity = numBaristas)

    /**
     * The number of baristas on duty. Annotating the setter with [KSLControl] is what
     * lets the applications vary this parameter. It delegates to the resource's
     * initial capacity, which is the capacity each replication starts with.
     */
    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0)
    var numBaristas: Int
        get() = myBarista.initialCapacity
        set(value) {
            require(value > 0) { "The number of baristas must be at least 1" }
            myBarista.initialCapacity = value
        }

    private val myTimeBtwArrivals = RandomVariable(
        this, rSource = ExponentialRV(mean = 6.0, streamNum = 1), name = "TimeBtwArrivals"
    )
    val timeBtwArrivals: RandomVariableCIfc
        get() = myTimeBtwArrivals

    private val myServiceTime = RandomVariable(
        this, rSource = ExponentialRV(mean = 5.0, streamNum = 2), name = "ServiceTime"
    )
    val serviceTime: RandomVariableCIfc
        get() = myServiceTime

    private val mySystemTime = Response(this, name = "System Time")
    val systemTime: ResponseCIfc
        get() = mySystemTime

    private val myNumInSystem = TWResponse(this, name = "Num in System")
    val numInSystem: TWResponseCIfc
        get() = myNumInSystem

    private val myNumServed = Counter(this, name = "Num Served")
    val numServed: CounterCIfc
        get() = myNumServed

    private inner class Customer : Entity() {
        val visit = process {
            val arrivalTime = time
            myNumInSystem.increment()
            val allocation = seize(myBarista)
            delay(myServiceTime)
            release(allocation)
            myNumInSystem.decrement()
            mySystemTime.value = time - arrivalTime
            myNumServed.increment()
        }
    }

    override fun initialize() {
        schedule(::arrival, myTimeBtwArrivals)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        activate(Customer().visit)
        schedule(::arrival, myTimeBtwArrivals)
    }
}

/**
 * The bridge between your model and the KSL applications.
 *
 * A [ModelBuilderIfc] with a public no-arg constructor is the *only* thing the
 * bundle tools require: `kslpkg assemble` finds every implementation in your
 * project's JAR and packages it as a loadable model. Without one, your model can
 * only run from `main` in the IDE.
 *
 * The `curateCatalog` block is optional but worth writing — it supplies the
 * display names and units the applications show for each input and output.
 */
class CoffeeShopModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("CoffeeShop", autoCSVReports = false)
        val shop = CoffeeShop(model, numBaristas = 1, name = "Shop")
        model.numberOfReplications = 30
        model.lengthOfReplication = 480.0
        model.lengthOfReplicationWarmUp = 60.0
        model.curateCatalog {
            input(shop, CoffeeShop::numBaristas) {
                displayName = "Number of Baristas"; unit = "baristas"
            }
            rvParameter(shop.timeBtwArrivals, "mean") {
                displayName = "Mean Time Between Arrivals"; unit = "min"
            }
            rvParameter(shop.serviceTime, "mean") {
                displayName = "Mean Service Time"; unit = "min"
            }
            output(shop.systemTime) { displayName = "Avg Time in System"; unit = "min" }
            output(shop.numInSystem) { displayName = "Avg Number in System" }
            output(shop.numServed) { displayName = "Number Served" }
        }
        return model
    }
}

/**
 * Run the model from the IDE. This is the fast loop while you are developing.
 *
 * When you want the KSL applications to run it instead — to compare scenarios, run
 * a designed experiment, or optimize it — build this project's JAR and package it:
 *
 *     ./gradlew jar
 *     kslpkg assemble build/libs/KSLProjectTemplate.jar --id edu.example.mywork -o my-work.jar
 *
 * then drop `my-work.jar` into the `bundles` folder of your KSL working directory.
 */
fun main() {
    val model = CoffeeShopModelBuilder().build()
    model.simulate()
    model.print()
}
