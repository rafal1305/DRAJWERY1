package pl.example.trasa

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

fun fmt1(x: Double): String = "%.1f".format(Locale.forLanguageTag("pl-PL"), x)   // przecinek dziesiętny

class RouteVM : ViewModel() {
    private val provider: RoutingProvider get() = App.provider()

    // ---- ustawienia ----
    var options by mutableStateOf(RouteOptions())
    var voiceOn by mutableStateOf(true)
    var serviceMin by mutableStateOf(5)
    var departText by mutableStateOf("")           // HH:MM; puste = teraz
    var demo by mutableStateOf(false)
    var phrases by mutableStateOf(Phrases())        // treść komunikatów głosowych
    var lastOdoKm by mutableStateOf<Double?>(null)  // ostatni znany przebieg auta
    var userName by mutableStateOf("Kierowca")      // nazwa użytkownika (Ustawienia)
    var historyOn by mutableStateOf(true)           // zapamiętywanie odwiedzonych adresów (można wyłączyć)
    var history by mutableStateOf<List<HistoryEntry>>(emptyList())

    // ---- wejście: start/meta = GPS albo adres; przystanków bez limitu ----
    var startText by mutableStateOf(""); var startGps by mutableStateOf<LatLng?>(null)
    var endText by mutableStateOf("");   var endGps by mutableStateOf<LatLng?>(null)
    val stops = mutableStateListOf("")
    var returnToStart by mutableStateOf(false)
    var openEnd by mutableStateOf(false)           // bez mety: trasa kończy się w ostatnim przystanku
    val manual = HashMap<String, LatLng>()         // punkty wskazane na mapie
    private val choice = HashMap<String, GeoResult>()
    val resolved = mutableStateMapOf<String, String>()
    var cand by mutableStateOf<Pair<String, List<GeoResult>>?>(null)

    // ---- wynik planowania ----
    var points by mutableStateOf<List<String>>(emptyList())
    var raw by mutableStateOf<List<String>>(emptyList())
    var order by mutableStateOf<List<Int>>(emptyList())
    var mats by mutableStateOf<Matrices?>(null)
    var coords by mutableStateOf<List<LatLng>>(emptyList())
    var mapPath by mutableStateOf<List<LatLng>>(emptyList())
    private var service = listOf<Double>(); private var deadline = listOf<Double?>()
    var dummyIdx = -1; private set                  // sztuczny punkt mety przy trasie bez mety
    var loading by mutableStateOf(false); var progress by mutableStateOf("")
    var error by mutableStateOf<String?>(null); var notice by mutableStateOf<String?>(null)
    var saved by mutableStateOf<List<SavedRoute>>(emptyList())

    val totalKm: Double? get() = mats?.let { RouteOptimizer.cost(order, it.dist) / 1000 }
    private fun nowMin(): Double = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60.0 + it.get(Calendar.MINUTE) }
    private fun departMin(): Double = Schedule.parseHhmm(departText)?.toDouble() ?: nowMin()

    /** Godziny przyjazdu do kolejnych pozycji trasy (w jeździe – od bieżącego czasu i trasy do następnego przystanku). */
    val etas: List<Eta> get() {
        val m = mats ?: return emptyList()
        if (order.isEmpty()) return emptyList()
        val l = leg
        if (navActive && l != null && visited.size < order.size) {
            val pre = List(visited.size) { Eta(0.0, false) }
            return pre + Schedule.arrivals(order.drop(visited.size), m.dur, service, deadline, nowMin() + l.seconds / 60.0)
        }
        return Schedule.arrivals(order, m.dur, service, deadline, departMin())
    }

    private var planJob: Job? = null; private var lastSig = ""

    private fun specsOf(rawAll: List<String>, open: Boolean): List<StopSpec> = rawAll.mapIndexed { i, r ->
        if (i == 0 || (i == rawAll.lastIndex && !open)) StopSpec(r, null, 0) else StopText.parse(r)
    }
    private fun svcOf(specs: List<StopSpec>, open: Boolean) = specs.mapIndexed { i, s ->
        if (i == 0 || (i == specs.lastIndex && !open)) 0.0 else (s.serviceMin ?: serviceMin).toDouble()
    }
    private fun withDummy(m: Matrices): Matrices {
        fun aug(a: Array<DoubleArray>) = Array(a.size + 1) { i -> DoubleArray(a.size + 1) { j ->
            if (i < a.size && j < a.size) a[i][j] else if (j == a.size && i < a.size) 0.0 else if (i == j) 0.0 else 1e12 } }
        return Matrices(aug(m.dist), aug(m.dur))
    }
    private suspend fun resolve(addr: String): LatLng? {
        manual[addr]?.let { return it }
        choice[addr]?.let { return it.at }
        if (historyOn) History.find(history, addr)?.let { return it.at }   // znany adres: bez geokodowania
        val r = provider.geocode(addr, 1).firstOrNull() ?: return null
        resolved[addr] = r.label
        return r.at
    }

    /** Przelicza trasę z bieżących pól. Wołane po zatwierdzeniu adresu, zmianie GPS, usunięciu przystanku, zmianie ustawień. */
    fun plan(force: Boolean = false) {
        if (navActive) return
        val sig = listOf(startText.trim(), startGps, endText.trim(), endGps, stops.map { it.trim() }.filter { it.isNotEmpty() },
            returnToStart, openEnd, options, serviceMin, departText).toString()
        if (!force && sig == lastSig) return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            delay(400)
            val needEnd = !openEnd && !returnToStart
            if ((startGps == null && startText.isBlank()) || (needEnd && endGps == null && endText.isBlank())) return@launch
            error = null; loading = true
            try {
                val rawAll = ArrayList<String>()
                rawAll += if (startGps != null) GPS_LABEL else startText.trim()
                stops.map { it.trim() }.filter { it.isNotEmpty() }.forEach { rawAll += it }
                if (!openEnd) rawAll += if (returnToStart) rawAll[0] else if (endGps != null) GPS_LABEL else endText.trim()
                val open = openEnd
                val specs = specsOf(rawAll, open)
                val cs = ArrayList<LatLng>()
                specs.forEachIndexed { i, sp ->
                    progress = "Adresy: ${i + 1}/${specs.size}"
                    val fixed = when {
                        i == 0 -> startGps
                        i == specs.lastIndex && !open -> if (returnToStart) cs[0] else endGps
                        else -> null
                    }
                    cs += fixed ?: resolve(sp.address) ?: error("Nie znaleziono adresu: ${sp.address}")
                }
                progress = "Odległości drogowe…"
                var m = provider.matrix(cs, options)
                var labels: List<String> = specs.map { it.address }
                var rw: List<String> = rawAll
                var svc: List<Double> = svcOf(specs, open)
                var dl: List<Double?> = specs.map { it.deadlineMin?.toDouble() }
                var csAll: List<LatLng> = cs
                var dm = -1
                if (open) {
                    m = withDummy(m); dm = cs.size
                    labels = labels + "(koniec trasy)"; rw = rw + ""; svc = svc + 0.0; dl = dl + listOf<Double?>(null); csAll = cs + cs.last()
                }
                progress = "Układam kolejność…"
                val t0 = departMin()
                val costM = if (options.criterion == Criterion.TIME) m.dur else m.dist
                val durMin = Array(m.dur.size) { i -> DoubleArray(m.dur.size) { j -> m.dur[i][j] / 60.0 } }
                val hasTw = dl.any { it != null }
                val ord = withContext(Dispatchers.Default) {
                    (if (hasTw) RouteOptimizer.optimizeTw(durMin, svc.toDoubleArray(), DoubleArray(dl.size) { dl[it] ?: Double.MAX_VALUE }, t0) else null)
                        ?: RouteOptimizer.optimize(costM)
                }
                coords = csAll; points = labels; raw = rw; mats = m; order = ord; service = svc; deadline = dl; dummyIdx = dm
                autoReorder = true; lastSig = sig
                if (hasTw && ord.isNotEmpty()) notice = null
                provider.takeWarning()?.let { notice = it }
                persistInputs()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = e.message ?: "Błąd połączenia" }
            loading = false; progress = ""
        }
    }
    fun setStartGps(l: LatLng?) { startGps = l; if (l != null) startText = ""; plan() }
    fun setEndGps(l: LatLng?) { endGps = l; if (l != null) endText = ""; plan() }
    fun removeStop(i: Int) { stops.removeAt(i); if (stops.isEmpty()) stops.add(""); plan() }
    fun pasteList(text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        stops.removeAll { it.isBlank() }; stops.addAll(lines); stops.add(""); plan(force = true)
    }
    fun importText(text: String) {
        val lines = Csv.parse(text)
        if (lines.isEmpty()) { error = "Plik nie zawiera adresów"; return }
        pasteList(lines.joinToString("\n")); notice = "Zaimportowano ${lines.size} adresów"
    }

    // ---- wybór adresu spośród kandydatów ----
    fun showCandidates(text: String) {
        val t = StopText.parse(text).address; if (t.isEmpty()) return
        viewModelScope.launch {
            try { cand = t to provider.geocode(t, 5) } catch (e: Exception) { error = e.message }
        }
    }
    fun pickCandidate(r: GeoResult) {
        cand?.let { (t, _) -> choice[t] = r; resolved[t] = r.label }
        cand = null; plan(force = true)
    }

    // ---- punkt wskazany na mapie (długie dotknięcie) ----
    fun addStopAt(p: LatLng) {
        viewModelScope.launch {
            val label = (try { provider.reverse(p) } catch (e: Exception) { null }) ?: "Punkt (%.5f, %.5f)".format(Locale.ROOT, p.lat, p.lng)
            manual[label] = p
            if (navActive) addLive(label) else { stops.removeAll { it.isBlank() }; stops.add(label); stops.add(""); plan(force = true) }
        }
    }
    /** Geometria drogi dla mapy (przy planowaniu). */
    fun loadPath() {
        val ord = order.filter { it != dummyIdx }
        if (ord.size < 2) { mapPath = emptyList(); return }
        viewModelScope.launch {
            mapPath = try { provider.path(ord.map { coords[it] }, options) } catch (e: Exception) { ord.map { coords[it] } }
        }
    }

    // ---- zapisane trasy, podział, udostępnianie ----
    fun inputs() = Inputs(startText, startGps, endText, endGps, stops.filter { it.isNotBlank() }, returnToStart, openEnd, HashMap(manual))
    private fun applyInputs(i: Inputs) {
        startText = i.startText; startGps = i.startGps; endText = i.endText; endGps = i.endGps
        stops.clear(); stops.addAll(i.stops); stops.add(""); returnToStart = i.returnToStart; openEnd = i.openEnd
        manual.clear(); manual.putAll(i.manual); lastSig = ""
    }
    fun saveCurrent(name: String) {
        saved = saved + SavedRoute(System.currentTimeMillis(), name.ifBlank { "Trasa ${saved.size + 1}" }, inputs())
        App.storage?.saveRoutes(saved); notice = "Zapisano trasę"
    }
    fun load(r: SavedRoute) { if (navActive) return; applyInputs(r.inputs); order = emptyList(); mats = null; plan(force = true) }
    fun delete(r: SavedRoute) { saved = saved.filter { it.id != r.id }; App.storage?.saveRoutes(saved) }

    /** Dzieli przystanki na [k] tras (np. dla kilku kierowców); wyniki trafiają do zakładki „Zapisane”. */
    fun splitInto(k: Int) {
        val m = mats
        val mid = order.drop(1).dropLast(1).filter { it != dummyIdx }
        if (m == null || mid.size < 2 || k < 2) { error = "Za mało przystanków, by podzielić trasę"; return }
        val groups = Splitter.split(mid.map { coords[it] }, k)
        val base = inputs(); val t = System.currentTimeMillis()
        saved = saved + groups.mapIndexed { gi, g -> SavedRoute(t + gi, "Trasa ${gi + 1}/${groups.size}", base.copy(stops = g.map { raw[mid[it]] })) }
        App.storage?.saveRoutes(saved); notice = "Podzielono na ${groups.size} tras – otwórz je w zakładce „Zapisane”."
    }
    fun csvText(): String {
        if (mats == null) return ""
        val e = etas
        return Csv.export(order.withIndex().filter { it.value != dummyIdx }.map { (p, g) ->
            CsvRow(p + 1, points[g], coords[g].lat, coords[g].lng, Schedule.hhmm(e[p].arrivalMin),
                service.getOrElse(g) { 0.0 }.toInt(), deadline.getOrNull(g)?.let { Schedule.hhmm(it) } ?: "")
        })
    }
    fun shareText(): String {
        if (mats == null) return ""
        val e = etas; val rows = order.withIndex().filter { it.value != dummyIdx }
        val sb = StringBuilder("Trasa: ${fmt1(totalKm ?: 0.0)} km\n")
        rows.forEach { (p, g) -> sb.append("${p + 1}. ${points[g]} – ${Schedule.hhmm(e[p].arrivalMin)}\n") }
        val pts = rows.map { coords[it.value] }
        if (pts.size >= 2) {
            fun s(l: LatLng) = "%.6f,%.6f".format(Locale.ROOT, l.lat, l.lng)
            sb.append("\nGoogle Maps: https://www.google.com/maps/dir/?api=1&origin=${s(pts.first())}&destination=${s(pts.last())}")
            val way = pts.drop(1).dropLast(1).take(9)
            if (way.isNotEmpty()) sb.append("&waypoints=" + way.joinToString("%7C") { s(it) })
            if (pts.size > 11) sb.append("\n(link obejmuje pierwsze 9 przystanków pośrednich – limit Google Maps)")
        }
        return sb.toString()
    }

    // ---- ustawienia i zapis ----
    fun settings() = Settings(options, voiceOn, serviceMin, departText, demo, lastOdoKm, phrases, userName, historyOn)
    fun applySettings(s: Settings) { options = s.options; voiceOn = s.voiceOn; serviceMin = s.serviceMin; departText = s.departText; demo = s.demo; lastOdoKm = s.lastOdoKm; phrases = s.phrases; userName = s.userName; historyOn = s.historyOn }
    fun saveSettings() { App.storage?.saveSettings(settings()); plan() }
    // ---- historia odwiedzonych adresów ----
    fun suggestions(query: String): List<HistoryEntry> = History.suggest(history, query, historyOn)
    fun recordVisit(label: String, at: LatLng) {
        if (!historyOn) return
        val upd = History.record(history, label, at, System.currentTimeMillis())
        if (upd === history) return
        history = upd
        App.storage?.let { s -> viewModelScope.launch(Dispatchers.IO) { s.saveHistory(upd) } }
    }
    fun clearHistory() { history = emptyList(); App.storage?.saveHistory(emptyList()) }
    fun useForStart(e: HistoryEntry) { manual[e.label] = e.at; startGps = null; startText = e.label; plan() }
    fun useForEnd(e: HistoryEntry) { manual[e.label] = e.at; endGps = null; endText = e.label; plan() }
    fun useForStop(i: Int, e: HistoryEntry) {
        val sp = StopText.parse(stops[i]); manual[e.label] = e.at
        stops[i] = e.label + (sp.deadlineMin?.let { " @" + Schedule.hhmm(it.toDouble()) } ?: "") + (sp.serviceMin?.let { " ~$it" } ?: "")
        plan()
    }

    private fun persistInputs() { App.storage?.let { s -> val i = inputs(); viewModelScope.launch(Dispatchers.IO) { s.saveInputs(i) } } }
    private fun persistSession() {
        val s = App.storage ?: return
        val t = trip
        val ses = Session(inputs(), points, raw, coords, order, visited, autoReorder, dummyIdx >= 0,
            t?.odoStartKm, t?.distanceM ?: 0.0, t?.drivingSec ?: 0.0, t?.stopSec ?: 0.0, plannedKm)
        viewModelScope.launch(Dispatchers.IO) { s.saveSession(ses) }
    }
    private var pendingSession: Session? = null
    var resumable by mutableStateOf(false)
    fun restore() {
        val s = App.storage ?: return
        saved = s.loadRoutes(); s.loadSettings()?.let { applySettings(it) }; history = s.loadHistory()
        s.loadInputs()?.let { applyInputs(it) }
        s.loadSession()?.let { pendingSession = it; resumable = true }
    }
    /** Wznawia jazdę przerwaną zabiciem aplikacji. Wołać z widocznej aktywności (potem start usługi). */
    fun resumeNav(): Boolean {
        val s = pendingSession ?: return false
        applyInputs(s.inputs); coords = s.coords; points = s.points; raw = s.raw; order = s.order
        val open = s.openEnd
        dummyIdx = if (open) s.coords.lastIndex else -1
        val rawAll = if (open) s.raw.dropLast(1) else s.raw
        val sp = specsOf(rawAll, open)
        service = svcOf(sp, open) + (if (open) listOf(0.0) else emptyList())
        deadline = sp.map { it.deadlineMin?.toDouble() } + (if (open) listOf<Double?>(null) else emptyList())
        openEnd = open; visited = s.visited; autoReorder = s.autoReorder
        plannedKm = s.plannedKm; trip = TripLog(s.odoStartKm, s.distanceM, s.drivingSec, s.stopSec); refreshTripStats()
        navActive = true; leg = null; offCount = 0; status = "Wznawiam jazdę…"
        pendingSession = null; resumable = false
        viewModelScope.launch {
            try {
                val real = if (open) s.coords.dropLast(1) else s.coords
                val m = provider.matrix(real, options); mats = if (open) withDummy(m) else m
            } catch (e: Exception) { status = "Brak danych do odświeżenia odległości" }
        }
        return true
    }

    // ---- jazda ----
    var navActive by mutableStateOf(false); var status by mutableStateOf("")
    var visited by mutableStateOf(listOf<Int>()); var leg by mutableStateOf<Leg?>(null)
    var maneuver by mutableStateOf("")
    // dziennik jazdy: przebieg, pokonany odcinek, czas jazdy i postoju
    private var trip: TripLog? = null; private var plannedKm: Double? = null
    var summary by mutableStateOf<TripSummary?>(null)
    var tripKm by mutableStateOf(0.0); var tripDrivingSec by mutableStateOf(0.0); var tripStopSec by mutableStateOf(0.0)
    private fun refreshTripStats() { trip?.let { tripKm = it.distanceM / 1000; tripDrivingSec = it.drivingSec; tripStopSec = it.stopSec } }
    /** Kończy dziennik jazdy i przygotowuje podsumowanie (okno „Koniec trasy”). */
    private fun finishTrip() {
        val t = trip ?: return
        trip = null; refreshTripStatsFrom(t)
        val s = t.summary(plannedKm); summary = s
        s.odoEndKm?.let { lastOdoKm = it; App.storage?.saveSettings(settings()) }
    }
    private fun refreshTripStatsFrom(t: TripLog) { tripKm = t.distanceM / 1000; tripDrivingSec = t.drivingSec; tripStopSec = t.stopSec }
    fun dismissSummary() { summary = null }
    var carPos by mutableStateOf<LatLng?>(null)          // pozycja kierowcy (do mapy)
    private var guid: GuidanceState? = null
    private var pos: LatLng? = null; private var busy = false; private var offCount = 0
    private var lastRefresh = 0L; private var autoReorder = true

    val busyNow: Boolean get() = busy
    val etaMin: Int? get() = leg?.let { (it.seconds / 60).roundToInt().coerceAtLeast(1) }
    val remainingKm: Double? get() {
        val l = leg ?: return null
        val m = mats ?: return l.meters / 1000
        return (l.meters + RouteOptimizer.cost(order.drop(visited.size), m.dist)) / 1000
    }
    fun nextTarget(): LatLng? = order.getOrNull(visited.size)?.takeIf { it != dummyIdx }?.let { coords.getOrNull(it) }
    fun nextLine(): String {
        val n = order.getOrNull(visited.size)?.takeIf { it != dummyIdx }?.let { points[it] } ?: return status
        val base = leg?.let { "$n · ${fmt1(it.meters / 1000)} km, ${(it.seconds / 60).toInt()} min" } ?: n
        return if (maneuver.isNotEmpty()) "$base\n$maneuver" else base
    }

    // ręczna edycja (także w jeździe – dotyczy nieodwiedzonych punktów)
    private val lo get() = if (navActive) maxOf(visited.size, 1) else 1
    fun move(pos: Int, d: Int) {
        val t = pos + d
        if (pos !in lo..order.size - 2 || t !in lo..order.size - 2) return
        order = order.toMutableList().also { val x = it[pos]; it[pos] = it[t]; it[t] = x }
        autoReorder = false; afterEdit()
    }
    fun remove(pos: Int) {
        if (navActive && pos in lo..order.size - 2) { order = order.toMutableList().also { it.removeAt(pos) }; afterEdit() }
    }
    fun reoptimize() {
        val m = mats ?: return
        viewModelScope.launch {
            val fixed = if (navActive && visited.isNotEmpty()) visited else listOf(order.first())
            val sub = listOf(fixed.last()) + order.filter { it !in fixed }
            val cm = if (options.criterion == Criterion.TIME) m.dur else m.dist
            val mm = Array(sub.size) { i -> DoubleArray(sub.size) { j -> cm[sub[i]][sub[j]] } }
            val ord = withContext(Dispatchers.Default) { RouteOptimizer.optimize(mm) }
            order = fixed.dropLast(1) + ord.map { sub[it] }
            autoReorder = true; afterEdit()
        }
    }
    private fun afterEdit() { if (navActive && pos != null) replan("Trasa zmieniona ręcznie – przeliczam…"); if (navActive) persistSession() }

    /** Dodanie przystanku w trakcie jazdy. */
    fun addLive(text: String) {
        val sp = StopText.parse(text); if (sp.address.isEmpty() || !navActive) return
        viewModelScope.launch {
            try {
                val c = resolve(sp.address) ?: error("Nie znaleziono adresu: ${sp.address}")
                val svc = (sp.serviceMin ?: serviceMin).toDouble(); val dl = sp.deadlineMin?.toDouble()
                if (dummyIdx >= 0) {   // trasa bez mety: nowy punkt wchodzi przed sztuczny koniec
                    val idx = dummyIdx
                    coords = coords.dropLast(1) + listOf(c, c); points = points.dropLast(1) + listOf(sp.address, "(koniec trasy)")
                    raw = raw.dropLast(1) + listOf(text.trim(), ""); service = service.dropLast(1) + listOf(svc, 0.0)
                    deadline = deadline.dropLast(1) + listOf<Double?>(dl, null)
                    order = order.dropLast(1) + listOf(idx, idx + 1); dummyIdx = idx + 1
                    mats = withDummy(provider.matrix(coords.dropLast(1), options))
                } else {
                    val idx = coords.size
                    coords = coords + c; points = points + sp.address; raw = raw + text.trim()
                    service = service + svc; deadline = deadline + listOf(dl)
                    order = order.dropLast(1) + idx + order.last()
                    mats = provider.matrix(coords, options)
                }
                if (pos != null) replan("Dodano przystanek: ${sp.address}") else status = "Dodano przystanek: ${sp.address}"
            } catch (e: Exception) { status = "Nie dodano: ${e.message}" }
        }
    }

    /** [odoKm] = stan licznika auta przed trasą (opcjonalnie). */
    fun startNav(odoKm: Double? = null) {
        // start z GPS = kierowca już tam jest; start wpisany adresem = najpierw dojazd do niego
        visited = if (startGps != null) listOf(order.first()) else emptyList()
        trip = TripLog(odoKm); plannedKm = totalKm; summary = null; refreshTripStats()
        navActive = true; leg = null; offCount = 0; maneuver = ""; status = "Ustalam pozycję GPS…"
    }
    fun stopNav() {
        if (navActive) finishTrip()
        navActive = false; leg = null; maneuver = ""; status = ""; App.storage?.clearSession()
    }
    private val remaining get() = order.drop(visited.size)
    private fun target() = coords[remaining.first()]
    private fun say(t: String) { if (voiceOn) App.speak?.invoke(t) }

    /** [speed] w m/s (ujemna = brak danych z GPS), [timeMs] = czas odczytu. */
    fun onLocation(p: LatLng, accuracy: Double = 0.0, speed: Double = -1.0, timeMs: Long = System.currentTimeMillis()) {
        if (!navActive) return
        if (accuracy > 80.0) return                         // zbyt niedokładny odczyt – pomijamy
        trip?.let { it.add(p, timeMs, speed, accuracy); refreshTripStatsFrom(it) }
        if (busy) return
        pos = p; carPos = p
        if (leg == null) { replan("Wyznaczam trasę…"); return }
        guid?.let { g -> g.update(p, speed).forEach { say(it) }; maneuver = g.describe(p) }
        when (NavLogic.check(p, target(), leg, accuracy, maxOf(0.0, speed))) {
            NavEvent.ARRIVED -> arrive()
            NavEvent.OFF_ROUTE -> if (++offCount >= 2) { offCount = 0; replan("Zboczono z trasy – przeliczam…") }
            NavEvent.OK -> { offCount = 0
                if (System.currentTimeMillis() - lastRefresh > 60_000) replan(null) }   // odświeżenie ruchu co minutę
        }
    }
    private fun arrive() {
        val reached = remaining.first()
        visited = visited + reached
        recordVisit(points[reached], coords[reached])        // odwiedzony adres trafia do historii (jeśli włączona)
        if (remaining.none { it != dummyIdx }) {
            finishTrip(); navActive = false; maneuver = ""; status = "${phrases.finished} ✔"; say(phrases.finished); App.storage?.clearSession(); return
        }
        say(phrases.stopReached); replan("Dotarłeś do: ${points[visited.last()]}")
    }
    /** Droga zablokowana: wariant objazdowy (TomTom omija zamknięcia sam, OSRM podaje alternatywę). */
    fun blocked() { if (navActive && pos != null) replan("Droga zablokowana – szukam objazdu…", alternative = true) }

    private fun replan(msg: String?, alternative: Boolean = false) {
        val p = pos ?: return; busy = true; if (msg != null) status = msg
        viewModelScope.launch {
            try {
                val rem = remaining.filter { it != dummyIdx }
                if (autoReorder && visited.isNotEmpty() && rem.size > 1) {        // od bieżącej pozycji; meta stała
                    var m = provider.matrix(listOf(p) + rem.map { coords[it] }, options)
                    if (dummyIdx >= 0) m = withDummy(m)
                    val ord = withContext(Dispatchers.Default) {
                        RouteOptimizer.optimize(if (options.criterion == Criterion.TIME) m.dur else m.dist)
                    }
                    order = visited + ord.drop(1).map { if (it - 1 < rem.size) rem[it - 1] else dummyIdx }
                }
                val l = provider.route(p, target(), options, alternative)
                leg = l; guid = GuidanceState(l.steps) { phrases }; lastRefresh = System.currentTimeMillis()
                maneuver = guid?.describe(p) ?: ""
                if (msg != null) status = "$msg Trasa zaktualizowana."
                provider.takeWarning()?.let { notice = it }
                persistSession()
            } catch (e: Exception) { status = "Brak danych (${e.message}) – jadę wg ostatniej trasy" }
            busy = false
        }
    }

    companion object { const val GPS_LABEL = "Moja lokalizacja (GPS)" }
}
