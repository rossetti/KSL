/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.station.config

import ksl.modeling.station.ChildCountIfc
import ksl.modeling.station.ChildFactoryIfc
import ksl.modeling.station.MarkingHookIfc
import ksl.modeling.station.QObjectPredicate
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * A [ModelBuilderIfc] that builds a runnable [Model] containing a single
 * [ksl.modeling.station.StationNetwork] from a [QueueingNetworkSpec]. This makes a
 * serialized (e.g., TOML) network a portable model source that plugs into the
 * app/config layer (run configuration, scenarios, optimization).
 *
 * @param spec the network specification to build
 * @param predicates hooks for [RoutingSpec.ByCondition] cases; unknown names fail loudly
 * @param childFactories hooks for [ForkStationSpec.childFactory] names
 * @param childCounts hooks for [ForkStationSpec.childCount] names
 */
class QueueingNetworkModelBuilder(
    private val spec: QueueingNetworkSpec,
    private val predicates: Map<String, QObjectPredicate> = emptyMap(),
    private val childFactories: Map<String, ChildFactoryIfc> = emptyMap(),
    private val childCounts: Map<String, ChildCountIfc> = emptyMap(),
    private val markings: Map<String, MarkingHookIfc> = emptyMap()
) : ModelBuilderIfc {

    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // the network is named spec.name; give the model a distinct name to avoid a collision
        val model = Model("${spec.name}_Model")
        StationNetworkBuilder(spec, predicates, childFactories, childCounts, markings).build(model)
        experimentRunParameters?.let { model.changeRunParameters(it) }
        return model
    }

    companion object {
        /** Builds from a TOML network description with no hooks. */
        fun fromToml(text: String): QueueingNetworkModelBuilder =
            QueueingNetworkModelBuilder(QueueingNetworkToml.decode(text))

        /** Builds from a TOML network description with a named-hook predicate registry. */
        fun fromToml(text: String, predicates: Map<String, QObjectPredicate>): QueueingNetworkModelBuilder =
            QueueingNetworkModelBuilder(QueueingNetworkToml.decode(text), predicates)

        /** Builds from a TOML network description with all hook registries. */
        fun fromToml(
            text: String,
            predicates: Map<String, QObjectPredicate>,
            childFactories: Map<String, ChildFactoryIfc>,
            childCounts: Map<String, ChildCountIfc>
        ): QueueingNetworkModelBuilder =
            QueueingNetworkModelBuilder(QueueingNetworkToml.decode(text), predicates, childFactories, childCounts)

        /** Builds from a JSON network description with no hooks. */
        fun fromJson(text: String): QueueingNetworkModelBuilder =
            QueueingNetworkModelBuilder(QueueingNetworkJson.decode(text))

        /** Builds from a JSON network description with a named-hook predicate registry. */
        fun fromJson(text: String, predicates: Map<String, QObjectPredicate>): QueueingNetworkModelBuilder =
            QueueingNetworkModelBuilder(QueueingNetworkJson.decode(text), predicates)
    }
}
