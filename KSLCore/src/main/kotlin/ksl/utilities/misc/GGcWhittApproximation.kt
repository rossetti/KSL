package ksl.utilities.misc

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

fun main() {
    val g = GGcWhittApproximation(6.0, 3.0)
    println(g)
}

/**
 *  Computes steady state queue performance for a G/G/c queue using
 *  Ward Whitt's G/G/c approximation.
 *  @param mtba mean time between arrivals
 *  @param meanST mean service time
 *  @param servers number of servers
 *  @param arrivalSqCv squared coefficient of variation for the arrivals
 *  @param stSqCv squared coefficient of variation for the service process
 */
class GGcWhittApproximation(
    val mtba: Double,
    val meanST: Double,
    val servers: Int = 1,
    val arrivalSqCv: Double = 1.0,
    val stSqCv: Double = 1.0,
) {

    val rho: Double

    init {
        require(mtba > 0.0) { "The mean time between arrivals must be > 0" }
        require(meanST > 0.0) { "The mean service time must be > 0" }
        require(servers > 0) { "The number of servers must be > 0" }
        require(arrivalSqCv >= 0.0) { "The squared coefficient of variation for the arrivals must be >= 0" }
        require(stSqCv >= 0.0) { "The squared coefficient of variation for the service times must be >= 0" }
        val r = meanST / (mtba * servers)
        require((0.0 < r) && (r < 1.0)) { "Utilization must be (0,1)" }
        rho = r
    }

    val waitInQ: Double
        get() {
            val ewqMMm = wqMMC(rho, 1.0, servers)
            val scv: Double = (arrivalSqCv + stSqCv) / 2.0
            val phi: Double = phi(rho, arrivalSqCv, stSqCv, servers)
            val wq = phi * scv * ewqMMm
            return (meanST * wq)
        }

    val numInQ: Double
        get() = waitInQ / mtba

    val numInSys: Double
        get() = systemTime / mtba

    val systemTime: Double
        get() = waitInQ + meanST

    val numInService: Double
        get() = meanST / mtba


    private fun phi(rho: Double, scva: Double, scvs: Double, m: Int): Double {
        val phi: Double
        val scv = (scva + scvs) / 2.0
        if (scva == scvs) { // this case should reduce directly to psi
            phi = psi(scva, m, rho)
        } else if (scva > scvs) {
            val d = 4.0 * scva - 3.0 * scvs
            val a = (4.0 * (scva - scvs)) / d
            val b = scvs / d
            phi = a * phiOne(m, rho) + b * psi(scv, m, rho)
        } else { // scva < scvs
            if (scva == 0.0) // handle special case where equation 2.25 doesn't reduce
                phi = phiThree(m, rho)
            else {
                val d = 2.0 * (scva + scvs)
                val a = (scvs - scva) / d
                val b = (scvs + 3.0 * scva) / d
                phi = a * phiThree(m, rho) + b * psi(scv, m, rho)
            }
        }
        return (phi)
    }

    private fun phiOne(m: Int, rho: Double): Double {
        val phiOne: Double = 1.0 + gamma(m, rho)
        return (phiOne)
    }

    private fun phiTwo(m: Int, rho: Double): Double {
        val phiTwo: Double = 1.0 - 4.0 * gamma(m, rho)
        return (phiTwo)
    }

    private fun phiThree(m: Int, rho: Double): Double {
        val x = exp(-2.0 * (1.0 - rho) / (3.0 * rho))
        val phiThree = phiTwo(m, rho) * x
        return (phiThree)
    }

    private fun phiFour(m: Int, rho: Double): Double {
        val x = (phiOne(m, rho) + phiThree(m, rho)) / 2.0
        val phiFour = min(1.0, x)
        return (phiFour)
    }

    private fun psi(scv: Double, m: Int, rho: Double): Double {
        val psi: Double
        if (scv >= 1.0) psi = 1.0
        else {
            val x = 2.0 * (1.0 - scv)
            psi = phiFour(m, rho).pow(x)
        }
        return (psi)
    }

    private fun gamma(m: Int, rho: Double): Double {
        val x = (1.0 - rho) * (m - 1.0) * (sqrt((4.0 + 5.0 * m).toDouble()) - 2.0) / (16.0 * m * rho)
        val gamma = min(0.24, x)
        return (gamma)
    }

    override fun toString(): String {
        return StringBuilder().apply {
            appendLine("GGcWhittApproximation")
            appendLine("rho = $rho")
            appendLine("mtba = $mtba")
            appendLine("arrivalSqCv = $arrivalSqCv")
            appendLine("meanST = $meanST")
            appendLine("stSqCv = $stSqCv")
            appendLine("servers = $servers")
            appendLine("waitInQ = $waitInQ")
            appendLine("numInQ = $numInQ")
            appendLine("numInSys = $numInSys")
            appendLine("systemTime = $systemTime")
            appendLine("numInService = $numInService")
        }.toString()
    }

    companion object {

        /**
         *  Computes the steady state expected waiting time in queue for an M/M/c queue.
         *  @param rho the utilization
         *  @param mu the service rate
         *  @param c the number of servers
         */
        fun wqMMC(rho: Double, mu: Double, c: Int): Double {
            require((0.0 < rho) && (rho < 1.0)) { "Utilization must be (0,1)" }
            require(mu > 0.0) { "Service rate must be > 0.0" }
            require(c > 0) { "Number of servers must be > 0" }
            val a = c * rho
            val csa = erlangC(c, a)
            val cmwq = 1.0 / ((1.0 - rho) * c * mu) // conditional mean wait in queue
            return (csa * cmwq)
        }

        /**
         *  Computes the steady state expected number in queue for an M/M/c queue.
         *  @param rho the utilization
         *  @param mu the service rate
         *  @param c the number of servers
         */
        fun lqMMC(rho: Double, mu: Double, c: Int): Double {
            require((0.0 < rho) && (rho < 1.0)) { "Utilization must be (0,1)" }
            require(mu > 0.0) { "Service rate must be > 0.0" }
            require(c > 0) { "Number of servers must be > 0" }
            val wq = wqMMC(rho, mu, c)
            val lambda = rho * c * mu
            return (lambda * wq)
        }

        /**
         *  Computes the steady state probability of blocking for the M/M/c/c model. Customers
         *  arriving when all c = [numServers] are busy will be blocked (lost).
         *  The offered load [offeredLoad] is the arrival rate divided by the service rate.
         */
        fun erlangB(numServers: Int, offeredLoad: Double): Double {
            require(offeredLoad > 0.0) { "The offered load must be > 0.0" }
            require(numServers > 0) { "The number of servers must be > 0" }
            var b = 1.0
            var d: Double
            var t: Double
            for (i in 1..numServers) {
                t = offeredLoad * b
                d = i + t
                b = t / d
            }
            return (b)
        }

        /**
         *  Computes the steady state probability of delay for an arriving customer for
         *  an M/M/c queue, where c = [numServers] the number of servers given the offered load [offeredLoad].
         *  The offered load [offeredLoad] is the arrival rate divided by the service rate.
         */
        fun erlangC(numServers: Int, offeredLoad: Double): Double {
            require(offeredLoad > 0.0) { "The offered load must be > 0.0" }
            require(numServers > 0) { "The number servers must be > 0" }
            val b = erlangB(numServers, offeredLoad)
            val t = numServers * b
            val d = numServers - offeredLoad * (1.0 - b)
            return (t / d)
        }
    }
}