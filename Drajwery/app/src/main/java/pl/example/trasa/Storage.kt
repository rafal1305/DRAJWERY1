package pl.example.trasa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Inputs(
    val startText: String = "", val startGps: LatLng? = null,
    val endText: String = "", val endGps: LatLng? = null,
    val stops: List<String> = emptyList(),
    val returnToStart: Boolean = false, val openEnd: Boolean = false,
    val manual: Map<String, LatLng> = emptyMap(),
)
data class SavedRoute(val id: Long, val name: String, val inputs: Inputs)
/** Stan jazdy do wznowienia po zabiciu aplikacji przez system. */
data class Session(val inputs: Inputs, val points: List<String>, val raw: List<String>, val coords: List<LatLng>,
                   val order: List<Int>, val visited: List<Int>, val autoReorder: Boolean, val openEnd: Boolean,
                   val odoStartKm: Double? = null, val distanceM: Double = 0.0, val drivingSec: Double = 0.0,
                   val stopSec: Double = 0.0, val plannedKm: Double? = null)
data class Settings(val options: RouteOptions = RouteOptions(), val voiceOn: Boolean = true,
                    val serviceMin: Int = 5, val departText: String = "", val demo: Boolean = false,
                    val lastOdoKm: Double? = null, val phrases: Phrases = Phrases(),
                    val userName: String = "Kierowca", val historyOn: Boolean = true)

/** Czysta (testowalna na JVM) zamiana modeli na JSON i z powrotem. */
object Codec {
    private fun ll(p: LatLng?): Any = if (p == null) JSONObject.NULL else JSONArray().put(p.lat).put(p.lng)
    private fun ll(a: JSONArray) = LatLng(a.getDouble(0), a.getDouble(1))
    private fun llOrNull(j: JSONObject, k: String): LatLng? = j.optJSONArray(k)?.let { ll(it) }
    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
    private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else List(a.length()) { a.getInt(it) }

    fun inputs(i: Inputs) = JSONObject().put("startText", i.startText).put("startGps", ll(i.startGps))
        .put("endText", i.endText).put("endGps", ll(i.endGps)).put("stops", JSONArray(i.stops))
        .put("returnToStart", i.returnToStart).put("openEnd", i.openEnd)
        .put("manual", JSONObject().also { o -> i.manual.forEach { (k, v) -> o.put(k, ll(v)) } })
    fun inputs(j: JSONObject): Inputs {
        val man = HashMap<String, LatLng>()
        j.optJSONObject("manual")?.let { m -> m.keys().forEach { k -> man[k] = ll(m.getJSONArray(k)) } }
        return Inputs(j.optString("startText"), llOrNull(j, "startGps"), j.optString("endText"), llOrNull(j, "endGps"),
            strs(j.optJSONArray("stops")), j.optBoolean("returnToStart"), j.optBoolean("openEnd"), man)
    }

    fun routes(l: List<SavedRoute>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("inputs", inputs(it.inputs))) } }
    fun routes(a: JSONArray) = List(a.length()) { val o = a.getJSONObject(it); SavedRoute(o.getLong("id"), o.getString("name"), inputs(o.getJSONObject("inputs"))) }

    fun session(s: Session) = JSONObject().put("inputs", inputs(s.inputs)).put("points", JSONArray(s.points)).put("raw", JSONArray(s.raw))
        .put("coords", JSONArray().also { a -> s.coords.forEach { a.put(ll(it)) } }).put("order", JSONArray(s.order))
        .put("visited", JSONArray(s.visited)).put("autoReorder", s.autoReorder).put("openEnd", s.openEnd)
        .put("odoStartKm", s.odoStartKm ?: JSONObject.NULL).put("distanceM", s.distanceM).put("drivingSec", s.drivingSec)
        .put("stopSec", s.stopSec).put("plannedKm", s.plannedKm ?: JSONObject.NULL)
    fun session(j: JSONObject): Session {
        val c = j.getJSONArray("coords")
        return Session(inputs(j.getJSONObject("inputs")), strs(j.getJSONArray("points")), strs(j.getJSONArray("raw")),
            List(c.length()) { ll(c.getJSONArray(it)) }, ints(j.getJSONArray("order")), ints(j.getJSONArray("visited")),
            j.optBoolean("autoReorder", true), j.optBoolean("openEnd"),
            if (j.isNull("odoStartKm")) null else j.optDouble("odoStartKm"), j.optDouble("distanceM", 0.0),
            j.optDouble("drivingSec", 0.0), j.optDouble("stopSec", 0.0), if (j.isNull("plannedKm")) null else j.optDouble("plannedKm"))
    }

    fun settings(s: Settings) = JSONObject().put("criterion", s.options.criterion.name).put("avoidMotorways", s.options.avoidMotorways)
        .put("avoidTolls", s.options.avoidTolls).put("avoidFerries", s.options.avoidFerries).put("vehicle", s.options.vehicle.name)
        .put("voiceOn", s.voiceOn).put("serviceMin", s.serviceMin).put("departText", s.departText).put("demo", s.demo)
        .put("lastOdoKm", s.lastOdoKm ?: JSONObject.NULL).put("userName", s.userName).put("historyOn", s.historyOn)
        .put("phrases", JSONObject().also { o -> PhraseFields.all.forEach { f -> o.put(f.key, f.get(s.phrases)) } })
    fun settings(j: JSONObject) = Settings(
        RouteOptions(runCatching { Criterion.valueOf(j.optString("criterion")) }.getOrDefault(Criterion.DISTANCE),
            j.optBoolean("avoidMotorways"), j.optBoolean("avoidTolls"), j.optBoolean("avoidFerries"),
            runCatching { Vehicle.valueOf(j.optString("vehicle")) }.getOrDefault(Vehicle.CAR)),
        j.optBoolean("voiceOn", true), j.optInt("serviceMin", 5), j.optString("departText"), j.optBoolean("demo"),
        if (j.isNull("lastOdoKm")) null else j.optDouble("lastOdoKm"),
        j.optJSONObject("phrases")?.let { o -> PhraseFields.all.fold(Phrases()) { p, f -> if (o.has(f.key)) f.set(p, o.getString(f.key)) else p } } ?: Phrases(),
        j.optString("userName", "Kierowca").ifBlank { "Kierowca" }, j.optBoolean("historyOn", true))

    fun history(l: List<HistoryEntry>) = JSONArray().also { a -> l.forEach { h -> a.put(JSONObject().put("label", h.label)
        .put("at", JSONArray().put(h.at.lat).put(h.at.lng)).put("count", h.count).put("lastMs", h.lastMs)) } }
    fun history(a: JSONArray) = List(a.length()) { val o = a.getJSONObject(it); val p = o.getJSONArray("at")
        HistoryEntry(o.getString("label"), LatLng(p.getDouble(0), p.getDouble(1)), o.optInt("count", 1), o.optLong("lastMs")) }
}

/** Zapis w plikach JSON w pamięci aplikacji (bez bazy danych). */
class Storage(private val ctx: Context) {
    private fun f(n: String) = File(ctx.filesDir, n)
    private fun read(n: String): String? = try { f(n).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
    private fun write(n: String, s: String) { try { f(n).writeText(s) } catch (e: Exception) { } }

    fun saveRoutes(l: List<SavedRoute>) = write("routes.json", Codec.routes(l).toString())
    fun loadRoutes(): List<SavedRoute> = read("routes.json")?.let { runCatching { Codec.routes(JSONArray(it)) }.getOrNull() } ?: emptyList()
    fun saveInputs(i: Inputs) = write("inputs.json", Codec.inputs(i).toString())
    fun loadInputs(): Inputs? = read("inputs.json")?.let { runCatching { Codec.inputs(JSONObject(it)) }.getOrNull() }
    fun saveSession(s: Session) = write("session.json", Codec.session(s).toString())
    fun loadSession(): Session? = read("session.json")?.let { runCatching { Codec.session(JSONObject(it)) }.getOrNull() }
    fun clearSession() { try { f("session.json").delete() } catch (e: Exception) { } }
    fun saveHistory(l: List<HistoryEntry>) = write("history.json", Codec.history(l).toString())
    fun loadHistory(): List<HistoryEntry> = read("history.json")?.let { runCatching { Codec.history(JSONArray(it)) }.getOrNull() } ?: emptyList()
    fun saveSettings(s: Settings) = write("settings.json", Codec.settings(s).toString())
    fun loadSettings(): Settings? = read("settings.json")?.let { runCatching { Codec.settings(JSONObject(it)) }.getOrNull() }
}

data class CsvRow(val n: Int, val address: String, val lat: Double, val lng: Double,
                  val arrival: String, val serviceMin: Int, val deadline: String)

/** CSV w polskim stylu (średnik, żeby Excel otwierał go poprawnie). */
object Csv {
    private fun q(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    fun export(rows: List<CsvRow>): String =
        (listOf("lp;adres;szerokosc;dlugosc;przyjazd;postoj_min;najpozniej") +
            rows.map { r -> val la = "%.6f".format(java.util.Locale.ROOT, r.lat); val ln = "%.6f".format(java.util.Locale.ROOT, r.lng)
                "${r.n};${q(r.address)};$la;$ln;${r.arrival};${r.serviceMin};${r.deadline}" }
            ).joinToString("\r\n")

    private fun split(line: String, d: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var inQ = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQ && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQ = !inQ
                c == d && !inQ -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString()); return out
    }

    /** Adresy z pliku: kolumna "adres" (opcjonalnie "najpozniej", "postoj_min") albo po prostu jeden adres w linii. */
    fun parse(text: String): List<String> {
        val lines = text.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val d = if (lines.any { it.contains(';') }) ';' else if (lines.any { it.contains('\t') }) '\t' else null
        if (d == null) return lines.map { it.trim() }
        val head = split(lines[0], d).map { it.trim().lowercase() }
        val ia = head.indexOf("adres")
        val hasHeader = ia >= 0
        val col = if (hasHeader) ia else 0
        val idl = head.indexOf("najpozniej"); val isv = head.indexOf("postoj_min")
        return lines.drop(if (hasHeader) 1 else 0).mapNotNull { l ->
            val c = split(l, d); val a = c.getOrNull(col)?.trim().orEmpty()
            if (a.isEmpty()) null else buildString {
                append(a)
                if (hasHeader) {
                    c.getOrNull(idl)?.trim()?.takeIf { it.matches(Regex("""\d{1,2}:\d{2}""")) }?.let { append(" @").append(it) }
                    c.getOrNull(isv)?.trim()?.takeIf { it.matches(Regex("""\d{1,3}""")) && it != "0" }?.let { append(" ~").append(it) }
                }
            }
        }
    }
}
