package ksl.controls

class ControlRecord(c: ControlIfc) {
    var key: String
    var value: Double
    var lowerBound: Double
    var upperBound: Double
    var comment: String
    var controlType: ControlType

    init {
        key = c.keyName
        value = c.value
        comment = c.comment
        lowerBound = c.lowerBound
        upperBound = c.upperBound
        controlType = c.type
    }

    override fun toString(): String {
        val str = StringBuilder()
        str.append("[key = ").append(key)
        str.append(", control type = ").append(controlType)
        str.append(", value = ").append(value)
        str.append(", lower bound = ").append(lowerBound)
        str.append(", upper bound = ").append(upperBound)
        str.append(", comment = ").append(if (comment == "") "\"\"" else comment)
        str.append("]")
        return str.toString()
    }
}