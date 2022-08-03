package ksl.simulation

import ksl.utilities.IdentityIfc
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableIfc

private var myCounter_: Int = 0

open class ModelElement internal constructor(theName: String? = null) : IdentityIfc,
    ObservableIfc<ModelElement> by Observable() {

    init {
        myCounter_ = myCounter_ + 1
    }

    override val id: Int = myCounter_

    override val name: String = makeName(theName)

    private fun makeName(str: String?): String {
        return if (str == null) {
            // no name is being passed, construct a default name
            var s = this::class.simpleName!!
            val k = s.lastIndexOf(".")
            if (k != -1) {
                s = s.substring(k + 1)
            }
            s + "_" + id
        } else {
            str
        }
    }

    override var label: String? = null
        get() {
            return if (field == null) name else field
        }

    /**
     * A flag to control whether the model element reacts to before
     * experiment actions.
     */
    protected var myBeforeExperimentOption = true

    /**
     * A flag to control whether the model element reacts to before
     * replication actions.
     */
    protected var myBeforeReplicationOption = true

    /**
     * A flag to control whether the model element participates in monte
     * carlo actions.
     */
    protected var myMonteCarloOption = false

    /**
     * A flag to control whether the model element reacts to
     * initialization actions
     */
    protected var myInitializationOption = true

    /**
     * A flag to control whether the model element reacts to end
     * replication actions.
     */
    protected var myReplicationEndedOption = true

    /**
     * A flag to control whether the model element reacts to after
     * replication actions.
     */
    protected var myAfterReplicationOption = true

    /**
     * A flag to control whether the model element reacts to after
     * experiment actions.
     */
    protected var myAfterExperimentOption = true

    /**
     * Specifies if this model element will be warmed up when the warmup action
     * occurs for its parent.
     */
    protected var myWarmUpOption = true

    /**
     * Specifies whether this model element participates in time update
     * event specified by its parent
     */
    protected var myTimedUpdateOption = true

    /**
     * A collection containing the first level children of this model element
     */
    protected val myModelElements: MutableList<ModelElement> = mutableListOf()

    /**
     * The parent of this model element
     */
    protected var myParentModelElement: ModelElement? = null

    /**
     * A reference to the overall model containing all model elements.
     */
    protected lateinit var myModel: Model

    constructor(parent: ModelElement, name: String?) : this(name) {
        // should not be leaking this
        // adds the model element to the parent and also set this element's parent
        parent.addModelElement(this)
        myModel = parent.myModel
        //TODO myModel.addToModelElementsMap(this)
    }

    /**
     * This method is called from the constructor of a ModelElement. The
     * constructor of a ModelElement uses the passed in parent ModelElement to
     * call this method on the parent ModelElement in order to add itself as a
     * child element on the parent The modelElement's parent will be set to this
     * element's parent
     *
     * @param modelElement the model element to be added.
     */
    private fun addModelElement(modelElement: ModelElement) {
        // add the model element to the list of children
        myModelElements.add(modelElement)
        // set its parent to this element
        modelElement.myParentModelElement = this
    }

}