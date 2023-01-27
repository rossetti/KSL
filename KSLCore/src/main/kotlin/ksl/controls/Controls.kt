/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.controls

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.maps.KSLMaps
import ksl.utilities.maps.toJson
import mu.KLoggable
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

interface ControlIfc {
    val type: ControlType
    var value: Double
    val keyName: String
    val lowerBound: Double
    val upperBound: Double
    val elementName: String
    val elementId: Int
    val elementType: String
    val propertyName: String
    val comment: String
    val modelName: String

    /**
     * Ensures that the supplied double is within the bounds
     * associated with the control. This function does
     * not change the state of the control.
     *
     * @param value the value to limit
     * @return the limited value for future use
     */
    fun limitToRange(value: Double): Double {
        if (value <= lowerBound) {
            return lowerBound
        } else if (value >= upperBound) {
            return upperBound
        }
        return value
    }
}

data class ControlData(
    val key: String,
    val value: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val comment: String,
    val controlType: ControlType,
    val elementType: String,
    val elementName: String,
    val modelName: String
)

class Controls(aModel: Model) {

    private val myControls = mutableMapOf<String, ControlIfc>()

    private val model = aModel

    val size: Int
        get() = myControls.size

    init {
        extractControls(model)
    }

    /**
     * Extracts all controls from every model element of the model
     * that has a control annotation.
     *
     * @param model the model for extraction
     */
    private fun extractControls(model: Model) {
        val elements = model.getModelElements()
        for (me in elements) {
            extractControls(me)
        }
    }

    /**
     * extract Controls for a modelElement
     *
     * @param modelElement the model element to extract from
     */
    private fun extractControls(modelElement: ModelElement) {
        val cls: KClass<out ModelElement> = modelElement::class
        val properties: Collection<KProperty1<out ModelElement, *>> = cls.memberProperties
        logger.info { "Extracting controls for model element: ${modelElement.name}" }
        for (property in properties) {
            logger.trace { "Reviewing member property: ${property.name}" }
            if (property is KMutableProperty<*>) {
                logger.trace { "Member property, ${property.name}, is mutable property" }
                if (hasControlAnnotation(property.setter)) {
                    logger.info { "Member property, ${property.name}, setter has control annotations" }
                    val kslControl: KSLControl = controlAnnotation(property.setter)!!
                    logger.info { "Extracted annotation: $kslControl" }
                    // check if property type is consistent with annotation type
                    if (ControlType.validType(property.returnType)) {
                        logger.trace { "Setter has valid type: ${property.returnType}" }
                        if (kslControl.include) {
                            logger.trace { "Controls will include annotated setter: ${property.setter.name}" }
                            val control = Control(modelElement, property, kslControl)
                            store(control)
                            logger.info { "Control ${control.keyName} for property ${property.name} was extracted and added to controls" }
                        } else {
                            logger.trace { "Control ${kslControl.name} from property ${property.setter.name} was excluded during extraction." }
                        }
                    } else {
                        logger.trace { "The property return type, ${property.returnType.classifier.toString()} is not valid for ${kslControl.controlType}" }
                    }
                } else {
                    logger.trace { "Member property, ${property.name}, has has no control annotations" }
                }
            } else {
                logger.trace { "Member property, ${property.name}, reported as not a mutable property" }
            }
        }
    }

    /**
     * Store a new control
     *
     * @param control the control to add
     */
    private fun store(control: ControlIfc) {
        val kn = control.keyName
        myControls[kn] = control
    }

    /**
     * @return the control keys as an unmodifiable set of strings
     */
    fun controlKeys(): Set<String> {
        return myControls.keys
    }

    /**
     *
     * @param name the control name to check
     * @return true if the named control is in the controls
     */
    fun hasControl(name: String): Boolean {
        return myControls.containsKey(name)
    }

    /**
     *
     * @return a list of the controls
     */
    fun asList(): List<ControlIfc> {
        val list = mutableListOf<ControlIfc>()
        for ((_, control) in myControls) {
            list.add(control)
        }
        return list
    }

    /**
     * The type should be associated with a valid control type.
     *
     * @param controlType the type of control wanted
     * @return a list of the controls associated with the supplied type, may be empty
     */
    fun asListByType(controlType: ControlType): List<ControlIfc> {
        val list = mutableListOf<ControlIfc>()
        for ((_, control) in myControls) {
            if (control.type == controlType) {
                list.add(control)
            }
        }
        return list
    }

    /**
     * Gets a control of the supplied key name or null
     *
     * @param controlKey the key for the control, must not be null
     */
    fun control(controlKey: String): ControlIfc? {
        return myControls[controlKey]
    }

    /**
     * @return the set of possible control types held
     */
    fun controlTypes(): Set<ControlType> {
        val set = mutableSetOf<ControlType>()
        for ((_, value) in myControls) {
            set.add(value.type)
        }
        return set
    }

    /**
     * Generate a "flat" map (String, Double) for communication
     * outside this class. The key is the control key and the
     * number is the last double value assigned to the control.
     * Any controls that cannot be translated to Double are ignored.
     *
     * @return the map
     */
    fun asMap(): Map<String, Double> {
        val map: MutableMap<String, Double> = LinkedHashMap()
        for ((key, c) in myControls) {
            map[key] = c.value
        }
        return map
    }

    /**
     * Sets all the contained control values using the supplied flat map
     *
     * @param controlMap a flat map of control keys and values, must not be null
     * @return the number of control (key, value) pairs that were successfully set
     */
    fun setControlsFromMap(controlMap: Map<String, Double>): Int {
        var j = 0
        for ((k, v) in controlMap.entries) {
            if (myControls.containsKey(k)) {
                val c = myControls[k]
                c!!.value = v
                j++
            } else {
                logger.warn { "The key $k was not found when trying to set control values for supplied flat map" }
            }
        }
        return j
    }

    /**
     *
     * @param json a valid json string representing a Map&lt;String, Double&gt;
     * that contains the control keys and double values for the controls
     * @return the number of control (key, value) pairs that were successfully set
     */
    fun setControlsFromJson(json: String): Int {
        return setControlsFromMap(KSLMaps.stringDoubleMapFromJson(json))
    }

    /**
     *  A JSON representation of the map of pairs (keyName, value) for the
     *  controls
     */
    fun controlsAsJsonString() : String {
        return asMap().toJson()
    }

    /**
     * Return an ArrayList of ControlData providing
     * additional detail on Controls (but without giving
     * direct access to the control)
     *
     * @return an ArrayList of ControlData
     */
    fun controlRecords(): ArrayList<ControlData> {
        val list = ArrayList<ControlData>()
        for ((_, control) in myControls) {
            with(control){
                val cd = ControlData(keyName, value, lowerBound, upperBound, comment, type, elementType, elementName, modelName )
                list.add(cd)
            }
        }
        return list
    }

    /**
     * @return the array list of controlRecords() as a string
     */
    fun controlRecordsAsString(): String {
        val str = StringBuilder()
        val list = controlRecords()
        if (list.size == 0) str.append("{empty}")
        for (cdr in list) {
            str.appendLine(cdr)
        }
        return str.toString()
    }

    override fun toString(): String {
        return controlRecordsAsString()
    }

    companion object : KLoggable {
        /**
         * A global logger for logging of model elements
         */
        override val logger = logger()

        fun <T> controlAnnotation(setter: KMutableProperty.Setter<T>): KSLControl? {
            return setter.annotations.filterIsInstance<KSLControl>().firstOrNull()
        }

        fun <T> hasControlAnnotation(setter: KMutableProperty.Setter<T>): Boolean {
            return setter.annotations.filterIsInstance<KSLControl>().isNotEmpty()
        }

    }
}