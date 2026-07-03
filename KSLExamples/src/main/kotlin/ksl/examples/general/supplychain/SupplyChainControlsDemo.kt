package ksl.examples.general.supplychain

import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.DemandGenerator
import ksl.modeling.supplychain.inventory.Inventory
import ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointOrderUpToLevel
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Demonstrates driving a supply-chain model through the controls machinery
 * (`ksl.controls`): discovering the model's controllable parameters by key, setting
 * them without referencing the model classes, and running replications that all begin
 * under the configured initial conditions.
 *
 * The key ideas on display:
 *
 * 1. **Discovery.** `model.controls()` reflects over the element tree and finds every
 *    annotated parameter. Keys are `elementName.propertyName` — name your elements
 *    meaningfully so keys are stable.
 * 2. **Initial-vs-current contract.** Policy controls (e.g. the (r, S) policy's
 *    `initialReorderPoint` / `initialOrderUpToPointDelta`) configure replication
 *    INITIAL conditions: the current policy parameters are re-seeded from the initial
 *    values before each replication, so every replication starts under the same
 *    settings. Changing an initial value during a replication is rejected.
 * 3. **The delta parameterization.** The order-up-to level is controlled as
 *    SDelta = S − r (with S derived as r + SDelta), following the (r,Q) policy's
 *    RDelta precedent: any combination of clamped control values is valid, so
 *    experiments and optimizers can set the keys independently, in any order.
 *
 * This is exactly how `ksl.controls.experiments` (factorial designs, scenarios) and
 * the simopt machinery parameterize models — this demo just does it by hand so the
 * mechanics are visible.
 */
fun main() {
    val model = Model("SupplyChainControlsDemo")
    val sc = SupplyChainModel(model)
    val item = ItemType(sc, name = "SKU")
    val policy = InventoryPolicyReorderPointOrderUpToLevel(
        sc, reorderPoint = 5, orderUpToPoint = 15, name = "RS"
    )
    val inventory = Inventory(sc, item, policy, initialOnHand = 20, name = "Store")
    // customers arrive ~every 2 time units and pull unit demands from the store
    val demand = DemandGenerator(
        sc, item,
        timeUntilFirstRV = ExponentialRV(2.0, streamNum = 1),
        timeBtwEventsRV = ExponentialRV(2.0, streamNum = 2),
        name = "Customers"
    )
    demand.demandFiller = inventory

    // 1. Discover what is controllable — no references to the classes above needed.
    val controls = model.controls()
    println("Discovered numeric controls:")
    for (key in controls.controlKeys().sorted()) {
        println("   $key")
    }
    println()

    // 2. Configure the experiment by key: raise the policy to (r = 10, S = 30) and
    //    start with more stock. SDelta = S - r = 20; order of the sets does not matter.
    controls.control("RS.initialReorderPoint")!!.value = 10.0
    controls.control("RS.initialOrderUpToPointDelta")!!.value = 20.0
    controls.control("Store.initialOnHand")!!.value = 30.0

    println("Configured initial conditions:")
    println("   r = ${policy.initialReorderPoint}, S = ${policy.initialOrderUpToPoint} " +
            "(SDelta = ${policy.initialOrderUpToPointDelta})")
    println("   initial on hand = ${inventory.initialOnHand}")
    println()

    // 3. Run. Every replication begins from these initial conditions because the
    //    current policy parameters are re-seeded from the initials at replication start.
    model.lengthOfReplication = 1000.0
    model.lengthOfReplicationWarmUp = 100.0
    model.numberOfReplications = 5
    model.simulate()
    model.print()

    println("Policy parameters used during the run: r = ${policy.reorderPoint}, " +
            "S = ${policy.orderUpToPoint}")
}
