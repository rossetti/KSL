package ksl.controls

import ksl.simulation.ModelElement
import ksl.utilities.math.KSLMath
import kotlin.reflect.KMutableProperty

internal class Control(
    private val modelElement: ModelElement,
    private val property: KMutableProperty<*>,
    private val kslControl: KSLControl
) : ControlIfc {
    override val type: ControlType
        get() = kslControl.controlType
    override val lowerBound: Double
        get() = kslControl.lowerBound
    override val upperBound: Double
        get() = kslControl.upperBound
    override val elementName: String
        get() = modelElement.name
    override val propertyName: String
        get() = property.name
    override val comment: String
        get() = kslControl.comment
    override val keyName: String
        get() {
            val en: String = elementName.replace(".", "\\.")
            val sn = propertyName.replace(".", "\\.")
            return "$en.$sn"
        }
    override val modelName: String
        get() = modelElement.model.name

    override var value: Double
        get() = getDoubleFromProperty()
        set(value) {
            setPropertyFromDouble(value)
            Controls.logger.trace{"Control: $keyName of type ${type.name} was assigned value = $value"}
        }

    private fun getDoubleFromProperty(): Double{
       return ControlType.coerceToDouble(property.getter.call(modelElement))
    }

    private fun setPropertyFromDouble(value: Double){
        val x = limitToRange(value)
        // x is now in valid range for the control type
        when (type) {
            ControlType.DOUBLE -> {
                property.setter.call(modelElement, x)
            }
            ControlType.INTEGER -> {
                property.setter.call(modelElement, KSLMath.toIntValue(x))
            }
            ControlType.LONG -> {
                property.setter.call(modelElement, KSLMath.toLongValue(x))
            }
            ControlType.FLOAT -> {
                property.setter.call(modelElement, KSLMath.toFloatValue(x))
            }
            ControlType.SHORT -> {
                property.setter.call(modelElement, KSLMath.toShortValue(x))
            }
            ControlType.BYTE -> {
                property.setter.call(modelElement, KSLMath.toByteValue(x))
            }
            ControlType.BOOLEAN -> {
                property.setter.call(modelElement, KSLMath.toBooleanValue(x))
            }
        }
    }

    override fun toString(): String {
        val str = StringBuilder()
        str.append("[key = ").append(keyName)
        str.append(", control type = ").append(type)
        str.append(", value = ").append(value)
        str.append(", lower bound = ").append(lowerBound)
        str.append(", upper bound = ").append(upperBound)
        str.append(", comment = ").append(if (comment == "") "\"\"" else comment)
        str.append(", element name = ").append(elementName)
        str.append(", model name = ").append(modelName)
        str.append("]")
        return str.toString()
    }
}