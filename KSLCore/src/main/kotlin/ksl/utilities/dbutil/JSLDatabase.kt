package ksl.utilities.dbutil

import org.ktorm.logging.Slf4jLoggerAdapter
import java.time.Instant

class JSLDatabase(private val db: Database) {

    val kDb = org.ktorm.database.Database.connect(db.dataSource, logger = Slf4jLoggerAdapter(DatabaseIfc.logger))

    interface SimulationRun{
        var id: Int
        var simName: String
        var expName: String
        var expStartTimeStamp: Instant
        var expEndTimeStamp: Instant
        var numReps: Int
        var lastRep: Int
        var lengthOfRep: Double
        var lengthOfWarmUp: Double
        var hasMoreReps: Boolean
        var repAllowedExecTime: Long
        var repInitOption: Boolean
        var repResetStartStreamOption: Boolean
        var antitheticOption: Boolean
        var advNextSubStreamOption : Boolean
        var numStreamAdvances: Int
    }

    interface DbModelElement{
        var simRunIDFk : Int
        var elementId : Int
        var elementName: String
        var elementClassName: String
        var parentIDFk : Int
        var parentName : String
        var leftCount: Int
        var rightCount: Int
    }

    interface WithinRepStat {
        var id: Int
        var elementIdFk : Int
        var simRunIdFk : Int
        var repNum: Int
        var statName: String
        var statCount: Double
        var average: Double
        var minimum : Double
        var maximum: Double
        var weightedSum: Double
        var sumOfWeights: Double
        var weightedSSQ :Double
        var lastValue : Double
        var lastWeight: Double
    }

    interface AcrossRepStat {
        var id: Int
        var elementIdFk : Int
        var simRunIdFk : Int
        var statName: String
        var statCount: Double
        var average: Double
        var stdDev: Double
        var stdError : Double
        var halfWidth: Double
        var confLevel : Double
        var minimum : Double
        var maximum: Double
        var sumOfObs : Double
        var devSSQ: Double
        var lastValue : Double
        var kurtosis : Double
        var skewness: Double
        var lag1Cov : Double
        var lag1Corr: Double
        var vonNeumanLag1Stat: Double
        var numMissingObs: Double
    }

    interface WithinRepCounterStat {
        var id: Int
        var elementIdFk : Int
        var simRunIdFk : Int
        var repNum: Int
        var statName: String
        var lastValue : Double
    }

    interface BatchStat {
        var id: Int
        var elementIdFk : Int
        var simRunIdFk : Int
        var repNum: Int
        var statName: String
        var statCount: Double
        var average: Double
        var stdDev: Double
        var stdError : Double
        var halfWidth: Double
        var confLevel : Double
        var minimum : Double
        var maximum: Double
        var sumOfObs : Double
        var devSSQ: Double
        var lastValue : Double
        var kurtosis : Double
        var skewness: Double
        var lag1Cov : Double
        var lag1Corr: Double
        var vonNeumanLag1Stat: Double
        var numMissingObs: Double
        var minBatchSize: Double
        var minNumBatches: Double
        var numRebatches: Double
        var currentBatchSize: Double
        var amtUnbatched: Double
        var totalNumObs : Double
    }
}

