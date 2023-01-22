package ksl.controls

class ControlRecord(c: Control<*>) {
    var key: String
    var value: Any?
    var lowerBound: Double
    var upperBound: Double
    var comment: String
    var controlType: ControlType

    init {
        key = c.key
        value = c.controlValue
        comment = c.annotationComment
        lowerBound = c.lowerBound
        upperBound = c.upperBound
        controlType = c.annotationType
    }

    override fun toString(): String {
        val str = StringBuilder()
        str.append("[key = ").append(key)
        str.append(", control type = ").append(controlType)
        str.append(", value = ").append(if (value == null) "[null]" else value)
        str.append(", lower bound = ").append(lowerBound)
        str.append(", upper bound = ").append(upperBound)
        str.append(", comment = ").append(if (comment == "") "\"\"" else comment)
        str.append("]")
        return str.toString()
    }
}