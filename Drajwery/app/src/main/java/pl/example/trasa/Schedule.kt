package pl.example.trasa

import kotlin.math.roundToInt

data class Eta(val arrivalMin: Double, val late: Boolean)

object Schedule {
    /** Godziny przyjazdu do kolejnych punktów [order]; pierwszy punkt: [firstArrivalMin]; czasy przejazdu w sekundach. */
    fun arrivals(order: List<Int>, durSec: Array<DoubleArray>, service: List<Double>,
                 deadline: List<Double?>, firstArrivalMin: Double): List<Eta> {
        val res = ArrayList<Eta>(); var t = firstArrivalMin
        for ((k, g) in order.withIndex()) {
            if (k > 0) t += durSec[order[k - 1]][g] / 60.0
            val dl = deadline.getOrNull(g)
            res.add(Eta(t, dl != null && t > dl + 1e-9))
            t += service.getOrElse(g) { 0.0 }
        }
        return res
    }
    fun hhmm(min: Double): String {
        val m = ((min.roundToInt() % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }
    fun parseHhmm(s: String): Int? {
        val r = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(s) ?: return null
        val h = r.groupValues[1].toInt(); val m = r.groupValues[2].toInt()
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
}
