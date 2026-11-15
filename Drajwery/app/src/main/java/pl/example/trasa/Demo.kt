package pl.example.trasa

import java.util.Random

/**
 * Tryb demo / testy: działa bez internetu. Każdy adres dostaje deterministyczne współrzędne
 * w okolicy Białegostoku; odległość = linia prosta × 1,3. Używany też w testach emulatora.
 */
object DemoProvider : RoutingProvider {
    fun fake(a: String): LatLng {
        val r = Random(a.trim().lowercase().hashCode().toLong())
        return LatLng(53.13 + (r.nextDouble() - 0.5) * 0.2, 23.16 + (r.nextDouble() - 0.5) * 0.3)
    }
    override suspend fun geocode(address: String, limit: Int): List<GeoResult> =
        if (address.isBlank()) emptyList()
        else List(minOf(limit, 3)) { i -> GeoResult("${address.trim()} (demo ${i + 1})", if (i == 0) fake(address) else fake("$address#$i")) }
    override suspend fun reverse(at: LatLng): String? = "Punkt demo (%.4f, %.4f)".format(at.lat, at.lng)
    override suspend fun matrix(points: List<LatLng>, o: RouteOptions): Matrices {
        val v = if (o.avoidMotorways) 11.0 else 14.0
        val d = Array(points.size) { i -> DoubleArray(points.size) { j -> Geo.meters(points[i], points[j]) * 1.3 } }
        return Matrices(d, Array(points.size) { i -> DoubleArray(points.size) { j -> d[i][j] / v } })
    }
    override suspend fun route(from: LatLng, to: LatLng, o: RouteOptions, alternative: Boolean): Leg {
        val bulge = if (alternative) 0.01 else 0.0
        val pts = List(21) { k -> val f = k / 20.0
            LatLng(from.lat + (to.lat - from.lat) * f + bulge * Math.sin(Math.PI * f), from.lng + (to.lng - from.lng) * f) }
        val m = Geo.meters(from, to) * 1.3 * (if (alternative) 1.2 else 1.0)
        val mid = pts[10]
        return Leg(pts, m, m / 14.0, listOf(Step(from, "depart"), Step(mid, "turn", "right", "ulica Demo", meters = m / 2), Step(to, "arrive")))
    }
    override suspend fun path(points: List<LatLng>, o: RouteOptions): List<LatLng> = points
}
