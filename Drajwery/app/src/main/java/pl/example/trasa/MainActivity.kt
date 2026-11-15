package pl.example.trasa

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm get() = App.vm   // jeden stan na cały proces – przeżywa zamknięcie okna, gdy działa usługa
    private var inPip by mutableStateOf(false)
    private var pendingGpsStart: Boolean? = null
    private var afterPerms: (() -> Unit)? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val act = afterPerms; afterPerms = null
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) act?.invoke()
        else vm.status = "Brak zgody na lokalizację – nawigacja wyłączona"
    }
    private val gpsPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val st = pendingGpsStart; pendingGpsStart = null
        if (ok && st != null) pickGps(st) else vm.error = "Brak zgody na lokalizację"
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            vm.importText(contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) { vm.error = "Nie udało się wczytać pliku" }
    }

    /** Zgody potrzebne do jazdy (lokalizacja + powiadomienia na Androidzie 13+), potem [action]. */
    private fun withPerms(action: () -> Unit) {
        val need = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) action() else { afterPerms = action; perms.launch(need.toTypedArray()) }
    }
    private fun beginNav(odoKm: Double?) {
        vm.startNav(odoKm)
        ContextCompat.startForegroundService(this, Intent(this, NavService::class.java)) // praca w tle
    }
    private fun resumeNav() {
        if (vm.resumeNav()) ContextCompat.startForegroundService(this, Intent(this, NavService::class.java))
    }
    private fun endNav() { stopService(Intent(this, NavService::class.java)); vm.stopNav() }

    /** Wybór lokalizacji GPS urządzenia jako punktu startu lub mety. */
    private fun pickGps(forStart: Boolean) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingGpsStart = forStart; gpsPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        vm.error = null
        getFix { ll ->
            if (ll == null) vm.error = "Nie udało się ustalić pozycji GPS – wyjdź na zewnątrz lub wpisz adres"
            else if (forStart) vm.setStartGps(ll) else vm.setEndGps(ll)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getFix(cb: (LatLng?) -> Unit) {
        val lm = getSystemService(LocationManager::class.java)
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }.maxByOrNull { it.time }
        val lastLl = last?.let { LatLng(it.latitude, it.longitude) }
        if (last != null && System.currentTimeMillis() - last.time < 60_000) { cb(lastLl); return }
        val prov = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        var done = false
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (done) return; done = true; lm.removeUpdates(this); cb(LatLng(loc.latitude, loc.longitude))
            }
        }
        try { lm.requestLocationUpdates(prov, 0L, 0f, l) } catch (e: Exception) { cb(lastLl); return }
        Handler(Looper.getMainLooper()).postDelayed({ if (!done) { done = true; lm.removeUpdates(l); cb(lastLl) } }, 15_000)
    }

    /** Udostępnianie: plik CSV (do Excela) + tekst z linkiem do Google Maps. */
    private fun share() {
        val csv = vm.csvText()
        if (csv.isEmpty()) { vm.error = "Najpierw ułóż trasę"; return }
        val f = File(File(cacheDir, "shared").apply { mkdirs() }, "trasa.csv"); f.writeText("\uFEFF" + csv)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, vm.shareText()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Udostępnij trasę"))
    }

    /** Prowadzenie do następnego przystanku w zewnętrznej aplikacji (Mapy Google, Waze…). */
    private fun externalNav() {
        val t = vm.nextTarget() ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${t.lat},${t.lng}&mode=d"))) }
        catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${t.lat},${t.lng}?q=${t.lat},${t.lng}"))) }
            catch (e2: Exception) { vm.status = "Brak aplikacji nawigacyjnej na telefonie" }
        }
    }

    private fun enterPip() {
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        catch (e: Exception) { vm.status = "Tryb ekran w ekranie jest wyłączony w ustawieniach telefonu" }
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (vm.navActive) enterPip() } // auto-PiP po wyjściu z aplikacji
    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig); inPip = isInPip
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent {
            DrajweryTheme {
                var splash by remember { mutableStateOf(!App.splashDone) }
                Box(Modifier.fillMaxSize()) {
                    AsphaltBackground()
                    when {
                        splash -> SplashScreen(vm.userName) { App.splashDone = true; splash = false }
                        inPip -> PipView(vm)
                        else -> Screen(vm, Actions(
                            onStart = { odo -> withPerms { beginNav(odo) } }, onStop = ::endNav, onPip = ::enterPip,
                            onGpsStart = { pickGps(true) }, onGpsEnd = { pickGps(false) },
                            onImport = { importPicker.launch("*/*") }, onShare = ::share,
                            onExternalNav = ::externalNav, onResume = { withPerms(::resumeNav) }))
                    }
                }
            }
        }
    }
}
