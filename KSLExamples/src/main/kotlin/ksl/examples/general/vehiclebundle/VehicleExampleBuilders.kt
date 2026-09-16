/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.vehiclebundle

import ksl.examples.book.chapter8.TestAndRepairShopWithGuidedTransporters
import ksl.examples.general.agv.ActiveShop
import ksl.examples.general.agv.DispatchingRuleComparison
import ksl.examples.general.agv.MultiFloorHospitalExample
import ksl.examples.general.agv.PassiveShop
import ksl.examples.general.agv.TwoLaneWarehouseExample
import ksl.examples.general.fleet.FreePathFleetExample
import ksl.examples.general.guidedpath.CrossingArbiterExample
import ksl.examples.general.guidedpath.GuidePathDisturbancesExample
import ksl.examples.general.guidedpath.SimpleAGVExample
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/*
 *  The vehicle examples that SHIP. One `ModelBuilderIfc` per model, so `kslpkg assemble` (the
 *  vehicleExamplesBundleJar task) can package them into the discoverable
 *  `edu.uark.ksl.vehicle-examples` bundle, dropped into the user's KSLWork/bundles folder to make
 *  every one of them pickable in the apps' Open Model... picker.
 *
 *  The MODELS ARE NOT COPIED HERE. Each builder imports the example where it lives -- in
 *  `general.guidedpath`, `general.agv`, `general.fleet` or `book.chapter8` -- so that the file a
 *  reader runs, the file the tutorial quotes and the file this bundle ships are one file. The
 *  animation bundle does the same; the older book bundle keeps its own copies, which is two of
 *  everything to maintain.
 *
 *  ADDING ONE: the bundle jar is assembled from path patterns in KSLExamples/build.gradle.kts, not
 *  from the call graph, so a builder whose model class lives somewhere not on that include list
 *  ships as an entry that cannot be built -- and nothing at compile time says so. Add the include,
 *  and VehicleExamplesBundleTest will confirm it.
 *
 *  WHAT IS NOT HERE, deliberately:
 *
 *  - `GuidedPathThroughputBenchmark` and `AgvThroughputBenchmark` measure wall-clock time on one
 *    machine. Nominating inputs and outputs would present them as decision models, which their own
 *    documentation says they are not.
 *  - `ZoneClosurePolicyExample` and `RetaskingInFlightExample` are single-trace demonstrations: what
 *    they produce is an arranged timeline read line by line, not a response with an interval.
 *
 *  Each `build` is a pure constructor, as [ModelBuilderIfc] requires: it makes a model and returns
 *  it, and may be called more than once per run.
 */

/** A run long enough to be worth reporting, short enough to open and run interactively. */
private const val REPLICATIONS: Int = 20
private const val HORIZON: Double = 8_000.0
private const val WARM_UP: Double = 1_000.0

/**
 *  Named, discoverable [ModelBuilderIfc] for the simple AGV shop: two carts on a one-way loop.
 *
 *  What the catalog nominates is the load on the shop and what handling a part costs, against the
 *  two headline responses. The arrival rate is named through the generator's own time-between-events
 *  random variable -- naming the generator is what gives that variable a stable key -- and the time
 *  to the *first* event is left alone: it is drawn once per replication and falls inside the warm-up.
 */
class SimpleAgvShopModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // The child element's name must differ from the model's own.
        val model = Model("SimpleAgvShop", autoCSVReports = false)
        val shop = SimpleAGVExample(model, name = "AgvShop")
        model.numberOfReplications = REPLICATIONS
        model.lengthOfReplication = HORIZON
        model.lengthOfReplicationWarmUp = WARM_UP
        model.curateCatalog {
            rvParameter("PartArrivals:TimeBtwEventsRV", "mean") {
                displayName = "Mean Time Between Arrivals"; unit = "min"
            }
            rvParameter(shop.loadingTimeRV, "value") {
                displayName = "Loading Time"; unit = "min"
            }
            rvParameter(shop.unLoadingTimeRV, "value") {
                displayName = "Unloading Time"; unit = "min"
            }
            output(shop.completed) { displayName = "Parts Delivered" }
            output(shop.timeInSystem) { displayName = "Avg Time in System"; unit = "min" }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the shop with spills and a maintenance window.
 *
 *  The disturbances are the point, so the catalog nominates what drives them -- how often a spill
 *  lands and how long it takes to clear -- alongside the load, against the blocked-cause
 *  decomposition they show up in. Both arrival rates are named through their generators' own
 *  time-between-events random variables, which is what the generators are named for.
 */
class GuidePathDisturbancesModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("GuidePathDisturbances", autoCSVReports = false)
        val shop = GuidePathDisturbancesExample(model, disturbed = true, name = "DisturbedShop")
        model.numberOfReplications = REPLICATIONS
        model.lengthOfReplication = HORIZON
        model.lengthOfReplicationWarmUp = WARM_UP
        model.curateCatalog {
            // Named by the example, not formatted here: a spill's cleanup time and the interval
            // between maintenance windows are the two rates the disturbances are made of.
            rvParameter("PartArrivals:TimeBtwEventsRV", "mean") {
                displayName = "Mean Time Between Arrivals"; unit = "min"
            }
            rvParameter("SpillArrivals:TimeBtwEventsRV", "mean") {
                displayName = "Mean Time Between Spills"; unit = "min"
            }
            rvParameter("CleanupTime", "mean") {
                displayName = "Mean Spill Cleanup Time"; unit = "min"
            }
            rvParameter("TimeBetweenWindows", "mean") {
                displayName = "Mean Time Between Maintenance Windows"; unit = "min"
            }
            output(shop.completed) { displayName = "Parts Delivered" }
            output(shop.timeInSystem) { displayName = "Avg Time in System"; unit = "min" }
            output(shop.spillsCleaned) { displayName = "Spills Cleaned" }
            output(shop.spillsAbsorbed) { displayName = "Spills Absorbed into Another" }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the two-floor hospital, whose lift is one zone.
 *
 *  Orders in circulation is the model's own `@KSLControl`, and it is the input the capacity study
 *  turns on: the fleet is closed, so it decides how much work is outstanding.
 */
class MultiFloorHospitalModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("MultiFloorHospital", autoCSVReports = false)
        val hospital = MultiFloorHospitalExample(
            model, numPorters = 3, shaftLength = 80.0, ordersInCirculation = 5
        )
        model.numberOfReplications = 10
        model.lengthOfReplication = 4_000.0
        model.lengthOfReplicationWarmUp = 500.0
        model.curateCatalog {
            input(hospital, "ordersInCirculation") {
                displayName = "Orders in Circulation"; unit = "orders"
            }
            rvParameter(hospital.preparationRV, "mean") {
                displayName = "Mean Order Preparation Time"; unit = "min"
            }
            output(hospital.delivered) { displayName = "Deliveries" }
            output(hospital.cycleTime) { displayName = "Avg Delivery Cycle Time"; unit = "min" }
            output(hospital.fleetBlocked) { displayName = "Fraction of Fleet Time Blocked" }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the two-lane warehouse grid.
 *
 *  Shipped in its two-lane configuration, which is the one that carries a fleet worth sizing; the
 *  single-aisle comparison is a second model instance the example's own `main` runs.
 */
class TwoLaneWarehouseModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("TwoLaneWarehouse", autoCSVReports = false)
        val warehouse = TwoLaneWarehouseExample(
            model, numCarts = 4, twoLane = true, name = "Warehouse"
        )
        model.numberOfReplications = 10
        model.lengthOfReplication = 5_000.0
        model.lengthOfReplicationWarmUp = 1_000.0
        model.curateCatalog {
            rvParameter(warehouse.timeBetweenArrivalsRV, "mean") {
                displayName = "Mean Time Between Arrivals"; unit = "min"
            }
            rvParameter(warehouse.pickTimeRV, "value") {
                displayName = "Pick Time"; unit = "min"
            }
            output(warehouse.delivered) { displayName = "Pallets Delivered" }
            output(warehouse.timeInSystem) { displayName = "Avg Time in System"; unit = "min" }
            output(warehouse.fleetBlocked) { displayName = "Fraction of Fleet Time Blocked" }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the free-path fleet yard: a dispatcher, no aisles.
 *
 *  The blocked fraction is deliberately absent from the catalog: on a free path it is zero for the
 *  whole run by construction, and an output that cannot vary is not a decision-relevant one.
 */
class FreePathFleetYardModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("FreePathFleetYard", autoCSVReports = false)
        // Not "Yard": the fleet inside the example already carries that name, and two model
        // elements cannot share one.
        val yard = FreePathFleetExample(model, cartCapacity = 4, name = "PalletYard")
        model.numberOfReplications = REPLICATIONS
        model.lengthOfReplication = HORIZON
        model.lengthOfReplicationWarmUp = WARM_UP
        model.curateCatalog {
            rvParameter(yard.timeBetweenArrivalsRV, "mean") {
                displayName = "Mean Time Between Arrivals"; unit = "min"
            }
            output(yard.delivered) { displayName = "Pallets Delivered" }
            output(yard.timeInSystem) { displayName = "Avg Time in System"; unit = "min" }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the same shop modelled the **passive** way: the part
 *  asks for a transporter and steers it.
 *
 *  Bundled beside [ActiveFleetShopModelBuilder] on purpose. The two are the same shop, and their
 *  numbers agree exactly, which is the result that makes the active subsystem a second way of
 *  modelling one world rather than a different world.
 */
class PassiveTransporterShopModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("PassiveTransporterShop", autoCSVReports = false)
        val shop = PassiveShop(model, name = "PassiveShop")
        model.numberOfReplications = REPLICATIONS
        model.lengthOfReplication = HORIZON
        model.lengthOfReplicationWarmUp = WARM_UP
        model.curateCatalog {
            rvParameter(shop.timeBetweenArrivals, "mean") {
                displayName = "Mean Time Between Arrivals"; unit = "min"
            }
            output(shop.delivered) { displayName = "Parts Delivered" }
            output(shop.timeInSystem) { displayName = "Avg Time in System"; unit = "min" }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the same shop modelled the **active** way: a
 *  dispatcher commits a vehicle to a task.
 *
 *  Reports four things its passive twin cannot -- how long a load waited to be *assigned* as
 *  distinct from how long it waited to be *collected* among them.
 */
class ActiveFleetShopModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("ActiveFleetShop", autoCSVReports = false)
        val shop = ActiveShop(model, name = "ActiveShop")
        model.numberOfReplications = REPLICATIONS
        model.lengthOfReplication = HORIZON
        model.lengthOfReplicationWarmUp = WARM_UP
        model.curateCatalog {
            rvParameter(shop.timeBetweenArrivals, "mean") {
                displayName = "Mean Time Between Arrivals"; unit = "min"
            }
            output(shop.delivered) { displayName = "Parts Delivered" }
            output(shop.timeInSystem) { displayName = "Avg Time in System"; unit = "min" }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the ring shop under six dispatching rules.
 *
 *  **One model, not six.** The rule is a string control with its six names declared, so the whole
 *  comparison is a study over one input rather than six models that happen to differ in a line.
 */
class DispatchingRulesRingShopModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("DispatchingRulesRingShop", autoCSVReports = false)
        val shop = DispatchingRuleComparison(model)
        model.numberOfReplications = 15
        model.lengthOfReplication = 10_000.0
        model.lengthOfReplicationWarmUp = 1_500.0
        model.curateCatalog {
            input("${shop.name}.ruleName") {
                displayName = "Dispatching Rule"
                description = "Which of the six rules the dispatcher runs"
            }
            rvParameter(shop.timeBetweenArrivals, "mean") {
                displayName = "Mean Time Between Arrivals"; unit = "min"
            }
            output(shop.delivered) { displayName = "Loads Delivered" }
            output(shop.timeInSystem) { displayName = "Avg Time in System"; unit = "min" }
            output(shop.waitForVehicle) { displayName = "Avg Wait for a Vehicle"; unit = "min" }
            output(shop.fleetImbalance) { displayName = "Fleet Imbalance" }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the pedestrian crossing under four disciplines.
 *
 *  Deterministic, so there is nothing to nominate as a random-variable parameter and nothing a
 *  confidence interval would add: the discipline *is* the input, and it is a string control with
 *  its four names declared. Two of the four starve one side of the crossing outright, which is
 *  what the example is for.
 */
class PedestrianCrossingModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("PedestrianCrossing", autoCSVReports = false)
        val town = CrossingArbiterExample(model, "BoundedBatch", name = "Town")
        model.numberOfReplications = 1
        model.lengthOfReplication = 120.0
        model.curateCatalog {
            input("${town.name}.arbiterName") {
                displayName = "Crossing Discipline"
                description = "Which admission discipline the crossing runs under"
            }
            output("${town.crossing.name}:CrossingsMade") { displayName = "Crossings Made" }
            output("${town.crossing.name}:TurnsTaken") { displayName = "Turns Taken" }
            output("${town.crossing.name}:FracTimeBarred") {
                displayName = "Fraction of Time Vehicles Were Barred"
            }
            output("${town.crossing.name}:WaitToCross") {
                displayName = "Avg Wait to Cross"; unit = "min"
            }
        }
        return model
    }
}

/**
 *  Named, discoverable [ModelBuilderIfc] for the chapter 8 test-and-repair shop with workers on a
 *  **guided path**.
 *
 *  The fourth of the chapter's four answers to one shop: resource-constrained, conveyor, movable
 *  resources, and this one, where a worker travels a one-way aisle and can be held up by the worker
 *  in front. The other three ship in the book-examples bundle; this one waits here while the
 *  guided-path subsystem is marked experimental, and joins them when it is not.
 */
class TestAndRepairShopWithGuidedTransportersModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("TestAndRepairShopWithGuidedTransporters", autoCSVReports = false)
        val shop = TestAndRepairShopWithGuidedTransporters(model, name = "Shop")
        model.numberOfReplications = 10
        model.lengthOfReplication = 52.0 * 5.0 * 2.0 * 480.0
        model.curateCatalog {
            rvParameter(shop.diagnosticTimeRV, "mean") {
                displayName = "Mean Diagnostic Time"; unit = "min"
            }
            output(shop.systemTime) { displayName = "Avg Time in System"; unit = "min" }
            output(shop.probWithinLimit) { displayName = "P(Time in System < 480)" }
            output(shop.transferTime) { displayName = "Avg Transfer Time"; unit = "min" }
            output(shop.numberOut) { displayName = "Jobs Completed" }
        }
        return model
    }
}
