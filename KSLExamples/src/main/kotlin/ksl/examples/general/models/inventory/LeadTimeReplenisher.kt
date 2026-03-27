package ksl.examples.general.models.inventory

import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

class LeadTimeReplenisher(
    parent: ModelElement,
    leadTimeRV: RVariableIfc,
    name: String
) : ModelElement(parent, name), InventoryFillerIfc {

    private val myLeadTimeRV = RandomVariable(
        this, leadTimeRV, name = "${this.name}:LeadTime"
    )
    val leadTime: RandomVariableCIfc
        get() = myLeadTimeRV

    override fun fillInventory(demand: DemandCreator.Demand) {
        require(demand.isNotFilled) { "The demand has already been filled. Cannot replenish a filled demand." }
        schedule(this::endOfLeadTime, timeToEvent = myLeadTimeRV, message = demand)
    }

    private fun endOfLeadTime(event: KSLEvent<DemandCreator.Demand>) {
        val demand = event.message!!
        // fill the demand and send it to the demand receiver
        demand.fill(demand.amountNeeded)
        demand.filledDemandReceiver.receiveInventory(demand)
    }
}