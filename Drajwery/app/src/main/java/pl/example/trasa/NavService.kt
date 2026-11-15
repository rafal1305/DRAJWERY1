package pl.example.trasa

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/** Stan aplikacji na proces – wspólny dla ekranu i usługi. W testach można podstawić [override]. */
object App {
    @Volatile var override: RoutingProvider? = null
    @Volatile var storage: Storage? = null
    @Volatile var speak: ((String) -> Unit)? = null
    @Volatile var vm = RouteVM()
    @Volatile var splashDone = false            // ekran startowy pokazujemy raz na uruchomienie procesu
    private val osm by lazy { OsmProvider(userAgent = "DRAJWERY/1.0 (${BuildConfig.CONTACT_EMAIL})") }
    private val tomtom by lazy { TomTomProvider(BuildConfig.TOMTOM_KEY, osm) }
    fun provider(): RoutingProvider = override ?: when {
        vm.demo -> DemoProvider
        BuildConfig.TOMTOM_KEY.isNotBlank() -> tomtom
        else -> osm
    }
    fun reset() { vm = RouteVM(); splashDone = true }   // używane w testach
}

/** Syntezator mowy (polski) – wspólny dla jazdy i podglądu komunikatów w ustawieniach. */
object Voice {
    private var tts: TextToSpeech? = null
    @Volatile var ready = false; private set
    fun init(ctx: Context) {
        if (tts != null) return
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) { val r = t?.setLanguage(Locale("pl", "PL")); ready = r != null && r >= TextToSpeech.LANG_AVAILABLE }
        }
        tts = t
    }
    fun speak(text: String) { if (ready && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "drajwery") }
}

class TrasaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.storage = Storage(this)
        Voice.init(this); App.speak = { Voice.speak(it) }
        App.vm.restore()
        val cfg = org.osmdroid.config.Configuration.getInstance()   // kafelki mapy w pamięci podręcznej aplikacji
        cfg.userAgentValue = packageName
        cfg.osmdroidBasePath = java.io.File(cacheDir, "osm"); cfg.osmdroidTileCache = java.io.File(cacheDir, "osm/tiles")
    }
}

/** Usługa pierwszoplanowa: GPS, przeliczanie trasy i komunikaty głosowe także przy zgaszonym ekranie. */
class NavService : Service() {
    private val lm by lazy { getSystemService(LocationManager::class.java) }
    private var lastText = ""
    private val listener = LocationListener { l ->
        App.vm.onLocation(LatLng(l.latitude, l.longitude), if (l.hasAccuracy()) l.accuracy.toDouble() else 0.0,
            if (l.hasSpeed()) l.speed.toDouble() else -1.0, l.time)
        if (!App.vm.navActive) { stopSelf(); return@LocationListener }
        val t = App.vm.nextLine()
        if (t != lastText) { lastText = t; getSystemService(NotificationManager::class.java).notify(ID, build()) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == STOP) { App.vm.stopNav(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Jazda", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, ID, build(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, listener)
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { App.vm.onLocation(LatLng(it.latitude, it.longitude)) }
        } catch (e: SecurityException) { App.vm.status = "Brak zgody na lokalizację"; stopSelf() }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NavService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val text = App.vm.nextLine()
        return NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("DRAJWERY – jazda trwa").setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).addAction(0, "Zakończ", stop).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    override fun onDestroy() {
        lm.removeUpdates(listener); super.onDestroy()
    }

    companion object { const val ID = 1; const val CH = "nav"; const val STOP = "pl.example.trasa.STOP" }
}
