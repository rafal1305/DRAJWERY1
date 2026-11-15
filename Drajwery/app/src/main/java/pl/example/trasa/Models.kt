package pl.example.trasa

data class LatLng(val lat: Double, val lng: Double)
/** Manewr na trasie. Tekst powstaje dopiero przy ogłaszaniu – z komunikatów edytowalnych w ustawieniach. */
data class Step(val at: LatLng, val type: String = "", val modifier: String? = null, val name: String? = null,
                val exit: Int? = null, val meters: Double = 0.0, val raw: String? = null)
data class Leg(val points: List<LatLng>, val meters: Double, val seconds: Double, val steps: List<Step> = emptyList())
data class GeoResult(val label: String, val at: LatLng)
/** Odległości (m) i czasy przejazdu (s) między wszystkimi punktami. */
class Matrices(val dist: Array<DoubleArray>, val dur: Array<DoubleArray>)

enum class Criterion { DISTANCE, TIME }
enum class Vehicle { CAR, VAN, TRUCK, BIKE }
data class RouteOptions(
    val criterion: Criterion = Criterion.DISTANCE,
    val avoidMotorways: Boolean = false, val avoidTolls: Boolean = false, val avoidFerries: Boolean = false,
    val vehicle: Vehicle = Vehicle.CAR,
)

/** Przystanek z opcjonalnym terminem ("@14:30" = najpóźniej) i postojem ("~10" = minut). */
data class StopSpec(val address: String, val deadlineMin: Int?, val serviceMin: Int?)

object StopText {
    private val DL = Regex("""@\s*(\d{1,2}):(\d{2})""")
    private val SV = Regex("""~\s*(\d{1,3})""")
    fun parse(raw: String): StopSpec {
        val dl = DL.find(raw)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val sv = SV.find(raw)?.groupValues?.get(1)?.toInt()
        val addr = raw.replace(DL, "").replace(SV, "").trim().trimEnd(',').trim()
        return StopSpec(addr, dl, sv)
    }
}

/** Treść komunikatów głosowych. {m} = metry, {akcja} = czynność, {ulica} = nazwa ulicy, {n} = numer zjazdu z ronda. */
data class Phrases(
    val far: String = "Za {m} metrów, {akcja}",
    val near: String = "{akcja}",
    val left: String = "skręć w lewo", val right: String = "skręć w prawo",
    val slightLeft: String = "skręć lekko w lewo", val slightRight: String = "skręć lekko w prawo",
    val sharpLeft: String = "skręć ostro w lewo", val sharpRight: String = "skręć ostro w prawo",
    val uturn: String = "zawróć", val straight: String = "jedź prosto",
    val roundabout: String = "na rondzie zjedź {n}. zjazdem",
    val merge: String = "włącz się do ruchu", val onRamp: String = "wjedź na zjazd", val offRamp: String = "zjedź z drogi",
    val forkLeft: String = "na rozwidleniu trzymaj się lewej", val forkRight: String = "na rozwidleniu trzymaj się prawej",
    val arrive: String = "dojechałeś do przystanku", val street: String = ", {ulica}",
    val stopReached: String = "Dotarłeś do przystanku", val finished: String = "Dotarłeś do celu",
)

/** Lista pól komunikatów do edycji w ustawieniach i do zapisu JSON. */
object PhraseFields {
    class F(val key: String, val label: String, val get: (Phrases) -> String, val set: (Phrases, String) -> Phrases)
    val all = listOf(
        F("far", "Z wyprzedzeniem ({m} = metry, {akcja})", { it.far }, { p, v -> p.copy(far = v) }),
        F("near", "Tuż przed manewrem ({akcja})", { it.near }, { p, v -> p.copy(near = v) }),
        F("left", "Skręt w lewo", { it.left }, { p, v -> p.copy(left = v) }),
        F("right", "Skręt w prawo", { it.right }, { p, v -> p.copy(right = v) }),
        F("slightLeft", "Lekko w lewo", { it.slightLeft }, { p, v -> p.copy(slightLeft = v) }),
        F("slightRight", "Lekko w prawo", { it.slightRight }, { p, v -> p.copy(slightRight = v) }),
        F("sharpLeft", "Ostro w lewo", { it.sharpLeft }, { p, v -> p.copy(sharpLeft = v) }),
        F("sharpRight", "Ostro w prawo", { it.sharpRight }, { p, v -> p.copy(sharpRight = v) }),
        F("uturn", "Zawracanie", { it.uturn }, { p, v -> p.copy(uturn = v) }),
        F("straight", "Jazda prosto", { it.straight }, { p, v -> p.copy(straight = v) }),
        F("roundabout", "Rondo ({n} = numer zjazdu)", { it.roundabout }, { p, v -> p.copy(roundabout = v) }),
        F("merge", "Włączenie się do ruchu", { it.merge }, { p, v -> p.copy(merge = v) }),
        F("onRamp", "Wjazd na łącznicę", { it.onRamp }, { p, v -> p.copy(onRamp = v) }),
        F("offRamp", "Zjazd z drogi", { it.offRamp }, { p, v -> p.copy(offRamp = v) }),
        F("forkLeft", "Rozwidlenie – lewa", { it.forkLeft }, { p, v -> p.copy(forkLeft = v) }),
        F("forkRight", "Rozwidlenie – prawa", { it.forkRight }, { p, v -> p.copy(forkRight = v) }),
        F("arrive", "Dojazd do punktu (w komunikacie manewru)", { it.arrive }, { p, v -> p.copy(arrive = v) }),
        F("street", "Dopisek z ulicą ({ulica}); puste = bez nazw ulic", { it.street }, { p, v -> p.copy(street = v) }),
        F("stopReached", "Po dotarciu do przystanku", { it.stopReached }, { p, v -> p.copy(stopReached = v) }),
        F("finished", "Po dotarciu do celu", { it.finished }, { p, v -> p.copy(finished = v) }),
    )
}
