package ksl.examples.general.models.inventory

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.simulation.ModelElement
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rvariable.RVariableIfc


//TODO - add item type to demand and demand generator.  This would allow for multiple types of items in the same system,

/**
 * Represents a generator of demand for the inventory system.
 * This is responsible for creating demand and sending it to the appropriate receiver.
 * The demand generator uses an event generator to schedule the creation of demand,
 * and a random variable to determine the amount of demand created.
 * @param parent the parent model element
 * @param itemType the item type requested
 * @param inventoryFiller the receiver of the demand created by this generator
 * @param timeBtwDemands the random variable representing the time between demand creation
 * @param demandAmount the random variable representing the amount of demand created
 * @param name the name of this demand generator
 */
class ItemDemandGenerator(
    parent: ModelElement,
    val itemType: ItemType,
    var inventoryFiller: InventoryFillerIfc,
    timeBtwDemands: RVariableIfc,
    demandAmount: RVariableIfc,
    name: String? = null
) : DemandCreator(parent, name), InventoryReceiverIfc {

    private val myEventGenerator = EventGenerator(
        this, this::generateDemand,
        timeUntilFirstRV = timeBtwDemands, timeBtwEventsRV = timeBtwDemands,
        name = "${this.name}:EventGenerator"
    )
    val eventGenerator: EventGeneratorRVCIfc
        get() = myEventGenerator

    private val myDemandAmountRV = RandomVariable(
        this, demandAmount, name = "${this.name}:DemandAmountRV"
    )
    val demandAmount: RandomVariableCIfc
        get() = myDemandAmountRV

    private fun generateDemand(generator: EventGeneratorIfc) {
        // create the demand
        //TODO use stochastic rounding
        val demand = Demand(itemType, myDemandAmountRV.value.toInt(), this)
        // send the demand to the receiver
        sender.sendDemand(demand)
    }

    private val sender: DemandSenderIfc = object : DemandSenderIfc, IdentityIfc by this {
        override fun sendDemand(demand: Demand) {
//            demand.demandSender = this
            inventoryFiller.fillInventory(demand)
            // could count number of demand sent
        }
    }

    override fun receiveInventory(demand: Demand) {
        // could check if demand is filled
        // could count demands received
    }

} 