package ksl.utilities.distributions.fitting

data class ShiftedData (
    val shift: Double,
    val shiftedData: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ShiftedData

        if (shift != other.shift) return false
        if (!shiftedData.contentEquals(other.shiftedData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shift.hashCode()
        result = 31 * result + shiftedData.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "shift = $shift"
    }
}