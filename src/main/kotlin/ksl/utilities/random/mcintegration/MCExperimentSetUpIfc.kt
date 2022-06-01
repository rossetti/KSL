package ksl.utilities.random.mcintegration

interface MCExperimentSetUpIfc {
    /**
     * the desired confidence level
     */
    var confidenceLevel: Double

    /**
     * the initial sample size for pilot simulation
     */
    var initialSampleSize: Int

    /**
     * the maximum number of samples permitted
     */
    var maxSampleSize: Long

    /**
     * the desired half-width bound for the experiment
     */
    var desiredHWErrorBound: Double

    /**
     * determines whether the reset stream option is on (true) or off (false)
     */
    var resetStreamOption : Boolean

    /**
     *  the number of micro replications to perform
     */
    var microRepSampleSize: Int
}