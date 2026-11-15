package pl.example.trasa

import kotlin.math.roundToInt

data class TripSummary(val odoStartKm: Double?, val odoEndKm: Double?, val distanceKm: Double,
                       val drivingSec: Double, val stopSec: Double, val plannedKm: Double?)

/**
 * Liczy pokonany odcinek (z GPS), czas jazdy i czas postoju. Jazda = prędkość > 1 m/s (3,6 km/h);
 * w postoju dryf GPS nie dolicza kilometrów. Bez prędkości z GPS (speed < 0) ruch ocenia po przemieszczeniu.
 */
class TripLog(val odoStartKm: Double?, var distanceM: Double = 0.0, var drivingSec: Double = 0.0, var stopSec: Double = 0.0) {
    private var last: LatLng? = null
    private var lastT = 0L

    fun add(p: LatLng, tMs: Long, speedMps: Double, accuracyM: Double = 0.0) {
        val l = last
        if (l != null && tMs > lastT) {
            val dt = (tMs - lastT) / 1000.0
            val d = Geo.meters(l, p)
            val moving = if (speedMps >= 0) speedMps > 1.0 else d > maxOf(12.0, 2.0 * accuracyM) && d / dt > 2.0
            if (moving && d / dt < 70.0) { drivingSec += dt; distanceM += d } else stopSec += dt   // 70 m/s = skok GPS, nie jazda
        }
        last = p; lastT = tMs
    }

    fun summary(plannedKm: Double?) = TripSummary(odoStartKm, odoStartKm?.let { it + distanceM / 1000 },
        distanceM / 1000, drivingSec, stopSec, plannedKm)
}

object TripFmt {
    /** "2 h 05 min" albo "7 min". */
    fun hm(sec: Double): String {
        val m = (sec / 60).roundToInt()
        return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min" else "$m min"
    }
}

/** Odwiedzone adresy: zapamiętywane, by podpowiadać je przy wpisywaniu i pomijać geokodowanie. */
data class HistoryEntry(val label: String, val at: LatLng, val count: Int, val lastMs: Long)

object History {
    const val MAX = 300
    private fun skip(label: String) = label.isBlank() || label == RouteVM.GPS_LABEL || label.startsWith("(")

    /** Dopisuje odwiedziny (najczęstsze i najświeższe na górze); przy zbyt długiej liście usuwa najstarsze. */
    fun record(list: List<HistoryEntry>, label: String, at: LatLng, nowMs: Long): List<HistoryEntry> {
        val key = label.trim(); if (skip(key)) return list
        val cur = list.firstOrNull { it.label.equals(key, true) }
        return (list.filterNot { it.label.equals(key, true) } + HistoryEntry(cur?.label ?: key, at, (cur?.count ?: 0) + 1, nowMs))
            .sortedByDescending { it.lastMs }.take(MAX)
    }
    /** Do 4 podpowiedzi zawierających wpisany tekst (min. 2 znaki), bez dokładnych trafień. */
    fun suggest(list: List<HistoryEntry>, query: String, enabled: Boolean): List<HistoryEntry> {
        val q = query.trim().lowercase(); if (!enabled || q.length < 2) return emptyList()
        return list.filter { val l = it.label.lowercase(); l.contains(q) && l != q }
            .sortedWith(compareByDescending<HistoryEntry> { it.count }.thenByDescending { it.lastMs }).take(4)
    }
    fun find(list: List<HistoryEntry>, address: String): HistoryEntry? = list.firstOrNull { it.label.equals(address.trim(), true) }
}
