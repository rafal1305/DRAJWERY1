package pl.example.trasa

import kotlin.math.*

object Geo {
    /** Przybliżenie równoodległościowe – wystarczające na odległości rzędu km. */
    private fun xy(p: LatLng, lat0: Double) = doubleArrayOf(p.lng * cos(Math.toRadians(lat0)) * 111_320.0, p.lat * 110_540.0)
    fun meters(a: LatLng, b: LatLng): Double {
        val u = xy(a, a.lat); val v = xy(b, a.lat); return hypot(u[0] - v[0], u[1] - v[1])
    }
    fun distToPolyline(p: LatLng, line: List<LatLng>): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        if (line.size == 1) return meters(p, line[0])
        val q = xy(p, p.lat)
        return (0 until line.size - 1).minOf { i ->
            val a = xy(line[i], p.lat); val b = xy(line[i + 1], p.lat)
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((q[0] - a[0]) * dx + (q[1] - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
            hypot(q[0] - (a[0] + t * dx), q[1] - (a[1] + t * dy))
        }
    }
}

enum class NavEvent { OK, ARRIVED, OFF_ROUTE }

object NavLogic {
    const val ARRIVE_M = 40.0   // tyle metrów od przystanku = dotarcie
    /** Próg zboczenia rośnie z niedokładnością GPS i prędkością (autostrada ≠ miasto). */
    fun offThreshold(accuracyM: Double, speedMps: Double) = maxOf(40.0, accuracyM * 1.5, 25.0 + speedMps * 2.5)
    fun check(p: LatLng, target: LatLng, leg: Leg?, accuracyM: Double = 0.0, speedMps: Double = 0.0): NavEvent = when {
        Geo.meters(p, target) < maxOf(ARRIVE_M, accuracyM) -> NavEvent.ARRIVED
        leg != null && Geo.distToPolyline(p, leg.points) > offThreshold(accuracyM, speedMps) -> NavEvent.OFF_ROUTE
        else -> NavEvent.OK
    }
}

object Guidance {
    private fun String.cap() = replaceFirstChar { it.uppercase() }

    /** Czynność małymi literami (np. "skręć w prawo, Lipowa"); pusty tekst = nie ogłaszaj. */
    fun action(s: Step, ph: Phrases): String {
        s.raw?.let { return it }
        val street = if (!s.name.isNullOrBlank() && ph.street.isNotBlank()) ph.street.replace("{ulica}", s.name) else ""
        val base = when (s.type) {
            "depart", "exit roundabout", "exit rotary" -> return ""
            "arrive" -> ph.arrive
            "merge" -> ph.merge
            "on ramp" -> ph.onRamp
            "off ramp" -> ph.offRamp
            "fork" -> if (s.modifier?.contains("left") == true) ph.forkLeft else ph.forkRight
            "roundabout", "rotary", "roundabout turn" -> ph.roundabout.replace("{n}", (s.exit ?: 1).toString())
            else -> when (s.modifier) {
                "left" -> ph.left; "right" -> ph.right; "slight left" -> ph.slightLeft; "slight right" -> ph.slightRight
                "sharp left" -> ph.sharpLeft; "sharp right" -> ph.sharpRight; "uturn" -> ph.uturn; else -> ph.straight
            }
        }
        return base + street
    }
    /** Komunikat tuż przed manewrem. */
    fun near(s: Step, ph: Phrases): String = action(s, ph).let { if (it.isEmpty()) "" else ph.near.replace("{akcja}", it).cap() }
    /** Komunikat z wyprzedzeniem, np. "Za 300 metrów, skręć w lewo". */
    fun far(s: Step, ph: Phrases, meters: Int): String =
        action(s, ph).let { if (it.isEmpty()) "" else ph.far.replace("{m}", meters.toString()).replace("{akcja}", it).cap() }
}

/** Śledzi kolejne manewry na odcinku i zwraca komunikaty do powiedzenia (z wyprzedzeniem zależnym od prędkości). */
class GuidanceState(val steps: List<Step>, private val phrases: () -> Phrases = { Phrases() }) {
    var idx = 0
    private val said = HashSet<Int>()

    private fun skipEmpty() { while (idx < steps.size && Guidance.action(steps[idx], phrases()).isEmpty()) idx++ }

    fun update(p: LatLng, speedMps: Double): List<String> {
        val out = ArrayList<String>()
        skipEmpty()
        if (idx >= steps.size) return out
        val s = steps[idx]; val d = Geo.meters(p, s.at); val ph = phrases(); val v = maxOf(0.0, speedMps)
        if (d < 25.0) { idx++; return out }                       // manewr wykonany
        val far = maxOf(300.0, v * 20); val near = maxOf(80.0, v * 5)
        if (d < near) { said.add(idx * 2); if (said.add(idx * 2 + 1)) out.add(Guidance.near(s, ph)) }
        else if (d < far && said.add(idx * 2)) out.add(Guidance.far(s, ph, ((d / 50).roundToInt() * 50).coerceAtLeast(50)))
        return out
    }

    /** Opis następnego manewru do wyświetlenia, np. "za 240 m: Skręć w prawo". */
    fun describe(p: LatLng): String {
        skipEmpty()
        val s = steps.getOrNull(idx) ?: return ""
        return "za ${((Geo.meters(p, s.at) / 10).roundToInt() * 10)} m: ${Guidance.near(s, phrases()).ifEmpty { Guidance.action(s, phrases()) }}"
    }
}
