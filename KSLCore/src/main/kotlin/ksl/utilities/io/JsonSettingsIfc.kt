package ksl.utilities.io

/**
 *  The purpose of this interface is to provide classes (especially model elements)
 *  the ability to be configured from a Json string.
 */
interface JsonSettingsIfc<out T> {

    /**
     *  Uses the supplied JSON string to configure the schedule via CapacityScheduleData
     *
     *  @param json a valid JSON encoded string representing CapacityScheduleData
     */
    fun configureFromJson(json: String) : T

    /**
     *  Converts the configuration settings to JSON
     */
    fun settingsToJson() : String
}