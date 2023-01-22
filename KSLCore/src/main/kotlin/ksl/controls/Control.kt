package ksl.controls

import ksl.simulation.ModelElement
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rvariable.toDouble
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.cast

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
        if (type == ControlType.DOUBLE){
            property.setter.call(modelElement, x)
        } else if (type == ControlType.INTEGER){
            property.setter.call(modelElement, KSLMath.toIntValue(x))
        } else if (type == ControlType.LONG){
            property.setter.call(modelElement, KSLMath.toLongValue(x))
        } else if (type == ControlType.FLOAT){
            property.setter.call(modelElement, KSLMath.toFloatValue(x))
        } else if (type == ControlType.SHORT){
            property.setter.call(modelElement, KSLMath.toShortValue(x))
        } else if (type == ControlType.BYTE){
            property.setter.call(modelElement, KSLMath.toByteValue(x))
        } else if (type == ControlType.BOOLEAN){
            property.setter.call(modelElement, KSLMath.toBooleanValue(x))
        }
    }
}