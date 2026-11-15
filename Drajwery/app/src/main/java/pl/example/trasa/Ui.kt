@file:OptIn(ExperimentalMaterial3Api::class)
package pl.example.trasa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class Actions(
    val onStart: (Double?) -> Unit, val onStop: () -> Unit, val onPip: () -> Unit,
    val onGpsStart: () -> Unit, val onGpsEnd: () -> Unit, val onImport: () -> Unit,
    val onShare: () -> Unit, val onExternalNav: () -> Unit, val onResume: () -> Unit,
)

@Composable
fun Screen(vm: RouteVM, a: Actions) {
    var tab by remember { mutableStateOf(0) }
    var askOdo by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
            title = { Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BrandTitle(size = 30.sp)
                    Spacer(Modifier.weight(1f))
                    if (vm.userName.isNotBlank()) Text(vm.userName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                }
                // długość całej trasy i planowany koniec, odświeżane z każdym dodanym przystankiem
                val end = vm.etas.lastOrNull()?.let { " · koniec ok. ${Schedule.hhmm(it.arrivalMin)}" } ?: ""
                if (vm.totalKm != null) Text("Trasa: ${fmt1(vm.totalKm ?: 0.0)} km$end", style = MaterialTheme.typography.titleMedium)
                if (vm.loading) Text(vm.progress.ifEmpty { "Liczę…" }, style = MaterialTheme.typography.labelSmall)
                else if (vm.order.isNotEmpty()) Text("przystanków pośrednich: ${(vm.order.size - 2).coerceAtLeast(0)}", style = MaterialTheme.typography.labelSmall)
            } }) },
        bottomBar = { if (vm.navActive && vm.leg != null) NavBottomBar(vm) },   // na dole: czas do przystanku i pozostałe km
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
                listOf("Trasa", "Mapa", "Zapisane", "Ustawienia").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            when (tab) { 0 -> PlanTab(vm, a) { askOdo = true }; 1 -> MapTab(vm); 2 -> SavedTab(vm); else -> SettingsTab(vm) }
        }
    }
    if (askOdo) OdoDialog(vm.lastOdoKm, onCancel = { askOdo = false }, onStart = { km -> askOdo = false; a.onStart(km) })
    vm.summary?.let { s -> SummaryDialog(s, vm.userName) { vm.dismissSummary() } }
    vm.cand?.let { c ->
        AlertDialog(onDismissRequest = { vm.cand = null }, confirmButton = {},
            dismissButton = { TextButton({ vm.cand = null }) { Text("Anuluj") } },
            title = { Text("Wybierz właściwy adres") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (c.second.isEmpty()) Text("Brak wyników dla: ${c.first}")
                c.second.forEach { r -> TextButton({ vm.pickCandidate(r) }) { Text(r.label) } }
            } })
    }
}

@Composable
fun PlanTab(vm: RouteVM, a: Actions, onAskStart: () -> Unit) {
    val clip = LocalClipboardManager.current
    var k by remember { mutableStateOf(2) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (vm.resumable && !vm.navActive) item {
            AppCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("Znaleziono przerwaną jazdę.")
                Button(a.onResume, Modifier.heightIn(min = 56.dp)) { Text("Wznów jazdę") }
            } }
        }
        if (vm.navActive || vm.status.isNotEmpty()) item { NavCard(vm, a) }
        vm.notice?.let { n -> item { Text(n, color = MaterialTheme.colorScheme.primary) } }
        vm.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        if (!vm.navActive) {
            item { SectionTitle("Start") }
            item { PointRow("Punkt początkowy", vm.startText, vm.startGps, { vm.startText = it }, a.onGpsStart, { vm.setStartGps(null) }) { vm.plan() } }
            item { Suggestions(vm, vm.startText) { vm.useForStart(it) } }
            item { SectionTitle("Przystanki") }
            item { Text("Przystanek: adres, opcjonalnie  @14:30 (najpóźniej)  ~10 (postój w min)", style = MaterialTheme.typography.labelSmall) }
            items(vm.stops.size) { i ->
                val v = vm.stops[i]
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Field("Przystanek ${i + 1}", v, { vm.stops[i] = it }) { vm.plan() } }
                        IconButton({ vm.removeStop(i) }) { Icon(Icons.Default.Close, "Usuń") }
                    }
                    Suggestions(vm, StopText.parse(v).address) { vm.useForStop(i, it) }
                    vm.resolved[StopText.parse(v).address]?.let { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("→ $r", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            TextButton({ vm.showCandidates(v) }) { Text("Zmień") }
                        }
                    }
                }
            }
            item {
                Row {
                    TextButton({ vm.stops.add(""); vm.plan() }) { Icon(Icons.Default.Add, null); Text(" Dodaj adres") }
                    TextButton({ clip.getText()?.text?.let { vm.pasteList(it) } }) { Icon(Icons.Default.ContentPaste, null); Text(" Wklej") }
                    TextButton(a.onImport) { Icon(Icons.Default.FileOpen, null); Text(" CSV") }
                }
            }
            item { SectionTitle("Meta i opcje") }
            if (!vm.returnToStart && !vm.openEnd)
                item { Column {
                    PointRow("Punkt końcowy", vm.endText, vm.endGps, { vm.endText = it }, a.onGpsEnd, { vm.setEndGps(null) }) { vm.plan() }
                    Suggestions(vm, vm.endText) { vm.useForEnd(it) }
                } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(vm.returnToStart, { vm.returnToStart = it; if (it) vm.openEnd = false; vm.plan() }); Text("Wróć do startu")
                    Spacer(Modifier.width(12.dp))
                    Checkbox(vm.openEnd, { vm.openEnd = it; if (it) vm.returnToStart = false; vm.plan() }); Text("Bez mety")
                }
            }
            item { Field("Wyjazd (GG:MM, puste = teraz)", vm.departText, { vm.departText = it }) { vm.plan() } }
            item {
                Button({ vm.plan(force = true) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !vm.loading) {
                    Text(if (vm.loading) vm.progress.ifEmpty { "Liczę…" } else "Przelicz trasę")
                }
            }
        }
        if (vm.order.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ vm.reoptimize() }) { Text("Optymalizuj ponownie") }
                    if (!vm.navActive) {
                        TextButton({ vm.saveCurrent("") }) { Icon(Icons.Default.Save, null); Text(" Zapisz") }
                        TextButton(a.onShare) { Icon(Icons.Default.Share, null); Text(" Udostępnij") }
                    }
                }
            }
            if (!vm.navActive) {
                item { Button(onAskStart, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Rozpocznij jazdę") } }
                item {
                    AppCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Kilku kierowców:", Modifier.weight(1f))
                        OutlinedButton({ k = (k - 1).coerceAtLeast(2) }) { Text("−") }
                        Text("  $k  ")
                        OutlinedButton({ k++ }) { Text("+") }
                        TextButton({ vm.splitInto(k) }) { Text("Podziel") }
                    } }
                }
            }
            val rows = vm.order.withIndex().filter { it.value != vm.dummyIdx }
            items(rows, key = { it.value }) { iv -> StopRow(vm, iv.index, iv.value) }
        }
        item { Text("Dane map: © autorzy OpenStreetMap (Nominatim, OSRM)", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun StopRow(vm: RouteVM, pos: Int, idx: Int) {
    val eta = vm.etas.getOrNull(pos)
    val done = vm.navActive && pos < vm.visited.size
    val fixed = pos == 0 || (vm.dummyIdx < 0 && pos == vm.order.lastIndex) || done
    var h by remember { mutableStateOf(1f) }
    val curPos by rememberUpdatedState(pos)
    AppCard(Modifier.fillMaxWidth().onSizeChanged { h = it.height.toFloat().coerceAtLeast(1f) }) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✔" else "${pos + 1}.", Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(vm.points[idx])
                if (eta != null && !done) Text(
                    "przyjazd ${Schedule.hhmm(eta.arrivalMin)}" + if (eta.late) "  ⚠ po terminie" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (eta.late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!fixed) {
                // przeciąganie: przytrzymaj uchwyt i przesuń w górę/w dół
                Icon(Icons.Default.DragHandle, "Przeciągnij", Modifier.padding(4.dp).pointerInput(Unit) {
                    var acc = 0f
                    detectDragGesturesAfterLongPress(onDragStart = { acc = 0f }, onDrag = { change, d ->
                        change.consume(); acc += d.y
                        if (acc > h) { vm.move(curPos, 1); acc -= h } else if (acc < -h) { vm.move(curPos, -1); acc += h }
                    })
                })
                IconButton({ vm.move(pos, -1) }) { Icon(Icons.Default.ArrowUpward, "W górę") }
                IconButton({ vm.move(pos, 1) }) { Icon(Icons.Default.ArrowDownward, "W dół") }
                if (vm.navActive) IconButton({ vm.remove(pos) }) { Icon(Icons.Default.Delete, "Usuń") }
            }
        }
    }
}

@Composable
fun NavBottomBar(vm: RouteVM) = Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (vm.maneuver.isNotEmpty()) Text(vm.maneuver, style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Do przystanku", style = MaterialTheme.typography.labelSmall)
                Text("${vm.etaMin ?: "–"} min", style = MaterialTheme.typography.headlineMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Pozostało do końca", style = MaterialTheme.typography.labelSmall)
                Text(vm.remainingKm?.let { "${fmt1(it)} km" } ?: "–", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
fun NavCard(vm: RouteVM, a: Actions) {
    var txt by remember { mutableStateOf("") }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (vm.navActive) {
                val next = vm.order.getOrNull(vm.visited.size)?.takeIf { it != vm.dummyIdx }?.let { vm.points[it] } ?: ""
                Text("Następny: $next", style = MaterialTheme.typography.titleMedium)
            }
            if (vm.status.isNotEmpty()) Text(vm.status)
            if (vm.navActive) Text("Przejechano ${fmt1(vm.tripKm)} km · jazda ${TripFmt.hm(vm.tripDrivingSec)} · postój ${TripFmt.hm(vm.tripStopSec)}",
                style = MaterialTheme.typography.labelLarge)
            if (vm.navActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field("Dodaj przystanek w trasie", txt, { txt = it }) }
                    IconButton({ vm.addLive(txt); txt = "" }) { Icon(Icons.Default.Add, "Dodaj") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(a.onExternalNav, Modifier.heightIn(min = 56.dp)) { Text("Nawiguj w Mapach") }
                    FilterChip(vm.voiceOn, { vm.voiceOn = !vm.voiceOn; vm.saveSettings() }, label = { Text("Głos") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.blocked() }, Modifier.heightIn(min = 56.dp)) { Text("Droga zablokowana") }
                    OutlinedButton(a.onPip, Modifier.heightIn(min = 56.dp)) { Text("Okno") }
                    Button(a.onStop, Modifier.heightIn(min = 56.dp)) { Text("Zakończ") }
                }
            }
        }
    }
}

/** Mały widok do okna „ekran w ekranie”. */
@Composable
fun PipView(vm: RouteVM) = Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
    Text(if (vm.navActive) "Następny:" else "Jazda zakończona", style = MaterialTheme.typography.labelSmall)
    Text(vm.nextLine(), style = MaterialTheme.typography.titleSmall, maxLines = 4)
    if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.labelSmall, maxLines = 2)
}

@Composable
fun SavedTab(vm: RouteVM) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.saved.isEmpty()) item { Text("Brak zapisanych tras. Zapisz trasę przyciskiem „Zapisz” albo podziel ją na kilka tras.") }
        items(vm.saved, key = { it.id }) { r ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Text("${r.inputs.stops.size} przystanków", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton({ vm.load(r) }) { Text("Otwórz") }
                    IconButton({ vm.delete(r) }) { Icon(Icons.Default.Delete, "Usuń") }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(vm: RouteVM) {
    fun o(n: RouteOptions) { vm.options = n; vm.saveSettings() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Użytkownik")
        Field("Nazwa użytkownika", vm.userName, { vm.userName = it }) { vm.saveSettings() }
        SectionTitle("Historia adresów")
        SwitchRow("Zapamiętuj odwiedzone adresy", vm.historyOn) { vm.historyOn = it; vm.saveSettings() }
        Text(if (vm.historyOn) "Zapamiętane adresy: ${vm.history.size}. Podpowiadam je przy wpisywaniu i szukam ich szybciej, bez pytania serwera map."
             else "Zapamiętywanie wyłączone – nowe adresy nie są zapisywane i nie ma podpowiedzi.", style = MaterialTheme.typography.labelMedium)
        OutlinedButton({ vm.clearHistory() }, enabled = vm.history.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, null); Text(" Wyczyść historię") }
        SectionTitle("Trasa i pojazd")
        Text("Kryterium trasy", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(vm.options.criterion == Criterion.DISTANCE, { o(vm.options.copy(criterion = Criterion.DISTANCE)) }, label = { Text("Najkrótsza (km)") })
            FilterChip(vm.options.criterion == Criterion.TIME, { o(vm.options.copy(criterion = Criterion.TIME)) }, label = { Text("Najszybsza (czas)") })
        }
        Text("Pojazd", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Vehicle.CAR to "Auto", Vehicle.VAN to "Dostawczy", Vehicle.TRUCK to "Ciężarówka", Vehicle.BIKE to "Rower").forEach { (v, n) ->
                FilterChip(vm.options.vehicle == v, { o(vm.options.copy(vehicle = v)) }, label = { Text(n) })
            }
        }
        SwitchRow("Unikaj autostrad", vm.options.avoidMotorways) { o(vm.options.copy(avoidMotorways = it)) }
        SwitchRow("Unikaj dróg płatnych", vm.options.avoidTolls) { o(vm.options.copy(avoidTolls = it)) }
        SwitchRow("Unikaj promów", vm.options.avoidFerries) { o(vm.options.copy(avoidFerries = it)) }
        SwitchRow("Komunikaty głosowe", vm.voiceOn) { vm.voiceOn = it; vm.saveSettings() }
        SwitchRow("Tryb demo (bez internetu)", vm.demo) { vm.demo = it; vm.saveSettings(); vm.plan(force = true) }
        PhrasesSection(vm)
        Field("Domyślny postój na przystanku (min)", vm.serviceMin.toString(), { s -> s.toIntOrNull()?.let { vm.serviceMin = it.coerceIn(0, 240) } }) { vm.saveSettings() }
        Text("Typ pojazdu inny niż auto działa po podaniu klucza TomTom; publiczny OSRM liczy tylko dla samochodu.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SwitchRow(label: String, on: Boolean, change: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label); Switch(on, change)
    }

/** Punkt początkowy/końcowy: wpisany adres albo lokalizacja GPS urządzenia. */
@Composable
fun PointRow(label: String, text: String, gps: LatLng?, onText: (String) -> Unit,
             onGps: () -> Unit, onClearGps: () -> Unit, onDone: () -> Unit) {
    if (gps != null) AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MyLocation, null)
            Text("  $label: moja lokalizacja (%.5f, %.5f)".format(gps.lat, gps.lng), Modifier.weight(1f))
            IconButton(onClearGps) { Icon(Icons.Default.Close, "Wpisz adres zamiast GPS") }
        }
    } else OutlinedTextField(text, onText,
        Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) onDone() },
        colors = fieldColors(), label = { Text("$label (adres)") }, singleLine = true,
        trailingIcon = { IconButton(onGps) { Icon(Icons.Default.MyLocation, "Użyj lokalizacji GPS") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }))
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, onDone: () -> Unit = {}) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) onDone() },
        colors = fieldColors(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }))

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 8.dp))

/** Okno przed jazdą: stan licznika auta. */
@Composable
fun OdoDialog(last: Double?, onCancel: () -> Unit, onStart: (Double?) -> Unit) {
    var txt by remember { mutableStateOf(last?.let { fmt1(it) } ?: "") }
    val km = txt.replace(',', '.').trim().toDoubleOrNull()
    AlertDialog(onDismissRequest = onCancel,
        title = { Text("Przebieg auta przed trasą") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wpisz stan licznika (km). Po trasie pokażę, ile przejechałeś, oraz czas jazdy i postoju.")
            OutlinedTextField(txt, { txt = it }, Modifier.fillMaxWidth(), label = { Text("Przebieg (km)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (txt.isNotBlank() && km == null) Text("Wpisz liczbę, np. 123456,7", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { Button({ onStart(km) }, enabled = txt.isBlank() || km != null) { Text(if (txt.isBlank()) "Start bez przebiegu" else "Start") } },
        dismissButton = { TextButton(onCancel) { Text("Anuluj") } })
}

/** Kółko kierownicy w stylu symbolu „jazda” z tachografu. */
@Composable
fun TachoWheel(modifier: Modifier = Modifier) {
    val c = LocalContentColor.current
    Canvas(modifier) {
        val r = size.minDimension / 2 - 2f; val w = size.minDimension / 12
        drawCircle(c, r, center, style = Stroke(w))
        drawCircle(c, r * 0.22f, center)
        drawLine(c, center, Offset(center.x - r, center.y), w); drawLine(c, center, Offset(center.x + r, center.y), w)
        drawLine(c, center, Offset(center.x, center.y + r), w)
    }
}

@Composable
fun TachoRow(label: String, value: String, symbol: @Composable () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.padding(10.dp).size(28.dp), contentAlignment = Alignment.Center) { symbol() }
        }
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }

/** Podsumowanie po trasie: pokonany odcinek, czas jazdy (kierownica) i postoju (filiżanka) – symbole jak w tachografie. */
@Composable
fun SummaryDialog(s: TripSummary, name: String, onClose: () -> Unit) = AlertDialog(
    onDismissRequest = onClose, confirmButton = { Button(onClose) { Text("Zamknij") } },
    title = { Text(if (name.isBlank()) "Koniec trasy" else "Koniec trasy – $name") },
    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TachoRow("Pokonany odcinek", "${fmt1(s.distanceKm)} km") { Icon(Icons.Default.Straighten, null) }
        if (s.odoStartKm != null) TachoRow("Przebieg licznika", "${fmt1(s.odoStartKm)} → ${fmt1(s.odoEndKm ?: s.odoStartKm)} km") { Icon(Icons.Default.Speed, null) }
        TachoRow("Czas jazdy", TripFmt.hm(s.drivingSec)) { TachoWheel(Modifier.fillMaxSize()) }
        TachoRow("Czas postoju", TripFmt.hm(s.stopSec)) { Icon(Icons.Default.LocalCafe, null) }
        TachoRow("Razem", TripFmt.hm(s.drivingSec + s.stopSec)) { Icon(Icons.Default.Schedule, null) }
        s.plannedKm?.let { Text("Zaplanowano: ${fmt1(it)} km", style = MaterialTheme.typography.labelMedium) }
    } })

/** Ustawienia: edycja treści komunikatów, np. „Za 300 metrów, skręć w lewo”, z podglądem i odsłuchem. */
@Composable
fun PhrasesSection(vm: RouteVM) {
    val sample = Step(LatLng(0.0, 0.0), "turn", "left", "Lipowa")
    val far = Guidance.far(sample, vm.phrases, 300); val near = Guidance.near(sample, vm.phrases)
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Treść komunikatów głosowych", style = MaterialTheme.typography.titleMedium)
            Text("Podgląd: „$far” oraz „$near”")
            if (!Voice.ready) Text("Telefon nie zgłasza polskiego głosu – zainstaluj „Mowa Google” i język polski w ustawieniach telefonu.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ Voice.speak("$far. $near") }) { Text("Odtwórz") }
                OutlinedButton({ vm.phrases = Phrases(); vm.saveSettings() }) { Text("Przywróć domyślne") }
            }
            PhraseFields.all.forEach { f -> Field(f.label, f.get(vm.phrases), { vm.phrases = f.set(vm.phrases, it) }) { vm.saveSettings() } }
        }
    }
}

/** Podpowiedzi z historii odwiedzonych adresów pod polem tekstowym. */
@Composable
fun Suggestions(vm: RouteVM, query: String, onPick: (HistoryEntry) -> Unit) {
    val s = vm.suggestions(query)
    if (s.isNotEmpty()) Column(Modifier.fillMaxWidth()) {
        s.forEach { e ->
            TextButton({ onPick(e) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.History, null, Modifier.size(18.dp)); Text("  ${e.label}", Modifier.weight(1f), maxLines = 1)
            }
        }
    }
}
