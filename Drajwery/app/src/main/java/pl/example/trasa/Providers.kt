package pl.example.trasa

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Warstwa danych. Reszta aplikacji nie wie, skąd pochodzą dane. */
interface RoutingProvider {
    /** Do [limit] kandydatów dla adresu (najlepszy pierwszy). */
    suspend fun geocode(address: String, limit: Int = 1): List<GeoResult>
    suspend fun reverse(at: LatLng): String?
    /** Odległości i czasy między wszystkimi punktami. Bez limitu liczby punktów. */
    suspend fun matrix(points: List<LatLng>, o: RouteOptions): Matrices
    /** Trasa z geometrią, czasem i wskazówkami. alternative = wariant objazdowy. */
    suspend fun route(from: LatLng, to: LatLng, o: RouteOptions, alternative: Boolean = false): Leg
    /** Geometria trasy przez wszystkie punkty po kolei (do mapy). */
    suspend fun path(points: List<LatLng>, o: RouteOptions): List<LatLng>
    /** Ostrzeżenie (np. brak obsługi wykluczeń) – jednorazowe. */
    fun takeWarning(): String? = null
}

/**
 * Darmowe źródła OpenStreetMap: geokodowanie Nominatim (1 zapytanie/s, wymagany User-Agent, cache)
 * i OSRM (/table, /route). Publiczne serwery są do testów i lekkiego użytku;
 * dla dużych list adresów uruchom własne instancje (adresy w konstruktorze).
 */
class OsmProvider(
    private val userAgent: String = "DRAJWERY/1.0 (zmien-na-swoj-email@example.com)",
    private val nominatim: String = "https://nominatim.openstreetmap.org",
    private val osrm: String = "https://router.project-osrm.org",
) : RoutingProvider {

    private val geoCache = HashMap<String, List<GeoResult>>()
    private val distCache = HashMap<Pair<LatLng, LatLng>, Pair<Double, Double>>()
    private var cacheKey = ""
    private val gate = Mutex()
    private var lastCall = 0L
    private var warning: String? = null

    override fun takeWarning(): String? = warning.also { warning = null }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", userAgent)
        c.connectTimeout = 10_000; c.readTimeout = 60_000
        if (c.responseCode != 200) error("Serwer map zwrócił błąd HTTP ${c.responseCode}")
        c.inputStream.bufferedReader().use { it.readText() }
    }

    private suspend fun politeGet(url: String): String {
        val wait = 1100 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        return get(url).also { lastCall = System.currentTimeMillis() }
    }

    override suspend fun geocode(address: String, limit: Int): List<GeoResult> = gate.withLock {
        val key = address.trim().lowercase()
        geoCache[key]?.let { return@withLock it.take(limit) }
        val q = URLEncoder.encode(address.trim(), "UTF-8")
        val arr = JSONArray(politeGet("$nominatim/search?q=$q&format=jsonv2&limit=5&countrycodes=pl"))
        val list = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            GeoResult(o.optString("display_name", address), LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }
        geoCache[key] = list; list.take(limit)
    }

    override suspend fun reverse(at: LatLng): String? = gate.withLock {
        try { JSONObject(politeGet("$nominatim/reverse?lat=${at.lat}&lon=${at.lng}&format=jsonv2")).optString("display_name").ifEmpty { null } }
        catch (e: Exception) { null }
    }

    private fun exclude(o: RouteOptions) = listOfNotNull(
        if (o.avoidMotorways) "motorway" else null, if (o.avoidTolls) "toll" else null, if (o.avoidFerries) "ferry" else null
    ).joinToString(",")

    /** Zapytanie OSRM z wykluczeniami; gdy serwer ich nie obsługuje – ponowienie bez nich i ostrzeżenie. */
    private suspend fun osrmGet(path: String, query: String, o: RouteOptions): String {
        val ex = exclude(o).let { if (it.isEmpty()) "" else "&exclude=$it" }
        if (o.vehicle != Vehicle.CAR)
            warning = "Publiczny OSRM liczy tylko dla samochodu osobowego (ciężarówka, dostawczy, rower: TomTom lub własny OSRM)."
        return try { get("$osrm$path?$query$ex") } catch (e: Exception) {
            if (ex.isEmpty()) throw e
            warning = "Serwer OSRM nie obsługuje wykluczeń (autostrady/opłaty/promy) – trasa policzona bez nich."
            get("$osrm$path?$query")
        }
    }

    /** Do 100 punktów jedno zapytanie; więcej – bloki 50×50 (limit serwera OSRM), z pamięcią podręczną par. */
    override suspend fun matrix(points: List<LatLng>, o: RouteOptions): Matrices {
        val n = points.size
        val d = Array(n) { DoubleArray(n) }; val t = Array(n) { DoubleArray(n) }
        if (n == 0) return Matrices(d, t)
        val key = exclude(o); if (key != cacheKey) { distCache.clear(); cacheKey = key }
        val blocks = (0 until n).chunked(if (n <= MAX_TABLE) n else BLOCK)
        for (bi in blocks) for (bj in blocks) {
            if (!fillFromCache(points, bi, bj, d, t)) { fetchBlock(points, bi, bj, d, t, o); if (blocks.size > 1) delay(150) }
        }
        return Matrices(d, t)
    }

    private fun fillFromCache(p: List<LatLng>, ri: List<Int>, ci: List<Int>, d: Array<DoubleArray>, t: Array<DoubleArray>): Boolean {
        for (i in ri) for (j in ci) if (i != j && !distCache.containsKey(p[i] to p[j])) return false
        for (i in ri) for (j in ci) if (i != j) { val v = distCache.getValue(p[i] to p[j]); d[i][j] = v.first; t[i][j] = v.second }
        return true
    }

    private suspend fun fetchBlock(p: List<LatLng>, ri: List<Int>, ci: List<Int>,
                                   d: Array<DoubleArray>, t: Array<DoubleArray>, o: RouteOptions) {
        val all = (ri + ci).distinct()
        val pos = all.withIndex().associate { (k, g) -> g to k }
        val coords = all.joinToString(";") { "${p[it].lng},${p[it].lat}" } // OSRM: długość,szerokość
        val src = ri.joinToString(";") { pos.getValue(it).toString() }
        val dst = ci.joinToString(";") { pos.getValue(it).toString() }
        val j = JSONObject(osrmGet("/table/v1/driving/$coords", "annotations=distance,duration&sources=$src&destinations=$dst", o))
        if (j.optString("code") != "Ok") error("OSRM: ${j.optString("code")} ${j.optString("message")}")
        val dd = parseDistances(j.getJSONArray("distances")); val tt = parseDistances(j.getJSONArray("durations"))
        for ((r, i) in ri.withIndex()) for ((c, k) in ci.withIndex()) {
            if (i == k) continue
            d[i][k] = dd[r][c]; t[i][k] = tt[r][c]; distCache[p[i] to p[k]] = dd[r][c] to tt[r][c]
        }
    }

    override suspend fun route(from: LatLng, to: LatLng, o: RouteOptions, alternative: Boolean): Leg {
        val q = "overview=full&geometries=geojson&steps=true&alternatives=$alternative"
        val body = osrmGet("/route/v1/driving/${from.lng},${from.lat};${to.lng},${to.lat}", q, o)
        return parseOsrmRoute(JSONObject(body), alternative)
    }

    override suspend fun path(points: List<LatLng>, o: RouteOptions): List<LatLng> {
        if (points.size < 2) return points
        val out = ArrayList<LatLng>(); var i = 0
        while (i < points.size - 1) {
            val chunk = points.subList(i, minOf(i + 90, points.size))
            val coords = chunk.joinToString(";") { "${it.lng},${it.lat}" }
            val j = JSONObject(osrmGet("/route/v1/driving/$coords", "overview=full&geometries=geojson", o))
            if (j.optString("code") != "Ok") error("OSRM: ${j.optString("code")}")
            out += geometry(j.getJSONArray("routes").getJSONObject(0))
            i += chunk.size - 1
        }
        return out
    }

    companion object {
        const val MAX_TABLE = 100
        const val BLOCK = 50

        private fun geometry(r: JSONObject): List<LatLng> {
            val c = r.getJSONObject("geometry").getJSONArray("coordinates") // [lon, lat]
            return List(c.length()) { LatLng(c.getJSONArray(it).getDouble(1), c.getJSONArray(it).getDouble(0)) }
        }

        fun parseOsrmRoute(j: JSONObject, alternative: Boolean): Leg {
            if (j.optString("code") != "Ok") error("OSRM: ${j.optString("code")}")
            val rs = j.getJSONArray("routes")
            val r = rs.getJSONObject(if (alternative && rs.length() > 1) 1 else 0)
            val steps = ArrayList<Step>()
            val legs = r.optJSONArray("legs")
            if (legs != null) for (li in 0 until legs.length()) {
                val st = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until st.length()) {
                    val s = st.getJSONObject(si); val man = s.getJSONObject("maneuver"); val loc = man.getJSONArray("location")
                    val exit = if (man.has("exit")) man.optInt("exit") else null
                    steps += Step(LatLng(loc.getDouble(1), loc.getDouble(0)), man.optString("type"),
                        man.optString("modifier").ifEmpty { null }, s.optString("name").ifEmpty { null }, exit, s.optDouble("distance", 0.0))
                }
            }
            return Leg(geometry(r), r.getDouble("distance"), r.getDouble("duration"), steps)
        }

        /** Brak połączenia drogowego (null w odpowiedzi) = bardzo duża wartość. */
        fun parseDistances(a: JSONArray): Array<DoubleArray> = Array(a.length()) { i ->
            val row = a.getJSONArray(i)
            DoubleArray(row.length()) { k -> if (row.isNull(k)) 1e12 else row.getDouble(k) }
        }
    }
}

/** Trasa z ruchem drogowym na żywo i typem pojazdu (TomTom, wymaga klucza); reszta z OSM. */
class TomTomProvider(private val key: String, private val base: OsmProvider) : RoutingProvider by base {
    override suspend fun route(from: LatLng, to: LatLng, o: RouteOptions, alternative: Boolean): Leg {
        val mode = when (o.vehicle) { Vehicle.CAR -> "car"; Vehicle.VAN -> "van"; Vehicle.TRUCK -> "truck"; Vehicle.BIKE -> "bicycle" }
        val avoid = listOfNotNull(if (o.avoidMotorways) "motorways" else null, if (o.avoidTolls) "tollRoads" else null,
            if (o.avoidFerries) "ferries" else null).joinToString("") { "&avoid=$it" }
        val url = "https://api.tomtom.com/routing/1/calculateRoute/${from.lat},${from.lng}:${to.lat},${to.lng}/json" +
            "?key=$key&traffic=true&routeRepresentation=polyline&travelMode=$mode&maxAlternatives=${if (alternative) 1 else 0}" +
            "&instructionsType=text&language=pl-PL$avoid"
        return parseTomTom(JSONObject(base.get(url)), alternative)
    }
    companion object {
        fun parseTomTom(j: JSONObject, alternative: Boolean): Leg {
            val rs = j.getJSONArray("routes")
            val r = rs.getJSONObject(if (alternative && rs.length() > 1) 1 else 0)
            val sum = r.getJSONObject("summary")
            val pts = r.getJSONArray("legs").getJSONObject(0).getJSONArray("points")
            val steps = ArrayList<Step>()
            val ins = r.optJSONObject("guidance")?.optJSONArray("instructions")
            if (ins != null) for (i in 0 until ins.length()) {
                val s = ins.getJSONObject(i); val p = s.optJSONObject("point") ?: continue
                steps += Step(LatLng(p.getDouble("latitude"), p.getDouble("longitude")), "raw", raw = s.optString("message"))
            }
            return Leg(List(pts.length()) { LatLng(pts.getJSONObject(it).getDouble("latitude"), pts.getJSONObject(it).getDouble("longitude")) },
                sum.getDouble("lengthInMeters"), sum.getDouble("travelTimeInSeconds"), steps)
        }
    }
}
