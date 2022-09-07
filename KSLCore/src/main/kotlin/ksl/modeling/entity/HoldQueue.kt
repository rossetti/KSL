package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.simulation.ModelElement

class  HoldQueue<T: EntityType.Entity>(parent: ModelElement, name: String? = null): Queue<EntityType.Entity>(parent, name) {
}